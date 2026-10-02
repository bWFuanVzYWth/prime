//! Prototype MC 26.2/26.3 adaptation. Java forwards fields and events; this owner plans demand.
#![feature(portable_simd)]
#![forbid(unsafe_code)]
mod biome;
mod biome_source;
mod chunks;
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
mod volume;
#[cfg(test)]
use compile::compile_slab;
#[cfg(test)]
mod reference;
mod schedule;
pub mod wire;

use model::{Catalog, Hacks};
use prime_scene::{
    SourceScene,
    compiled::{CompiledQuad, CompiledSection},
    workers::CpuWorkers,
};
use schedule::{Demand, FrameInput, Section};
use std::{
    collections::{BTreeSet, HashMap, HashSet},
    time::Instant,
};
use wire::{Reader, u32_to, u64_to};

#[derive(PartialEq)]
struct SectionData {
    palette: Vec<u32>,
    storage: Vec<u64>,
    bits: u32,
    per_word: usize,
}
impl SectionData {
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
    tint_requests: Vec<u8>,
    requests: Vec<u8>,
    workers: Option<CpuWorkers>,
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
    pub fn plan(&mut self, pages: &[&[u8]], source_epoch: u64) -> Result<&[u8], String> {
        self.plan_budget(pages, source_epoch, usize::MAX)
    }

    /// Production entry: finish source capture now, but compile at most this many cells.
    /// Deferred compilation resumes on subsequent live batches, not while offline is frozen.
    pub fn plan_with_budget(
        &mut self,
        pages: &[&[u8]],
        source_epoch: u64,
        cell_budget: u32,
    ) -> Result<&[u8], String> {
        if !(1..=128).contains(&cell_budget) {
            return Err("terrain build budget must be between 1 and 128 cells".into());
        }
        self.plan_budget(pages, source_epoch, cell_budget as usize)
    }

    fn plan_budget(
        &mut self,
        pages: &[&[u8]],
        source_epoch: u64,
        cell_budget: usize,
    ) -> Result<&[u8], String> {
        let start = Instant::now();
        if self.pending.is_some() || self.awaiting_colors.is_some() {
            return Err("previous source batch still awaiting completion".into());
        }
        let input = FrameInput::read(pages)?;
        if input.epoch != source_epoch {
            return Err("section request epoch differs from renderer".into());
        }
        if input.epoch != self.epoch {
            let workers = self.workers.take();
            *self = Self {
                epoch: input.epoch,
                version: input.version,
                workers,
                ..Self::default()
            };
        }
        if input.version != self.version || input.batch <= self.last_batch {
            return Err("stale section request frame/version".into());
        }
        let demand = self.chunks.plan(&input);
        self.requests.clear();
        u64_to(&mut self.requests, input.batch);
        u64_to(&mut self.requests, demand.requests.len() as u64);
        u64_to(&mut self.requests, demand.columns.len() as u64);
        u64_to(&mut self.requests, self.chunks.active_len() as u64);
        for &s in &demand.requests {
            for v in [s.0, s.1, s.2] {
                u32_to(&mut self.requests, v as u32);
            }
            u32_to(&mut self.requests, u32::from(self.chunks.is_active(&s)));
        }
        for &(x, z, active) in &demand.columns {
            for v in [x, z, i32::from(active)] {
                u32_to(&mut self.requests, v as u32);
            }
        }
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
        Ok(&self.requests)
    }

    pub fn accept(&mut self, pages: &[&[u8]], scene: &mut SourceScene) -> Result<(), String> {
        if self.awaiting_colors.is_some() {
            return self.accept_colors(pages, scene);
        }
        self.tint_requests.clear();
        let start = Instant::now();
        let pending = self
            .pending
            .as_ref()
            .ok_or("unsolicited section response")?;
        let mut r = Reader::new(pages)?;
        let (version, epoch, batch) = r.header(2)?;
        if (version, epoch, batch) != (self.version, self.epoch, pending.input.batch)
            || epoch != scene.epoch()
        {
            return Err("section response identity mismatch".into());
        }
        let requested: HashSet<_> = pending.demand.requests.iter().copied().collect();
        let mut received = HashMap::with_capacity(requested.len());
        let mut states = HashMap::new();
        let mut models = HashMap::new();
        let mut faces = HashMap::new();
        let mut fluids = HashMap::new();
        let mut sprites = HashMap::new();
        loop {
            match r.u32()? {
                0 => break,
                1 => {
                    let (id, state) = model::state(&mut r)?;
                    if states.insert(id, state).is_some() {
                        return Err("duplicate state definition".into());
                    }
                }
                2 => {
                    let (id, model) = model::model(&mut r)?;
                    if models.insert(id, model).is_some() {
                        return Err("duplicate model definition".into());
                    }
                }
                3 => {
                    let section = Section(r.i32()?, r.i32()?, r.i32()?);
                    if !requested.contains(&section) || received.contains_key(&section) {
                        return Err("section response was not requested or is duplicated".into());
                    }
                    let data = match r.u32()? {
                        0 => None,
                        1 => Some(SectionData::read(&mut r)?),
                        _ => return Err("invalid section availability".into()),
                    };
                    received.insert(section, data);
                }
                4 => {
                    let (id, face) = shape::Face::read(&mut r)?;
                    if faces.insert(id, face).is_some() {
                        return Err("duplicate face profile".into());
                    }
                }
                5 => {
                    let (id, fluid) = fluid::FluidMaterial::read(&mut r)?;
                    if fluids.insert(id, fluid).is_some() {
                        return Err("duplicate fluid material".into());
                    }
                }
                6 => {
                    let (id, sprite) = sprite::Sprite::read(&mut r)?;
                    if sprites.insert(id, sprite).is_some() {
                        return Err("duplicate sprite definition".into());
                    }
                }
                9 => {
                    let id = r.u32()?;
                    let sprite = sprites
                        .get_mut(&id)
                        .ok_or("LabPBR references undefined batch sprite")?;
                    if sprite.material.is_some() {
                        return Err("duplicate LabPBR definition".into());
                    }
                    sprite.material = Some(labpbr::Material::read(&mut r, sprite)?);
                }
                _ => return Err("unknown section response record".into()),
            }
        }
        r.finish()?;
        if received.len() != requested.len() {
            return Err("incomplete section response".into());
        }
        for model in models.values_mut() {
            if let model::Model::Mesh(quads) = model {
                for quad in quads {
                    if quad.sprite != 0 {
                        let sprite = sprites
                            .get(&quad.sprite)
                            .or_else(|| {
                                (!pending.demand.reset_catalog)
                                    .then(|| self.catalog.sprites.get(&quad.sprite))
                                    .flatten()
                            })
                            .ok_or("undefined source sprite")?;
                        if let Some(uvs) = sprite.local(quad.uvs) {
                            quad.uvs = uvs;
                        } else {
                            // Preserve unknown cross-sprite sampling, not a silently clamped edge.
                            quad.sprite = 0;
                            self.stats.hacks.sprite += 1;
                        }
                    }
                }
            }
        }
        // A resource ID denotes immutable content until the explicit catalog invalidation.
        // Reject in-place changes before committing any source data or consuming Pending.
        fn unchanged<T: PartialEq>(old: &HashMap<u32, T>, new: &HashMap<u32, T>) -> bool {
            new.iter()
                .all(|(id, value)| old.get(id).is_none_or(|previous| previous == value))
        }
        if !pending.demand.reset_catalog
            && (!unchanged(&self.catalog.states, &states)
                || !unchanged(&self.catalog.models, &models)
                || !unchanged(&self.catalog.fluids, &fluids)
                || !unchanged(&self.catalog.sprites, &sprites)
                || faces
                    .iter()
                    .any(|(id, value)| self.catalog.faces.get(id).is_some_and(|old| old != value)))
        {
            return Err("resource definition changed without catalog invalidation".into());
        }
        for state in states.values() {
            if state.faces.iter().any(|id| {
                id.0 > 1
                    && !faces.contains_key(id)
                    && (pending.demand.reset_catalog || !self.catalog.faces.contains_key(id))
            }) {
                return Err("undefined face profile".into());
            }
            if state.fluid.kind != 0
                && !fluids.contains_key(&state.fluid.material)
                && (pending.demand.reset_catalog
                    || !self.catalog.fluids.contains_key(&state.fluid.material))
            {
                return Err("undefined fluid material".into());
            }
        }
        for fluid in fluids.values() {
            if fluid.identities.iter().any(|id| {
                *id != 0
                    && !sprites.contains_key(id)
                    && (pending.demand.reset_catalog || !self.catalog.sprites.contains_key(id))
            }) {
                return Err("undefined fluid sprite".into());
            }
        }
        // No fallible resource interpretation after taking Pending or mutating the catalog.
        let mut references = Vec::new();
        let mut textures = Vec::new();
        for (&id, sprite) in &sprites {
            textures.push((
                sprite::texture(id),
                sprite.image(pending.input.tick, scene)?,
            ));
            references.push((sprite::texture(id), sprite.reference(scene)?));
        }
        if !pending.demand.reset_catalog && self.animation_tick != Some(pending.input.tick) {
            for &id in &self.animated {
                if !sprites.contains_key(&id) {
                    textures.push((
                        sprite::texture(id),
                        self.catalog.sprites[&id].image(pending.input.tick, scene)?,
                    ));
                }
            }
        }
        if let Some(atlas) = labpbr::atlas(scene, &sprites) {
            textures.push((1, atlas));
        }
        scene.validate_textures(&textures)?;
        self.textures = textures;
        let Pending {
            input,
            demand,
            cell_budget,
        } = self.pending.take().unwrap();
        if demand.reset_catalog {
            self.catalog = Catalog::default();
            self.animated.clear();
        }
        self.animation_tick = Some(input.tick);
        let prepare = !models.is_empty() || !states.is_empty();
        let added_fluids = !fluids.is_empty();
        self.catalog.states.extend(states);
        self.catalog.faces.extend(faces);
        self.catalog.fluids.extend(fluids);
        self.catalog.glass_references.extend(references);
        for (&id, sprite) in &sprites {
            if !sprite.frames.is_empty() && !self.catalog.sprites.contains_key(&id) {
                self.animated.push(id);
            }
        }
        self.catalog.sprites.extend(sprites);
        if prepare {
            self.catalog.models.extend(models);
            self.catalog.prepare();
        } else if added_fluids {
            self.catalog.refresh_contact_capability();
        }
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
        self.stats.bytes = pages.iter().map(|p| p.len()).sum();
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
                self.workers = Some(CpuWorkers::configured()?);
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
            u64_to(&mut self.tint_requests, batch);
            u64_to(&mut self.tint_requests, unique.len() as u64);
            u64_to(&mut self.tint_requests, epoch);
            u32_to(&mut self.tint_requests, version);
            u32_to(
                &mut self.tint_requests,
                if self.chunks.biome_sources().definitions.is_none() {
                    2
                } else {
                    0
                },
            );
            for request in &unique {
                for v in request.position {
                    u32_to(&mut self.tint_requests, v as u32);
                }
                u32_to(&mut self.tint_requests, request.state);
                u32_to(&mut self.tint_requests, request.slot as u32);
            }
            self.stats.tint_bytes = self.tint_requests.len();
            self.awaiting_colors = Some(AwaitingColors {
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
    /// Session-owned read-only view; valid until the next mutable source call.
    pub fn tint_requests(&self) -> &[u8] {
        &self.tint_requests
    }

    fn accept_colors(&mut self, pages: &[&[u8]], scene: &mut SourceScene) -> Result<(), String> {
        let start = Instant::now();
        let waiting = self.awaiting_colors.as_ref().unwrap();
        let (kind, expected) = match &waiting.stage {
            ColorStage::Sources => (3, self.stats.tint_requests),
            ColorStage::Biomes(_, source) => (4, source.requests.len()),
        };
        let mut r = Reader::new(pages)?;
        if r.header(kind)? != (self.version, self.epoch, waiting.batch)
            || self.epoch != scene.epoch()
        {
            return Err("tint response identity mismatch".into());
        }
        let count = usize::try_from(r.u64()?).map_err(|_| "tint count overflow")?;
        if count != expected {
            return Err("tint response count mismatch".into());
        }
        let (pending, colors);
        if kind == 3 {
            let radius = r.i32()?;
            if !(0..=7).contains(&radius) {
                return Err("unsupported biome blend radius".into());
            }
            let mut recipes = Vec::with_capacity(count);
            let mut callbacks = 0;
            for _ in 0..count {
                let kind = r.u32()?;
                let value = r.u32()?;
                recipes.push(biome::Recipe::read(kind, value)?);
                callbacks += usize::from(kind == 0);
            }
            let definitions = match r.u32()? {
                0 => None,
                1 if self.chunks.biome_sources().definitions.is_none() => {
                    Some(biome_source::Definitions::read(&mut r)?)
                }
                _ => return Err("unexpected biome definitions".into()),
            };
            if definitions.is_none()
                && self.chunks.biome_sources().definitions.is_none()
                && recipes
                    .iter()
                    .any(|recipe| matches!(recipe, biome::Recipe::Biome { .. }))
            {
                return Err("missing biome definitions".into());
            }
            r.finish()?;
            if let Some(definitions) = definitions {
                self.chunks.biome_sources_mut().definitions = Some(definitions);
            }
            self.stats.tint_callbacks = callbacks;
            self.stats.tint_decode_ms += start.elapsed().as_secs_f64() * 1000.;
            let prepare = Instant::now();
            let recipes: Vec<_> = waiting.aliases.iter().map(|&i| recipes[i]).collect();
            let plan = self.chunks.biomes_mut().prepare(
                waiting
                    .jobs
                    .iter()
                    .flat_map(|j| j.tints.requests.iter().copied()),
                &recipes,
                radius,
            );
            self.stats.biome_plan_ms = prepare.elapsed().as_secs_f64() * 1000.;
            self.stats.biome_hits = plan.hits;
            self.stats.biome_samples = plan.samples.len();
            self.stats.biome_cached_samples = plan.cached_samples;
            if !plan.samples.is_empty() {
                let source_start = Instant::now();
                let source = self.chunks.biome_sources_mut().prepare(&plan.samples);
                self.stats.biome_source_ms = source_start.elapsed().as_secs_f64() * 1000.;
                self.stats.biome_pages = source.requests.len();
                self.stats.biome_host_cells = source
                    .requests
                    .iter()
                    .map(|r| r.mask.count_ones() as usize)
                    .sum();
                if !source.requests.is_empty() {
                    self.tint_requests.clear();
                    u64_to(&mut self.tint_requests, waiting.batch);
                    u64_to(&mut self.tint_requests, source.requests.len() as u64);
                    u64_to(&mut self.tint_requests, self.epoch);
                    u32_to(&mut self.tint_requests, self.version);
                    u32_to(&mut self.tint_requests, 3);
                    for request in &source.requests {
                        for v in [request.section.0, request.section.1, request.section.2] {
                            u32_to(&mut self.tint_requests, v as u32);
                        }
                        u64_to(&mut self.tint_requests, request.mask);
                    }
                    self.stats.tint_bytes +=
                        self.tint_requests.len() + pages.iter().map(|p| p.len()).sum::<usize>();
                    self.stats.request_batches += 1;
                    self.stats.response_batches += 1;
                    self.awaiting_colors.as_mut().unwrap().stage =
                        ColorStage::Biomes(plan, Box::new(source));
                    return Ok(());
                }
                colors = self.finish_biome_colors(plan, source, None);
            } else {
                let filter = Instant::now();
                colors = self.chunks.biomes_mut().finish(plan, &[]);
                self.stats.biome_filter_ms = filter.elapsed().as_secs_f64() * 1000.;
            }
            pending = self.awaiting_colors.take().unwrap();
        } else {
            let ColorStage::Biomes(_, source) = &waiting.stage else {
                unreachable!()
            };
            let response = self.chunks.biome_sources().read_response(source, &mut r)?;
            r.finish()?;
            self.stats.tint_decode_ms += start.elapsed().as_secs_f64() * 1000.;
            pending = self.awaiting_colors.take().unwrap();
            let ColorStage::Biomes(plan, source) = pending.stage else {
                unreachable!()
            };
            colors = self.finish_biome_colors(plan, *source, Some(response));
        }
        self.stats.tint_bytes += pages.iter().map(|p| p.len()).sum::<usize>();
        self.stats.response_batches += 1;
        self.tint_requests.clear();
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
        publication.release_retired(self.workers.as_ref())?;
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
