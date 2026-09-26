//! Persistent 64-block clusters: section updates never repack the complete world.
use super::resources::{Acceleration, Buffer, Context};
use super::{float, float4, uint};
use ash::vk;
use prime_scene::scene::{MeshKey, Scene, SceneMesh, Texture};
use std::{collections::BTreeMap, sync::Arc};

type ClusterKey = ([i32; 3], u32);
type Signature = Vec<(MeshKey, u64, [f32; 3])>;
type GroupMembers<'a> = Vec<(MeshKey, &'a SceneMesh, [f32; 3])>;

struct Cluster {
    signature: Signature,
    first: u32,
    count: u32,
    acceleration: Acceleration,
}

pub(super) struct Textures {
    source: BTreeMap<u32, Texture>,
    indices: BTreeMap<u32, u32>,
    pub metadata: Buffer,
    pub texels: Buffer,
}
impl Textures {
    fn new(context: &Arc<Context>, source: &BTreeMap<u32, Texture>) -> Result<Self, String> {
        let mut indices = BTreeMap::from([(0, 0)]);
        let mut metadata = Vec::new();
        for value in [0, 1, 1, 0] {
            uint(&mut metadata, value);
        }
        let mut pixels = vec![255; 4];
        for (id, texture) in source {
            if *id == 0 {
                continue;
            }
            let expected = u64::from(texture.width)
                .checked_mul(u64::from(texture.height))
                .and_then(|n| n.checked_mul(4))
                .ok_or("Texture dimensions overflow")?;
            if texture.width == 0 || texture.height == 0 || expected != texture.pixels.len() as u64
            {
                return Err(format!("Texture {id} has invalid RGBA8 dimensions"));
            }
            indices.insert(*id, (metadata.len() / 16) as u32);
            let offset = u32::try_from(pixels.len() / 4).map_err(|_| "Texture address overflow")?;
            for value in [offset, texture.width, texture.height, 0] {
                uint(&mut metadata, value);
            }
            pixels.extend_from_slice(&texture.pixels);
        }
        Ok(Self {
            source: source.clone(),
            indices,
            metadata: Buffer::upload_device(
                context,
                &metadata,
                vk::BufferUsageFlags::STORAGE_BUFFER,
            )?,
            texels: Buffer::upload_device(context, &pixels, vk::BufferUsageFlags::STORAGE_BUFFER)?,
        })
    }
    fn matches(&self, source: &BTreeMap<u32, Texture>) -> bool {
        source.len() == self.source.len()
            && source.iter().all(|(id, texture)| {
                self.source.get(id).is_some_and(|old| {
                    old.width == texture.width
                        && old.height == texture.height
                        && Arc::ptr_eq(&old.pixels, &texture.pixels)
                })
            })
    }
}

#[derive(Default)]
struct Slots {
    end: u32,
    free: BTreeMap<u32, u32>,
}
impl Slots {
    fn allocate(&mut self, count: u32) -> Result<u32, String> {
        if let Some((first, available)) = self
            .free
            .iter()
            .find(|(_, n)| **n >= count)
            .map(|(&p, &n)| (p, n))
        {
            self.free.remove(&first);
            if available > count {
                self.free.insert(first + count, available - count);
            }
            return Ok(first);
        }
        let first = self.end;
        let end = first
            .checked_add(count)
            .ok_or("Triangle address overflow")?;
        if end > 0x0100_0000 {
            return Err("Triangle arena exceeds the 24-bit TLAS instance offset".into());
        }
        self.end = end;
        Ok(first)
    }
    fn release(&mut self, mut first: u32, mut count: u32) {
        if let Some((&previous, &length)) = self.free.range(..first).next_back()
            && previous + length == first
        {
            self.free.remove(&previous);
            first = previous;
            count += length;
        }
        if let Some(length) = self.free.remove(&(first + count)) {
            count += length;
        }
        if first + count == self.end {
            self.end = first;
        } else {
            self.free.insert(first, count);
        }
    }
}

pub(super) struct Geometry {
    pub revision: u64,
    epoch: u64,
    anchor: [f64; 3],
    pub triangle_count: u32,
    // Retire TLAS before any BLAS whose address it contains.
    pub top: Option<Acceleration>,
    clusters: BTreeMap<ClusterKey, Cluster>,
    slots: Slots,
    pub triangles: Buffer,
    pub textures: Textures,
    pub rebuilt_clusters: u32,
}

impl Geometry {
    pub fn new(context: &Arc<Context>, scene: &Scene) -> Result<Self, String> {
        let mut geometry = Self {
            revision: 0,
            epoch: scene.epoch,
            anchor: scene.anchor,
            triangle_count: 0,
            top: None,
            clusters: BTreeMap::new(),
            slots: Slots::default(),
            triangles: Self::arena(context, 1024)?,
            textures: Textures::new(context, &scene.textures)?,
            rebuilt_clusters: 0,
        };
        geometry.update(context, scene)?;
        Ok(geometry)
    }
    fn arena(context: &Arc<Context>, records: u32) -> Result<Buffer, String> {
        Buffer::new(
            context,
            u64::from(records) * 128,
            vk::BufferUsageFlags::STORAGE_BUFFER
                | vk::BufferUsageFlags::TRANSFER_SRC
                | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )
    }
    pub fn needs_update(&self, scene: &Scene) -> bool {
        self.revision != scene.revision || self.epoch != scene.epoch || self.anchor != scene.anchor
    }
    pub fn update(&mut self, context: &Arc<Context>, scene: &Scene) -> Result<(), String> {
        if scene.anchor.iter().any(|v| !v.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        let texture_changed = !self.textures.matches(&scene.textures);
        let mut material_indices_changed = false;
        if texture_changed {
            let replacement = Textures::new(context, &scene.textures)?;
            material_indices_changed = replacement.indices != self.textures.indices;
            self.textures = replacement;
        }
        let mut groups: BTreeMap<ClusterKey, GroupMembers<'_>> = BTreeMap::new();
        for (id, mesh) in &scene.meshes {
            if mesh.triangles.is_empty() {
                continue;
            }
            if mesh.flags & !1 != 0 {
                return Err("Unsupported mesh material flags".into());
            }
            let world: [f64; 3] =
                std::array::from_fn(|i| scene.anchor[i] + f64::from(mesh.origin[i]));
            if world
                .iter()
                .any(|p| !p.is_finite() || p.abs() > 33_000_000.0)
            {
                return Err("Invalid mesh world origin".into());
            }
            let cell = world.map(|p| (p / 64.0).floor() as i32);
            let local = std::array::from_fn(|i| (world[i] - f64::from(cell[i]) * 64.0) as f32);
            groups
                .entry((cell, mesh.flags))
                .or_default()
                .push((*id, mesh, local));
        }
        // Each retained BLAS owns one allocation. Reserve room for the 32-build
        // transient batch, output, textures, material arena growth and TLAS.
        if groups.len().saturating_add(160) > context.max_memory_allocations as usize {
            return Err("Scene exceeds the device memory allocation budget".into());
        }
        self.top.take();
        if self.epoch != scene.epoch {
            self.clusters.clear();
            self.slots = Slots::default();
            self.epoch = scene.epoch;
        }
        let removed: Vec<_> = self
            .clusters
            .keys()
            .filter(|key| !groups.contains_key(key))
            .copied()
            .collect();
        for key in removed {
            let old = self.clusters.remove(&key).unwrap();
            self.slots.release(old.first, old.count);
        }
        let mut changes = Vec::new();
        for (key, members) in &groups {
            let signature: Signature = members
                .iter()
                .map(|(id, mesh, local)| (*id, mesh.revision, *local))
                .collect();
            if !material_indices_changed
                && self
                    .clusters
                    .get(key)
                    .is_some_and(|old| old.signature == signature)
            {
                continue;
            }
            let count = u32::try_from(
                members
                    .iter()
                    .map(|(_, mesh, _)| mesh.triangles.len())
                    .sum::<usize>(),
            )
            .map_err(|_| "Too many cluster triangles")?;
            if let Some(old) = self.clusters.remove(key) {
                self.slots.release(old.first, old.count);
            }
            let first = self.slots.allocate(count)?;
            changes.push((*key, signature, first, count));
        }
        self.rebuilt_clusters = changes.len() as u32;
        if u64::from(self.slots.end) * 128 > self.triangles.size {
            let records = self
                .slots
                .end
                .checked_next_power_of_two()
                .ok_or("Arena capacity overflow")?;
            let replacement = Self::arena(context, records)?;
            context.submit_named("grow_material_arena", |command| unsafe {
                context.device.cmd_copy_buffer(
                    command,
                    self.triangles.buffer,
                    replacement.buffer,
                    &[vk::BufferCopy::default().size(self.triangles.size)],
                );
                transfer_barrier(context, command);
            })?;
            self.triangles = replacement;
        }
        // Bound transient VkDeviceMemory allocations; clusters retain one BLAS allocation.
        // This batches independently prepared builds rather than waiting per section.
        for batch in changes.chunks(32) {
            let mut inputs = Vec::new();
            let mut uploads = Vec::new();
            let mut prepared = Vec::new();
            for (key, signature, first, count) in batch {
                let mut vertices = Vec::with_capacity(*count as usize * 36);
                let mut materials = Vec::with_capacity(*count as usize * 128);
                for (_, mesh, offset) in &groups[key] {
                    for triangle in mesh.triangles.iter() {
                        if triangle.flags != mesh.flags {
                            return Err("Mesh material flags must be uniform".into());
                        }
                        for position in triangle.positions {
                            let position =
                                std::array::from_fn::<_, 3, _>(|i| position[i] + offset[i]);
                            if position.iter().any(|p| !p.is_finite()) {
                                return Err("Non-finite vertex".into());
                            }
                            for value in position {
                                float(&mut vertices, value);
                            }
                            float4(&mut materials, [position[0], position[1], position[2], 0.0]);
                        }
                        for color in triangle.colors {
                            if color
                                .iter()
                                .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
                            {
                                return Err("Invalid vertex tint".into());
                            }
                            float4(&mut materials, color);
                        }
                        for uv in triangle.uvs {
                            if uv.iter().any(|v| !v.is_finite()) {
                                return Err("Invalid texture coordinate".into());
                            }
                            for value in uv {
                                float(&mut materials, value);
                            }
                        }
                        uint(
                            &mut materials,
                            *self
                                .textures
                                .indices
                                .get(&triangle.texture_id)
                                .ok_or("Unknown texture")?,
                        );
                        uint(&mut materials, triangle.flags);
                    }
                }
                let input = Buffer::upload(
                    context,
                    &vertices,
                    vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                        | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
                )?;
                let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
                    .vertex_format(vk::Format::R32G32B32_SFLOAT)
                    .vertex_data(vk::DeviceOrHostAddressConstKHR {
                        device_address: input.address(),
                    })
                    .vertex_stride(12)
                    .max_vertex(count * 3 - 1)
                    .index_type(vk::IndexType::NONE_KHR);
                let geometry = vk::AccelerationStructureGeometryKHR::default()
                    .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
                    .flags(if key.1 == 0 {
                        vk::GeometryFlagsKHR::OPAQUE
                    } else {
                        vk::GeometryFlagsKHR::empty()
                    })
                    .geometry(vk::AccelerationStructureGeometryDataKHR { triangles });
                prepared.push((
                    *key,
                    signature.clone(),
                    *first,
                    *count,
                    Acceleration::prepare(
                        context,
                        geometry,
                        *count,
                        vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
                    )?,
                ));
                uploads.push((
                    *first,
                    Buffer::upload(context, &materials, vk::BufferUsageFlags::TRANSFER_SRC)?,
                ));
                inputs.push(input);
            }
            context.submit_named("dirty_clusters", |command| unsafe {
                for (first, upload) in &uploads {
                    context.device.cmd_copy_buffer(
                        command,
                        upload.buffer,
                        self.triangles.buffer,
                        &[vk::BufferCopy::default()
                            .dst_offset(u64::from(*first) * 128)
                            .size(upload.size)],
                    );
                }
                for (_, _, _, _, build) in &prepared {
                    build.record_unbarriered(command);
                }
                Acceleration::read_barrier(context, command);
                transfer_barrier(context, command);
            })?;
            for (key, signature, first, count, build) in prepared {
                self.clusters.insert(
                    key,
                    Cluster {
                        signature,
                        first,
                        count,
                        acceleration: build.finish(),
                    },
                );
            }
        }
        let mut instances = Vec::with_capacity(self.clusters.len());
        for ((cell, _), cluster) in &self.clusters {
            let origin: [f32; 3] =
                std::array::from_fn(|i| (f64::from(cell[i]) * 64.0 - scene.anchor[i]) as f32);
            instances.push(vk::AccelerationStructureInstanceKHR {
                transform: vk::TransformMatrixKHR {
                    matrix: [
                        1.0, 0.0, 0.0, origin[0], 0.0, 1.0, 0.0, origin[1], 0.0, 0.0, 1.0,
                        origin[2],
                    ],
                },
                instance_custom_index_and_mask: vk::Packed24_8::new(cluster.first, 0xff),
                instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(
                    0,
                    vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE.as_raw() as u8,
                ),
                acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                    device_handle: cluster.acceleration.address(),
                },
            });
        }
        let bytes = unsafe {
            std::slice::from_raw_parts(instances.as_ptr().cast::<u8>(), instances.len() * 64)
        };
        let instance_buffer = Buffer::upload(
            context,
            bytes,
            vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
                | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        )?;
        let data = vk::AccelerationStructureGeometryInstancesDataKHR::default().data(
            vk::DeviceOrHostAddressConstKHR {
                device_address: instance_buffer.address(),
            },
        );
        self.top = Some(Acceleration::build(
            context,
            vk::AccelerationStructureGeometryKHR::default()
                .geometry_type(vk::GeometryTypeKHR::INSTANCES)
                .geometry(vk::AccelerationStructureGeometryDataKHR { instances: data }),
            instances.len() as u32,
            vk::AccelerationStructureTypeKHR::TOP_LEVEL,
        )?);
        self.triangle_count =
            u32::try_from(scene.triangle_count()).map_err(|_| "Too many triangles")?;
        self.revision = scene.revision;
        self.anchor = scene.anchor;
        Ok(())
    }
}

unsafe fn transfer_barrier(context: &Context, command: vk::CommandBuffer) {
    let barrier = [vk::MemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(
            vk::AccessFlags::SHADER_READ
                | vk::AccessFlags::TRANSFER_READ
                | vk::AccessFlags::TRANSFER_WRITE,
        )];
    unsafe {
        context.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &barrier,
            &[],
            &[],
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn material_slots_reuse_and_coalesce_without_aliasing_live_ranges() {
        let mut slots = Slots::default();
        let a = slots.allocate(3).unwrap();
        let b = slots.allocate(5).unwrap();
        let c = slots.allocate(7).unwrap();
        slots.release(a, 3);
        slots.release(b, 5);
        assert_eq!(slots.allocate(8).unwrap(), a);
        assert_eq!(slots.allocate(1).unwrap(), 15);
        slots.release(c, 7);
        assert_eq!(slots.allocate(7).unwrap(), c);
        assert_eq!(slots.end, 16);
    }
}
