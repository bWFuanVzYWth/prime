//! Persistent prototype/bucket BLAS and shared shading arenas.
#[path = "object_motion.rs"]
mod motion;
use crate::cpu_profile::{FrameCpu, Stage};
use crate::dynamic::slot_buffer;
use crate::material_arena::{Allocation, MaterialArena};
use crate::plan::{
    INHERIT, MATERIAL_BYTES, MAX_MATERIAL_RECORDS, OBJECT_BIT, ObjectKey, Planner, ScenePlan,
    arena_capacity, pack_material, validate_material_count,
};
use crate::resources::{Acceleration, Buffer, Context, PreparedAcceleration};
use crate::textures::Textures;
use crate::{FRAME_SLOTS, uint};
use ash::vk;
use prime_scene::translation::BatchLimits;
use prime_scene::{instances::InstanceInput, scene::Scene};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};

struct Object {
    build: PreparedAcceleration<'static>,
    allocation: Allocation,
    capacity: u32,
    count: u32,
    opaque: bool,
    texture_dependencies: BTreeSet<u32>,
}
#[derive(Clone, Copy, Default, PartialEq)]
struct OcclusionMaterial {
    texture: u32,
    flags: u32,
    alpha: u8,
    uv: [f32; 4],
}
impl OcclusionMaterial {
    fn from_placement(p: &crate::plan::Placement) -> Self {
        Self {
            texture: p.texture_id,
            flags: p.flags,
            alpha: p.tint[3],
            uv: p.uv,
        }
    }
}

pub(crate) struct Objects {
    workers: Arc<prime_scene::workers::CpuWorkers>,
    // Acceleration objects retire before their arena and shared index input.
    objects: BTreeMap<ObjectKey, Object>,
    planner: Planner,
    motion: motion::History,
    motion_enabled: bool,
    anchor: [f64; 3],
    materials: MaterialArena,
    indices: Buffer,
    pub metadata: Buffer,
    uploads: [Option<Buffer>; FRAME_SLOTS],
    material_bytes: Vec<u8>,
    occlusion_materials: Vec<OcclusionMaterial>,
    occlusion_changed: bool,
    tlas_update: bool,
    pub instances: Vec<vk::AccelerationStructureInstanceKHR>,
    pub changed_instances: Vec<usize>,
    // Semantic edits before accepted-motion metadata settling adds its own dirty rows.
    pub history_changed_instances: Vec<usize>,
    pub history_scene_changed: bool,
    history_identity_enabled: bool,
    history_source: Option<[u64; 4]>,
    history_dynamic_origin: [f64; 3],
    pub triangle_count: u64,
    pub rebuilt: u32,
}

pub(crate) struct ObjectChanges {
    pub scene: bool,
    pub tlas: bool,
    pub bindings: bool,
    pub occlusion: bool,
    pub tlas_update: bool,
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
            motion: motion::History::default(),
            motion_enabled: false,
            anchor: [0.0; 3],
            materials: MaterialArena::with_stride(crate::packing::stride(0) as u64),
            indices: index_buffer_with_stride(context, 16, 11)?,
            metadata: Buffer::new(
                context,
                MATERIAL_BYTES,
                vk::BufferUsageFlags::STORAGE_BUFFER
                    | vk::BufferUsageFlags::TRANSFER_DST
                    | vk::BufferUsageFlags::TRANSFER_SRC,
                false,
            )?,
            uploads: std::array::from_fn(|_| None),
            material_bytes: Vec::new(),
            occlusion_materials: Vec::new(),
            occlusion_changed: false,
            tlas_update: false,
            instances: Vec::new(),
            changed_instances: Vec::new(),
            history_changed_instances: Vec::new(),
            history_scene_changed: false,
            history_identity_enabled: false,
            history_source: None,
            history_dynamic_origin: [0.0; 3],
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
    ) -> Result<ObjectChanges, String> {
        let started = cpu.start();
        let mut plan = self.planner.plan(scene, source)?;
        let history_source = [
            scene.epoch,
            source.resource_revision,
            source.instance_revision,
            scene.dynamic.revision,
        ];
        let pure_rebase = self.anchor != scene.anchor
            && self.history_source == Some(history_source)
            && self.history_dynamic_origin == scene.dynamic.origin;
        self.history_changed_instances.clear();
        self.history_scene_changed = plan.placements_changed && !pure_rebase;
        if self.history_identity_enabled && !pure_rebase {
            self.history_changed_instances
                .extend(plan.changes.iter().map(|change| change.slot));
        }
        self.history_source = Some(history_source);
        self.history_dynamic_origin = scene.dynamic.origin;
        self.anchor = scene.anchor;
        if self.motion_enabled {
            self.motion.prepare(&self.planner, &plan, source, scene);
            for &slot in self.motion.rows() {
                plan.changes
                    .push(prime_scene::translation::PlacementChange {
                        slot,
                        transform: false,
                        material: true,
                    });
            }
            plan.changes.sort_unstable_by_key(|change| change.slot);
            plan.changes.dedup_by(|next, previous| {
                if next.slot != previous.slot {
                    return false;
                }
                previous.transform |= next.transform;
                previous.material |= next.material;
                true
            });
            self.motion.clear_rows();
        }
        cpu.finish(Stage::Plan, started);
        let started = cpu.start();
        // Material-only placement edits can change ray coverage without changing
        // acceleration inputs. Keep scene/cache invalidation separate from builds.
        let mut changes = ObjectChanges {
            scene: plan.placements_changed,
            tlas: plan.tlas_changed,
            bindings: false,
            occlusion: false,
            tlas_update: false,
        };
        let result = self.execute(context, &plan, textures, slot, builds);
        self.triangle_count = plan.triangle_count;
        self.planner.recycle(plan);
        changes.bindings = result?;
        changes.occlusion = self.occlusion_changed;
        changes.tlas_update = self.tlas_update;
        cpu.finish(Stage::Execute, started);
        Ok(changes)
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
        self.occlusion_changed = plan.tlas_changed;
        self.tlas_update = plan.geometry.is_empty() && plan.removed.is_empty();
        self.changed_instances.clear();
        if plan.geometry.is_empty() && plan.removed.is_empty() && plan.changes.is_empty() {
            return Ok(false);
        }
        for key in &plan.removed {
            if let Some(old) = self.objects.remove(key) {
                old.build.retire(builds);
                self.materials.free(old.allocation);
            }
        }
        let plans = plan
            .geometry
            .iter()
            .map(|item| {
                crate::packing::Plan::new(
                    [crate::packing::Input {
                        triangles: self.planner.triangles(item).into(),
                        offset: None,
                        flags: None,
                    }],
                    false,
                )
            })
            .collect::<Result<Vec<_>, String>>()?;
        let mut largest = 16;
        let mut locations = Vec::with_capacity(plan.geometry.len());
        for (item, packing) in plan.geometry.iter().zip(&plans) {
            let count = packing.groups[0].count;
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
            locations.push((item.key, allocation, count, capacity, replace));
        }
        let mut bindings = false;
        if u64::from(largest) * 24 > self.indices.size {
            self.indices = index_buffer_with_stride(context, largest, 11)?;
        }
        let packed_bytes = plans
            .iter()
            .try_fold(0usize, |sum, p| sum.checked_add(p.bytes()))
            .ok_or("Object upload size overflow")?;
        self.material_bytes.clear();
        let mut copies: BTreeMap<vk::Buffer, Vec<vk::BufferCopy>> = BTreeMap::new();
        let mut offset = 0;
        for ((item, &(key, allocation, count, capacity, replace)), packing) in
            plan.geometry.iter().zip(&locations).zip(&plans)
        {
            let size = crate::packing::stride(0) as u64;
            copies
                .entry(self.materials.buffer(allocation).buffer)
                .or_default()
                .push(
                    vk::BufferCopy::default()
                        .src_offset(offset)
                        .dst_offset(u64::from(allocation.first) * size)
                        .size(u64::from(count) * size),
                );
            offset += u64::from(count) * size;
            let opaque = self
                .planner
                .triangles(item)
                .iter()
                .all(|triangle| triangle.flags == 0);
            let mut texture_dependencies = BTreeSet::new();
            packing.texture_dependencies(&mut texture_dependencies);
            if replace {
                let build = Acceleration::prepare_with_flags(
                    context,
                    builds,
                    geometry(
                        self.materials.address(allocation),
                        self.indices.address(),
                        capacity,
                    ),
                    capacity * 2,
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
                        capacity,
                        count,
                        opaque,
                        texture_dependencies,
                    },
                );
            } else {
                let object = self.objects.get_mut(&key).unwrap();
                object.count = count;
                object.opaque = opaque;
                object.texture_dependencies = texture_dependencies;
                object.build.ensure_scratch(builds)?;
            }
        }
        let count = self.planner.placement_count();
        self.tlas_update &= count == self.instances.len();
        self.occlusion_materials
            .resize(count, OcclusionMaterial::default());
        let metadata_bytes = (count as u64 * MATERIAL_BYTES).max(MATERIAL_BYTES);
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
                let occlusion = OcclusionMaterial::from_placement(placement);
                self.occlusion_changed |= self.occlusion_materials[index] != occlusion;
                self.occlusion_materials[index] = occlusion;
                let texture = if placement.texture_id == INHERIT {
                    INHERIT
                } else {
                    textures.index(placement.texture_id)?
                };
                let offset = packed_bytes as u64 + self.material_bytes.len() as u64;
                pack_material(
                    &mut self.material_bytes,
                    self.materials.address(object.allocation),
                    texture,
                    placement,
                    self.motion.previous(index, self.anchor),
                );
                let regions = copies.entry(self.metadata.buffer).or_default();
                let dst = index as u64 * MATERIAL_BYTES;
                if let Some(last) = regions.last_mut().filter(|last| {
                    last.src_offset + last.size == offset && last.dst_offset + last.size == dst
                }) {
                    last.size += MATERIAL_BYTES;
                } else {
                    regions.push(
                        vk::BufferCopy::default()
                            .src_offset(offset)
                            .dst_offset(dst)
                            .size(MATERIAL_BYTES),
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
                let instance = vk::AccelerationStructureInstanceKHR {
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
                let previous = &self.instances[index];
                self.tlas_update &= previous.instance_custom_index_and_mask
                    == instance.instance_custom_index_and_mask
                    && previous.instance_shader_binding_table_record_offset_and_flags
                        == instance.instance_shader_binding_table_record_offset_and_flags
                    && unsafe {
                        previous.acceleration_structure_reference.device_handle
                            == instance.acceleration_structure_reference.device_handle
                    }
                    && unsafe { instance.acceleration_structure_reference.device_handle != 0 };
                self.instances[index] = instance;
                self.changed_instances.push(index);
            }
        }
        let upload_bytes = packed_bytes
            .checked_add(self.material_bytes.len())
            .ok_or("Object upload size overflow")?;
        if upload_bytes == 0 {
            return Ok(bindings);
        }
        let staging = slot_buffer(
            context,
            &mut self.uploads[slot],
            upload_bytes as u64,
            vk::BufferUsageFlags::TRANSFER_SRC,
        )?;
        // SAFETY: Frame preparation has proved this slot's previous GPU submission complete.
        // Packing workers write disjoint ranges and join before returning. A failed write is
        // never recorded, so no GPU consumer can observe an incompletely initialized batch.
        unsafe {
            staging.write_with(0, upload_bytes, |output| {
                let (mut geometry, materials) = output.split_at_mut(packed_bytes);
                for packing in &plans {
                    for group in &packing.groups {
                        let size = group.count as usize * crate::packing::stride(group.format);
                        let (destination, tail) = geometry.split_at_mut(size);
                        packing.pack(&self.workers, group, destination, &textures.indices)?;
                        geometry = tail;
                    }
                }
                for (out, byte) in materials.iter_mut().zip(&self.material_bytes) {
                    out.write(*byte);
                }
                Ok(())
            })?;
        }
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
                    count * 2,
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

    pub fn set_motion_enabled(&mut self, enabled: bool) {
        if self.motion_enabled != enabled {
            self.motion = motion::History::default();
            self.motion_enabled = enabled;
        }
    }

    pub fn history_primitive_count(&self, slot: usize) -> u32 {
        self.objects[&self.planner.placement(slot).key].count * 2
    }

    // Called only for nonempty texture support events. Prototype dependencies were
    // captured at packing time; instance overrides replace the inherited texture.
    pub fn history_support_slots<'a>(
        &'a self,
        changed: &'a BTreeSet<u32>,
    ) -> impl Iterator<Item = (usize, u32)> + 'a {
        (0..self.planner.placement_count()).filter_map(move |slot| {
            let placement = self.planner.placement(slot);
            let object = &self.objects[&placement.key];
            crate::textures::history_support::placement_depends_on(
                placement.texture_id,
                &object.texture_dependencies,
                changed,
            )
            .then_some((slot, object.count * 2))
        })
    }

    pub fn enable_history_identity(&mut self) {
        self.history_identity_enabled = true;
    }

    /// Only an actual ordered submission promotes a prepared rigid pose to history.
    pub fn commit_motion(&mut self) {
        self.motion.commit();
    }

    pub fn reset_motion(&mut self) {
        self.motion.reset();
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

pub(crate) fn index_buffer_with_stride(
    context: &Arc<Context>,
    capacity: u32,
    stride: u32,
) -> Result<Buffer, String> {
    validate_material_count(capacity)?;
    let mut bytes = Vec::with_capacity(capacity as usize * 24);
    for quad in 0..capacity {
        for corner in [0, 1, 2, 2, 3, 0] {
            uint(&mut bytes, quad * stride + corner);
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
            u32::try_from(u64::from(capacity) * 11 - 1)
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
