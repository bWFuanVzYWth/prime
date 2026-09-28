//! Prototype MC 26.2/26.3 adaptation. Java forwards fields and events; this owner plans demand.
#![forbid(unsafe_code)]
mod compile;
mod fluid;
mod model;
mod shape;
use compile::compile_slab;
#[cfg(test)]
mod reference;
mod schedule;
pub mod wire;

use model::{Catalog, Hacks};
use prime_scene::{SourceScene, Triangle, compiled::CompiledSection, workers::CpuWorkers};
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
        let mut palette = Vec::with_capacity(count);
        for _ in 0..count {
            palette.push(r.u32()?);
        }
        let mut storage = Vec::with_capacity(words);
        for _ in 0..words {
            storage.push(r.u64()?);
        }
        let result = Self {
            palette,
            storage,
            bits,
            per_word,
        };
        if count != 0 && bits != 0 {
            let mask = (1u64 << bits) - 1;
            for (i, &word) in result.storage.iter().enumerate() {
                let mut packed = word;
                for _ in 0..per_word.min(4096 - i * per_word) {
                    if packed & mask >= count as u64 {
                        return Err("section palette index out of range".into());
                    }
                    packed >>= bits;
                }
            }
        }
        Ok(result)
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
    for y in 0..16 {
        for z in 0..16 {
            for x in 0..16 {
                if x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15 {
                    continue;
                }
                if !differs(y * 256 + z * 16 + x) {
                    continue;
                }
                let dx = if x == 0 {
                    -1
                } else if x == 15 {
                    1
                } else {
                    0
                };
                let dy = if y == 0 {
                    -1
                } else if y == 15 {
                    1
                } else {
                    0
                };
                let dz = if z == 0 {
                    -1
                } else if z == 15 {
                    1
                } else {
                    0
                };
                for ox in [0, dx] {
                    for oy in [0, dy] {
                        for oz in [0, dz] {
                            changed |= 1 << ((oy + 1) * 9 + (oz + 1) * 3 + ox + 1);
                        }
                    }
                }
            }
        }
    }
    changed & ALL
}
fn invalidate_neighbors(
    key: Section,
    changed: u32,
    active: &HashSet<Section>,
    compile: &mut HashSet<Section>,
) {
    for (i, n) in key.halo().into_iter().enumerate() {
        if changed & (1 << i) != 0 && active.contains(&n) {
            compile.insert(n);
        }
    }
}

struct Pending {
    input: FrameInput,
    demand: Demand,
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
        if self.pending.is_some() {
            return Err("previous section request still awaiting its single response".into());
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
        }
        self.catalog.states.extend(states);
        self.catalog.faces.extend(faces);
        self.catalog.fluids.extend(fluids);
        if !models.is_empty() {
            self.catalog.models.extend(models);
            self.catalog.prepare();
        }
        let mut compile = demand.compile;
        for key in demand.forget {
            if let Some(old) = self.sections.remove(&key) {
                invalidate_neighbors(
                    key,
                    changed_boundaries(Some(&old), None, &self.catalog),
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
                        changed_boundaries(Some(&old), None, &self.catalog),
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
                    changed_boundaries(self.sections.get(&key), Some(&data), &self.catalog),
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
        let finalize_start = Instant::now();
        if !jobs.is_empty() {
            self.workers
                .as_ref()
                .unwrap()
                .chunks_mut(&mut jobs, 4, |_, jobs| {
                    for group in jobs.chunks_mut(4) {
                        let layers = std::array::from_fn(|layer| {
                            // Reuse the first populated allocation; reserve the remainder once, in output order.
                            let mut output = Vec::new();
                            let count: usize = group.iter().map(|j| j.layers[layer].len()).sum();
                            for job in group.iter_mut() {
                                let mut source = std::mem::take(&mut job.layers[layer]);
                                if output.is_empty() && !source.is_empty() {
                                    output = source;
                                    output.reserve_exact(count - output.len());
                                } else {
                                    output.append(&mut source);
                                }
                            }
                            output
                        });
                        let first = &mut group[0];
                        first.compiled = Some(scene.prepare_compiled(
                            first.key.key(),
                            first.key.origin(),
                            layers,
                        ));
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
        self.stats.compile_ms = compile_start.elapsed().as_secs_f64() * 1000.0;
        let publish_start = Instant::now();
        let publication = scene.publish_compiled(
            epoch,
            batch,
            replacements,
            &removed.iter().map(|s| s.key()).collect::<Vec<_>>(),
        )?;
        self.stats.published_layers = publication.replaced_layers;
        self.stats.retained_layers = publication.retained_layers;
        self.stats.publish_ms = publish_start.elapsed().as_secs_f64() * 1000.0;
        self.last_batch = input.batch;
        self.stats.response_batches = 1;
        Ok(())
    }
    pub fn diagnostics(&self) -> String {
        let s = &self.stats;
        let h = s.hacks;
        format!(
            "mc_source[epoch={} batch={} plan={:.3} decode={:.3} compile={:.3} kernel={:.3} finalize={:.3} publish={:.3} published_layers={} retained_layers={} request_batches={} response_batches={} requested={} changed={} compiled={} jobs={} source_bytes={} triangles={} resident={} active={} hacks(model={},tint={},offset={},fluid={})]",
            self.epoch,
            self.pending
                .as_ref()
                .map_or(self.last_batch, |p| p.input.batch),
            s.plan_ms,
            s.decode_ms,
            s.compile_ms,
            s.kernel_ms,
            s.finalize_ms,
            s.publish_ms,
            s.published_layers,
            s.retained_layers,
            s.request_batches,
            s.response_batches,
            s.requested,
            s.changed,
            s.compiled,
            s.jobs,
            s.bytes,
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
    layers: [Vec<Triangle>; 3],
    hacks: Hacks,
    compiled: Option<CompiledSection>,
}

#[cfg(test)]
mod oracle;
#[cfg(test)]
mod perf;
#[cfg(test)]
mod tests;
