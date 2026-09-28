//! Executes translated static batches; material records live in reusable device-addressed pages.
use super::dynamic::TopLevel;
use super::resources::{Acceleration, Buffer, Context};
use super::textures::Textures;
use crate::context::objects::Objects;
use crate::cpu_profile::{FrameCpu, Stage};
use crate::material_arena::{Allocation, MaterialArena};
use crate::plan::{MAX_MATERIAL_RECORDS, OBJECT_BIT, validate_material_count};
use ash::vk;
use prime_scene::instances::InstanceInput;
use prime_scene::{
    incremental::{SceneInput, ScenePublication},
    scene::{InstanceScene, Scene},
    spatial::Cell,
    translation::{TerrainLimits, TerrainPlanner},
};
use std::{collections::BTreeMap, sync::Arc};

struct Cluster {
    allocations: Vec<Allocation>,
    acceleration: Acceleration,
}

pub(super) struct Geometry {
    workers: Arc<prime_scene::workers::CpuWorkers>,
    indices: Buffer,
    builds: crate::arena::Arena,
    uploads: crate::arena::Arena,
    pub revision: ScenePublication,
    epoch: u64,
    anchor: [f64; 3],
    pub triangle_count: u64,
    // Retire TLAS before any BLAS whose address it contains.
    pub top: TopLevel,
    clusters: BTreeMap<Cell, Cluster>,
    static_planner: TerrainPlanner,
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
    pub fn new(
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        let mut uploads = crate::arena::Arena::new(context, true);
        let textures = Textures::new(context, scene.texture_input(), &mut uploads)?;
        let mut geometry = Self {
            workers: workers.clone(),
            indices: crate::context::objects::index_buffer(context, 16)?,
            builds: crate::arena::Arena::new(context, false),
            uploads,
            revision: scene.publication(),
            epoch: scene.epoch,
            anchor: scene.anchor,
            triangle_count: 0,
            top: TopLevel::default(),
            clusters: BTreeMap::new(),
            static_planner: TerrainPlanner::new(TerrainLimits {
                triangles_per_geometry: MAX_MATERIAL_RECORDS,
                geometry_records: OBJECT_BIT - 1,
            })?,
            objects: Objects::new(context, workers)?,
            instances: Vec::new(),
            top_dirty: true,
            static_count: 0,
            materials: MaterialArena::new(),
            // update() publishes every entry before TLAS use. An initial zero
            // upload would be overwritten in the same host command buffer.
            static_bases: Buffer::new(
                context,
                8,
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?,
            textures,
            rebuilt_clusters: 0,
        };
        geometry.update(context, scene)?;
        Ok(geometry)
    }
    pub fn begin_frame(&mut self, context: &Context, completed: u64) {
        // Frozen frames do not execute an object plan; do not report the prior frame's rebuilds.
        self.objects.rebuilt = 0;
        let serial = context.retirement_serial();
        self.builds.begin(completed, serial);
        self.uploads.begin(completed, serial);
    }
    #[cfg(test)]
    pub fn assert_incremental_workspaces(&self) -> (usize, u64) {
        self.top
            .assert_current_input(&self.instances, &self.objects.instances);
        (
            self.builds.page_count() + self.uploads.page_count(),
            self.builds.reserved_bytes() + self.uploads.reserved_bytes(),
        )
    }
    pub fn needs_update(&self, scene: SceneInput<'_>) -> bool {
        self.revision != scene.publication() || self.anchor != scene.anchor
    }

    pub fn same_owner(&self, scene: SceneInput<'_>) -> bool {
        self.revision.same_owner(scene.publication())
    }
    pub fn update(&mut self, context: &Arc<Context>, scene: SceneInput<'_>) -> Result<(), String> {
        if scene.anchor.iter().any(|v| !v.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        if self.epoch != scene.epoch {
            self.textures = Textures::new(context, scene.texture_input(), &mut self.uploads)?;
        } else {
            self.textures
                .update(context, scene.texture_input(), &mut self.uploads)?;
        }
        let plan = self.static_planner.plan_input(scene)?;
        self.top_dirty |= plan.placements_changed;
        if self.epoch != scene.epoch {
            for (_, old) in std::mem::take(&mut self.clusters) {
                old.acceleration.retire(&mut self.builds);
            }
            self.materials = MaterialArena::new();
            self.epoch = scene.epoch;
        }
        for key in &plan.removed {
            if let Some(old) = self.clusters.remove(key) {
                old.acceleration.retire(&mut self.builds);
                for allocation in old.allocations {
                    self.materials.free(allocation);
                }
            }
        }
        let mut allocated_changes = Vec::with_capacity(plan.geometry.len());
        for update in &plan.geometry {
            if let Some(old) = self.clusters.remove(&update.key) {
                old.acceleration.retire(&mut self.builds);
                for allocation in old.allocations {
                    self.materials.free(allocation);
                }
            }
            let allocations = update
                .geometries
                .iter()
                .map(|geometry| {
                    validate_material_count(geometry.triangle_count)?;
                    self.materials.allocate(context, geometry.triangle_count)
                })
                .collect::<Result<Vec<_>, String>>()?;
            allocated_changes.push((update, allocations));
        }
        self.rebuilt_clusters = allocated_changes.len() as u32;
        let largest = allocated_changes
            .iter()
            .flat_map(|(update, _)| &update.geometries)
            .map(|g| g.triangle_count)
            .max()
            .unwrap_or(0);
        if u64::from(largest) * 12 > self.indices.size {
            self.indices = crate::context::objects::index_buffer(context, largest)?;
        }
        // Each wave completes CPU preparation synchronously. Its GPU resources retain
        // their real submission lifetime; wave size is not a live-allocation bound.
        for batch in allocated_changes.chunks(32) {
            let mut uploads = Vec::new();
            let mut prepared = Vec::new();
            for (update, allocations) in batch {
                let count: usize = update
                    .geometries
                    .iter()
                    .map(|g| g.triangle_count as usize)
                    .sum();
                let bytes = count
                    .checked_mul(128)
                    .ok_or("Static packing byte overflow")?;
                let sources: Vec<_> = update
                    .geometries
                    .iter()
                    .flat_map(|geometry| {
                        geometry.members.iter().map(|member| crate::packing::Input {
                            triangles: member.triangles.view(member.range.clone()),
                            offset: Some(member.offset),
                            flags: Some(geometry.flags),
                        })
                    })
                    .collect();
                let mut upload = self.uploads.allocate(context, bytes as u64, 16)?;
                upload.write_with(|output| {
                    crate::packing::pack_uninit_ranges(
                        &self.workers,
                        output,
                        &sources,
                        &self.textures.indices,
                    )
                })?;
                let mut geometries = Vec::with_capacity(update.geometries.len());
                let mut counts = Vec::with_capacity(update.geometries.len());
                for (geometry, &allocation) in update.geometries.iter().zip(allocations) {
                    let count = geometry.triangle_count;
                    let triangles = vk::AccelerationStructureGeometryTrianglesDataKHR::default()
                        .vertex_format(vk::Format::R32G32B32_SFLOAT)
                        .vertex_data(vk::DeviceOrHostAddressConstKHR {
                            device_address: self.materials.address(allocation),
                        })
                        .vertex_stride(16)
                        .max_vertex(count * 8 - 1)
                        .index_type(vk::IndexType::UINT32)
                        .index_data(vk::DeviceOrHostAddressConstKHR {
                            device_address: self.indices.address(),
                        });
                    geometries.push(
                        vk::AccelerationStructureGeometryKHR::default()
                            .geometry_type(vk::GeometryTypeKHR::TRIANGLES)
                            .flags(if geometry.flags == 0 {
                                vk::GeometryFlagsKHR::OPAQUE
                            } else {
                                vk::GeometryFlagsKHR::empty()
                            })
                            .geometry(vk::AccelerationStructureGeometryDataKHR { triangles }),
                    );
                    counts.push(count);
                }
                prepared.push((
                    update.key,
                    allocations,
                    Acceleration::prepare_geometries(
                        context,
                        &mut self.builds,
                        geometries,
                        &counts,
                    )?,
                ));
                uploads.push(upload);
            }
            context.submit_named("dirty_clusters", |command| unsafe {
                for ((_, allocations, _), upload) in prepared.iter().zip(&uploads) {
                    let mut offset = 0;
                    for &allocation in *allocations {
                        let size = u64::from(allocation.count) * 128;
                        context.device.cmd_copy_buffer(
                            command,
                            upload.buffer.buffer,
                            self.materials.buffer(allocation).buffer,
                            &[vk::BufferCopy::default()
                                .src_offset(upload.offset + offset)
                                .dst_offset(u64::from(allocation.first) * 128)
                                .size(size)],
                        );
                        offset += size;
                    }
                }
                transfer_barrier(context, command);
                for (_, _, build) in &prepared {
                    build.record_unbarriered(command);
                }
                Acceleration::read_barrier(context, command);
            })?;
            for upload in uploads {
                self.uploads.retire(upload);
            }
            for (key, allocations, build) in prepared {
                self.clusters.insert(
                    key,
                    Cluster {
                        allocations: allocations.clone(),
                        acceleration: build.finish(&mut self.builds),
                    },
                );
            }
        }
        if !plan.placements_changed {
            self.revision = scene.publication();
            self.anchor = scene.anchor;
            self.static_count = plan.triangle_count;
            self.static_planner.recycle(plan);
            return Ok(());
        }
        self.instances.clear();
        self.instances.reserve(self.clusters.len() + 1);
        let mut static_bases = Vec::with_capacity(self.clusters.len().max(1) * 8);
        for placement in self.static_planner.placements() {
            let cluster = &self.clusters[&placement.key];
            // The instance ID points to the first geometry address; hardware geometry index
            // selects the range without a second pointer lookup or per-primitive search.
            let index = static_bases.len() / 8;
            for &allocation in &cluster.allocations {
                static_bases.extend_from_slice(&self.materials.address(allocation).to_le_bytes());
            }
            self.instances.push(vk::AccelerationStructureInstanceKHR {
                transform: vk::TransformMatrixKHR {
                    matrix: placement.transform,
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
        let bases_upload = self
            .uploads
            .allocate(context, static_bases.len() as u64, 16)?;
        bases_upload.write(&static_bases)?;
        context.submit_named("static_cluster_bases", |command| unsafe {
            context.device.cmd_copy_buffer(
                command,
                bases_upload.buffer.buffer,
                self.static_bases.buffer,
                &[vk::BufferCopy::default()
                    .src_offset(bases_upload.offset)
                    .size(static_bases.len() as u64)],
            );
            transfer_barrier(context, command);
        })?;
        self.uploads.retire(bases_upload);
        self.static_count = plan.triangle_count;
        self.static_planner.recycle(plan);
        self.revision = scene.publication();
        self.anchor = scene.anchor;
        Ok(())
    }

    pub fn prepare_dynamic<'a>(
        &mut self,
        context: &Arc<Context>,
        scene: &Scene,
        objects: impl Into<InstanceInput<'a>>,
        slot: usize,
        cpu: &mut FrameCpu,
    ) -> Result<(bool, bool), String> {
        let (changed, mut bindings) = self.objects.prepare(
            context,
            scene,
            objects.into(),
            &self.textures,
            slot,
            &mut self.builds,
            cpu,
        )?;
        if self.top_dirty || changed {
            let started = cpu.start();
            bindings |= self.top.rebuild(
                context,
                &self.instances,
                &self.objects.instances,
                &self.objects.changed_instances,
                self.top_dirty,
                slot,
                &mut self.builds,
            )?;
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
            vk::AccessFlags::ACCELERATION_STRUCTURE_READ_KHR
                | vk::AccessFlags::SHADER_READ
                | vk::AccessFlags::TRANSFER_READ
                | vk::AccessFlags::TRANSFER_WRITE,
        )];
    unsafe {
        context.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::ACCELERATION_STRUCTURE_BUILD_KHR
                | vk::PipelineStageFlags::COMPUTE_SHADER
                | vk::PipelineStageFlags::TRANSFER,
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
    use prime_scene::{
        SceneMesh, Triangle,
        spatial::{CELL_EDGE, Cell},
    };

    fn cell(coordinates: [i32; 3]) -> Cell {
        Cell::containing(coordinates.map(|v| f64::from(v) * CELL_EDGE)).unwrap()
    }

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
            origin: [f64::from(x), 0.0, 0.0],
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
    fn gpu_incremental_source_matches_fresh_snapshots_and_retains_unaffected_blas() {
        use prime_scene::{
            SourceScene, incremental::TranslatedScene, protocol::MAGIC, settings::RenderMode,
        };
        fn header(op: u32) -> Vec<u8> {
            let mut bytes = Vec::new();
            for value in [MAGIC, prime_scene::protocol::ABI_VERSION, op, 0] {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend(1_u64.to_le_bytes());
            bytes
        }
        fn section(id: u64, sequence: u64, color: Option<[u8; 4]>) -> Vec<u8> {
            let mut bytes = header(8);
            bytes.extend(id.to_le_bytes());
            bytes.extend(sequence.to_le_bytes());
            let slot = id % 64;
            for value in [
                ((id / 64) * 64 + (slot / 16) * 16) as f64,
                ((slot / 4 % 4) * 16) as f64,
                ((slot % 4) * 16) as f64,
            ] {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend(u32::from(color.is_some()).to_le_bytes());
            bytes.extend(0_u32.to_le_bytes());
            if let Some(color) = color {
                for value in [0_u32, 7, 0, 4, 4, 24, 0, 12, 16, 0] {
                    bytes.extend(value.to_le_bytes());
                }
                for position in [
                    [-20_f32, -20., 0.],
                    [20., -20., 0.],
                    [20., 20., 0.],
                    [-20., 20., 0.],
                ] {
                    for value in position {
                        bytes.extend(value.to_le_bytes());
                    }
                    bytes.extend(color);
                    bytes.extend([0_u8; 8]);
                }
            }
            bytes
        }
        fn texture(color: [u8; 4]) -> Vec<u8> {
            let mut bytes = header(4);
            for value in [7_u32, 1, 1, 0] {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend(color);
            bytes
        }
        let camera = Camera {
            position: [32., 0., 160.],
            forward: [0., 0., -1.],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            vertical_fov_radians: 1.0,
        };
        let mut source = SourceScene::default();
        source.submit(&header(1)).unwrap();
        source.submit(&texture([255; 4])).unwrap();
        for id in 0..128 {
            source
                .submit(&section(
                    id,
                    1,
                    (id % 64 == 0).then_some([255, 180, 100, 255]),
                ))
                .unwrap();
        }
        let mut translated = TranslatedScene::default();
        let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
        let mut host = crate::HostBenchmark::new(96, 64).unwrap();
        let mut retained = None;
        let mut previous = Vec::new();
        for stage in 0..6 {
            match stage {
                1 => source
                    .submit(&section(0, 2, Some([64, 255, 64, 255])))
                    .unwrap(),
                2 => source.submit(&texture([64, 180, 255, 255])).unwrap(),
                3 => {
                    let mut packet = header(3);
                    for value in [63_u64, 2] {
                        packet.extend(value.to_le_bytes());
                    }
                    source.submit(&packet).unwrap();
                }
                4 => source.submit(&section(63, 3, None)).unwrap(),
                5 => {
                    let mut reset = header(1);
                    reset[16..24].copy_from_slice(&2_u64.to_le_bytes());
                    source.submit(&reset).unwrap();
                }
                _ => {}
            }
            translated.update(&mut source, [0.; 3]).unwrap();
            let pixels = renderer
                .render_with_instances(&translated, source.instances(), &camera, 96, 64, 0)
                .unwrap();
            host.enqueue_with_instances(&translated, source.instances(), &camera, stage)
                .unwrap();
            let mut fresh = Renderer::with_mode(RenderMode::Realtime).unwrap();
            let expected = fresh
                .render(&source.translate([0.; 3]).unwrap(), &camera, 96, 64, 0)
                .unwrap();
            assert_eq!(
                pixels, expected,
                "stage {stage}: source delta must match a fresh full snapshot"
            );
            if stage > 0 {
                assert_ne!(pixels, previous, "stage {stage} must have visible coverage");
            }
            previous = pixels;
            let geometry = renderer.geometry.as_ref().unwrap();
            if stage < 5 {
                let blas = geometry.clusters[&cell([1, 0, 0])].acceleration.address();
                if let Some(retained) = retained {
                    assert_eq!(blas, retained);
                }
                retained = Some(blas);
            } else {
                assert!(geometry.clusters.is_empty());
            }
            assert_eq!(
                geometry.rebuilt_clusters,
                match stage {
                    0 => 2,
                    1 | 4 => 1,
                    _ => 0,
                }
            );
        }
        let completed = host.drain().unwrap();
        assert_eq!(
            completed.len(),
            6,
            "Every borrowed-host submission must have completion proof"
        );
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
        scene
            .ready_terrain
            .extend([cell([0, 0, 0]), cell([1, 0, 0])]);
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
        let first = geometry.clusters[&cell([0, 0, 0])].allocations[0];
        // Reserve the unused part of one page without producing fictitious geometry or uploading 64 MiB.
        let filler = geometry.materials.allocate(&context, 524288 - 2).unwrap();
        assert_eq!(first.page, filler.page);
        scene
            .meshes
            .insert((2, 0), mesh(64.0, 0, [0.1, 0.8, 1.0, 1.0]));
        scene.revision += 1;
        let paged = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        let second = &geometry.clusters[&cell([1, 0, 0])];
        assert_ne!(first.page, second.allocations[0].page);
        let retained_blas = second.acceleration.address();
        let retained_material = geometry.materials.address(second.allocations[0]);
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
        assert_eq!(geometry.clusters[&cell([0, 0, 0])].allocations[0], first);
        let second = &geometry.clusters[&cell([1, 0, 0])];
        assert_eq!(second.acceleration.address(), retained_blas);
        assert_eq!(
            geometry.materials.address(second.allocations[0]),
            retained_material
        );
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(fresh.render(&scene, &camera, 96, 64, 0).unwrap(), replaced);
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; compact source versus scalar triangle pixels"]
    fn gpu_compact_terrain_matches_triangles_for_all_materials_and_replacements() {
        use prime_scene::geometry::{CompiledQuad, MeshGeometry};
        let mut scene = Scene {
            revision: 1,
            ..Default::default()
        };
        for flags in 0..3 {
            let mut mesh = mesh(
                flags as f32 * 64.,
                flags,
                [0.2, 0.6, 0.8, if flags == 2 { 0.6 } else { 1. }],
            );
            let a = mesh.triangles.triangle(0);
            let b = mesh.triangles.triangle(1);
            let mut quad = CompiledQuad {
                positions: [
                    a.positions[0],
                    a.positions[1],
                    a.positions[2],
                    b.positions[1],
                ],
                uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                color: a.colors[0],
                texture_id: 0,
                flags,
            };
            quad.positions[3][2] = 2.;
            mesh.triangles = MeshGeometry::Quads(vec![quad].into());
            scene.meshes.insert((flags as u64, flags), mesh);
            scene.ready_terrain.insert(cell([flags as i32, 0, 0]));
        }
        let camera = Camera {
            position: [64., 0., 160.],
            forward: [0., 0., -1.],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            vertical_fov_radians: 1.,
        };
        let mut compact = Renderer::new().unwrap();
        let mut previous = Vec::new();
        for step in 0..3 {
            if step > 0 {
                for mesh in scene.meshes.values_mut() {
                    let MeshGeometry::Quads(quads) = &mut mesh.triangles else {
                        unreachable!()
                    };
                    let quad = &mut Arc::make_mut(quads)[0];
                    quad.color[0] += 0.2;
                    quad.positions[3][2] += 3.;
                    mesh.revision += 1;
                }
                scene.revision += 1;
            }
            let actual = compact.render(&scene, &camera, 96, 64, 0).unwrap();
            let mut reference = Scene {
                revision: scene.revision,
                ready_terrain: scene.ready_terrain.clone(),
                ..Default::default()
            };
            for (&key, mesh) in &scene.meshes {
                let mut mesh = mesh.clone();
                let MeshGeometry::Quads(quads) = &mesh.triangles else {
                    unreachable!()
                };
                mesh.triangles = quads
                    .iter()
                    .flat_map(|q| {
                        [[0, 1, 2], [2, 3, 0]].map(|c| Triangle {
                            positions: c.map(|i| q.positions[i]),
                            colors: [q.color; 3],
                            uvs: c.map(|i| q.uvs[i]),
                            texture_id: q.texture_id,
                            flags: q.flags,
                        })
                    })
                    .collect::<Vec<_>>()
                    .into();
                reference.meshes.insert(key, mesh);
            }
            let expected = Renderer::new()
                .unwrap()
                .render(&reference, &camera, 96, 64, 0)
                .unwrap();
            assert_eq!(actual, expected, "step {step}");
            assert_ne!(
                actual, previous,
                "each replacement must visibly change pixels"
            );
            previous = actual;
        }
    }

    #[test]
    #[ignore = "requires Vulkan; translated dense parts, shared cell, independent static/dynamic lifetime"]
    fn gpu_translated_parts_preserve_pixels_and_static_dynamic_resources_are_independent() {
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        scene
            .meshes
            .insert((1, 0), mesh(0.0, 0, [1.0, 0.1, 0.1, 1.0]));
        scene
            .meshes
            .insert((2, 0), mesh(48.0, 0, [0.1, 0.8, 0.1, 1.0]));
        scene
            .meshes
            .insert((3, 0), mesh(96.0, 0, [0.1, 0.1, 0.8, 1.0]));
        scene.dynamic = prime_scene::scene::DynamicScene {
            revision: 1,
            origin: [16.0, 16.0, 10.0],
            triangles: Arc::new(
                mesh(0.0, 0, [0.8, 0.5, 0.1, 1.0])
                    .triangles
                    .iter()
                    .collect(),
            ),
        };
        scene
            .ready_terrain
            .extend([cell([0, 0, 0]), cell([1, 0, 0])]);
        let camera = Camera {
            position: [32.0, 0.0, 160.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let reference = {
            let mut renderer = Renderer::new().unwrap();
            let pixels = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
            assert_eq!(renderer.geometry.as_ref().unwrap().clusters.len(), 2);
            pixels
        };
        let mut renderer = Renderer::new().unwrap();
        renderer
            .render(&Scene::default(), &camera, 96, 64, 0)
            .unwrap();
        // Exercise the exact production split path with a small executor limit, without a multi-GiB fixture.
        renderer.geometry.as_mut().unwrap().static_planner = TerrainPlanner::new(TerrainLimits {
            triangles_per_geometry: 2,
            geometry_records: OBJECT_BIT - 1,
        })
        .unwrap();
        assert!(
            renderer.render(&scene, &camera, 96, 64, 0).unwrap() == reference,
            "Splitting material ranges inside a cell BLAS must preserve every pixel"
        );
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.clusters.len(), 2);
        assert_eq!(geometry.clusters[&cell([0, 0, 0])].allocations.len(), 2);
        let addresses = |geometry: &Geometry| {
            geometry
                .clusters
                .iter()
                .map(|(&key, cluster)| (key, cluster.acceleration.address()))
                .collect::<BTreeMap<_, _>>()
        };
        let retained = addresses(geometry);
        assert!(geometry.objects.addresses().keys().any(
            |key| matches!(key, crate::plan::ObjectKey::Raw(batch) if batch.cell == cell([0, 0, 0]))
        ));
        scene.dynamic.origin[0] = 80.0;
        scene.dynamic.revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        assert_eq!(addresses(renderer.geometry.as_ref().unwrap()), retained);
        scene.dynamic.triangles = Arc::default();
        scene.dynamic.revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        assert_eq!(addresses(renderer.geometry.as_ref().unwrap()), retained);
        assert!(
            renderer
                .geometry
                .as_ref()
                .unwrap()
                .objects
                .addresses()
                .is_empty()
        );
        scene.meshes.get_mut(&(1, 0)).unwrap().revision += 1;
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(
            addresses(geometry)[&cell([1, 0, 0])],
            retained[&cell([1, 0, 0])]
        );
    }

    #[test]
    #[ignore = "requires Vulkan; one BLAS with opaque/cutout/alpha ranges across material pages"]
    fn gpu_mixed_static_cell_matches_separate_dynamic_blas_and_geometry_addressing() {
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        scene
            .ready_terrain
            .extend([cell([0, 0, 0]), cell([1, 0, 0])]);
        scene.textures.insert(
            7,
            prime_scene::Texture {
                width: 2,
                height: 1,
                pixels: vec![255, 255, 255, 0, 255, 255, 255, 255].into(),
            },
        );
        for (id, x, flags, z, color) in [
            (0, 0.0, 0, 4.0, [0.1, 0.8, 0.1, 1.0]),
            (1, 0.0, 1, 6.0, [0.8, 0.1, 0.1, 1.0]),
            (2, 0.0, 2, 8.0, [0.1, 0.1, 0.8, 0.45]),
            (3, 64.0, 0, 4.0, [0.8, 0.1, 0.8, 1.0]),
        ] {
            let mut value = mesh(x, flags, color.map(|v| (v * 255.0_f32).round() / 255.0));
            let prime_scene::geometry::MeshGeometry::Triangles(data) = &mut value.triangles else {
                panic!("expected diagnostic triangle mesh")
            };
            for triangle in Arc::make_mut(data) {
                for position in &mut triangle.positions {
                    position[0] += 32.0;
                    position[1] += 32.0;
                    position[2] = z;
                }
                triangle.uvs = triangle.positions.map(|p| [(p[0] - 12.0) / 40.0, 0.5]);
                triangle.texture_id = if flags == 1 { 7 } else { 0 };
            }
            scene.meshes.insert((id, flags), value);
        }
        let mut reference = Scene {
            epoch: 1,
            ..Default::default()
        };
        reference.textures = scene.textures.clone();
        reference.dynamic.revision = 1;
        reference.dynamic.triangles = scene
            .meshes
            .values()
            .flat_map(|mesh| {
                mesh.triangles.iter().map(|triangle| {
                    let mut value = triangle;
                    for position in &mut value.positions {
                        for i in 0..3 {
                            position[i] += mesh.origin[i] as f32;
                        }
                    }
                    value
                })
            })
            .collect::<Vec<_>>()
            .into();
        let camera = Camera {
            position: [48.0, 32.0, 100.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let mut renderer = Renderer::new().unwrap();
        renderer
            .render(
                &Scene {
                    epoch: 1,
                    ..Default::default()
                },
                &camera,
                96,
                64,
                0,
            )
            .unwrap();
        let context = renderer.context.clone();
        // Force geometry ranges in the same BLAS onto distinct physical material pages.
        renderer
            .geometry
            .as_mut()
            .unwrap()
            .materials
            .allocate(&context, 524288 - 2)
            .unwrap();
        let mut separate = Renderer::new().unwrap();
        for seed in [0, 19, 500] {
            let merged = renderer.render(&scene, &camera, 96, 64, seed).unwrap();
            let expected = separate.render(&reference, &camera, 96, 64, seed).unwrap();
            assert!(
                merged == expected,
                "mixed material geometry index mismatch, seed={seed}"
            );
        }
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.clusters.len(), 2);
        assert_eq!(geometry.instances.len(), 2);
        let mixed = &geometry.clusters[&cell([0, 0, 0])];
        assert_eq!(mixed.allocations.len(), 3);
        assert_ne!(mixed.allocations[0].page, mixed.allocations[1].page);
        assert_eq!(
            geometry.instances[1]
                .instance_custom_index_and_mask
                .low_24(),
            3
        );
        let retained = geometry.clusters[&cell([1, 0, 0])].acceleration.address();
        scene.meshes.remove(&(1, 1));
        scene.revision += 1;
        reference.dynamic.triangles = reference
            .dynamic
            .triangles
            .iter()
            .filter(|triangle| triangle.flags != 1)
            .copied()
            .collect::<Vec<_>>()
            .into();
        reference.dynamic.revision += 1;
        assert!(
            renderer.render(&scene, &camera, 96, 64, 501).unwrap()
                == separate.render(&reference, &camera, 96, 64, 501).unwrap(),
            "Removing a geometry range must remap later static instance material bases"
        );
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.clusters[&cell([0, 0, 0])].allocations.len(), 2);
        assert_eq!(
            geometry.instances[1]
                .instance_custom_index_and_mask
                .low_24(),
            2
        );
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(
            geometry.clusters[&cell([1, 0, 0])].acceleration.address(),
            retained
        );
    }
}
