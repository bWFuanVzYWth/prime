//! Versioned byte protocol. All reads are little endian and alignment independent.
use crate::{
    instances::InstanceContext,
    scene::{Camera, DynamicMesh, Instance, Mesh, Prototype, SourceScene, Texture, Triangle},
};
use std::collections::BTreeMap;

pub const ABI_VERSION: u32 = 1;
pub const MAGIC: u32 = 0x5450_5250;
pub const MAX_PACKET_BYTES: usize = 256 * 1024 * 1024;
pub(crate) const MAX_TRIANGLES: usize = 8_000_000;
const MAX_TEXTURE_BYTES: usize = 512 * 1024 * 1024;
const MAX_SECTIONS: usize = 262_144;

struct Reader<'a> {
    data: &'a [u8],
    offset: usize,
}
impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Result<Self, String> {
        if data.len() > MAX_PACKET_BYTES {
            return Err("packet exceeds 256 MiB limit".into());
        }
        Ok(Self { data, offset: 0 })
    }
    fn take(&mut self, size: usize) -> Result<&'a [u8], String> {
        let end = self
            .offset
            .checked_add(size)
            .ok_or("packet length overflow")?;
        let value = self.data.get(self.offset..end).ok_or("truncated packet")?;
        self.offset = end;
        Ok(value)
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn f32(&mut self) -> Result<f32, String> {
        let value = f32::from_bits(self.u32()?);
        if !value.is_finite() {
            return Err("non-finite f32 in packet".into());
        }
        Ok(value)
    }
    fn f64(&mut self) -> Result<f64, String> {
        let value = f64::from_bits(self.u64()?);
        if !value.is_finite() {
            return Err("non-finite f64 in packet".into());
        }
        Ok(value)
    }
    fn vector(&mut self) -> Result<[f32; 3], String> {
        Ok([self.f32()?, self.f32()?, self.f32()?])
    }
    fn origin(&mut self) -> Result<[f64; 3], String> {
        let value = [self.f64()?, self.f64()?, self.f64()?];
        if value.iter().any(|x| x.abs() > 32_000_000.0) {
            return Err("world position out of range".into());
        }
        Ok(value)
    }
    fn zero(&mut self) -> Result<(), String> {
        if self.u32()? != 0 {
            return Err("reserved field must be zero".into());
        }
        Ok(())
    }
    fn header(&mut self) -> Result<(u32, u64), String> {
        if self.u32()? != MAGIC {
            return Err("invalid PRPT packet magic".into());
        }
        if self.u32()? != ABI_VERSION {
            return Err("unsupported packet ABI version".into());
        }
        let op = self.u32()?;
        self.zero()?;
        Ok((op, self.u64()?))
    }
    fn finish(&self) -> Result<(), String> {
        if self.offset != self.data.len() {
            return Err("trailing bytes in packet".into());
        }
        Ok(())
    }
}

impl SourceScene {
    /// Validate fully before publication. Failed or stale packets never change the scene.
    pub fn submit(&mut self, bytes: &[u8]) -> Result<(), String> {
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        if op == 7 {
            return self.instances.submit(
                bytes,
                &self.textures,
                self.triangle_count + self.dynamic.triangles.len(),
            );
        }
        let revision = if op == 6 {
            self.revision
        } else {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        };
        if op == 1 {
            input.finish()?;
            if epoch <= self.epoch {
                return Err("reset epoch must increase".into());
            }
            *self = SourceScene {
                epoch,
                revision,
                instances: InstanceContext::new(epoch),
                ..Default::default()
            };
            return Ok(());
        }
        if epoch == 0 || epoch != self.epoch {
            return Err("stale or uninitialized resource epoch".into());
        }
        let mut changed = true;
        match op {
            2 => {
                let key = input.u64()?;
                let mesh_revision = input.u64()?;
                let origin = input.origin()?;
                let count = input.u32()? as usize;
                let stride = input.u32()? as usize;
                let position_offset = input.u32()? as usize;
                let color_offset = input.u32()? as usize;
                let uv_offset = input.u32()? as usize;
                let topology = input.u32()? as usize;
                let texture_id = input.u32()?;
                let flags = input.u32()?;
                let layer = input.u32()?;
                input.zero()?;
                validate_flags(flags)?;
                if layer > 255 {
                    return Err("source layer out of range".into());
                }
                let layout = VertexLayout {
                    count,
                    stride,
                    position_offset,
                    color_offset,
                    uv_offset,
                    topology,
                };
                layout.validate()?;
                if mesh_revision <= self.removed.get(&key).copied().unwrap_or(0)
                    || self
                        .meshes
                        .get(&(key, layer))
                        .is_some_and(|m| mesh_revision <= m.revision)
                {
                    return Err("stale mesh revision".into());
                }
                let raw = input.take(
                    count
                        .checked_mul(stride)
                        .ok_or("vertex byte count overflow")?,
                )?;
                input.finish()?;
                let triangle_count = count / topology * (topology - 2);
                let old_count = self
                    .meshes
                    .get(&(key, layer))
                    .map_or(0, |m| m.triangles.len());
                if self.triangle_count - old_count
                    + triangle_count
                    + self.dynamic.triangles.len()
                    + self.instances.triangle_count()
                    > MAX_TRIANGLES
                {
                    return Err("scene exceeds 8 million triangle capacity".into());
                }
                if self.meshes.len() >= MAX_SECTIONS && !self.meshes.contains_key(&(key, layer)) {
                    return Err("mesh capacity exceeded".into());
                }
                let mut triangles = Vec::with_capacity(triangle_count);
                let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
                decode_vertices(raw, &layout, texture_id, flags, &mut triangles, &mut bounds)?;
                self.triangle_count = self.triangle_count - old_count + triangle_count;
                changed = old_count != 0 || triangle_count != 0;
                self.meshes.insert(
                    (key, layer),
                    Mesh {
                        revision: mesh_revision,
                        origin,
                        triangles: triangles.into(),
                        bounds,
                        texture_id,
                        flags,
                    },
                );
            }
            3 => {
                let key = input.u64()?;
                let mesh_revision = input.u64()?;
                input.finish()?;
                if mesh_revision <= self.removed.get(&key).copied().unwrap_or(0)
                    || self
                        .meshes
                        .range((key, 0)..=(key, u32::MAX))
                        .any(|(_, m)| m.revision >= mesh_revision)
                {
                    return Err("stale section removal".into());
                }
                if self.removed.len() >= MAX_SECTIONS && !self.removed.contains_key(&key) {
                    return Err("section history capacity exceeded; reset the scene".into());
                }
                let keys: Vec<_> = self
                    .meshes
                    .range((key, 0)..=(key, u32::MAX))
                    .map(|(k, _)| *k)
                    .collect();
                changed = false;
                for mesh_key in keys {
                    let mesh = self.meshes.remove(&mesh_key).unwrap();
                    self.triangle_count -= mesh.triangles.len();
                    changed |= !mesh.triangles.is_empty();
                }
                self.removed.insert(key, mesh_revision);
            }
            4 => {
                let id = input.u32()?;
                let width = input.u32()?;
                let height = input.u32()?;
                input.zero()?;
                if id == 0 || width == 0 || height == 0 || width > 16384 || height > 16384 {
                    return Err("invalid texture identity or extent".into());
                }
                let len = width as usize * height as usize * 4;
                let pixels = input.take(len)?;
                input.finish()?;
                let current = self.texture_bytes;
                let old = self.textures.get(&id).map_or(0, |t| t.pixels.len());
                if current - old + len > MAX_TEXTURE_BYTES {
                    return Err("texture capacity exceeded".into());
                }
                self.texture_bytes = current - old + len;
                self.textures.insert(
                    id,
                    Texture {
                        width,
                        height,
                        pixels: pixels.into(),
                    },
                );
            }
            6 => {
                let sequence = input.u64()?;
                let origin = input.origin()?;
                let span_count = input.u32()? as usize;
                input.zero()?;
                if sequence <= self.dynamic.revision {
                    return Err(
                        "dynamic frame sequence must increase within its resource epoch".into(),
                    );
                }
                if span_count > (input.data.len() - input.offset) / 32 {
                    return Err("truncated dynamic span descriptors".into());
                }
                let mut triangles = Vec::new();
                let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
                for _ in 0..span_count {
                    let texture_id = input.u32()?;
                    let flags = input.u32()?;
                    let topology = input.u32()? as usize;
                    let count = input.u32()? as usize;
                    let stride = input.u32()? as usize;
                    let position_offset = input.u32()? as usize;
                    let color_offset = input.u32()? as usize;
                    let uv_offset = input.u32()? as usize;
                    validate_flags(flags)?;
                    let layout = VertexLayout {
                        count,
                        stride,
                        position_offset,
                        color_offset,
                        uv_offset,
                        topology,
                    };
                    layout.validate()?;
                    if count != 0 && texture_id != 0 && !self.textures.contains_key(&texture_id) {
                        return Err(
                            "dynamic frame references a texture that has not been captured".into(),
                        );
                    }
                    let raw = input.take(
                        count
                            .checked_mul(stride)
                            .ok_or("vertex byte count overflow")?,
                    )?;
                    let triangle_count = count / topology * (topology - 2);
                    if self.triangle_count
                        + triangles.len()
                        + triangle_count
                        + self.instances.triangle_count()
                        > MAX_TRIANGLES
                    {
                        return Err("scene exceeds 8 million triangle capacity".into());
                    }
                    triangles
                        .try_reserve(triangle_count)
                        .map_err(|_| "dynamic frame allocation failed")?;
                    decode_vertices(raw, &layout, texture_id, flags, &mut triangles, &mut bounds)?;
                }
                input.finish()?;
                self.dynamic = DynamicMesh {
                    revision: sequence,
                    origin,
                    triangles: triangles.into(),
                    bounds,
                };
                changed = false;
            }
            _ => return Err("unknown scene operation".into()),
        }
        if changed {
            self.revision = revision;
        }
        Ok(())
    }
}

/// Owned decoding result, independent of all current scene state.
/// Publication uses InstanceContext's read-only validation followed by one mutation phase.
pub struct InstanceBatch {
    pub(crate) epoch: u64,
    pub(crate) sequence: u64,
    pub(crate) prototypes: BTreeMap<u64, Prototype>,
    pub(crate) prototype_removals: Vec<(u64, u64)>,
    pub(crate) instances: BTreeMap<u64, Instance>,
    pub(crate) instance_removals: Vec<(u64, u64)>,
}

impl InstanceBatch {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        if op != 7 {
            return Err("expected instance batch operation 7".into());
        }
        let sequence = input.u64()?;
        let counts = [input.u32()?, input.u32()?, input.u32()?, input.u32()?];
        let minimum_bytes = counts
            .into_iter()
            .zip([24_u64, 16, 128, 16])
            .map(|(count, stride)| u64::from(count) * stride)
            .sum::<u64>();
        if minimum_bytes == 0 {
            return Err("empty instance batches must not be submitted".into());
        }
        if minimum_bytes > (input.data.len() - input.offset) as u64 {
            return Err("truncated instance batch records".into());
        }
        let mut prototypes = BTreeMap::new();
        let mut triangle_count = 0;
        for _ in 0..counts[0] {
            let id = input.u64()?;
            let revision = input.u64()?;
            let span_count = input.u32()?;
            input.zero()?;
            if span_count as usize > (input.data.len() - input.offset) / 32 {
                return Err("truncated prototype span descriptors".into());
            }
            let mut triangles = Vec::new();
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for _ in 0..span_count {
                let texture_id = input.u32()?;
                let flags = input.u32()?;
                let layout = VertexLayout {
                    topology: input.u32()? as usize,
                    count: input.u32()? as usize,
                    stride: input.u32()? as usize,
                    position_offset: input.u32()? as usize,
                    color_offset: input.u32()? as usize,
                    uv_offset: input.u32()? as usize,
                };
                validate_flags(flags)?;
                layout.validate()?;
                let raw = input.take(
                    layout
                        .count
                        .checked_mul(layout.stride)
                        .ok_or("vertex byte count overflow")?,
                )?;
                let count = layout.count / layout.topology * (layout.topology - 2);
                triangle_count += count;
                if triangle_count > MAX_TRIANGLES {
                    return Err("prototype batch exceeds 8 million triangle capacity".into());
                }
                triangles
                    .try_reserve(count)
                    .map_err(|_| "prototype allocation failed")?;
                decode_vertices(raw, &layout, texture_id, flags, &mut triangles, &mut bounds)?;
            }
            if triangles.is_empty() {
                return Err("prototype must contain at least one triangle".into());
            }
            if prototypes
                .insert(
                    id,
                    Prototype {
                        revision,
                        triangles: triangles.into(),
                        bounds,
                    },
                )
                .is_some()
            {
                return Err("duplicate prototype upsert identity".into());
            }
        }
        let mut prototype_removals = Vec::new();
        for _ in 0..counts[1] {
            prototype_removals.push((input.u64()?, input.u64()?));
        }
        let mut instances = BTreeMap::new();
        for _ in 0..counts[2] {
            let id = input.u64()?;
            let revision = input.u64()?;
            let prototype_id = input.u64()?;
            let origin = input.origin()?;
            let mut transform = [0.0; 12];
            for value in &mut transform {
                *value = input.f32()?;
            }
            validate_affine(transform)?;
            let texture_id = input.u32()?;
            let flags = input.u32()?;
            if flags != u32::MAX {
                validate_flags(flags)?;
            }
            let tint = input.take(4)?.try_into().unwrap();
            input.zero()?;
            let uv_transform = [input.f32()?, input.f32()?, input.f32()?, input.f32()?];
            if instances
                .insert(
                    id,
                    Instance {
                        revision,
                        prototype_id,
                        origin,
                        transform,
                        texture_id,
                        flags,
                        tint,
                        uv_transform,
                    },
                )
                .is_some()
            {
                return Err("duplicate instance upsert identity".into());
            }
        }
        let mut instance_removals = Vec::new();
        for _ in 0..counts[3] {
            instance_removals.push((input.u64()?, input.u64()?));
        }
        input.finish()?;
        Ok(Self {
            epoch,
            sequence,
            prototypes,
            prototype_removals,
            instances,
            instance_removals,
        })
    }
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

#[derive(Debug)]
pub struct Frame {
    pub epoch: u64,
    pub world_position: [f64; 3],
    pub camera: Camera,
    pub width: u32,
    pub height: u32,
    pub sample_index: u32,
}
impl Frame {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        if op != 5 {
            return Err("expected frame operation 5".into());
        }
        let world_position = input.origin()?;
        let forward = input.vector()?;
        let right = input.vector()?;
        let up = input.vector()?;
        for v in [forward, right, up] {
            let squared: f32 = v.into_iter().map(|a| a * a).sum();
            if (squared - 1.0).abs() > 0.01 {
                return Err("camera basis must be normalized".into());
            }
        }
        for (a, b) in [(forward, right), (forward, up), (right, up)] {
            if (0..3).map(|i| a[i] * b[i]).sum::<f32>().abs() > 0.01 {
                return Err("camera basis must be orthogonal".into());
            }
        }
        let vertical_fov_radians = input.f32()?;
        if !(0.01..3.0).contains(&vertical_fov_radians) {
            return Err("invalid vertical field of view".into());
        }
        let width = input.u32()?;
        let height = input.u32()?;
        let sample_index = input.u32()?;
        input.zero()?;
        input.finish()?;
        if width == 0 || height == 0 || width > 4096 || height > 4096 {
            return Err("frame dimensions must be 1..4096".into());
        }
        Ok(Self {
            epoch,
            world_position,
            camera: Camera {
                position: [0.0; 3],
                forward,
                right,
                up,
                vertical_fov_radians,
            },
            width,
            height,
            sample_index,
        })
    }
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
