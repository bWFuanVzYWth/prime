//! Persistent 64-block clusters with material records in reusable device-addressed pages.
use super::dynamic::TopLevel;
use super::resources::{Acceleration, Buffer, Context};
use super::textures::Textures;
use super::{float, float4, uint};
use crate::context::objects::Objects;
use crate::cpu_profile::{FrameCpu, Stage};
use crate::material_arena::{Allocation, MaterialArena};
use crate::plan::{OBJECT_BIT, validate_material_count};
use ash::vk;
use prime_scene::scene::{InstanceScene, MeshKey, Scene, SceneMesh};
use std::{collections::BTreeMap, sync::Arc};

type ClusterKey = ([i32; 3], u32);
type Signature = Vec<(MeshKey, u64, [f32; 3])>;
type GroupMembers<'a> = Vec<(MeshKey, &'a SceneMesh, [f32; 3])>;

struct Cluster {
    signature: Signature,
    allocation: Allocation,
    acceleration: Acceleration,
}

pub(super) struct Geometry {
    pub revision: u64,
    epoch: u64,
    anchor: [f64; 3],
    pub triangle_count: u64,
    // Retire TLAS before any BLAS whose address it contains.
    pub top: TopLevel,
    clusters: BTreeMap<ClusterKey, Cluster>,
    pub objects: Objects,
    instances: Vec<vk::AccelerationStructureInstanceKHR>,
    top_dirty: bool,
    static_count: u64,
    materials: MaterialArena,
    pub static_bases: Buffer,
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
            materials: MaterialArena::new(),
            static_bases: Buffer::upload_device(
                context,
                &[0; 8],
                vk::BufferUsageFlags::STORAGE_BUFFER,
            )?,
            textures: Textures::new(context, &scene.textures)?,
            rebuilt_clusters: 0,
        };
        geometry.update(context, scene)?;
        Ok(geometry)
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
            self.materials = MaterialArena::new();
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
            self.materials.free(old.allocation);
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
                self.materials.free(old.allocation);
            }
            validate_material_count(count)?;
            changes.push((*key, signature, count));
        }
        let mut allocated_changes = Vec::with_capacity(changes.len());
        for (key, signature, count) in changes {
            let allocation = self.materials.allocate(context, count)?;
            allocated_changes.push((key, signature, allocation, count));
        }
        self.rebuilt_clusters = allocated_changes.len() as u32;
        // Bound transient VkDeviceMemory allocations; clusters retain one BLAS allocation.
        // This batches independently prepared builds rather than waiting per section.
        for batch in allocated_changes.chunks(32) {
            let mut inputs = Vec::new();
            let mut uploads = Vec::new();
            let mut prepared = Vec::new();
            for (key, signature, allocation, count) in batch {
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
                    .max_vertex(
                        count
                            .checked_mul(3)
                            .and_then(|n| n.checked_sub(1))
                            .ok_or("Static BLAS vertex index overflow")?,
                    )
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
                    *allocation,
                    *count,
                    Acceleration::prepare(
                        context,
                        geometry,
                        *count,
                        vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
                    )?,
                ));
                uploads.push((
                    *allocation,
                    Buffer::upload(context, &materials, vk::BufferUsageFlags::TRANSFER_SRC)?,
                ));
                inputs.push(input);
            }
            context.submit_named("dirty_clusters", |command| unsafe {
                for (allocation, upload) in &uploads {
                    context.device.cmd_copy_buffer(
                        command,
                        upload.buffer,
                        self.materials.buffer(*allocation).buffer,
                        &[vk::BufferCopy::default()
                            .dst_offset(u64::from(allocation.first) * 128)
                            .size(upload.size)],
                    );
                }
                for (_, _, _, _, build) in &prepared {
                    build.record_unbarriered(command);
                }
                Acceleration::read_barrier(context, command);
                transfer_barrier(context, command);
            })?;
            for (key, signature, allocation, _, build) in prepared {
                self.clusters.insert(
                    key,
                    Cluster {
                        signature,
                        allocation,
                        acceleration: build.finish(),
                    },
                );
            }
        }
        self.instances.clear();
        self.instances.reserve(self.clusters.len() + 1);
        if self.clusters.len() >= OBJECT_BIT as usize {
            return Err("Too many static cluster instances".into());
        }
        let mut static_bases = Vec::with_capacity(self.clusters.len().max(1) * 8);
        for (index, ((cell, _), cluster)) in self.clusters.iter().enumerate() {
            static_bases
                .extend_from_slice(&self.materials.address(cluster.allocation).to_le_bytes());
            let origin: [f32; 3] =
                std::array::from_fn(|i| (f64::from(cell[i]) * 64.0 - scene.anchor[i]) as f32);
            self.instances.push(vk::AccelerationStructureInstanceKHR {
                transform: vk::TransformMatrixKHR {
                    matrix: [
                        1.0, 0.0, 0.0, origin[0], 0.0, 1.0, 0.0, origin[1], 0.0, 0.0, 1.0,
                        origin[2],
                    ],
                },
                instance_custom_index_and_mask: vk::Packed24_8::new(index as u32, 0xff),
                instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(
                    0,
                    vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE.as_raw() as u8,
                ),
                acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                    device_handle: cluster.acceleration.address(),
                },
            });
        }
        if static_bases.is_empty() {
            static_bases.extend_from_slice(&0u64.to_le_bytes());
        }
        if static_bases.len() as u64 > self.static_bases.size {
            self.static_bases = Buffer::new(
                context,
                (static_bases.len() as u64).next_power_of_two(),
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?;
        }
        let bases_upload =
            Buffer::upload(context, &static_bases, vk::BufferUsageFlags::TRANSFER_SRC)?;
        context.submit_named("static_cluster_bases", |command| unsafe {
            context.device.cmd_copy_buffer(
                command,
                bases_upload.buffer,
                self.static_bases.buffer,
                &[vk::BufferCopy::default().size(static_bases.len() as u64)],
            );
            transfer_barrier(context, command);
        })?;
        self.static_count = scene.meshes.values().try_fold(0u64, |count, mesh| {
            count
                .checked_add(mesh.triangles.len() as u64)
                .ok_or("Static triangle count overflow")
        })?;
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
        cpu: &mut FrameCpu,
    ) -> Result<(bool, bool), String> {
        let (changed, mut bindings) = self.objects.prepare(
            context,
            scene,
            objects,
            &self.textures,
            slot,
            self.clusters.len(),
            cpu,
        )?;
        if self.top_dirty || changed {
            let started = cpu.start();
            let count = self.instances.len();
            self.instances.extend_from_slice(&self.objects.instances);
            let result = self.top.rebuild(context, &self.instances, slot);
            self.instances.truncate(count);
            bindings |= result?;
            self.top_dirty = false;
            cpu.tlas_rebuilds += 1;
            cpu.finish(Stage::Tlas, started);
        }
        self.triangle_count = self
            .static_count
            .checked_add(self.objects.triangle_count)
            .ok_or("Triangle count overflow")?;
        Ok((changed, bindings))
    }

    pub fn cpu_load(
        &self,
        scene: &Scene,
        instances: &InstanceScene,
        cpu: &FrameCpu,
        uploaded: u64,
    ) -> [u64; 14] {
        [
            self.static_count,
            scene.dynamic.triangles.len() as u64,
            instances.prototypes.len() as u64,
            instances.instances.len() as u64,
            self.clusters.len() as u64,
            self.materials.page_count() as u64,
            self.objects.page_count() as u64,
            self.objects.count() as u64,
            (self.instances.len() + self.objects.instances.len()) as u64,
            if cpu.static_updates > 0 {
                u64::from(self.rebuilt_clusters)
            } else {
                0
            },
            u64::from(self.objects.rebuilt),
            cpu.static_updates,
            cpu.tlas_rebuilds,
            uploaded,
        ]
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
    use crate::{Camera, Renderer};
    use prime_scene::Triangle;

    fn mesh(x: f32, flags: u32, color: [f32; 4]) -> SceneMesh {
        let corners = [
            [-20.0, -20.0, 0.0],
            [20.0, -20.0, 0.0],
            [20.0, 20.0, 0.0],
            [-20.0, 20.0, 0.0],
        ];
        SceneMesh {
            revision: 1,
            flags,
            origin: [x, 0.0, 0.0],
            triangles: [[0, 1, 2], [2, 3, 0]]
                .map(|indices| Triangle {
                    positions: indices.map(|i| corners[i]),
                    colors: [color; 3],
                    uvs: [[0.0; 2]; 3],
                    texture_id: 0,
                    flags,
                })
                .into(),
        }
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; run with synchronization validation"]
    fn gpu_static_material_pages_preserve_pixels_and_retained_blas() {
        let mut scene = Scene {
            revision: 1,
            ..Default::default()
        };
        scene
            .meshes
            .insert((1, 0), mesh(0.0, 2, [1.0, 0.1, 0.1, 0.6]));
        let camera = Camera {
            position: [32.0, 0.0, 160.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let mut renderer = Renderer::new().unwrap();
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let context = renderer.context.clone();
        let geometry = renderer.geometry.as_mut().unwrap();
        let first = geometry.clusters[&([0, 0, 0], 2)].allocation;
        // Reserve the unused part of one page without producing fictitious geometry or uploading 64 MiB.
        let filler = geometry.materials.allocate(&context, 524288 - 2).unwrap();
        assert_eq!(first.page, filler.page);
        scene
            .meshes
            .insert((2, 0), mesh(64.0, 0, [0.1, 0.8, 1.0, 1.0]));
        scene.revision += 1;
        let paged = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        let second = &geometry.clusters[&([1, 0, 0], 0)];
        assert_ne!(first.page, second.allocation.page);
        let retained_blas = second.acceleration.address();
        let retained_material = geometry.materials.address(second.allocation);
        let mut fresh = Renderer::new().unwrap();
        assert_eq!(
            fresh.render(&scene, &camera, 96, 64, 0).unwrap(),
            paged,
            "Production Slang must shade equivalent triangles across distinct physical pages"
        );
        scene.meshes.remove(&(1, 0));
        scene
            .meshes
            .insert((3, 0), mesh(0.0, 1, [0.2, 1.0, 0.2, 1.0]));
        scene.revision += 1;
        let replaced = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        assert_ne!(replaced, paged);
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.clusters[&([0, 0, 0], 1)].allocation, first);
        let second = &geometry.clusters[&([1, 0, 0], 0)];
        assert_eq!(second.acceleration.address(), retained_blas);
        assert_eq!(
            geometry.materials.address(second.allocation),
            retained_material
        );
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(fresh.render(&scene, &camera, 96, 64, 0).unwrap(), replaced);
    }
}
