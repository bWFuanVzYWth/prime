//! Persistent 64-block clusters: section updates never repack the complete world.
use super::dynamic::TopLevel;
use super::resources::{Acceleration, Buffer, Context};
use super::textures::Textures;
use super::{float, float4, uint};
use crate::context::objects::Objects;
use crate::plan::Slots;
use ash::vk;
use prime_scene::scene::{InstanceScene, MeshKey, Scene, SceneMesh};
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

pub(super) struct Geometry {
    pub revision: u64,
    epoch: u64,
    anchor: [f64; 3],
    pub triangle_count: u32,
    // Retire TLAS before any BLAS whose address it contains.
    pub top: TopLevel,
    clusters: BTreeMap<ClusterKey, Cluster>,
    pub objects: Objects,
    instances: Vec<vk::AccelerationStructureInstanceKHR>,
    top_dirty: bool,
    static_count: u32,
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
            top: TopLevel::default(),
            clusters: BTreeMap::new(),
            objects: Objects::new(context)?,
            instances: Vec::new(),
            top_dirty: true,
            static_count: 0,
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
        if self.epoch != scene.epoch {
            self.textures = Textures::new(context, &scene.textures)?;
        } else {
            self.textures.update(context, &scene.textures)?;
        }
        let mut groups: BTreeMap<ClusterKey, GroupMembers<'_>> = BTreeMap::new();
        for (id, mesh) in &scene.meshes {
            if mesh.triangles.is_empty() {
                continue;
            }
            if mesh.flags > 2 {
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
        self.top_dirty = true;
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
            if self
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
                        uint(&mut materials, self.textures.index(triangle.texture_id)?);
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
        self.instances.clear();
        self.instances.reserve(self.clusters.len() + 1);
        for ((cell, _), cluster) in &self.clusters {
            let origin: [f32; 3] =
                std::array::from_fn(|i| (f64::from(cell[i]) * 64.0 - scene.anchor[i]) as f32);
            self.instances.push(vk::AccelerationStructureInstanceKHR {
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
        self.static_count = u32::try_from(
            scene
                .meshes
                .values()
                .map(|mesh| mesh.triangles.len())
                .sum::<usize>(),
        )
        .map_err(|_| "Too many static triangles")?;
        self.revision = scene.revision;
        self.anchor = scene.anchor;
        Ok(())
    }

    pub fn prepare_dynamic(
        &mut self,
        context: &Arc<Context>,
        scene: &Scene,
        objects: &InstanceScene,
        slot: usize,
    ) -> Result<(bool, bool), String> {
        let (changed, mut bindings) = self.objects.prepare(
            context,
            scene,
            objects,
            &self.textures,
            slot,
            self.clusters.len(),
        )?;
        if self.top_dirty || changed {
            let count = self.instances.len();
            self.instances.extend_from_slice(&self.objects.instances);
            let result = self.top.rebuild(context, &self.instances, slot);
            self.instances.truncate(count);
            bindings |= result?;
            self.top_dirty = false;
        }
        self.triangle_count = self
            .static_count
            .checked_add(self.objects.triangle_count)
            .ok_or("Triangle count overflow")?;
        Ok((changed, bindings))
    }

    pub fn dynamic_buffer(&self) -> &Buffer {
        &self.objects.data
    }
}

pub(super) unsafe fn transfer_barrier(context: &Context, command: vk::CommandBuffer) {
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
    use crate::plan::OBJECT_BIT;
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

    #[test]
    fn static_material_slots_never_alias_object_instance_indices() {
        let mut slots = Slots::default();
        assert_eq!(slots.allocate(OBJECT_BIT).unwrap(), 0);
        assert!(slots.allocate(1).is_err());
        slots.release(OBJECT_BIT - 1, 1);
        assert_eq!(slots.allocate(1).unwrap(), OBJECT_BIT - 1);
        assert!(slots.allocate(1).is_err());
    }
}
