//! Persistent prototype/bucket BLAS and shared shading arenas.
use crate::cpu_profile::{FrameCpu, Stage};
use crate::dynamic::slot_buffer;
use crate::material_arena::{Allocation, MaterialArena};
use crate::plan::{
    INHERIT, MAX_MATERIAL_RECORDS, OBJECT_BIT, ObjectKey, Planner, ScenePlan, arena_capacity,
    pack_material, pack_triangle, validate_material_count,
};
use crate::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use crate::textures::Textures;
use crate::{FRAME_SLOTS, uint};
use ash::vk;
use prime_scene::scene::{InstanceScene, Scene};
use std::{collections::BTreeMap, sync::Arc};

struct Object {
    build: PreparedAcceleration<'static>,
    allocation: Allocation,
    capacity: u32,
    count: u32,
    opaque: bool,
}

pub(crate) struct Objects {
    // Acceleration objects retire before their arena and shared index input.
    objects: BTreeMap<ObjectKey, Object>,
    planner: Planner,
    materials: MaterialArena,
    indices: Buffer,
    pub metadata: Buffer,
    uploads: [Option<Buffer>; FRAME_SLOTS],
    bytes: Vec<u8>,
    pub instances: Vec<vk::AccelerationStructureInstanceKHR>,
    pub triangle_count: u64,
    pub rebuilt: u32,
}

impl Objects {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        Ok(Self {
            objects: BTreeMap::new(),
            planner: Planner::default(),
            materials: MaterialArena::new(),
            indices: index_buffer(context, 16)?,
            metadata: Buffer::new(
                context,
                48,
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?,
            uploads: std::array::from_fn(|_| None),
            bytes: Vec::new(),
            instances: Vec::new(),
            triangle_count: 0,
            rebuilt: 0,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn prepare(
        &mut self,
        context: &Arc<Context>,
        scene: &Scene,
        source: &InstanceScene,
        textures: &Textures,
        slot: usize,
        static_clusters: usize,
        cpu: &mut FrameCpu,
    ) -> Result<(bool, bool), String> {
        let started = cpu.start();
        let plan = self.planner.plan(scene, source)?;
        cpu.finish(Stage::Plan, started);
        let started = cpu.start();
        let changed = plan.placements_changed;
        let result = self.execute(context, &plan, textures, slot, static_clusters);
        self.triangle_count = plan.triangle_count;
        self.planner.recycle(plan);
        let bindings = result?;
        cpu.finish(Stage::Execute, started);
        Ok((changed, bindings))
    }

    fn execute(
        &mut self,
        context: &Arc<Context>,
        plan: &ScenePlan,
        textures: &Textures,
        slot: usize,
        static_clusters: usize,
    ) -> Result<bool, String> {
        self.rebuilt = plan.geometry.len() as u32;
        if plan.geometry.is_empty() && plan.removed.is_empty() && !plan.placements_changed {
            return Ok(false);
        }
        for key in &plan.removed {
            if let Some(old) = self.objects.remove(key) {
                self.materials.free(old.allocation);
            }
        }
        let additions = plan
            .geometry
            .iter()
            .filter(|item| !self.objects.contains_key(&item.key))
            .count();
        if (self.objects.len() + additions) * 2 + static_clusters + 160
            > context.max_memory_allocations as usize
        {
            return Err("Object BLAS exceeds the device allocation budget".into());
        }
        let mut largest = 16;
        let mut locations = Vec::with_capacity(plan.geometry.len());
        let mut upload_capacity = 0u64;
        for item in &plan.geometry {
            let count = u32::try_from(self.planner.triangles(item).len())
                .map_err(|_| "Object triangle overflow")?;
            validate_material_count(count)?;
            let capacity = arena_capacity(count.max(16), MAX_MATERIAL_RECORDS)?;
            largest = largest.max(capacity);
            let replace = self
                .objects
                .get(&item.key)
                .is_none_or(|old| old.capacity < capacity);
            let allocation = if replace {
                if let Some(old) = self.objects.remove(&item.key) {
                    self.materials.free(old.allocation);
                }
                self.materials.allocate(context, capacity)?
            } else {
                self.objects[&item.key].allocation
            };
            upload_capacity += u64::from(capacity) * 128;
            locations.push((item.key, allocation, count, capacity, replace));
        }
        let mut bindings = false;
        if u64::from(largest) * 12 > self.indices.size {
            self.indices = index_buffer(context, largest)?;
        }
        self.bytes.clear();
        let mut copies: BTreeMap<vk::Buffer, Vec<vk::BufferCopy>> = BTreeMap::new();
        for (item, &(key, allocation, count, capacity, replace)) in
            plan.geometry.iter().zip(&locations)
        {
            let offset = self.bytes.len() as u64;
            let triangles = self.planner.triangles(item);
            for triangle in triangles {
                pack_triangle(
                    &mut self.bytes,
                    triangle,
                    textures.index(triangle.texture_id)?,
                );
            }
            copies
                .entry(self.materials.buffer(allocation).buffer)
                .or_default()
                .push(
                    vk::BufferCopy::default()
                        .src_offset(offset)
                        .dst_offset(u64::from(allocation.first) * 128)
                        .size(u64::from(count) * 128),
                );
            let opaque = triangles.iter().all(|triangle| triangle.flags == 0);
            if replace {
                // Nonopaque geometry permits per-instance material overrides. A
                // uniform opaque placement uses FORCE_OPAQUE in the TLAS instead.
                let build = Acceleration::prepare_with_flags(
                    context,
                    geometry(
                        self.materials.address(allocation),
                        self.indices.address(),
                        capacity,
                    ),
                    capacity,
                    vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
                    if matches!(key, ObjectKey::Prototype(_)) {
                        vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE
                    } else {
                        vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_BUILD
                    },
                )?;
                self.objects.insert(
                    key,
                    Object {
                        build,
                        allocation,
                        count,
                        capacity,
                        opaque,
                    },
                );
            } else {
                let object = self.objects.get_mut(&key).unwrap();
                object.count = count;
                object.opaque = opaque;
            }
        }
        let metadata_offset = self.bytes.len() as u64;
        let mut metadata_bytes = 0;
        if plan.placements_changed {
            let placements = self.planner.placements();
            self.instances.clear();
            self.instances.reserve(placements.len());
            for (index, placement) in placements.iter().enumerate() {
                let object = self
                    .objects
                    .get(&placement.key)
                    .ok_or("GPU instance prototype missing")?;
                let texture = if placement.texture_id == INHERIT {
                    INHERIT
                } else {
                    textures.index(placement.texture_id)?
                };
                pack_material(
                    &mut self.bytes,
                    self.materials.address(object.allocation),
                    texture,
                    placement,
                );
                let opaque = placement.flags == 0 || placement.flags == INHERIT && object.opaque;
                let flags = vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE
                    | if opaque {
                        vk::GeometryInstanceFlagsKHR::FORCE_OPAQUE
                    } else {
                        vk::GeometryInstanceFlagsKHR::FORCE_NO_OPAQUE
                    };
                self.instances.push(vk::AccelerationStructureInstanceKHR {
                    transform: vk::TransformMatrixKHR {
                        matrix: placement.transform,
                    },
                    instance_custom_index_and_mask: vk::Packed24_8::new(
                        OBJECT_BIT | index as u32,
                        0xff,
                    ),
                    instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(
                        0,
                        flags.as_raw() as u8,
                    ),
                    acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                        device_handle: object.build.acceleration().address(),
                    },
                });
            }
            metadata_bytes = (placements.len() as u64 * 48).max(48);
            if placements.is_empty() {
                self.bytes.extend_from_slice(&[0; 48]);
            }
            if metadata_bytes > self.metadata.size {
                self.metadata = Buffer::new(
                    context,
                    metadata_bytes
                        .checked_next_power_of_two()
                        .ok_or("Instance metadata overflow")?,
                    vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                    false,
                )?;
                bindings = true;
            }
        }
        upload_capacity += metadata_bytes;
        if self.bytes.is_empty() {
            return Ok(bindings);
        }
        let staging = slot_buffer(
            context,
            &mut self.uploads[slot],
            upload_capacity.max(self.bytes.len() as u64),
            vk::BufferUsageFlags::TRANSFER_SRC,
        )?;
        staging.write(&self.bytes)?;
        context.submit_named("object_updates", |command| unsafe {
            for (destination, regions) in &copies {
                context
                    .device
                    .cmd_copy_buffer(command, staging.buffer, *destination, regions);
            }
            if metadata_bytes > 0 {
                context.device.cmd_copy_buffer(
                    command,
                    staging.buffer,
                    self.metadata.buffer,
                    &[vk::BufferCopy::default()
                        .src_offset(metadata_offset)
                        .size(metadata_bytes)],
                );
            }
            let memory = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(
                    vk::AccessFlags::ACCELERATION_STRUCTURE_READ_KHR | vk::AccessFlags::SHADER_READ,
                )];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::ACCELERATION_STRUCTURE_BUILD_KHR
                    | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &memory,
                &[],
                &[],
            );
            for &(key, allocation, count, _, _) in &locations {
                let object = &self.objects[&key];
                object.build.record_geometry(
                    command,
                    geometry(
                        self.materials.address(allocation),
                        self.indices.address(),
                        object.capacity,
                    ),
                    count,
                );
            }
            if !locations.is_empty() {
                Acceleration::read_barrier(context, command);
            }
        })?;
        Ok(bindings)
    }

    #[cfg(test)]
    pub fn addresses(&self) -> BTreeMap<ObjectKey, u64> {
        self.objects
            .iter()
            .map(|(key, object)| (*key, object.build.acceleration().address()))
            .collect()
    }

    #[cfg(test)]
    pub fn material_addresses(&self) -> BTreeMap<ObjectKey, u64> {
        self.objects
            .iter()
            .map(|(key, object)| (*key, self.materials.address(object.allocation)))
            .collect()
    }

    pub fn count(&self) -> usize {
        self.objects.len()
    }

    pub fn page_count(&self) -> usize {
        self.materials.page_count()
    }
}

fn index_buffer(context: &Arc<Context>, capacity: u32) -> Result<Buffer, String> {
    validate_material_count(capacity)?;
    let mut bytes = Vec::with_capacity(capacity as usize * 12);
    for primitive in 0..capacity {
        for corner in 0..3 {
            // The validated <= 4 GiB pointed range has at most 2^25 records,
            // so its 16-byte-stride vertex indices remain below 2^28.
            uint(&mut bytes, primitive * 8 + corner);
        }
    }
    Buffer::upload_device(
        context,
        &bytes,
        vk::BufferUsageFlags::ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_KHR
            | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
    )
}

fn geometry(
    address: u64,
    indices: u64,
    capacity: u32,
) -> vk::AccelerationStructureGeometryKHR<'static> {
    let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
        .vertex_format(vk::Format::R32G32B32_SFLOAT)
        .vertex_stride(16)
        .max_vertex(
            u32::try_from(u64::from(capacity) * 8 - 1)
                .expect("validated material capacity fits the Vulkan vertex index"),
        )
        .vertex_data(vk::DeviceOrHostAddressConstKHR {
            device_address: address,
        })
        .index_type(vk::IndexType::UINT32)
        .index_data(vk::DeviceOrHostAddressConstKHR {
            device_address: indices,
        });
    vk::AccelerationStructureGeometryKHR::default()
        .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
        .geometry(vk::AccelerationStructureGeometryDataKHR { triangles })
}
