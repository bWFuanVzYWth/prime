//! Versioned byte protocol. All reads are little endian and alignment independent.
use crate::scene::{Camera, Mesh, SourceScene, Texture, Triangle};

pub const ABI_VERSION: u32 = 1;
pub const MAGIC: u32 = 0x5450_5250;
pub const MAX_PACKET_BYTES: usize = 256 * 1024 * 1024;
const MAX_TRIANGLES: usize = 8_000_000;
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
        let revision = self
            .revision
            .checked_add(1)
            .ok_or("scene revision exhausted")?;
        if op == 1 {
            input.finish()?;
            if epoch <= self.epoch {
                return Err("reset epoch must increase".into());
            }
            *self = SourceScene {
                epoch,
                revision,
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
                if flags & !1 != 0 {
                    return Err(
                        "unsupported material flags (only opaque and alpha cutout are implemented)"
                            .into(),
                    );
                }
                if layer > 255 {
                    return Err("source layer out of range".into());
                }
                if topology != 3 && topology != 4 {
                    return Err("topology must be triangles (3) or quads (4)".into());
                }
                if !count.is_multiple_of(topology) {
                    return Err("incomplete primitive".into());
                }
                if !(24..=256).contains(&stride)
                    || position_offset + 12 > stride
                    || color_offset + 4 > stride
                    || uv_offset + 8 > stride
                {
                    return Err("invalid source vertex layout".into());
                }
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
                if self.triangle_count - old_count + triangle_count > MAX_TRIANGLES {
                    return Err("scene exceeds 8 million triangle capacity".into());
                }
                if self.meshes.len() >= MAX_SECTIONS && !self.meshes.contains_key(&(key, layer)) {
                    return Err("mesh capacity exceeded".into());
                }
                let mut triangles = Vec::with_capacity(triangle_count);
                let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
                for base in (0..count).step_by(topology) {
                    for indices in if topology == 4 {
                        &[[0, 1, 2], [2, 3, 0]][..]
                    } else {
                        &[[0, 1, 2]][..]
                    } {
                        let mut triangle = Triangle {
                            positions: [[0.0; 3]; 3],
                            colors: [[0.0; 4]; 3],
                            uvs: [[0.0; 2]; 3],
                            texture_id,
                            flags,
                        };
                        for (corner, index) in indices.iter().enumerate() {
                            let vertex = &raw[(base + index) * stride..(base + index + 1) * stride];
                            let mut position =
                                Reader::new(&vertex[position_offset..position_offset + 12])?;
                            triangle.positions[corner] = position.vector()?;
                            for i in 0..3 {
                                bounds[0][i] = bounds[0][i].min(triangle.positions[corner][i]);
                                bounds[1][i] = bounds[1][i].max(triangle.positions[corner][i]);
                            }
                            if triangle.positions[corner].iter().any(|v| v.abs() > 4096.0) {
                                return Err(
                                    "source mesh must use local positions within 4096 blocks"
                                        .into(),
                                );
                            }
                            triangle.colors[corner] = std::array::from_fn(|i| {
                                f32::from(vertex[color_offset + i]) / 255.0
                            });
                            let mut uv = Reader::new(&vertex[uv_offset..uv_offset + 8])?;
                            triangle.uvs[corner] = [uv.f32()?, uv.f32()?];
                        }
                        triangles.push(triangle);
                    }
                }
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
            _ => return Err("unknown scene operation".into()),
        }
        if changed {
            self.revision = revision;
        }
        Ok(())
    }
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
