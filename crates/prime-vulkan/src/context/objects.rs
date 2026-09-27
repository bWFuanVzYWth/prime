//! Persistent prototype/bucket BLAS and shared shading arenas.
use crate::cpu_profile::{FrameCpu, Stage};
use crate::dynamic::slot_buffer;
use crate::material_arena::{Allocation, MaterialArena};
use crate::plan::{
    INHERIT, MAX_MATERIAL_RECORDS, OBJECT_BIT, ObjectKey, Planner, ScenePlan, arena_capacity,
    pack_material, validate_material_count,
};
use crate::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use crate::textures::Textures;
use crate::{FRAME_SLOTS, uint};
use ash::vk;
use prime_scene::translation::BatchLimits;
use prime_scene::{instances::InstanceInput, scene::Scene};
use std::{collections::BTreeMap, sync::Arc};

struct Object {
    build: PreparedAcceleration<'static>,
    allocation: Allocation,
    capacity: u32,
    count: u32,
    opaque: bool,
}

pub(crate) struct Objects {
    workers: Arc<prime_scene::workers::CpuWorkers>,
    // Acceleration objects retire before their arena and shared index input.
    objects: BTreeMap<ObjectKey, Object>,
    planner: Planner,
    materials: MaterialArena,
    indices: Buffer,
    pub metadata: Buffer,
    uploads: [Option<Buffer>; FRAME_SLOTS],
    bytes: Vec<u8>,
    pub instances: Vec<vk::AccelerationStructureInstanceKHR>,
    pub changed_instances: Vec<usize>,
    pub triangle_count: u64,
    pub rebuilt: u32,
}

impl Objects {
    pub fn new(
        context: &Arc<Context>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        Ok(Self {
            workers,
            objects: BTreeMap::new(),
            planner: Planner::new(BatchLimits {
                triangles: MAX_MATERIAL_RECORDS,
                placements: OBJECT_BIT - 1,
            })?,
            materials: MaterialArena::new(),
            indices: index_buffer(context, 16)?,
            metadata: Buffer::new(
                context,
                48,
                vk::BufferUsageFlags::STORAGE_BUFFER
                    | vk::BufferUsageFlags::TRANSFER_DST
                    | vk::BufferUsageFlags::TRANSFER_SRC,
                false,
            )?,
            uploads: std::array::from_fn(|_| None),
            bytes: Vec::new(),
            instances: Vec::new(),
            changed_instances: Vec::new(),
            triangle_count: 0,
            rebuilt: 0,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn prepare(
        &mut self,
        context: &Arc<Context>,
        scene: &Scene,
        source: InstanceInput<'_>,
        textures: &Textures,
        slot: usize,
        builds: &mut crate::arena::Arena,
        cpu: &mut FrameCpu,
    ) -> Result<(bool, bool), String> {
        let started = cpu.start();
        let plan = self.planner.plan(scene, source)?;
        cpu.finish(Stage::Plan, started);
        let started = cpu.start();
        let changed = plan.tlas_changed;
        let result = self.execute(context, &plan, textures, slot, builds);
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
        builds: &mut crate::arena::Arena,
    ) -> Result<bool, String> {
        self.rebuilt = plan.geometry.len() as u32;
        self.changed_instances.clear();
        if plan.geometry.is_empty() && plan.removed.is_empty() && !plan.placements_changed {
            return Ok(false);
        }
        for key in &plan.removed {
            if let Some(old) = self.objects.remove(key) {
                old.build.retire(builds);
                self.materials.free(old.allocation);
            }
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
                    old.build.retire(builds);
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
        // Seal the complete geometry batch before dispatch: many small prototypes
        // share the same disjoint packing pass without one task per prototype.
        let inputs: Vec<_> = plan
            .geometry
            .iter()
            .map(|item| crate::packing::Input {
                triangles: self.planner.triangles(item),
                offset: None,
                flags: None,
            })
            .collect();
        let packed_bytes = inputs
            .iter()
            .try_fold(0usize, |bytes, input| {
                input
                    .triangles
                    .len()
                    .checked_mul(128)
                    .and_then(|len| bytes.checked_add(len))
            })
            .ok_or("Object upload size overflow")?;
        self.bytes.resize(packed_bytes, 0);
        crate::packing::pack_ranges(&self.workers, &mut self.bytes, &inputs, &textures.indices)?;
        let mut copies: BTreeMap<vk::Buffer, Vec<vk::BufferCopy>> = BTreeMap::new();
        let mut offset = 0;
        for (item, &(key, allocation, count, capacity, replace)) in
            plan.geometry.iter().zip(&locations)
        {
            let triangles = self.planner.triangles(item);
            copies
                .entry(self.materials.buffer(allocation).buffer)
                .or_default()
                .push(
                    vk::BufferCopy::default()
                        .src_offset(offset)
                        .dst_offset(u64::from(allocation.first) * 128)
                        .size(u64::from(count) * 128),
                );
            offset += u64::from(count) * 128;
            let opaque = triangles.iter().all(|triangle| triangle.flags == 0);
            if replace {
                // Nonopaque geometry permits per-instance material overrides. A
                // uniform opaque placement uses FORCE_OPAQUE in the TLAS instead.
                let build = Acceleration::prepare_with_flags(
                    context,
                    builds,
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
                object.build.ensure_scratch(builds)?;
            }
        }
        let count = self.planner.placement_count();
        let metadata_bytes = (count as u64 * 48).max(48);
        let metadata_grown = metadata_bytes > self.metadata.size;
        if metadata_grown {
            let next = Buffer::new(
                context,
                metadata_bytes
                    .checked_next_power_of_two()
                    .ok_or("Instance metadata overflow")?,
                vk::BufferUsageFlags::STORAGE_BUFFER
                    | vk::BufferUsageFlags::TRANSFER_DST
                    | vk::BufferUsageFlags::TRANSFER_SRC,
                false,
            )?;
            // Preserve clean slots entirely on GPU when capacity grows.
            context.submit_named("grow_instance_metadata", |command| unsafe {
                context.device.cmd_copy_buffer(
                    command,
                    self.metadata.buffer,
                    next.buffer,
                    &[vk::BufferCopy::default().size(self.metadata.size)],
                );
                let memory = [vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(
                        vk::AccessFlags::TRANSFER_WRITE | vk::AccessFlags::SHADER_READ,
                    )];
                context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::TRANSFER | vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &memory,
                    &[],
                    &[],
                );
            })?;
            self.metadata = next;
            bindings = true;
        }
        self.instances.resize(
            count,
            vk::AccelerationStructureInstanceKHR {
                transform: vk::TransformMatrixKHR { matrix: [0.0; 12] },
                instance_custom_index_and_mask: vk::Packed24_8::new(0, 0),
                instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(0, 0),
                acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                    device_handle: 0,
                },
            },
        );
        for change in &plan.changes {
            let index = change.slot;
            let placement = self.planner.placement(index);
            let object = self
                .objects
                .get(&placement.key)
                .ok_or("GPU instance prototype missing")?;
            if change.material {
                let texture = if placement.texture_id == INHERIT {
                    INHERIT
                } else {
                    textures.index(placement.texture_id)?
                };
                let offset = self.bytes.len() as u64;
                pack_material(
                    &mut self.bytes,
                    self.materials.address(object.allocation),
                    texture,
                    placement,
                );
                let regions = copies.entry(self.metadata.buffer).or_default();
                let dst = index as u64 * 48;
                if let Some(last) = regions.last_mut().filter(|last| {
                    last.src_offset + last.size == offset && last.dst_offset + last.size == dst
                }) {
                    last.size += 48;
                } else {
                    regions.push(
                        vk::BufferCopy::default()
                            .src_offset(offset)
                            .dst_offset(dst)
                            .size(48),
                    );
                }
            }
            if change.transform {
                let opaque = placement.flags == 0 || placement.flags == INHERIT && object.opaque;
                let flags = vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE
                    | if opaque {
                        vk::GeometryInstanceFlagsKHR::FORCE_OPAQUE
                    } else {
                        vk::GeometryInstanceFlagsKHR::FORCE_NO_OPAQUE
                    };
                self.instances[index] = vk::AccelerationStructureInstanceKHR {
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
                };
                self.changed_instances.push(index);
            }
        }
        upload_capacity += (self.bytes.len() - packed_bytes) as u64;
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
        for &(key, _, _, _, _) in &locations {
            self.objects
                .get_mut(&key)
                .unwrap()
                .build
                .release_scratch(builds);
        }
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

pub(crate) fn index_buffer(context: &Arc<Context>, capacity: u32) -> Result<Buffer, String> {
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
