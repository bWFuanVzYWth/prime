//! Typed source protocol and shared compact-payload validation.
//! The optional byte protocol is restricted to offline fixtures.
pub mod typed;
use crate::scene::{Camera, Instance, Prototype, SourceScene, Texture, Triangle};

#[cfg(test)]
#[path = "protocol_capacity_tests.rs"]
mod capacity_tests;

#[cfg(any(test, feature = "legacy-fixtures"))]
mod legacy;
#[cfg(any(test, feature = "legacy-fixtures"))]
pub(crate) use legacy::SectionScratch;
#[cfg(any(test, feature = "legacy-fixtures"))]
pub use legacy::{ABI_VERSION, MAGIC};
pub const MAX_PACKET_BYTES: usize = 256 * 1024 * 1024;
pub(crate) const MAX_TEXTURE_BYTES: usize = 512 * 1024 * 1024;

pub(crate) fn validate_triangle_capacity(total: usize) -> Result<(), String> {
    // Scene counts are independent of per-packet counts and GPU page addresses.
    // This is an address-space check, not a promise of physical memory residency.
    total
        .checked_mul(std::mem::size_of::<Triangle>())
        .ok_or("source triangle storage size overflows the host address space")?;
    Ok(())
}

pub(crate) struct Reader<'a> {
    pub(crate) data: &'a [u8],
    pub(crate) offset: usize,
}
impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Result<Self, String> {
        if data.len() > MAX_PACKET_BYTES {
            return Err("packet exceeds 256 MiB limit".into());
        }
        Ok(Self { data, offset: 0 })
    }
    pub(crate) fn take(&mut self, size: usize) -> Result<&'a [u8], String> {
        let end = self
            .offset
            .checked_add(size)
            .ok_or("packet length overflow")?;
        let value = self.data.get(self.offset..end).ok_or("truncated packet")?;
        self.offset = end;
        Ok(value)
    }
    pub(crate) fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub(crate) fn f32(&mut self) -> Result<f32, String> {
        let value = f32::from_bits(self.u32()?);
        if !value.is_finite() {
            return Err("non-finite f32 in packet".into());
        }
        Ok(value)
    }
    pub(crate) fn vector(&mut self) -> Result<[f32; 3], String> {
        Ok([self.f32()?, self.f32()?, self.f32()?])
    }
}

/// Owned decoding result, independent of all current scene state.
/// Publication uses InstanceContext's read-only validation followed by one mutation phase.
#[derive(Default)]
pub struct InstanceBatch {
    pub(crate) epoch: u64,
    pub(crate) sequence: u64,
    pub(crate) prototypes: Vec<(u64, Prototype)>,
    pub(crate) prototype_removals: Vec<(u64, u64)>,
    pub(crate) instances: Vec<(u64, Instance)>,
    pub(crate) instance_removals: Vec<(u64, u64)>,
}

impl InstanceBatch {
    /// Scratch owns decoded values only; partial results are never published after an error.
    /// Clearing retains record capacity, while dropping any uncommitted prototype geometry.
    pub(crate) fn clear(&mut self) {
        self.prototypes.clear();
        self.prototype_removals.clear();
        self.instances.clear();
        self.instance_removals.clear();
    }
}

fn validate_record(id: u64, revision: u64, sequence: u64) -> Result<(), String> {
    if id == 0 {
        return Err("instance batch identities must be nonzero".into());
    }
    if revision != sequence {
        return Err("prototype and instance revisions must equal their batch sequence".into());
    }
    Ok(())
}

/// Ordering is not a wire requirement. Canonicalize owned records in place, then reject
/// duplicates both within and across update/removal groups without allocating another set.
fn validate_unique<T>(updates: &mut [(u64, T)], removals: &mut [(u64, u64)]) -> Result<(), String> {
    updates.sort_unstable_by_key(|(id, _)| *id);
    removals.sort_unstable_by_key(|(id, _)| *id);
    if updates.windows(2).any(|pair| pair[0].0 == pair[1].0)
        || removals.windows(2).any(|pair| pair[0].0 == pair[1].0)
    {
        return Err("duplicate identity within instance batch category".into());
    }
    let mut updates = updates.iter().peekable();
    for (removed, _) in removals {
        while updates.peek().is_some_and(|(id, _)| *id < *removed) {
            updates.next();
        }
        if updates.peek().is_some_and(|(id, _)| *id == *removed) {
            return Err("identity is both updated and removed in instance batch".into());
        }
    }
    Ok(())
}

fn validate_affine(transform: [f32; 12]) -> Result<(), String> {
    let m = transform.map(f64::from);
    let cofactors = [
        m[5] * m[10] - m[6] * m[9],
        m[6] * m[8] - m[4] * m[10],
        m[4] * m[9] - m[5] * m[8],
        m[2] * m[9] - m[1] * m[10],
        m[0] * m[10] - m[2] * m[8],
        m[1] * m[8] - m[0] * m[9],
        m[1] * m[6] - m[2] * m[5],
        m[2] * m[4] - m[0] * m[6],
        m[0] * m[5] - m[1] * m[4],
    ];
    let determinant = m[0] * cofactors[0] + m[1] * cofactors[1] + m[2] * cofactors[2];
    if determinant == 0.0
        || cofactors
            .iter()
            .any(|cofactor| !((*cofactor / determinant) as f32).is_finite())
    {
        return Err("instance affine must have a finite representable inverse".into());
    }
    Ok(())
}

fn validate_flags(flags: u32) -> Result<(), String> {
    if flags > 2 {
        return Err("material flags must be opaque (0), cutout (1), or alpha blend (2)".into());
    }
    Ok(())
}

struct VertexLayout {
    count: usize,
    stride: usize,
    position_offset: usize,
    color_offset: usize,
    uv_offset: usize,
    topology: usize,
}

impl VertexLayout {
    fn validate(&self) -> Result<(), String> {
        if self.topology != 3 && self.topology != 4 {
            return Err("topology must be triangles (3) or quads (4)".into());
        }
        if !self.count.is_multiple_of(self.topology) {
            return Err("incomplete primitive".into());
        }
        if !(24..=256).contains(&self.stride)
            || self.position_offset > self.stride - 12
            || self.color_offset > self.stride - 4
            || self.uv_offset > self.stride - 8
        {
            return Err("invalid source vertex layout".into());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Default)]
struct Vertex {
    position: [f32; 3],
    color: [f32; 4],
    uv: [f32; 2],
}

/// Decode each source corner once; triangulation only copies the validated values.
fn decode_vertices(
    raw: &[u8],
    layout: &VertexLayout,
    texture_id: u32,
    flags: u32,
    triangles: &mut Vec<Triangle>,
    bounds: &mut [[f32; 3]; 2],
) -> Result<(), String> {
    for primitive in raw.chunks_exact(layout.stride * layout.topology) {
        let mut vertices = [Vertex::default(); 4];
        for (vertex, source) in vertices
            .iter_mut()
            .zip(primitive.chunks_exact(layout.stride))
        {
            let mut position =
                Reader::new(&source[layout.position_offset..layout.position_offset + 12])?;
            vertex.position = position.vector()?;
            if vertex.position.iter().any(|v| v.abs() > 4096.0) {
                return Err("source mesh must use local positions within 4096 blocks".into());
            }
            for (i, value) in vertex.position.into_iter().enumerate() {
                bounds[0][i] = bounds[0][i].min(value);
                bounds[1][i] = bounds[1][i].max(value);
            }
            vertex.color =
                std::array::from_fn(|i| f32::from(source[layout.color_offset + i]) / 255.0);
            let mut uv = Reader::new(&source[layout.uv_offset..layout.uv_offset + 8])?;
            vertex.uv = [uv.f32()?, uv.f32()?];
        }
        for indices in if layout.topology == 4 {
            &[[0, 1, 2], [2, 3, 0]][..]
        } else {
            &[[0, 1, 2]][..]
        } {
            triangles.push(Triangle {
                positions: indices.map(|i| vertices[i].position),
                colors: indices.map(|i| vertices[i].color),
                uvs: indices.map(|i| vertices[i].uv),
                texture_id,
                flags,
            });
        }
    }
    Ok(())
}

#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub epoch: u64,
    pub world_position: [f64; 3],
    pub camera: Camera,
    pub width: u32,
    pub height: u32,
    pub sample_index: u32,
    pub solar_hour_angle: f32,
}
impl Frame {
    pub fn anchor(&self) -> [f64; 3] {
        self.world_position.map(|p| (p / 256.0).floor() * 256.0)
    }
    pub fn relative_camera(&self, anchor: [f64; 3]) -> Camera {
        Camera {
            position: std::array::from_fn(|i| (self.world_position[i] - anchor[i]) as f32),
            ..self.camera
        }
    }
    pub fn output_len(&self) -> usize {
        self.width as usize * self.height as usize * 4
    }
}
