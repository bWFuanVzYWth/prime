//! Prototype MC 26.2/26.3 adaptation. Java forwards fields and events; this owner plans demand.
#![feature(portable_simd)]
#![forbid(unsafe_code)]
mod biome;
mod biome_source;
mod compile;
pub mod environment;
mod fluid;
mod model;
mod shape;
mod tint;
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
use schedule::{Demand, FrameInput, Scheduler, Section};
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

/// Packed masks for the section surface. Only the layouts actually seen are initialized;
/// palette IDs and section contents do not participate in this immutable layout cache.
fn boundary_word_masks(bits: u32) -> &'static [u64] {
    static MASKS: [std::sync::OnceLock<Box<[u64]>>; 33] =
        [const { std::sync::OnceLock::new() }; 33];
    MASKS[bits as usize].get_or_init(|| {
        let per_word = 64 / bits as usize;
        let field = (1_u64 << bits) - 1;
        let mut masks = vec![0; 4096_usize.div_ceil(per_word)];
        for index in 0..4096 {
            if [index & 15, index >> 8, (index >> 4) & 15]
                .iter()
                .any(|&v| v == 0 || v == 15)
            {
                masks[index / per_word] |= field << ((index % per_word) * bits as usize);
            }
        }
        masks.into_boxed_slice()
    })
}

/// Mask of the exact neighboring section regions whose one-cell dependencies changed.
fn changed_boundaries(
    before: Option<&SectionData>,
    after: Option<&SectionData>,
    catalog: &Catalog,
) -> u32 {
    let differs = |index| {
        let a = before.map(|d| d.state(index));
        let b = after.map(|d| d.state(index));
        if a == b {
            return false;
        }
        match (
            a.and_then(|id| catalog.states.get(&id)),
            b.and_then(|id| catalog.states.get(&id)),
        ) {
            (Some(a), Some(b)) => !a.same_boundary(b),
            (None, None) => false,
            (Some(s), None) | (None, Some(s)) => !s.same_boundary(&model::State::default()),
        }
    };
    const ALL: u32 = ((1 << 27) - 1) ^ (1 << 13);
    if before.is_none_or(|d| d.bits == 0) && after.is_none_or(|d| d.bits == 0) {
        return if differs(0) { ALL } else { 0 };
    }
    let mut changed = 0;
    let mut visit = |index: usize| {
        let [x, y, z] = [index & 15, index >> 8, (index >> 4) & 15];
        let side = |v| {
            if v == 0 {
                -1
            } else if v == 15 {
                1
            } else {
                0
            }
        };
        let [dx, dy, dz] = [x, y, z].map(side);
        if [dx, dy, dz] == [0; 3] || !differs(index) {
            return;
        }
        for ox in [0, dx] {
            for oy in [0, dy] {
                for oz in [0, dz] {
                    changed |= 1 << ((oy + 1) * 9 + (oz + 1) * 3 + ox + 1);
                }
            }
        }
    };
    // Identical palette/layout proves identical words have identical source semantics. Visit
    // only changed packed fields, ignoring unused high bits and final-word padding. A local
    // edit usually needs no boundary state lookup at all; layout changes use the full surface.
    if let (Some(a), Some(b)) = (before, after)
        && a.bits == b.bits
        && a.palette == b.palette
    {
        let mask = (1_u64 << a.bits) - 1;
        for (word, ((&a_word, &b_word), &surface)) in a
            .storage
            .iter()
            .zip(&b.storage)
            .zip(boundary_word_masks(a.bits))
            .enumerate()
        {
            let mut delta = (a_word ^ b_word) & surface;
            while delta != 0 {
                let lane = delta.trailing_zeros() as usize / a.bits as usize;
                let index = word * a.per_word + lane;
                visit(index);
                delta &= !(mask << (lane * a.bits as usize));
            }
        }
        return changed & ALL;
    }
    for y in 0..16 {
        for z in 0..16 {
            if y == 0 || y == 15 || z == 0 || z == 15 {
                for x in 0..16 {
                    visit(y * 256 + z * 16 + x);
                }
            } else {
                visit(y * 256 + z * 16);
                visit(y * 256 + z * 16 + 15);
            }
        }
    }
    changed & ALL
}
fn invalidate_neighbors(
    key: Section,
    changed: impl FnOnce() -> u32,
    active: &HashSet<Section>,
    compile: &mut HashSet<Section>,
) {
    let neighbors = key.halo();
    // The closed batch compiles every queued consumer after all source updates. No boundary
    // query can add work when every active neighbor is already queued (notably first load).
    if !neighbors
        .iter()
        .any(|n| *n != key && active.contains(n) && !compile.contains(n))
    {
        return;
    }
    let changed = changed();
    for (i, n) in neighbors.into_iter().enumerate() {
        if changed & (1 << i) != 0 && active.contains(&n) {
            compile.insert(n);
        }
    }
}

struct Pending {
    input: FrameInput,
    demand: Demand,
}
enum ColorStage {
    Sources,
    Biomes(biome::Plan, Box<biome_source::Plan>),
}
struct AwaitingColors {
    stage: ColorStage,
    batch: u64,
    jobs: Vec<Job>,
    ordered: Vec<Section>,
    removed: BTreeSet<Section>,
}
#[derive(Default)]
pub struct TerrainContext {
    epoch: u64,
    version: u32,
    last_batch: u64,
    scheduler: Scheduler,
    catalog: Catalog,
    sections: HashMap<Section, SectionData>,
    pending: Option<Pending>,
    awaiting_colors: Option<AwaitingColors>,
    tinted: HashSet<Section>,
    biomes: biome::Cache,
    biome_sources: biome_source::Cache,
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
    jobs: usize,
    bytes: usize,
    triangles: usize,
    hacks: Hacks,
}
impl TerrainContext {
    pub fn plan(&mut self, pages: &[&[u8]], source_epoch: u64) -> Result<&[u8], String> {
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
        let demand = self.scheduler.plan(&input);
        self.requests.clear();
        u64_to(&mut self.requests, input.batch);
        u64_to(&mut self.requests, demand.requests.len() as u64);
        u64_to(&mut self.requests, demand.columns.len() as u64);
        u64_to(&mut self.requests, self.scheduler.active.len() as u64);
        for &s in &demand.requests {
            for v in [s.0, s.1, s.2] {
                u32_to(&mut self.requests, v as u32);
            }
            u32_to(
                &mut self.requests,
                u32::from(self.scheduler.active.contains(&s)),
            );
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
        self.pending = Some(Pending { input, demand });
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
                _ => return Err("unknown section response record".into()),
            }
        }
        r.finish()?;
        if received.len() != requested.len() {
            return Err("incomplete section response".into());
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
        let Pending { input, demand } = self.pending.take().unwrap();
        if demand.reset_catalog {
            self.catalog = Catalog::default();
            self.biomes = biome::Cache::default();
            self.biome_sources = biome_source::Cache::default();
        }
        self.catalog.states.extend(states);
        self.catalog.faces.extend(faces);
        self.catalog.fluids.extend(fluids);
        if !models.is_empty() {
            self.catalog.models.extend(models);
            self.catalog.prepare();
        }
        let mut compile = demand.compile;
        // Merge invalidations before filtering consumers. A burst of chunk arrivals must
        // not scan every tinted section once for every arriving column.
        let mut tint_all = false;
        let mut tint_columns = HashSet::new();
        let mut biome_columns = HashSet::new();
        for &(kind, key) in &input.events {
            if kind == 7 {
                tint_all = true;
            }
            // Unloading a column also changes getBiome's missing-column fallback.
            if kind == 6 || kind == 2 {
                biome_columns.insert((key.0, key.2));
                for x in key.0 - 1..=key.0 + 1 {
                    for z in key.2 - 1..=key.2 + 1 {
                        tint_columns.insert((x, z));
                    }
                }
            }
        }
        if tint_all || !tint_columns.is_empty() {
            compile.extend(
                self.tinted
                    .iter()
                    .copied()
                    .filter(|s| tint_all || tint_columns.contains(&(s.0, s.2))),
            );
        }
        self.biomes.invalidate(tint_all, &biome_columns);
        self.biome_sources.invalidate(tint_all, &biome_columns);
        for &key in &demand.removed {
            self.biomes.forget(key);
            self.biome_sources.forget(key);
        }
        for key in demand.forget {
            self.biomes.forget(key);
            self.biome_sources.forget(key);
            if let Some(old) = self.sections.remove(&key) {
                invalidate_neighbors(
                    key,
                    || changed_boundaries(Some(&old), None, &self.catalog),
                    &self.scheduler.active,
                    &mut compile,
                );
            }
        }
        let mut removed: BTreeSet<_> = demand.removed.into_iter().collect();
        for (key, data) in received {
            let Some(data) = data else {
                if let Some(old) = self.sections.remove(&key) {
                    if self.scheduler.active.contains(&key) {
                        removed.insert(key);
                    }
                    invalidate_neighbors(
                        key,
                        || changed_boundaries(Some(&old), None, &self.catalog),
                        &self.scheduler.active,
                        &mut compile,
                    );
                }
                continue;
            };
            if self.sections.get(&key) == Some(&data) && !demand.reset_catalog {
                // A host event may have changed a neighbor; preserve its independently planned compile.
            } else {
                self.stats.changed += 1;
                if self.scheduler.active.contains(&key) {
                    compile.insert(key);
                }
                invalidate_neighbors(
                    key,
                    || changed_boundaries(self.sections.get(&key), Some(&data), &self.catalog),
                    &self.scheduler.active,
                    &mut compile,
                );
            }
            self.sections.insert(key, data);
        }
        compile.retain(|s| self.scheduler.active.contains(s) && self.sections.contains_key(s));
        removed.retain(|s| !compile.contains(s));
        self.stats.decode_ms = start.elapsed().as_secs_f64() * 1000.0;
        self.stats.bytes = pages.iter().map(|p| p.len()).sum();
        let compile_start = Instant::now();
        let mut ordered: Vec<_> = compile.into_iter().collect();
        ordered.sort_unstable();
        // Four equal-height slabs share costly sections. Both phases join on the same private pool.
        let mut jobs = Vec::new();
        for &key in &ordered {
            if !self.sections[&key].empty(&self.catalog) {
                for first_y in [0, 4, 8, 12] {
                    jobs.push(Job {
                        key,
                        first_y,
                        layers: Default::default(),
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
            let sections = &self.sections;
            self.workers
                .as_ref()
                .unwrap()
                .chunks_mut(&mut jobs, 1, |_, output| {
                    for job in output {
                        compile_slab(job, catalog, sections);
                    }
                    Ok(())
                })?;
        }
        self.stats.kernel_ms = kernel_start.elapsed().as_secs_f64() * 1000.;
        self.stats.jobs = jobs.len();
        self.stats.compiled = ordered.len();
        self.stats.compile_ms = compile_start.elapsed().as_secs_f64() * 1000.;
        let tint_start = Instant::now();
        let mut count = 0usize;
        for job in &mut jobs {
            job.color_start = count;
            count = count
                .checked_add(job.tints.requests.len())
                .ok_or("tint count overflow")?;
        }
        self.stats.tint_requests = count;
        self.stats.response_batches = 1;
        if count != 0 {
            self.stats.request_batches += 1;
            u64_to(&mut self.tint_requests, batch);
            u64_to(&mut self.tint_requests, count as u64);
            u64_to(&mut self.tint_requests, epoch);
            u32_to(&mut self.tint_requests, version);
            u32_to(
                &mut self.tint_requests,
                if self.biome_sources.definitions.is_none() {
                    2
                } else {
                    0
                },
            );
            for request in jobs.iter().flat_map(|j| &j.tints.requests) {
                for v in request.position {
                    u32_to(&mut self.tint_requests, v as u32);
                }
                u32_to(&mut self.tint_requests, request.state);
                u32_to(&mut self.tint_requests, request.slot as u32);
            }
            self.stats.tint_bytes = self.tint_requests.len();
            self.awaiting_colors = Some(AwaitingColors {
                stage: ColorStage::Sources,
                batch,
                jobs,
                ordered,
                removed,
            });
            self.stats.tint_pack_ms = tint_start.elapsed().as_secs_f64() * 1000.;
            return Ok(());
        }
        self.finalize(jobs, ordered, removed, batch, scene, None)
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
                1 if self.biome_sources.definitions.is_none() => {
                    Some(biome_source::Definitions::read(&mut r)?)
                }
                _ => return Err("unexpected biome definitions".into()),
            };
            if definitions.is_none()
                && self.biome_sources.definitions.is_none()
                && recipes
                    .iter()
                    .any(|recipe| matches!(recipe, biome::Recipe::Biome { .. }))
            {
                return Err("missing biome definitions".into());
            }
            r.finish()?;
            if let Some(definitions) = definitions {
                self.biome_sources.definitions = Some(definitions);
            }
            self.stats.tint_callbacks = callbacks;
            self.stats.tint_decode_ms += start.elapsed().as_secs_f64() * 1000.;
            let prepare = Instant::now();
            let plan = self.biomes.prepare(
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
                let source = self.biome_sources.prepare(&plan.samples);
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
                colors = self.biomes.finish(plan, &[]);
                self.stats.biome_filter_ms = filter.elapsed().as_secs_f64() * 1000.;
            }
            pending = self.awaiting_colors.take().unwrap();
        } else {
            let ColorStage::Biomes(_, source) = &waiting.stage else {
                unreachable!()
            };
            let response = self.biome_sources.read_response(source, &mut r)?;
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
            pending.ordered,
            pending.removed,
            pending.batch,
            scene,
            Some(&colors),
        )
    }
    fn finish_biome_colors(
        &mut self,
        plan: biome::Plan,
        source: biome_source::Plan,
        response: Option<biome_source::Response>,
    ) -> Vec<u32> {
        let start = Instant::now();
        let raw = self
            .biome_sources
            .finish(source, response, &plan.samples, self.version);
        self.stats.biome_source_ms += start.elapsed().as_secs_f64() * 1000.;
        let start = Instant::now();
        let colors = self.biomes.finish(plan, &raw);
        self.stats.biome_filter_ms = start.elapsed().as_secs_f64() * 1000.;
        colors
    }
    #[allow(clippy::too_many_arguments)]
    fn finalize(
        &mut self,
        mut jobs: Vec<Job>,
        ordered: Vec<Section>,
        removed: BTreeSet<Section>,
        batch: u64,
        scene: &mut SourceScene,
        colors: Option<&[u32]>,
    ) -> Result<(), String> {
        let finalize_start = Instant::now();
        for key in ordered.iter().chain(&removed) {
            self.tinted.remove(key);
        }
        self.tinted.extend(
            jobs.iter()
                .filter(|j| !j.tints.requests.is_empty())
                .map(|j| j.key),
        );
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
                                );
                            }
                        }
                        let parts: [_; 4] =
                            std::array::from_fn(|i| std::mem::take(&mut group[i].layers));
                        let compiled = scene.prepare_compiled_quad_fragments(
                            group[0].key.key(),
                            group[0].key.origin(),
                            parts,
                        );
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
        let mut replacements = Vec::with_capacity(ordered.len());
        for key in ordered {
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
        let mut publication = scene.publish_compiled(
            self.epoch,
            batch,
            replacements,
            &removed.iter().map(|s| s.key()).collect::<Vec<_>>(),
        )?;
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
            "mc_source[epoch={} batch={} plan={:.3} decode={:.3} compile={:.3} kernel={:.3} finalize={:.3} publish={:.3} retire={:.3} published_layers={} retained_layers={} request_batches={} response_batches={} requested={} changed={} compiled={} jobs={} source_bytes={} tint_requests={} tint_callbacks={} tint_bytes={} tint_pack={:.3} tint_decode={:.3} biome_samples={} biome_cached_samples={} biome_hits={} biome_plan={:.3} biome_filter={:.3} biome_source={:.3} biome_host_cells={} biome_pages={} triangles={} resident={} active={} hacks(model={},tint={},offset={},fluid={})]",
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
            self.sections.len(),
            self.scheduler.active.len(),
            h.model,
            h.tint,
            h.offset,
            h.fluid
        )
    }
}
struct Job {
    key: Section,
    first_y: usize,
    layers: [Vec<CompiledQuad>; 3],
    hacks: Hacks,
    compiled: Option<CompiledSection>,
    tints: tint::Deferred,
    color_start: usize,
}

#[cfg(test)]
mod oracle;
#[cfg(test)]
mod perf;
#[cfg(test)]
mod tests;
