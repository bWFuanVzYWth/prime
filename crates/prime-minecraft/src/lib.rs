//! Prototype MC 26.2/26.3 adaptation. Java forwards fields and events; this owner plans demand.
#![feature(portable_simd)]
#![forbid(unsafe_code)]
mod biome;
mod biome_source;
mod chunks;
mod column_cache;
mod compile;
mod compile_queue;
mod contact;
pub mod environment;
mod fluid;
mod labpbr;
mod model;
mod optics;
mod placement;
mod shape;
mod sprite;
mod surfaces;
mod tint;
mod typed;
mod typed_resources;
mod volume;
#[cfg(test)]
use compile::compile_slab;
#[cfg(test)]
mod legacy;
#[cfg(test)]
mod reference;
mod schedule;
#[cfg(test)]
mod wire;

use model::{Catalog, Hacks};
use prime_scene::{
    SourceScene,
    compiled::{CompiledQuad, CompiledSection},
    workers::CpuWorkers,
};
use schedule::{Demand, FrameInput, Section};
use std::{
    collections::{BTreeSet, HashMap, HashSet},
    sync::Arc,
    time::Instant,
};
#[cfg(test)]
use wire::{Reader, u32_to, u64_to};

#[derive(PartialEq)]
struct SectionData {
    palette: Vec<u32>,
    storage: Vec<u64>,
    bits: u32,
    per_word: usize,
}
impl SectionData {
    #[cfg(test)]
    fn read(r: &mut Reader<'_>) -> Result<Self, String> {
        let bits = r.u32()?;
        let count = r.count(4)?;
        let words = r.u32()? as usize;
        if bits > 32 || count > 4096 || (bits == 0 && (words != 0 || count != 1)) {
            return Err("invalid section palette layout".into());
        }
        let per_word = if bits == 0 { 0 } else { 64 / bits as usize };
        if bits != 0 && words != 4096usize.div_ceil(per_word) {
            return Err("invalid section storage length".into());
        }
        let palette = r.u32s(count)?;
        let storage = r.u64s(words)?;
        let result = Self {
            palette,
            storage,
            bits,
            per_word,
        };
        if !result.valid_indices() {
            return Err("section palette index out of range".into());
        }
        Ok(result)
    }
    fn valid_indices(&self) -> bool {
        use std::simd::{Simd, cmp::SimdPartialEq};
        type Words = Simd<u64, 8>;
        let count = self.palette.len() as u64;
        if self.bits == 0 || count == 0 || count >= 1_u64 << self.bits {
            return true;
        }
        // Put a sentinel in each packed field before subtraction: a borrow can clear that
        // field's sentinel but cannot escape into the next field. Combine with the source
        // high bit to compare the whole index, without unpacking all 4096 scalar indices.
        let ones = (0..self.per_word).fold(0, |bits, lane| bits | 1 << (lane * self.bits as usize));
        let high_bit = 1 << (self.bits - 1);
        let high = ones * high_bit;
        let threshold = ones * (count & (high_bit - 1));
        let invalid = |word: Words| {
            let top = word & Words::splat(high);
            let lower_ge = (((word & !Words::splat(high)) | Words::splat(high))
                - Words::splat(threshold))
                & Words::splat(high);
            if count < high_bit {
                top | lower_ge
            } else {
                top & lower_ge
            }
        };
        let full = 4096 / self.per_word;
        let (vectors, tail) = self.storage[..full].as_chunks::<8>();
        for words in vectors {
            if invalid(Words::from_array(*words))
                .simd_ne(Words::splat(0))
                .any()
            {
                return false;
            }
        }
        for &word in tail {
            if invalid(Words::splat(word))[0] != 0 {
                return false;
            }
        }
        if full < self.storage.len() {
            let used = 4096 % self.per_word;
            let active = high & ((1_u64 << (used * self.bits as usize)) - 1);
            if invalid(Words::splat(self.storage[full]))[0] & active != 0 {
                return false;
            }
        }
        true
    }
    fn index(&self, i: usize) -> u32 {
        if self.bits == 0 {
            0
        } else {
            ((self.storage[i / self.per_word] >> ((i % self.per_word) * self.bits as usize))
                & ((1u64 << self.bits) - 1)) as u32
        }
    }
    fn state(&self, i: usize) -> u32 {
        let n = self.index(i);
        if self.palette.is_empty() {
            n
        } else {
            self.palette[n as usize]
        }
    }
    fn empty(&self, catalog: &Catalog) -> bool {
        !self.palette.is_empty()
            && self
                .palette
                .iter()
                .all(|id| catalog.states.get(id).is_some_and(model::State::air))
    }
}

struct Pending {
    input: FrameInput,
    demand: Demand,
    cell_budget: usize,
}
enum ColorStage {
    Sources,
    Biomes(biome::Plan, Box<biome_source::Plan>),
}
struct AwaitingColors {
    requests: Vec<tint::Request>,
    stage: ColorStage,
    batch: u64,
    jobs: Vec<Job>,
    selection: compile_queue::Selection,
    removed: BTreeSet<Section>,
    aliases: Vec<usize>,
    reset_catalog: bool,
}
#[derive(Default)]
pub struct TerrainContext {
    typed_output: typed::Output,
    resource_generation: u64,
    resource_version: u32,
    resource_reset_pending: bool,
    epoch: u64,
    version: u32,
    last_batch: u64,
    chunks: chunks::ChunkManager,
    catalog: Catalog,
    animation_tick: Option<u64>,
    animated: Vec<u32>,
    textures: Vec<(u32, prime_scene::Texture)>,
    pending: Option<Pending>,
    awaiting_colors: Option<AwaitingColors>,
    #[cfg(test)]
    tint_requests: Vec<u8>,
    #[cfg(test)]
    requests: Vec<u8>,
    workers: Option<Arc<CpuWorkers>>,
    stats: Stats,
}
#[derive(Default)]
struct Stats {
    request_batches: u32,
    response_batches: u32,
    plan_ms: f64,
    decode_ms: f64,
    compile_ms: f64,
    kernel_ms: f64,
    finalize_ms: f64,
    publish_ms: f64,
    retire_ms: f64,
    tint_pack_ms: f64,
    tint_decode_ms: f64,
    tint_requests: usize,
    tint_callbacks: usize,
    tint_bytes: usize,
    biome_samples: usize,
    biome_cached_samples: usize,
    biome_hits: usize,
    biome_plan_ms: f64,
    biome_filter_ms: f64,
    biome_source_ms: f64,
    biome_host_cells: usize,
    biome_pages: usize,
    published_layers: usize,
    retained_layers: usize,
    requested: usize,
    changed: usize,
    compiled: usize,
    compiled_cells: usize,
    jobs: usize,
    bytes: usize,
    triangles: usize,
    hacks: Hacks,
    #[cfg(test)]
    availability_updates: usize,
}
impl TerrainContext {
    /// All production CPU consumers borrow the session's synchronous worker pool.
    pub fn with_workers(workers: Arc<CpuWorkers>) -> Self {
        Self {
            workers: Some(workers),
            ..Self::default()
        }
    }
    pub fn cpu_workers(&self) -> Option<&Arc<CpuWorkers>> {
        self.workers.as_ref()
    }
    /// Release all world-owned CPU sources immediately; resources and the shared pool survive.
    /// The caller validates and commits the corresponding SourceScene epoch first.
    pub fn clear_world(&mut self, epoch: u64) {
        self.epoch = epoch;
        self.version = self.resource_version;
        self.last_batch = 0;
        self.chunks = Default::default();
        self.pending = None;
        self.awaiting_colors = None;
        #[cfg(test)]
        {
            self.requests = Vec::new();
            self.tint_requests = Vec::new();
        }
        self.textures = Vec::new();
        self.typed_output = Default::default();
        self.stats = Default::default();
        if self.resource_generation == 0 {
            self.catalog = Catalog::default();
            self.animated.clear();
            self.animation_tick = None;
        }
    }

    fn plan_input(
        &mut self,
        input: FrameInput,
        source_epoch: u64,
        cell_budget: usize,
        start: Instant,
    ) -> Result<(), String> {
        if input.epoch != source_epoch {
            return Err("section request epoch differs from renderer".into());
        }
        if input.epoch != self.epoch {
            self.clear_world(input.epoch);
        }
        if self.version == 0 {
            self.version = input.version;
        }
        if input.version != self.version || input.batch <= self.last_batch {
            return Err("stale section request frame/version".into());
        }
        let demand = self.chunks.plan(&input);
        self.stats = Stats {
            request_batches: 1,
            requested: demand.requests.len(),
            plan_ms: start.elapsed().as_secs_f64() * 1000.0,
            ..Stats::default()
        };
        self.pending = Some(Pending {
            input,
            demand,
            cell_budget,
        });
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn start_compile(
        &mut self,
        input: FrameInput,
        demand: Demand,
        received: HashMap<Section, Option<SectionData>>,
        cell_budget: usize,
        scene: &mut SourceScene,
        start: Instant,
        bytes: usize,
    ) -> Result<(), String> {
        let batch = input.batch;
        let chunks::CompileBatch {
            selection,
            removed,
            reset_catalog,
            changed,
        } = self
            .chunks
            .accept_sources(&input, demand, received, &self.catalog, cell_budget);
        self.stats.changed = changed;
        #[cfg(test)]
        {
            self.stats.availability_updates = self.chunks.availability_updates();
        }
        self.stats.decode_ms = start.elapsed().as_secs_f64() * 1000.0;
        self.stats.bytes = bytes;
        let compile_start = Instant::now();
        // Four equal-height slabs share costly sections. Both phases join on the same private pool.
        let mut jobs = Vec::new();
        for &key in &selection.sections {
            if !self.chunks.sources()[&key].empty(&self.catalog) {
                for first_y in [0, 4, 8, 12] {
                    jobs.push(Job {
                        key,
                        first_y,
                        layers: Default::default(),
                        surfaces: Default::default(),
                        hacks: Hacks::default(),
                        compiled: None,
                        tints: Default::default(),
                        color_start: 0,
                    });
                }
            }
        }
        let kernel_start = Instant::now();
        if !jobs.is_empty() {
            if self.workers.is_none() {
                self.workers = Some(Arc::new(CpuWorkers::configured()?));
            }
            let catalog = &self.catalog;
            let sections = self.chunks.sources();
            let renderable = self.chunks.renderable_cells();
            self.workers
                .as_ref()
                .unwrap()
                .chunks_mut(&mut jobs, 1, |_, output| {
                    for job in output {
                        compile::compile_contacts(job, catalog, sections, renderable);
                    }
                    Ok(())
                })?;
        }
        self.stats.kernel_ms = kernel_start.elapsed().as_secs_f64() * 1000.;
        self.stats.jobs = jobs.len();
        self.stats.compiled = selection.sections.len();
        self.stats.compiled_cells = selection.cell_count();
        self.stats.compile_ms = compile_start.elapsed().as_secs_f64() * 1000.;
        let tint_start = Instant::now();
        let mut count = 0usize;
        for job in &mut jobs {
            job.color_start = count;
            count = count
                .checked_add(job.tints.requests.len())
                .ok_or("tint count overflow")?;
        }
        let mut unique = Vec::new();
        let mut indices = HashMap::new();
        let aliases: Vec<_> = jobs
            .iter()
            .flat_map(|j| &j.tints.requests)
            .map(|&request| {
                *indices.entry(request).or_insert_with(|| {
                    unique.push(request);
                    unique.len() - 1
                })
            })
            .collect();
        self.stats.tint_requests = unique.len();
        self.stats.response_batches = 1;
        if count != 0 {
            self.stats.request_batches += 1;
            self.stats.tint_bytes =
                unique.len() * std::mem::size_of::<prime_abi::PrimeMcColorRequest>();
            self.awaiting_colors = Some(AwaitingColors {
                requests: unique,
                stage: ColorStage::Sources,
                aliases,
                batch,
                jobs,
                selection,
                removed,
                reset_catalog,
            });
            self.stats.tint_pack_ms = tint_start.elapsed().as_secs_f64() * 1000.;
            return Ok(());
        }
        self.finalize(jobs, selection, removed, batch, scene, None, reset_catalog)
    }
    /// Decoders validate their complete response before entering this shared preparation.
    /// Request counts/bytes describe typed arrays; fixture adapters account their wire framing.
    #[allow(clippy::too_many_arguments)]
    fn prepare_color_sources(
        &mut self,
        recipes: &[biome::Recipe],
        definitions: Option<biome_source::Definitions>,
        radius: i32,
        callbacks: usize,
        bytes: usize,
        decode_start: Instant,
    ) -> Result<Option<Vec<u32>>, String> {
        if definitions.is_some() && self.chunks.biome_sources().definitions.is_some() {
            return Err("unexpected biome definitions".into());
        }
        if definitions.is_none()
            && self.chunks.biome_sources().definitions.is_none()
            && recipes
                .iter()
                .any(|r| matches!(r, biome::Recipe::Biome { .. }))
        {
            return Err("missing biome definitions".into());
        }
        if let Some(definitions) = definitions {
            self.chunks.biome_sources_mut().definitions = Some(definitions);
        }
        self.stats.tint_callbacks = callbacks;
        self.stats.tint_decode_ms += decode_start.elapsed().as_secs_f64() * 1000.;
        self.stats.tint_bytes += bytes;
        self.stats.response_batches += 1;
        let start = Instant::now();
        let waiting = self.awaiting_colors.as_ref().unwrap();
        let recipes: Vec<_> = waiting.aliases.iter().map(|&i| recipes[i]).collect();
        let plan = self.chunks.biomes_mut().prepare(
            waiting
                .jobs
                .iter()
                .flat_map(|j| j.tints.requests.iter().copied()),
            &recipes,
            radius,
        );
        self.stats.biome_plan_ms = start.elapsed().as_secs_f64() * 1000.;
        self.stats.biome_hits = plan.hits;
        self.stats.biome_samples = plan.samples.len();
        self.stats.biome_cached_samples = plan.cached_samples;
        if plan.samples.is_empty() {
            let start = Instant::now();
            let colors = self.chunks.biomes_mut().finish(plan, &[]);
            self.stats.biome_filter_ms = start.elapsed().as_secs_f64() * 1000.;
            return Ok(Some(colors));
        }
        let start = Instant::now();
        let source = self.chunks.biome_sources_mut().prepare(&plan.samples);
        self.stats.biome_source_ms = start.elapsed().as_secs_f64() * 1000.;
        self.stats.biome_pages = source.requests.len();
        self.stats.biome_host_cells = source
            .requests
            .iter()
            .map(|r| r.mask.count_ones() as usize)
            .sum();
        if source.requests.is_empty() {
            return Ok(Some(self.finish_biome_colors(plan, source, None)));
        }
        self.stats.request_batches += 1;
        self.stats.tint_bytes +=
            source.requests.len() * std::mem::size_of::<prime_abi::PrimeMcBiomeRequest>();
        self.awaiting_colors.as_mut().unwrap().stage = ColorStage::Biomes(plan, Box::new(source));
        Ok(None)
    }

    fn complete_colors(&mut self, colors: &[u32], scene: &mut SourceScene) -> Result<(), String> {
        let pending = self.awaiting_colors.take().unwrap();
        self.finalize(
            pending.jobs,
            pending.selection,
            pending.removed,
            pending.batch,
            scene,
            Some(colors),
            pending.reset_catalog,
        )
    }

    fn complete_biome_sources(
        &mut self,
        response: biome_source::Response,
        bytes: usize,
        decode_start: Instant,
        scene: &mut SourceScene,
    ) -> Result<(), String> {
        self.stats.tint_decode_ms += decode_start.elapsed().as_secs_f64() * 1000.;
        self.stats.tint_bytes += bytes;
        self.stats.response_batches += 1;
        let pending = self.awaiting_colors.take().unwrap();
        let ColorStage::Biomes(plan, source) = pending.stage else {
            unreachable!()
        };
        let colors = self.finish_biome_colors(plan, *source, Some(response));
        self.finalize(
            pending.jobs,
            pending.selection,
            pending.removed,
            pending.batch,
            scene,
            Some(&colors),
            pending.reset_catalog,
        )
    }

    fn finish_biome_colors(
        &mut self,
        plan: biome::Plan,
        source: biome_source::Plan,
        response: Option<biome_source::Response>,
    ) -> Vec<u32> {
        let start = Instant::now();
        let raw =
            self.chunks
                .biome_sources_mut()
                .finish(source, response, &plan.samples, self.version);
        self.stats.biome_source_ms += start.elapsed().as_secs_f64() * 1000.;
        let start = Instant::now();
        let colors = self.chunks.biomes_mut().finish(plan, &raw);
        self.stats.biome_filter_ms = start.elapsed().as_secs_f64() * 1000.;
        colors
    }
    #[allow(clippy::too_many_arguments)]
    fn finalize(
        &mut self,
        mut jobs: Vec<Job>,
        selection: compile_queue::Selection,
        removed: BTreeSet<Section>,
        batch: u64,
        scene: &mut SourceScene,
        colors: Option<&[u32]>,
        reset_catalog: bool,
    ) -> Result<(), String> {
        let finalize_start = Instant::now();
        let tinted: Vec<_> = jobs
            .chunks(4)
            .filter(|group| group.iter().any(|job| !job.tints.requests.is_empty()))
            .map(|group| group[0].key)
            .collect();
        if !jobs.is_empty() {
            self.workers
                .as_ref()
                .unwrap()
                .chunks_mut(&mut jobs, 4, |_, jobs| {
                    for group in jobs.chunks_mut(4) {
                        if let Some(colors) = colors {
                            for job in group.iter_mut() {
                                job.tints.apply(
                                    &colors[job.color_start
                                        ..job.color_start + job.tints.requests.len()],
                                    &mut job.layers,
                                    &mut job.surfaces,
                                );
                            }
                        }
                        let parts: [_; 4] =
                            std::array::from_fn(|i| std::mem::take(&mut group[i].layers));
                        let mut compiled = scene.prepare_compiled_quad_fragments(
                            group[0].key.key(),
                            group[0].key.origin(),
                            parts,
                        );
                        let surfaces = std::array::from_fn(|i| {
                            group
                                .iter_mut()
                                .flat_map(|job| std::mem::take(&mut job.surfaces[i]))
                                .collect()
                        });
                        scene.prepare_surface_layers(&mut compiled, surfaces);
                        // Tint scratch is no longer needed. Geometry ownership moved into the
                        // proof; redundant fragment buffers were disposed on these workers.
                        for job in group.iter_mut() {
                            job.tints = Default::default();
                        }
                        group[0].compiled = Some(compiled);
                    }
                    Ok(())
                })?;
        }
        let mut jobs = jobs.into_iter().peekable();
        let mut replacements = Vec::with_capacity(selection.sections.len());
        for &key in &selection.sections {
            let mut compiled = None;
            while jobs.peek().is_some_and(|j| j.key == key) {
                let job = jobs.next().unwrap();
                self.stats.hacks += job.hacks;
                if let Some(section) = job.compiled {
                    compiled = Some(section);
                }
            }
            let compiled = compiled.unwrap_or_else(|| {
                scene.prepare_compiled(key.key(), key.origin(), Default::default())
            });
            self.stats.triangles += compiled.triangle_count();
            replacements.push(compiled);
        }
        self.stats.finalize_ms = finalize_start.elapsed().as_secs_f64() * 1000.;
        self.stats.compile_ms += self.stats.finalize_ms;
        let publish_start = Instant::now();
        let removed_keys: Vec<_> = removed.iter().map(|s| s.key()).collect();
        let textures = std::mem::take(&mut self.textures);
        let mut publication = if reset_catalog {
            scene.replace_compiled_resource_generation(
                self.epoch,
                batch,
                replacements,
                &removed_keys,
                textures,
            )?
        } else {
            scene.publish_compiled_with_textures(
                self.epoch,
                batch,
                replacements,
                &removed_keys,
                textures,
            )?
        };
        self.chunks.published(&selection, &removed, tinted);
        self.stats.published_layers = publication.replaced_layers;
        self.stats.retained_layers = publication.retained_layers;
        self.stats.publish_ms = publish_start.elapsed().as_secs_f64() * 1000.0;
        let retire_start = Instant::now();
        publication.release_retired(self.workers.as_deref())?;
        self.stats.retire_ms = retire_start.elapsed().as_secs_f64() * 1000.;
        self.last_batch = batch;
        Ok(())
    }
    pub fn diagnostics(&self) -> String {
        let s = &self.stats;
        let h = s.hacks;
        format!(
            "mc_source[epoch={} batch={} plan={:.3} decode={:.3} compile={:.3} kernel={:.3} finalize={:.3} publish={:.3} retire={:.3} published_layers={} retained_layers={} request_batches={} response_batches={} requested={} changed={} compiled={} compiled_cells={} pending_cells={} jobs={} source_bytes={} tint_requests={} tint_callbacks={} tint_bytes={} tint_pack={:.3} tint_decode={:.3} biome_samples={} biome_cached_samples={} biome_hits={} biome_plan={:.3} biome_filter={:.3} biome_source={:.3} biome_host_cells={} biome_pages={} triangles={} resident={} active={} hacks(model={},tint={},offset={},fluid={},optics={},sprite={}) placement_unknown(offset={},seed={})]",
            self.epoch,
            self.pending
                .as_ref()
                .map(|p| p.input.batch)
                .or_else(|| self.awaiting_colors.as_ref().map(|p| p.batch))
                .unwrap_or(self.last_batch),
            s.plan_ms,
            s.decode_ms,
            s.compile_ms,
            s.kernel_ms,
            s.finalize_ms,
            s.publish_ms,
            s.retire_ms,
            s.published_layers,
            s.retained_layers,
            s.request_batches,
            s.response_batches,
            s.requested,
            s.changed,
            s.compiled,
            s.compiled_cells,
            self.chunks.pending_cells(),
            s.jobs,
            s.bytes,
            s.tint_requests,
            s.tint_callbacks,
            s.tint_bytes,
            s.tint_pack_ms,
            s.tint_decode_ms,
            s.biome_samples,
            s.biome_cached_samples,
            s.biome_hits,
            s.biome_plan_ms,
            s.biome_filter_ms,
            s.biome_source_ms,
            s.biome_host_cells,
            s.biome_pages,
            s.triangles,
            self.chunks.sources().len(),
            self.chunks.active_len(),
            h.model,
            h.tint,
            h.offset,
            h.fluid,
            h.optics,
            h.sprite,
            h.offset_unknown,
            h.seed_unknown
        )
    }
}
struct Job {
    key: Section,
    first_y: usize,
    layers: [Vec<CompiledQuad>; 3],
    surfaces: [Vec<prime_scene::surface::SurfaceFace>; 3],
    hacks: Hacks,
    compiled: Option<CompiledSection>,
    tints: tint::Deferred,
    color_start: usize,
}

#[cfg(test)]
mod budget_tests;
#[cfg(test)]
mod oracle;
#[cfg(test)]
mod perf;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod typed_tests;
