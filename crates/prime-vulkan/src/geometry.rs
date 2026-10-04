//! Executes translated static batches; material records live in reusable device-addressed pages.
use super::dynamic::TopLevel;
use super::resources::{Acceleration, Buffer, Context};
use super::textures::Textures;
use crate::cpu_profile::{FrameCpu, Stage};
use crate::material_arena::{Allocation, MaterialArena};
use crate::objects::Objects;
use crate::plan::{OBJECT_BIT, validate_material_count};
use ash::vk;
use prime_scene::instances::InstanceInput;
use prime_scene::{
    geometry::MeshGeometry,
    incremental::{SceneInput, ScenePublication},
    scene::{InstanceScene, Scene},
    spatial::Cell,
    surface::SurfaceCompiler,
    translation::{TerrainLimits, TerrainMember, TerrainPlanner},
};
use std::{
    collections::{BTreeMap, BTreeSet},
    rc::Rc,
    sync::Arc,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct StaticAllocation {
    records: Allocation,
    format: usize,
}

fn record_bytes(format: usize) -> u64 {
    crate::packing::stride(format) as u64
}

struct Cluster {
    generation: u64,
    allocations: Vec<StaticAllocation>,
    acceleration: Acceleration,
    triangle_count: u64,
    micromaps: Vec<crate::omm::Micromap>,
    omm_textures: BTreeSet<u32>,
    texture_dependencies: BTreeSet<u32>,
    omm_counts: [u64; 4],
    // A deferred rebuild must use shader alpha against the current texture coverage.
    stale_omm: bool,
    cutout: bool,
    light_pages: Vec<Option<Rc<crate::surface::LightPage>>>,
    optical: bool,
    // Immutable owners, not hashes: RGB-only edits can preserve atmospheric visibility.
    occlusion: Option<OcclusionProof>,
}

struct OcclusionProof(Vec<(u32, Vec<TerrainMember>)>);
impl OcclusionProof {
    fn new(update: &prime_scene::translation::TerrainUpdate) -> Option<Self> {
        // Keeping expanded compiler SurfaceMesh output alive adds a second resident
        // per-face owner. Ordinary producer pages are already shared by the scene.
        if update
            .geometries
            .iter()
            .flat_map(|g| &g.members)
            .any(|m| matches!(m.triangles, MeshGeometry::Surfaces(_)))
        {
            return None;
        }
        Some(Self(
            update
                .geometries
                .iter()
                .map(|geometry| {
                    (
                        geometry.flags,
                        geometry
                            .members
                            .iter()
                            .map(|member| TerrainMember {
                                triangles: member.triangles.clone(),
                                range: member.range.clone(),
                                offset: member.offset,
                            })
                            .collect(),
                    )
                })
                .collect(),
        ))
    }
    fn matches(&self, update: &prime_scene::translation::TerrainUpdate) -> bool {
        self.0.len() == update.geometries.len()
            && self
                .0
                .iter()
                .zip(&update.geometries)
                .all(|((flags, members), geometry)| {
                    *flags == geometry.flags
                        && members.len() == geometry.members.len()
                        && members.iter().zip(&geometry.members).all(|(a, b)| {
                            a.range == b.range
                                && a.offset.map(f32::to_bits) == b.offset.map(f32::to_bits)
                                && !matches!(b.triangles, MeshGeometry::Surfaces(_))
                                && (a.triangles.ptr_eq(&b.triangles)
                                    || a.range.clone().all(|i| {
                                        let a = a.triangles.triangle(i);
                                        let b = b.triangles.triangle(i);
                                        a.positions.map(|p| p.map(f32::to_bits))
                                            == b.positions.map(|p| p.map(f32::to_bits))
                                            && a.uvs.map(|uv| uv.map(f32::to_bits))
                                                == b.uvs.map(|uv| uv.map(f32::to_bits))
                                            && a.texture_id == b.texture_id
                                            && a.flags == b.flags
                                            && a.colors
                                                .iter()
                                                .zip(&b.colors)
                                                .all(|(a, b)| a[3].to_bits() == b[3].to_bits())
                                    }))
                        })
                })
    }
}
impl Cluster {
    fn capabilities(&self) -> [usize; 3] {
        [
            usize::from(self.allocations.iter().any(|a| a.format != 0)),
            usize::from(self.allocations.iter().any(|a| a.format >= 2)),
            usize::from(self.optical),
        ]
    }
}

pub(super) struct Geometry {
    workers: Arc<prime_scene::workers::CpuWorkers>,
    indices: [Buffer; crate::packing::FORMATS],
    builds: crate::arena::Arena,
    uploads: crate::arena::Arena,
    pub revision: ScenePublication,
    epoch: u64,
    anchor: [f64; 3],
    pub triangle_count: u64,
    // Retire TLAS before any BLAS whose address it contains.
    pub top: TopLevel,
    clusters: BTreeMap<Cell, Cluster>,
    compactions: crate::compaction::Compactions,
    compaction_sources: Vec<(Acceleration, u64)>,
    next_blas_generation: u64,
    completed: u64,
    static_planner: TerrainPlanner,
    surface_compilers: Vec<SurfaceCompiler>,
    pub objects: Objects,
    directory: crate::static_directory::StaticDirectory,
    light_sources: BTreeMap<u64, ([f64; 3], Rc<crate::surface::LightPage>)>,
    capabilities: [usize; 3],
    top_dirty: bool,
    static_count: u64,
    materials: [MaterialArena; crate::packing::FORMATS],
    pub static_bases: Buffer,
    light_sampler: crate::light_sampler::LightSampler,
    light_sampling: prime_scene::settings::LightSampling,
    sampler_dirty: bool,
    next_light_key: u64,
    has_surfaces: bool,
    has_compounds: bool,
    has_optics: bool,
    resources: crate::scene_resources::SharedResources,
    resource_revision: u64,
    resource_occlusion_revision: u64,
    omm_revision: u64,
    pub rebuilt_clusters: u32,
    pub(crate) static_occlusion_changed: bool,
    pub(crate) opacity_micromap: bool,
    omm_dirty: bool,
    pub(crate) omm_prepare_ns: [u64; 3],
    pub(crate) omm_work_counts: [u64; 2],
}

#[cfg(test)]
pub(super) struct LightSamplingSnapshot {
    pub method: prime_scene::settings::LightSampling,
    pub dirty: bool,
    pub has_lights: bool,
    pub pages: Vec<(
        u64,
        Arc<Buffer>,
        Arc<prime_scene::surface::LightTree>,
        prime_scene::settings::LightSampling,
    )>,
    pub blas: Vec<vk::AccelerationStructureKHR>,
    pub local_depths: [u64; 28],
}

impl Geometry {
    #[cfg(test)]
    pub fn new(
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
    ) -> Result<Self, String> {
        Self::new_with_omm(context, scene, workers, true)
    }
    #[cfg(test)]
    pub fn new_with_omm(
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
        enabled: bool,
    ) -> Result<Self, String> {
        Self::new_with_budget(context, scene, workers, enabled, usize::MAX)
    }
    #[cfg(test)]
    pub fn new_with_budget(
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
        enabled: bool,
        cell_budget: usize,
    ) -> Result<Self, String> {
        let resources = crate::scene_resources::SceneResources::new(context, scene)?;
        Self::new_with_resources(
            context,
            scene,
            workers,
            enabled,
            cell_budget,
            resources,
            prime_scene::settings::LightSampling::Grid,
        )
    }
    pub fn new_with_resources(
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        workers: Arc<prime_scene::workers::CpuWorkers>,
        enabled: bool,
        cell_budget: usize,
        resources: crate::scene_resources::SharedResources,
        light_sampling: prime_scene::settings::LightSampling,
    ) -> Result<Self, String> {
        let mut geometry = Self {
            workers: workers.clone(),
            indices: [
                crate::objects::index_buffer_with_stride(context, 16, 11)?,
                crate::objects::index_buffer_with_stride(context, 16, 15)?,
                crate::objects::index_buffer_with_stride(context, 16, 17)?,
                crate::objects::index_buffer_with_stride(context, 16, 27)?,
            ],
            builds: crate::arena::Arena::new(context, false),
            uploads: crate::arena::Arena::new(context, true),
            revision: scene.publication(),
            epoch: scene.epoch,
            anchor: scene.anchor,
            triangle_count: 0,
            top: TopLevel::default(),
            clusters: BTreeMap::new(),
            compactions: crate::compaction::Compactions::default(),
            compaction_sources: Vec::new(),
            next_blas_generation: 1,
            completed: 0,
            static_planner: TerrainPlanner::new(TerrainLimits {
                triangles_per_geometry: crate::surface::MAX_RECORDS,
                geometry_records: OBJECT_BIT - 1,
            })?,
            surface_compilers: (0..workers.threads())
                .map(|_| SurfaceCompiler::new())
                .collect(),
            objects: Objects::new(context, workers)?,
            directory: crate::static_directory::StaticDirectory::default(),
            light_sources: BTreeMap::new(),
            capabilities: [0; 3],
            top_dirty: true,
            static_count: 0,
            materials: std::array::from_fn(|format| {
                MaterialArena::with_stride(record_bytes(format))
            }),
            // update() publishes every entry before TLAS use. An initial zero
            // upload would be overwritten in the same host command buffer.
            static_bases: Buffer::new(
                context,
                crate::surface::PAGE_BYTES as u64,
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?,
            light_sampler: crate::light_sampler::LightSampler::new(context, light_sampling),
            light_sampling,
            sampler_dirty: false,
            next_light_key: 1,
            has_surfaces: false,
            has_compounds: false,
            has_optics: false,
            resources,
            resource_revision: 0,
            resource_occlusion_revision: 0,
            omm_revision: 0,
            rebuilt_clusters: 0,
            static_occlusion_changed: true,
            opacity_micromap: enabled && context.opacity_micromap.is_some(),
            omm_dirty: false,
            omm_prepare_ns: [0; 3],
            omm_work_counts: [0; 2],
        };
        geometry.update_prepared(context, scene, cell_budget)?;
        Ok(geometry)
    }
    pub fn textures(&self) -> std::cell::Ref<'_, Textures> {
        std::cell::Ref::map(self.resources.borrow(), |resources| &resources.textures)
    }
    pub fn begin_frame(&mut self, context: &Context, completed: u64) {
        // Frozen frames do not execute an object plan; do not report the prior frame's rebuilds.
        self.objects.rebuilt = 0;
        self.rebuilt_clusters = 0;
        self.omm_prepare_ns = [0; 3];
        self.omm_work_counts = [0; 2];
        let serial = context.retirement_serial();
        self.builds.begin(completed, serial);
        self.completed = completed;
        if self.compactions.complete(completed) {
            self.builds.reclaim_empty_pages();
        }
        self.uploads.begin(completed, serial);
        self.resources.borrow_mut().begin(context, completed);
    }
    #[cfg(test)]
    pub fn assert_incremental_workspaces(&self) -> (usize, u64) {
        self.top
            .assert_current_input(&self.directory.instances, &self.objects.instances);
        (
            self.builds.page_count() + self.uploads.page_count(),
            self.builds.reserved_bytes() + self.uploads.reserved_bytes(),
        )
    }
    pub fn needs_update(&self, scene: SceneInput<'_>) -> bool {
        self.compactions.needs_update(self.completed)
            || self.sampler_dirty
            || self.omm_dirty
            || self.source_changed(scene)
            || self.static_planner.has_pending()
            || self.resource_revision != self.resources.borrow().revision
    }
    pub fn source_changed(&self, scene: SceneInput<'_>) -> bool {
        self.revision != scene.publication() || self.anchor != scene.anchor
    }
    #[cfg(test)]
    pub fn static_slot(&self, key: Cell) -> Option<(u32, u32, u32)> {
        self.directory.slot(key)
    }
    pub fn occlusion_resources_changed(&self) -> bool {
        self.resource_occlusion_revision != self.resources.borrow().occlusion_revision
    }

    #[cfg(test)]
    pub fn surface_memory(&self) -> [u64; 6] {
        [
            self.clusters
                .values()
                .flat_map(|c| &c.allocations)
                .map(|a| u64::from(a.records.count) * record_bytes(a.format))
                .sum(),
            self.materials
                .iter()
                .map(MaterialArena::reserved_bytes)
                .sum(),
            self.clusters
                .values()
                .map(|c| c.acceleration.storage_bytes())
                .sum(),
            self.builds.reserved_bytes(),
            self.uploads.reserved_bytes(),
            self.indices.iter().map(|b| b.size).sum(),
        ]
    }

    #[cfg(test)]
    pub(super) fn light_sampling_snapshot(&self) -> LightSamplingSnapshot {
        let mut local_depths = [0; 28];
        for (_, page) in self.light_sources.values() {
            if let Some(tree) = &page.tree {
                for (total, count) in local_depths
                    .iter_mut()
                    .zip(crate::light_sphere_cpu::leaf_depths(&tree.paths))
                {
                    *total += count;
                }
            }
        }
        LightSamplingSnapshot {
            method: self.light_sampling,
            dirty: self.sampler_dirty,
            has_lights: self.light_sampler.has_lights,
            pages: self
                .light_sources
                .iter()
                .map(|(&key, (_, page))| {
                    (key, page.emitters.clone(), page.source.clone(), page.method)
                })
                .collect(),
            blas: self
                .clusters
                .values()
                .map(|cluster| cluster.acceleration.handle)
                .collect(),
            local_depths,
        }
    }

    /// Defer uploads to the next active record; published BLAS and emitter identities survive.
    pub fn set_light_sampling(&mut self, method: prime_scene::settings::LightSampling) {
        if self.light_sampling != method {
            self.light_sampling = method;
            self.sampler_dirty = true;
        }
    }

    fn rebuild_light_sampler(&mut self, context: &Arc<Context>) -> Result<(), String> {
        let mut trace = prime_diagnostics::scope("lights.switch");
        trace.fail();
        trace.count("ls", self.light_sampling as u64);
        trace.count("pg", self.light_sources.len() as u64);
        let mut sources = BTreeMap::new();
        for (&key, (origin, page)) in &self.light_sources {
            let page = if page.method == self.light_sampling {
                page.clone()
            } else {
                Rc::new(page.rebuild_sampler(context, self.light_sampling)?)
            };
            sources.insert(key, (*origin, page));
        }
        for cluster in self.clusters.values_mut() {
            for page in cluster.light_pages.iter_mut().flatten() {
                *page = sources[&page.key].1.clone();
            }
        }
        self.light_sources = sources;
        self.light_sampler = crate::light_sampler::LightSampler::new(context, self.light_sampling);
        trace.succeed();
        Ok(())
    }

    pub fn set_omm(&mut self, enabled: bool) {
        if self.opacity_micromap != enabled {
            self.opacity_micromap = enabled;
            for (&cell, cluster) in &mut self.clusters {
                if cluster.cutout {
                    self.static_planner.invalidate_cells([cell]);
                    cluster.stale_omm |= !cluster.micromaps.is_empty();
                }
            }
            self.omm_dirty = true;
        }
    }
    pub(crate) fn omm_stats(&self) -> [u64; 4] {
        self.clusters.values().fold([0; 4], |mut total, cluster| {
            for (total, count) in total.iter_mut().zip(cluster.omm_counts) {
                *total += count;
            }
            total
        })
    }
    pub(crate) fn omm_pool_stats(&self) -> [u64; 4] {
        self.resources.borrow().pool.stats()
    }
    pub fn same_owner(&self, scene: SceneInput<'_>) -> bool {
        self.revision.same_owner(scene.publication())
    }
    pub fn shader_variant(&self) -> usize {
        if self.has_optics {
            4 + usize::from(self.light_sampler.has_lights)
        } else if self.light_sampler.has_lights {
            3
        } else if self.has_compounds || self.textures().sprites != 0 {
            2
        } else {
            usize::from(self.has_surfaces)
        }
    }
    #[cfg(test)]
    pub fn update(&mut self, context: &Arc<Context>, scene: SceneInput<'_>) -> Result<(), String> {
        self.update_limited(context, scene, usize::MAX).map(|_| ())
    }
    /// Returns whether published geometry content changed; equivalent OMM rebuilds do not.
    #[cfg(test)]
    pub fn update_limited(
        &mut self,
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        cell_budget: usize,
    ) -> Result<bool, String> {
        self.resources.borrow_mut().prepare(context, scene)?;
        self.update_prepared(context, scene, cell_budget)
    }
    pub fn update_prepared(
        &mut self,
        context: &Arc<Context>,
        scene: SceneInput<'_>,
        cell_budget: usize,
    ) -> Result<bool, String> {
        let mut plan_trace = prime_diagnostics::scope("geom.plan");
        plan_trace.fail();
        plan_trace.count("cells", self.clusters.len() as u64);
        plan_trace.count("budget", cell_budget as u64);
        if scene.anchor.iter().any(|v| !v.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        self.static_occlusion_changed = self.epoch != scene.epoch || self.anchor != scene.anchor;
        let mut directory_changed = BTreeSet::new();
        if self.omm_dirty {
            directory_changed.extend(
                self.clusters
                    .iter()
                    .filter(|(_, c)| c.cutout)
                    .map(|(&key, _)| key),
            );
        }
        let mut lights_changed = self.anchor != scene.anchor || self.sampler_dirty;
        let mut instance_flags_changed = self.omm_dirty;
        let owner = self.resources.clone();
        let mut resources = owner.borrow_mut();
        self.omm_prepare_ns[0] = resources.prepare_ns[0];
        self.omm_prepare_ns[2] = resources.prepare_ns[2];
        self.omm_work_counts[0] = resources.preparations;
        if !resources.coverage_changed.is_empty() {
            for (&key, cluster) in &mut self.clusters {
                // A source may retire an identity while its replacement is still queued.
                // Withdraw old records before a released descriptor can be consumed again.
                if cluster
                    .texture_dependencies
                    .iter()
                    .any(|id| !resources.textures.source.contains_key(id))
                {
                    self.static_planner.withdraw_cells([key]);
                }
                if cluster
                    .omm_textures
                    .iter()
                    .any(|id| resources.coverage_changed.contains(id))
                {
                    self.static_planner.invalidate_cells([key]);
                    if !cluster.micromaps.is_empty() {
                        instance_flags_changed |= !cluster.stale_omm;
                        cluster.stale_omm = true;
                        directory_changed.insert(key);
                    }
                }
            }
        }
        if self.omm_revision != resources.omm_revision {
            // Global IDs belong to this generation. Rebind all existing cutout BLAS,
            // including those whose source pixels did not change in this reload.
            for (&cell, cluster) in &mut self.clusters {
                if cluster.cutout {
                    self.static_planner.invalidate_cells([cell]);
                    if !cluster.micromaps.is_empty() {
                        instance_flags_changed |= !cluster.stale_omm;
                        cluster.stale_omm = true;
                        directory_changed.insert(cell);
                    }
                }
            }
        }
        for compiler in &mut self.surface_compilers {
            compiler.set_cutout_squares(self.opacity_micromap);
        }
        let mut plan = self.static_planner.plan_input_limited(scene, cell_budget)?;
        plan.placements_changed |= instance_flags_changed;
        let published_changed = plan.content_changed;
        plan_trace.count("new", plan.geometry.len() as u64);
        plan_trace.count("del", plan.removed.len() as u64);
        plan_trace.succeed();
        drop(plan_trace);
        let mut compile_trace = prime_diagnostics::scope("geom.compile");
        compile_trace.fail();
        compile_trace.count("cells", plan.geometry.len() as u64);
        self.workers.batches_mut(
            &mut self.surface_compilers,
            &mut plan.geometry,
            |compiler, updates| {
                for update in updates {
                    for geometry in &mut update.geometries {
                        let Some(mesh) = compiler.compile_terrain(scene.revision, geometry)? else {
                            continue;
                        };
                        let count = mesh.quads.len() * 2;
                        geometry.triangle_count =
                            u32::try_from(count).map_err(|_| "Surface triangle count overflow")?;
                        geometry.members = vec![TerrainMember {
                            triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
                            range: 0..count,
                            offset: [0.0; 3],
                        }];
                    }
                }
                Ok(())
            },
        )?;
        compile_trace.succeed();
        drop(compile_trace);
        let mut material_trace = prime_diagnostics::scope("geom.material");
        material_trace.fail();
        self.static_occlusion_changed |= !plan.removed.is_empty()
            || plan.geometry.iter().any(|update| {
                self.clusters.get(&update.key).is_none_or(|old| {
                    old.occlusion
                        .as_ref()
                        .is_none_or(|proof| !proof.matches(update))
                })
            });
        self.top_dirty |= plan.placements_changed;
        if self.epoch != scene.epoch {
            self.compactions.cancel_sources();
            self.directory = crate::static_directory::StaticDirectory::default();
            self.light_sources.clear();
            self.capabilities = [0; 3];
            self.static_count = 0;
            lights_changed = true;
            for (_, old) in std::mem::take(&mut self.clusters) {
                old.acceleration.retire(&mut self.builds);
                for micromap in old.micromaps {
                    micromap.retire(&mut self.builds);
                }
            }
            self.materials =
                std::array::from_fn(|format| MaterialArena::with_stride(record_bytes(format)));
            self.epoch = scene.epoch;
        }
        for key in &plan.removed {
            self.directory.remove(*key);
            if let Some(old) = self.clusters.remove(key) {
                self.static_count -= old.triangle_count;
                for (total, value) in self.capabilities.iter_mut().zip(old.capabilities()) {
                    *total -= value;
                }
                for page in old.light_pages.iter().flatten() {
                    lights_changed |= self.light_sources.remove(&page.key).is_some();
                }
                old.acceleration.retire(&mut self.builds);
                for micromap in old.micromaps {
                    micromap.retire(&mut self.builds);
                }
                for allocation in old.allocations {
                    self.materials[allocation.format].free(allocation.records);
                }
            }
        }
        let mut allocated_changes = Vec::with_capacity(plan.geometry.len());
        for update in &plan.geometry {
            directory_changed.insert(update.key);
            if let Some(old) = self.clusters.remove(&update.key) {
                self.static_count -= old.triangle_count;
                for (total, value) in self.capabilities.iter_mut().zip(old.capabilities()) {
                    *total -= value;
                }
                for page in old.light_pages.iter().flatten() {
                    lights_changed |= self.light_sources.remove(&page.key).is_some();
                }
                old.acceleration.retire(&mut self.builds);
                for micromap in old.micromaps {
                    micromap.retire(&mut self.builds);
                }
                for allocation in old.allocations {
                    self.materials[allocation.format].free(allocation.records);
                }
            }
            let plans = update
                .geometries
                .iter()
                .map(|geometry| {
                    crate::packing::Plan::new(
                        geometry.members.iter().map(|member| crate::packing::Input {
                            triangles: member.triangles.view(member.range.clone()),
                            offset: Some(member.offset),
                            flags: Some(geometry.flags),
                        }),
                        true,
                    )
                })
                .collect::<Result<Vec<_>, String>>()?;
            let allocations = plans
                .iter()
                .flat_map(|p| &p.groups)
                .map(|group| {
                    validate_material_count(group.count)?;
                    Ok(StaticAllocation {
                        records: self.materials[group.format].allocate(context, group.count)?,
                        format: group.format,
                    })
                })
                .collect::<Result<Vec<_>, String>>()?;
            allocated_changes.push((update, plans, allocations));
        }
        self.rebuilt_clusters = allocated_changes.len() as u32;
        for (format, indices) in self.indices.iter_mut().enumerate() {
            let largest = allocated_changes
                .iter()
                .flat_map(|(_, _, a)| a)
                .filter(|a| a.format == format)
                .map(|a| a.records.count)
                .max()
                .unwrap_or(0);
            if u64::from(largest) * 24 > indices.size {
                *indices = crate::objects::index_buffer_with_stride(
                    context,
                    largest,
                    (record_bytes(format) / 16) as u32,
                )?;
            }
        }
        material_trace.count("cells", allocated_changes.len() as u64);
        material_trace.succeed();
        drop(material_trace);
        // Each wave completes CPU preparation synchronously. Its GPU resources retain
        // their real submission lifetime; wave size is not a live-allocation bound.
        // Newly recorded queries stay pending until this update's completion proof.
        self.compactions.resolve(self.completed)?;
        for batch in allocated_changes.chunks(32) {
            let mut uploads = Vec::new();
            let mut prepared = Vec::new();
            for (update, plans, allocations) in batch {
                let bytes: u64 = allocations
                    .iter()
                    .map(|a| u64::from(a.records.count) * record_bytes(a.format))
                    .sum();
                let mut pack_trace = prime_diagnostics::scope("geom.pack");
                pack_trace.fail();
                pack_trace.count("bytes", bytes);
                pack_trace.count("ranges", allocations.len() as u64);
                let mut upload = self.uploads.allocate(context, bytes, 16)?;
                upload.write_with(|output| {
                    let mut remaining = output;
                    for ((plan, group), a) in plans
                        .iter()
                        .flat_map(|p| p.groups.iter().map(move |g| (p, g)))
                        .zip(allocations)
                    {
                        let size = a.records.count as usize * record_bytes(a.format) as usize;
                        let (destination, tail) = remaining.split_at_mut(size);
                        plan.pack(
                            &self.workers,
                            group,
                            destination,
                            &resources.textures.indices,
                        )?;
                        remaining = tail;
                    }
                    Ok(())
                })?;
                pack_trace.succeed();
                drop(pack_trace);
                let mut omm_trace = prime_diagnostics::scope("geom.omm");
                omm_trace.fail();
                let mut micromaps = Vec::with_capacity(allocations.len());
                let mut omm_textures = BTreeSet::new();
                let mut omm_counts = [0u64; 4];
                for (geometry, plan, group) in
                    update
                        .geometries
                        .iter()
                        .zip(plans)
                        .flat_map(|(geometry, p)| {
                            p.groups.iter().map(move |group| (geometry, p, group))
                        })
                {
                    let data = if self.opacity_micromap
                        && geometry.flags == 1
                        && let Some(templates) = &resources.templates
                    {
                        let start = context.diagnostics_enabled().then(std::time::Instant::now);
                        let data = templates.bind(plan, group);
                        if let Some(start) = start {
                            self.omm_prepare_ns[1] += start.elapsed().as_nanos() as u64;
                        }
                        data
                    } else {
                        None
                    };
                    let prepared = if let Some(data) = data {
                        omm_textures.extend(data.textures.iter().copied());
                        if context.diagnostics_enabled() {
                            self.omm_work_counts[1] += data.indices.len() as u64;
                        }
                        let start = context.diagnostics_enabled().then(std::time::Instant::now);
                        let prepared = resources.pool.bind(
                            context,
                            &mut self.builds,
                            &mut self.uploads,
                            &data.indices,
                        )?;
                        if let Some(start) = start {
                            self.omm_prepare_ns[1] += start.elapsed().as_nanos() as u64;
                        }
                        if let Some(binding) = &prepared {
                            for (count, value) in omm_counts.iter_mut().zip(binding.stats) {
                                *count += value;
                            }
                        }
                        prepared
                    } else {
                        None
                    };
                    micromaps.push(prepared);
                }
                omm_trace.count("ranges", micromaps.len() as u64);
                omm_trace.succeed();
                drop(omm_trace);
                let mut as_trace = prime_diagnostics::scope("geom.as");
                as_trace.fail();
                as_trace.count("ranges", allocations.len() as u64);
                let mut geometries = Vec::with_capacity(allocations.len());
                let mut counts = Vec::with_capacity(allocations.len());
                for (range_index, ((geometry, _), &allocation)) in update
                    .geometries
                    .iter()
                    .zip(plans)
                    .flat_map(|(g, p)| p.groups.iter().map(move |group| (g, group)))
                    .zip(allocations)
                    .enumerate()
                {
                    let count = allocation.records.count;
                    let mut triangles =
                        vk::AccelerationStructureGeometryTrianglesDataKHR::default()
                            .vertex_format(vk::Format::R32G32B32_SFLOAT)
                            .vertex_data(vk::DeviceOrHostAddressConstKHR {
                                device_address: self.materials[allocation.format]
                                    .address(allocation.records),
                            })
                            .vertex_stride(16)
                            .max_vertex(count * (record_bytes(allocation.format) / 16) as u32 - 1)
                            .index_type(vk::IndexType::UINT32)
                            .index_data(vk::DeviceOrHostAddressConstKHR {
                                device_address: self.indices[allocation.format].address(),
                            });
                    if let Some(micromap) = &mut micromaps[range_index] {
                        // Box owns a stable pNext pointee through size query and command recording.
                        triangles.p_next = (&*micromap.attachment
                            as *const vk::AccelerationStructureTrianglesOpacityMicromapEXT<'_>)
                            .cast();
                    }
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
                    counts.push(count * 2);
                }
                let generation = self.next_blas_generation;
                self.next_blas_generation = generation
                    .checked_add(1)
                    .ok_or("Static BLAS generation exhausted")?;
                prepared.push((
                    update.key,
                    allocations,
                    Acceleration::prepare_geometries(
                        context,
                        &mut self.builds,
                        geometries,
                        &counts,
                        micromaps.iter().any(Option::is_some),
                    )?,
                    micromaps,
                    omm_textures,
                    omm_counts,
                    generation,
                ));
                uploads.push(upload);
                as_trace.succeed();
            }
            let mut command_trace = prime_diagnostics::scope("geom.cmd");
            command_trace.fail();
            command_trace.count("cells", prepared.len() as u64);
            let queries = crate::compaction::QueryPool::new(context, prepared.len())?;
            let handles: Vec<_> = prepared
                .iter()
                .map(|(_, _, build, _, _, _, _)| build.acceleration().handle)
                .collect();
            context.submit_named("dirty_clusters", |command| unsafe {
                for ((_, allocations, _, _, _, _, _), upload) in prepared.iter().zip(&uploads) {
                    let mut offset = 0;
                    for &allocation in *allocations {
                        let stride = record_bytes(allocation.format);
                        let size = u64::from(allocation.records.count) * stride;
                        context.device.cmd_copy_buffer(
                            command,
                            upload.buffer.buffer,
                            self.materials[allocation.format]
                                .buffer(allocation.records)
                                .buffer,
                            &[vk::BufferCopy::default()
                                .src_offset(upload.offset + offset)
                                .dst_offset(u64::from(allocation.records.first) * stride)
                                .size(size)],
                        );
                        offset += size;
                    }
                }
                let has_omm = prepared
                    .iter()
                    .any(|(_, _, _, maps, _, _, _)| maps.iter().any(Option::is_some));
                for (_, _, _, maps, _, _, _) in &prepared {
                    for map in maps.iter().flatten() {
                        map.record_copy(command);
                    }
                }
                transfer_barrier(context, command);
                if has_omm {
                    crate::omm::upload_barrier(context, command);
                }
                for (_, _, build, _, _, _, _) in &prepared {
                    build.record_unbarriered(command);
                }
                Acceleration::read_barrier(context, command);
                queries.record(command, &handles);
            })?;
            self.compactions.record_batch(
                queries,
                prepared
                    .iter()
                    .map(
                        |(key, _, build, _, _, _, generation)| crate::compaction::Source {
                            cell: *key,
                            generation: *generation,
                            bytes: build.acceleration().storage_bytes(),
                        },
                    )
                    .collect(),
                context.retirement_serial(),
            );
            for upload in uploads {
                self.uploads.retire(upload);
            }
            command_trace.succeed();
            drop(command_trace);
            let mut publish_trace = prime_diagnostics::scope("geom.publish");
            publish_trace.fail();
            publish_trace.count("cells", prepared.len() as u64);
            for (key, allocations, build, maps, omm_textures, omm_counts, generation) in prepared {
                let (update, plans, _) = batch.iter().find(|(u, _, _)| u.key == key).unwrap();
                // Every material range keeps its source mesh's canonical emitter IDs.
                // The shared owner uploads the emitter records once, independent of formats.
                let mut light_pages = Vec::with_capacity(allocations.len());
                for (g, plan) in update.geometries.iter().zip(plans) {
                    let light = if let Some(member) = g.members.first()
                        && let MeshGeometry::Surfaces(mesh) = &member.triangles
                    {
                        crate::surface::upload_lights(
                            context,
                            mesh,
                            &resources.textures.indices,
                            self.next_light_key,
                            self.light_sampling,
                        )?
                        .map(Rc::new)
                    } else {
                        None
                    };
                    if light.is_some() {
                        self.next_light_key = self
                            .next_light_key
                            .checked_add(1)
                            .ok_or("Light page identity overflow")?;
                    }
                    for _ in &plan.groups {
                        light_pages.push(light.clone());
                    }
                }
                let cluster = Cluster {
                        generation,
                        occlusion: OcclusionProof::new(update),
                        optical: update.geometries.iter().flat_map(|g| &g.members).any(|m| matches!(&m.triangles, MeshGeometry::Surfaces(mesh) if mesh.quads.iter().any(|q|q.optics.is_some()))),
                        cutout: update.geometries.iter().any(|g| g.flags == 1),
                        micromaps: maps.into_iter().flatten().map(|m| m.finish(&mut self.builds, &mut self.uploads)).collect(),
                        omm_textures,
                        texture_dependencies: {
                            let mut ids = BTreeSet::new();
                            for plan in plans { plan.texture_dependencies(&mut ids); }
                            ids
                        },
                        omm_counts,
                        stale_omm: false,
                        allocations: allocations.clone(),
                        acceleration: build.finish(&mut self.builds),
                        triangle_count: update
                            .geometries
                            .iter()
                            .map(|g| u64::from(g.triangle_count))
                            .sum(),
                        light_pages,
                    };
                self.static_count += cluster.triangle_count;
                for (total, value) in self.capabilities.iter_mut().zip(cluster.capabilities()) {
                    *total += value;
                }
                for page in cluster.light_pages.iter().flatten() {
                    self.light_sources
                        .insert(page.key, (key.origin(), page.clone()));
                    lights_changed = true;
                }
                self.clusters.insert(key, cluster);
            }
            publish_trace.succeed();
        }
        let mut retire_trace = prime_diagnostics::scope("geom.retire");
        resources.collect();
        drop(allocated_changes);
        retire_trace.count("cells", self.clusters.len() as u64);
        drop(retire_trace);
        let compacted = {
            let mut compact_trace = prime_diagnostics::scope("geom.compact");
            compact_trace.fail();
            let compacted = self.compact(context, cell_budget)?;
            compact_trace.count("changed", u64::from(compacted));
            compact_trace.succeed();
            compacted
        };
        plan.placements_changed |= compacted;
        self.top_dirty |= compacted;
        if !plan.placements_changed && !self.sampler_dirty {
            self.omm_dirty = false;
            self.revision = scene.publication();
            self.anchor = scene.anchor;
            self.resource_revision = resources.revision;
            self.resource_occlusion_revision = resources.occlusion_revision;
            self.omm_revision = resources.omm_revision;
            resources.coverage_changed.clear();
            self.static_planner.recycle(plan);
            return Ok(false);
        }
        if self.sampler_dirty {
            self.rebuild_light_sampler(context)?;
        }
        if lights_changed {
            let mut lights_trace = prime_diagnostics::scope("geom.lights");
            lights_trace.fail();
            lights_trace.count("pages", self.light_sources.len() as u64);
            let sources = self
                .light_sources
                .iter()
                .map(|(&key, (origin, page))| (key, (*origin, page.as_ref())))
                .collect();
            self.light_sampler.update(
                context,
                scene.anchor,
                &sources,
                &mut self.uploads,
                self.completed,
            )?;
            lights_trace.succeed();
        }
        let mut directory_trace = prime_diagnostics::scope("geom.dir");
        directory_trace.fail();
        if self.anchor != scene.anchor {
            directory_changed.extend(self.clusters.keys().copied());
        }
        self.has_surfaces = self.capabilities[0] != 0;
        self.has_compounds = self.capabilities[1] != 0;
        self.has_optics = self.capabilities[2] != 0;
        for key in directory_changed {
            let Some(cluster) = self.clusters.get(&key) else {
                continue;
            };
            let origin: [f32; 3] =
                std::array::from_fn(|i| (key.origin()[i] - scene.anchor[i]) as f32);
            let mut rows = Vec::with_capacity(cluster.allocations.len());
            for (i, &allocation) in cluster.allocations.iter().enumerate() {
                let mut row = [0; crate::surface::PAGE_BYTES];
                row[..8].copy_from_slice(
                    &self.materials[allocation.format]
                        .address(allocation.records)
                        .to_le_bytes(),
                );
                row[8..12].copy_from_slice(&(allocation.format as u32).to_le_bytes());
                let light = &cluster.light_pages[i];
                row[16..20].copy_from_slice(
                    &light
                        .as_ref()
                        .map_or(0, |l| self.light_sampler.first_emitter(l.key))
                        .to_le_bytes(),
                );
                row[24..32].copy_from_slice(
                    &light
                        .as_ref()
                        .map_or(0, |l| l.emitters.address())
                        .to_le_bytes(),
                );
                row[44..48].copy_from_slice(&light.as_ref().map_or(1, |l| l.format).to_le_bytes());
                for (i, value) in origin.into_iter().enumerate() {
                    row[48 + i * 4..52 + i * 4].copy_from_slice(&value.to_le_bytes());
                }
                rows.push(row);
            }
            self.directory.publish(
                key,
                &rows,
                vk::AccelerationStructureInstanceKHR {
                    transform: vk::TransformMatrixKHR {
                        matrix: [
                            1., 0., 0., origin[0], 0., 1., 0., origin[1], 0., 0., 1., origin[2],
                        ],
                    },
                    instance_custom_index_and_mask: vk::Packed24_8::new(0, 0xff),
                    instance_shader_binding_table_record_offset_and_flags: vk::Packed24_8::new(
                        0,
                        (vk::GeometryInstanceFlagsKHR::TRIANGLE_FACING_CULL_DISABLE
                            | if cluster.stale_omm {
                                vk::GeometryInstanceFlagsKHR::DISABLE_OPACITY_MICROMAPS_EXT
                            } else {
                                vk::GeometryInstanceFlagsKHR::empty()
                            })
                        .as_raw() as u8,
                    ),
                    acceleration_structure_reference: vk::AccelerationStructureReferenceKHR {
                        device_handle: cluster.acceleration.address(),
                    },
                },
            )?;
        }
        if self.sampler_dirty {
            for (&key, cluster) in &self.clusters {
                let fields: Vec<_> = cluster
                    .light_pages
                    .iter()
                    .map(|page| {
                        page.as_ref().map_or((0, 0, 1), |page| {
                            (
                                self.light_sampler.first_emitter(page.key),
                                page.emitters.address(),
                                page.format,
                            )
                        })
                    })
                    .collect();
                self.directory.light_fields(key, &fields)?;
            }
        }
        self.directory.light_header(
            self.light_sampler.world_count(),
            self.light_sampler.header_address(),
        );
        let bytes = self.directory.bytes.len() * crate::surface::PAGE_BYTES;
        if bytes as u64 > self.static_bases.size {
            self.static_bases = Buffer::new(
                context,
                (bytes as u64).next_power_of_two(),
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                false,
            )?;
            self.directory.dirty_records = (0..self.directory.bytes.len()).collect();
        }
        self.directory.dirty_records.sort_unstable();
        self.directory.dirty_records.dedup();
        self.directory
            .dirty_records
            .retain(|&i| i < self.directory.bytes.len());
        directory_trace.count("rows", self.directory.dirty_records.len() as u64);
        if !self.directory.dirty_records.is_empty() {
            let mut upload = self.uploads.allocate(
                context,
                (self.directory.dirty_records.len() * crate::surface::PAGE_BYTES) as u64,
                16,
            )?;
            upload.write_with(|output| {
                for (dst, &at) in output
                    .as_chunks_mut::<{ crate::surface::PAGE_BYTES }>()
                    .0
                    .iter_mut()
                    .zip(&self.directory.dirty_records)
                {
                    for (out, &byte) in dst.iter_mut().zip(&self.directory.bytes[at]) {
                        out.write(byte);
                    }
                }
                Ok(())
            })?;
            let mut copies: Vec<vk::BufferCopy> = Vec::new();
            for (i, &at) in self.directory.dirty_records.iter().enumerate() {
                let source = upload.offset + (i * crate::surface::PAGE_BYTES) as u64;
                let destination = (at * crate::surface::PAGE_BYTES) as u64;
                if let Some(last) = copies.last_mut().filter(|last| {
                    last.src_offset + last.size == source
                        && last.dst_offset + last.size == destination
                }) {
                    last.size += crate::surface::PAGE_BYTES as u64;
                } else {
                    copies.push(
                        vk::BufferCopy::default()
                            .src_offset(source)
                            .dst_offset(destination)
                            .size(crate::surface::PAGE_BYTES as u64),
                    );
                }
            }
            context.submit_named("static_cluster_bases", |command| unsafe {
                transfer_write_barrier(context, command);
                context.device.cmd_copy_buffer(
                    command,
                    upload.buffer.buffer,
                    self.static_bases.buffer,
                    &copies,
                );
                transfer_barrier(context, command);
            })?;
            self.uploads.retire(upload);
        }
        self.static_planner.recycle(plan);
        self.omm_dirty = false;
        self.revision = scene.publication();
        self.anchor = scene.anchor;
        self.resource_revision = resources.revision;
        self.resource_occlusion_revision = resources.occlusion_revision;
        self.omm_revision = resources.omm_revision;
        resources.coverage_changed.clear();
        directory_trace.succeed();
        self.sampler_dirty = false;
        Ok(published_changed)
    }

    pub fn prepare_dynamic<'a>(
        &mut self,
        context: &Arc<Context>,
        scene: &Scene,
        objects: impl Into<InstanceInput<'a>>,
        slot: usize,
        cpu: &mut FrameCpu,
    ) -> Result<(bool, bool, bool), String> {
        let resources = self.resources.borrow();
        let changes = self.objects.prepare(
            context,
            scene,
            objects.into(),
            &resources.textures,
            slot,
            &mut self.builds,
            cpu,
        )?;
        let mut bindings = changes.bindings;
        if self.top_dirty || changes.tlas {
            let started = cpu.start();
            bindings |= self.top.rebuild(
                context,
                &self.directory.instances,
                &self.objects.instances,
                &self.objects.changed_instances,
                self.top_dirty,
                &self.directory.dirty_instances,
                changes.tlas_update,
                slot,
                &mut self.builds,
            )?;
            self.top_dirty = false;
            self.directory.finish_upload();
            // Keep original sources owned until the replacement TLAS is recorded.
            for (source, reclaimed) in self.compaction_sources.drain(..) {
                source.retire(&mut self.builds);
                self.compactions
                    .published(context.retirement_serial(), reclaimed);
            }
            cpu.tlas_rebuilds += 1;
            cpu.finish(Stage::Tlas, started);
        }
        self.triangle_count = self
            .static_count
            .checked_add(self.objects.triangle_count)
            .ok_or("Triangle count overflow")?;
        Ok((changes.scene, changes.occlusion, bindings))
    }

    fn compact(&mut self, context: &Arc<Context>, budget: usize) -> Result<bool, String> {
        let mut selected: Vec<(crate::compaction::Candidate, Acceleration)> = Vec::new();
        let mut bytes = 0;
        while let Some(candidate) = self.compactions.pop() {
            let Some(cluster) = self
                .clusters
                .get(&candidate.source.cell)
                .filter(|cluster| cluster.generation == candidate.source.generation)
            else {
                continue;
            };
            if !crate::compaction::admit(selected.len(), bytes, candidate.bytes, budget) {
                self.compactions.defer(candidate);
                break;
            }
            let target = match cluster
                .acceleration
                .compact_target(&mut self.builds, candidate.bytes)
            {
                Ok(target) => target,
                Err(error) => {
                    for (_, target) in selected {
                        target.retire(&mut self.builds);
                    }
                    return Err(error);
                }
            };
            bytes += candidate.bytes;
            selected.push((candidate, target));
        }
        if selected.is_empty() {
            return Ok(false);
        }
        let result = context.submit_named("blas_compaction", |command| {
            for (candidate, target) in &selected {
                self.clusters[&candidate.source.cell]
                    .acceleration
                    .record_compact_copy(command, target);
            }
            Acceleration::read_barrier(context, command);
        });
        if let Err(error) = result {
            for (_, target) in selected {
                target.retire(&mut self.builds);
            }
            return Err(error);
        }
        for (candidate, target) in selected {
            let cluster = self.clusters.get_mut(&candidate.source.cell).unwrap();
            self.directory
                .acceleration(candidate.source.cell, target.address());
            let source = std::mem::replace(&mut cluster.acceleration, target);
            self.compaction_sources
                .push((source, candidate.source.bytes - candidate.bytes));
        }
        Ok(true)
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
            self.materials.iter().map(|a| a.page_count() as u64).sum(),
            self.objects.page_count() as u64,
            self.objects.count() as u64,
            (self.directory.instances.len() + self.objects.instances.len()) as u64,
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

pub(super) unsafe fn transfer_write_barrier(context: &Context, command: vk::CommandBuffer) {
    let barrier = [vk::MemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
        .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)];
    unsafe {
        context.device.cmd_pipeline_barrier(
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &barrier,
            &[],
            &[],
        );
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
    #[ignore = "windowless real BLAS compaction sizes, budgeted frozen maintenance and pixel equivalence"]
    fn gpu_static_blas_compaction_preserves_pixels_slots_and_cache_dependencies() {
        let mut scene = Scene {
            revision: 1,
            ..Default::default()
        };
        scene.textures.insert(
            7,
            prime_scene::scene::Texture {
                width: 2,
                height: 2,
                pixels: vec![
                    255, 255, 255, 0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0,
                ]
                .into(),
                region: Some([0, 0, 2, 2]),
                sampling: None,
                material: None,
            },
        );
        for (i, flags) in [0, 1].into_iter().enumerate() {
            let mut source = mesh(i as f32 * 64., flags, [0.3, 0.7, 0.2, 1.]);
            let triangles: Vec<_> = (0..12)
                .flat_map(|y| {
                    (0..12).flat_map(move |x| {
                        let corners = [
                            [x as f32 * 3. - 18., y as f32 * 3. - 18., 0.],
                            [x as f32 * 3. - 15., y as f32 * 3. - 18., 0.],
                            [x as f32 * 3. - 15., y as f32 * 3. - 15., 0.],
                            [x as f32 * 3. - 18., y as f32 * 3. - 15., 0.],
                        ];
                        [[0, 1, 2], [2, 3, 0]].map(|indices| Triangle {
                            positions: indices.map(|i| corners[i]),
                            colors: [[0.3, 0.7, 0.2, 1.]; 3],
                            uvs: indices.map(|i| [[0., 0.], [1., 0.], [1., 1.], [0., 1.]][i]),
                            texture_id: if flags == 1 { 7 } else { 0 },
                            flags,
                        })
                    })
                })
                .collect();
            source.triangles = triangles.into();
            scene.meshes.insert((i as u64 + 1, 0), source);
            scene.ready_terrain.insert(cell([i as i32, 0, 0]));
        }
        let camera = Camera {
            position: [32., 0., 96.],
            forward: [0., 0., -1.],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            vertical_fov_radians: 1.0,
        };
        let settings = crate::RenderSettings {
            mode: crate::RenderMode::Realtime,
            view: prime_scene::settings::DiagnosticView::NoisyColor,
            ray_reconstruction: false,
            stars: 0.0,
            auto_exposure_compensation: 0.0,
            ..Default::default()
        };
        let workers = Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap());
        let mut renderer = Renderer::with_settings_and_workers(settings, workers.clone()).unwrap();
        let expected = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert!(
            geometry.opacity_micromap,
            "actual OMM capability is required, not skipped"
        );
        let cutout = &geometry.clusters[&cell([1, 0, 0])];
        assert!(!cutout.micromaps.is_empty());
        assert!(cutout.omm_counts[..2].iter().any(|count| *count > 0));
        assert!(
            cutout.omm_counts[3] > 0,
            "actual shared micromap blocks, not special indices"
        );
        eprintln!(
            "compacted cutout actual OMM binding counts={:?}",
            cutout.omm_counts
        );
        let originals: Vec<_> = geometry
            .clusters
            .iter()
            .map(|(&cell, cluster)| {
                (
                    cell,
                    cluster.generation,
                    cluster.acceleration.address(),
                    cluster.acceleration.storage_bytes(),
                    geometry.static_slot(cell),
                )
            })
            .collect();
        let revision = renderer.atmosphere_scene_revision;
        renderer
            .configure(crate::RenderSettings {
                terrain_batches_per_frame: 1,
                ..settings
            })
            .unwrap();
        renderer.set_scene_frozen(true);
        for count in 1..=2 {
            let actual = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
            assert_eq!(
                actual, expected,
                "compaction changed coverage/geometry/material output"
            );
            let geometry = renderer.geometry.as_ref().unwrap();
            geometry
                .top
                .assert_current_input(&geometry.directory.instances, &geometry.objects.instances);
            assert_eq!(geometry.compactions.copies, count);
            assert_eq!(geometry.rebuilt_clusters, 0);
            assert!(!geometry.static_occlusion_changed);
            assert_eq!(renderer.atmosphere_scene_revision, revision);
            for &(cell, generation, _, _, slot) in &originals {
                assert_eq!(geometry.clusters[&cell].generation, generation);
                assert_eq!(geometry.static_slot(cell), slot);
            }
        }
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.compactions.completed, 2);
        assert!(!geometry.needs_update((&scene).into()));
        for &(cell, _, address, bytes, _) in &originals {
            let target = &geometry.clusters[&cell].acceleration;
            assert_ne!(target.address(), address);
            assert!(target.storage_bytes() < bytes);
            eprintln!(
                "BLAS compact cell={cell:?} source={bytes} compacted={} arena_reserved={}",
                target.storage_bytes(),
                geometry.builds.reserved_bytes()
            );
        }
        let mut offline = Renderer::with_settings_and_workers(
            crate::RenderSettings {
                mode: crate::RenderMode::Offline,
                view: prime_scene::settings::DiagnosticView::Output,
                ..settings
            },
            workers,
        )
        .unwrap();
        offline.render(&scene, &camera, 32, 24, 0).unwrap();
        offline.set_scene_frozen(true);
        assert_eq!(offline.samples, 1);
        offline.render(&scene, &camera, 32, 24, 1).unwrap();
        assert_eq!(offline.geometry.as_ref().unwrap().compactions.copies, 2);
        assert_eq!(
            offline.samples, 2,
            "AS address maintenance must preserve accumulation"
        );
        drop(offline);
        // Replace then remove before the new query is consumed: stale results cannot
        // compact another generation even if Vulkan reuses raw handles or page slots.
        renderer.set_scene_frozen(false);
        scene.revision += 1;
        scene.meshes.get_mut(&(1, 0)).unwrap().revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let copies = renderer.geometry.as_ref().unwrap().compactions.copies;
        scene.revision += 1;
        scene.meshes.remove(&(1, 0));
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        assert_eq!(
            renderer.geometry.as_ref().unwrap().compactions.copies,
            copies
        );
        scene.meshes.get_mut(&(2, 0)).unwrap().revision += 1;
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let pending_generation =
            renderer.geometry.as_ref().unwrap().clusters[&cell([1, 0, 0])].generation;
        scene.epoch += 1;
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_ne!(
            geometry.clusters[&cell([1, 0, 0])].generation,
            pending_generation
        );
        assert_eq!(
            geometry.compactions.copies, copies,
            "epoch must cancel old query candidates"
        );
        renderer.set_scene_frozen(true);
        renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        assert_eq!(
            renderer.geometry.as_ref().unwrap().compactions.copies,
            copies + 1
        );
    }

    #[test]
    fn static_visibility_proof_accepts_rgb_only_and_rejects_coverage_geometry_changes() {
        use prime_scene::translation::{TerrainGeometry, TerrainUpdate};
        let source = mesh(0., 1, [1.; 4]);
        let update = |triangles: MeshGeometry| TerrainUpdate {
            key: cell([0; 3]),
            geometries: vec![TerrainGeometry {
                flags: 1,
                triangle_count: 2,
                members: vec![TerrainMember {
                    range: 0..2,
                    offset: [0.; 3],
                    triangles,
                }],
            }],
        };
        let proof = OcclusionProof::new(&update(source.triangles.clone())).unwrap();
        assert!(proof.matches(&update(source.triangles.clone())));
        let mut triangles = source.triangles.iter().collect::<Vec<_>>();
        for triangle in &mut triangles {
            for color in &mut triangle.colors {
                color[0] = 0.25;
                color[1] = 0.5;
            }
        }
        assert!(proof.matches(&update(triangles.clone().into())));
        for field in 0..5 {
            let mut changed = triangles.clone();
            match field {
                0 => changed[0].colors[0][3] = 0.5,
                1 => changed[0].positions[0][0] += 1.,
                2 => changed[0].uvs[0][0] += 1.,
                3 => changed[0].texture_id = 1,
                _ => changed[0].flags = 2,
            }
            assert!(!proof.matches(&update(changed.into())));
        }
        let mut moved = update(source.triangles.clone());
        moved.geometries[0].members[0].offset[0] = 1.;
        assert!(!proof.matches(&moved));
        let rich = prime_scene::surface::SurfaceMesh::from_resolved(
            1,
            vec![prime_scene::surface::SurfaceFace::from_quad(
                prime_scene::geometry::CompiledQuad {
                    positions: [[0.; 3]; 4],
                    color: [1.; 4],
                    uvs: [[0.; 2]; 4],
                    texture_id: 0,
                    flags: 1,
                },
            )],
        )
        .unwrap();
        assert!(OcclusionProof::new(&update(MeshGeometry::Surfaces(Arc::new(rich)))).is_none());
    }

    #[test]
    #[ignore = "windowless actual TLAS UPDATE pixels versus fresh BUILD and structural fallback"]
    fn gpu_pose_only_tlas_update_matches_fresh_build_and_structural_changes_fall_back() {
        use prime_scene::{
            Instance, Prototype,
            settings::{DiagnosticView, RenderMode, RenderSettings},
        };
        let scene = Scene {
            epoch: 1,
            ..Default::default()
        };
        let mut source = InstanceScene {
            epoch: 1,
            resource_revision: 1,
            instance_revision: 1,
            ..Default::default()
        };
        let triangles = mesh(0., 0, [1.; 4])
            .triangles
            .iter()
            .map(|mut t| {
                for p in &mut t.positions {
                    p[0] /= 40.;
                    p[1] /= 40.;
                }
                t
            })
            .collect::<Vec<_>>();
        source.prototypes.insert(
            1,
            Prototype {
                revision: 1,
                triangles: triangles.into(),
                bounds: [[-0.5, -0.5, 0.], [0.5, 0.5, 0.]],
            },
        );
        source.instances.insert(
            1,
            Instance {
                revision: 1,
                prototype_id: 1,
                origin: [0.; 3],
                transform: crate::plan::translation([0.; 3]),
                texture_id: crate::plan::INHERIT,
                flags: 0,
                tint: [255; 4],
                uv_transform: [1., 1., 0., 0.],
            },
        );
        let camera = Camera {
            position: [0., 0., 2.],
            forward: [0., 0., -1.],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            vertical_fov_radians: 1.,
        };
        let mut renderer = Renderer::new().unwrap();
        let mut reference = Renderer::new().unwrap();
        for r in [&mut renderer, &mut reference] {
            r.configure(RenderSettings {
                mode: RenderMode::Realtime,
                view: DiagnosticView::LinearDepth,
                ..Default::default()
            })
            .unwrap();
        }
        for (frame, x) in [0., 0.25, -0.25, 0.75, 0.5].into_iter().enumerate() {
            source.instances.get_mut(&1).unwrap().transform[3] = x;
            source.instances.get_mut(&1).unwrap().revision += 1;
            source.instance_revision += 1;
            let actual = renderer
                .render_with_instances(&scene, &source, &camera, 48, 32, 0)
                .unwrap();
            reference.geometry.take();
            let expected = reference
                .render_with_instances(&scene, &source, &camera, 48, 32, 0)
                .unwrap();
            assert_eq!(
                actual, expected,
                "pose UPDATE must match fresh BUILD at frame {frame}"
            );
            assert_eq!(
                renderer.geometry.as_ref().unwrap().top.last_update,
                frame != 0
            );
            renderer
                .geometry
                .as_ref()
                .unwrap()
                .assert_incremental_workspaces();
        }
        source.instances.get_mut(&1).unwrap().flags = 1;
        source.instances.get_mut(&1).unwrap().revision += 1;
        source.instance_revision += 1;
        renderer
            .render_with_instances(&scene, &source, &camera, 48, 32, 0)
            .unwrap();
        assert!(
            !renderer.geometry.as_ref().unwrap().top.last_update,
            "opacity flags require BUILD"
        );
        source.instances.remove(&1);
        source.instance_revision += 1;
        renderer
            .render_with_instances(&scene, &source, &camera, 48, 32, 0)
            .unwrap();
        assert!(
            !renderer.geometry.as_ref().unwrap().top.last_update,
            "membership/count changes require BUILD"
        );
    }

    #[test]
    #[ignore = "requires Vulkan; checks bounded publication, frozen accumulation and cancellation"]
    fn gpu_terrain_frame_budget_drains_frozen_scene_and_matches_full_build() {
        use prime_scene::settings::{RenderMode, RenderSettings};
        let settings = RenderSettings {
            mode: RenderMode::Offline,
            bounces: 1,
            ..Default::default()
        };
        let mut scene = Scene {
            epoch: 1,
            revision: 1,
            ..Default::default()
        };
        for id in 0..17 {
            scene.ready_terrain.insert(cell([id, 0, 0]));
            scene.meshes.insert(
                (id as u64, 0),
                mesh(id as f32 * 64., 0, [0.8, 0.2, 0.1, 1.]),
            );
        }
        let camera = Camera {
            position: [512., 0., 1200.],
            forward: [0., 0., -1.],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            vertical_fov_radians: 1.,
        };
        let mut renderer = Renderer::with_mode(RenderMode::Offline).unwrap();
        renderer.configure(settings).unwrap();
        let mut reference = Renderer::with_mode(RenderMode::Offline).unwrap();
        reference
            .configure(RenderSettings {
                terrain_batches_per_frame: 128,
                ..settings
            })
            .unwrap();
        let expected = reference.render(&scene, &camera, 96, 48, 0).unwrap();
        let mut final_pixels = Vec::new();
        let mut atmosphere = renderer.atmosphere_scene_revision;
        for (frame, (built, resident)) in [(8, 8), (8, 16), (1, 17)].into_iter().enumerate() {
            final_pixels = renderer
                .render(&scene, &camera, 96, 48, frame as u32)
                .unwrap();
            let geometry = renderer.geometry.as_ref().unwrap();
            assert_eq!(geometry.rebuilt_clusters, built);
            assert_eq!(geometry.clusters.len(), resident);
            assert_eq!(geometry.triangle_count, resident as u64 * 2);
            assert_eq!(
                renderer.samples, 1,
                "new geometry must discard partial-scene history"
            );
            assert!(renderer.atmosphere_scene_revision > atmosphere);
            atmosphere = renderer.atmosphere_scene_revision;
            renderer.set_scene_frozen(true);
        }
        assert_eq!(
            final_pixels, expected,
            "drained bounded build differs from full build"
        );
        renderer
            .configure(RenderSettings {
                terrain_batches_per_frame: 1,
                ..settings
            })
            .unwrap();
        renderer.render(&scene, &camera, 96, 48, 3).unwrap();
        assert_eq!(
            renderer.samples, 2,
            "budget-only changes must preserve history"
        );
        assert_eq!(renderer.atmosphere_scene_revision, atmosphere);
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 0);

        // Replace every resident cell and withdraw a queued cell before it is rebuilt.
        renderer.set_scene_frozen(false);
        for mesh in scene.meshes.values_mut() {
            mesh.revision += 1;
        }
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 48, 4).unwrap();
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 1);
        scene.meshes.remove(&(16, 0));
        scene.ready_terrain.remove(&cell([16, 0, 0]));
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 48, 5).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(geometry.clusters.len(), 16);
        assert!(!geometry.clusters.contains_key(&cell([16, 0, 0])));

        // An epoch change discards all old published and queued work before the new quota.
        scene.epoch += 1;
        scene.revision += 1;
        renderer.render(&scene, &camera, 96, 48, 6).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(geometry.clusters.len(), 1);
        assert_eq!(geometry.triangle_count, 2);
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
                // Compaction may replace BDA; the unchanged source build must survive.
                let blas = geometry.clusters[&cell([1, 0, 0])].generation;
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
        // Fill the actual first page without producing fictitious geometry or uploading padding.
        let capacity = (geometry.materials[0].buffer(first.records).size
            / crate::packing::stride(0) as u64) as u32;
        let filler = geometry.materials[0]
            .allocate(&context, capacity - first.records.count)
            .unwrap();
        assert_eq!(first.records.page, filler.page);
        scene
            .meshes
            .insert((2, 0), mesh(64.0, 0, [0.1, 0.8, 1.0, 1.0]));
        scene.revision += 1;
        let paged = renderer.render(&scene, &camera, 96, 64, 0).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        let second = &geometry.clusters[&cell([1, 0, 0])];
        assert_ne!(first.records.page, second.allocations[0].records.page);
        let retained_blas = second.generation;
        let retained_material = geometry.materials[0].address(second.allocations[0].records);
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
        assert_eq!(second.generation, retained_blas);
        assert_eq!(
            geometry.materials[0].address(second.allocations[0].records),
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
                region: None,
                sampling: None,
                material: None,
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
        let arena = &mut renderer.geometry.as_mut().unwrap().materials[0];
        let reserved = arena.allocate(&context, 1).unwrap();
        let capacity = (arena.buffer(reserved).size / crate::packing::stride(0) as u64) as u32;
        arena.allocate(&context, capacity - 2).unwrap();
        let mut separate = Renderer::new().unwrap();
        for seed in [0, 19, 500] {
            let merged = renderer.render(&scene, &camera, 96, 64, seed).unwrap();
            let expected = separate.render(&reference, &camera, 96, 64, seed).unwrap();
            if let Some(directory) = std::env::var_os("PRIME_MIXED_DIAGNOSTIC_DIR") {
                let directory = std::path::Path::new(&directory);
                std::fs::create_dir_all(directory).unwrap();
                std::fs::write(directory.join(format!("merged-{seed}.rgba")), &merged).unwrap();
                std::fs::write(directory.join(format!("dynamic-{seed}.rgba")), &expected).unwrap();
            }
            renderer
                .geometry
                .as_ref()
                .unwrap()
                .assert_incremental_workspaces();
            assert!(
                merged == expected,
                "mixed material geometry index mismatch, seed={seed}, differences={}, first={:?}",
                merged.iter().zip(&expected).filter(|(a, b)| a != b).count(),
                merged
                    .iter()
                    .zip(&expected)
                    .enumerate()
                    .find(|(_, (a, b))| a != b)
            );
        }
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.clusters.len(), 2);
        assert_eq!(geometry.directory.instances.len(), 2);
        let mixed = &geometry.clusters[&cell([0, 0, 0])];
        assert_eq!(mixed.allocations.len(), 3);
        assert_ne!(
            mixed.allocations[0].records.page,
            mixed.allocations[1].records.page
        );
        assert_eq!(
            geometry.directory.instances[1]
                .instance_custom_index_and_mask
                .low_24(),
            4
        );
        let retained = geometry.clusters[&cell([1, 0, 0])].generation;
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
            geometry.directory.instances[1]
                .instance_custom_index_and_mask
                .low_24(),
            4
        );
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(geometry.clusters[&cell([1, 0, 0])].generation, retained);
    }
}
