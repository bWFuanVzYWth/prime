//! Owns source residency, dependency invalidation and bounded cell compilation admission.
//! Future LOD policies belong at this boundary; the current unit is always a full 4x4x4 cell.
use crate::{
    SectionData, biome, biome_source,
    compile_queue::{CompileQueue, Selection},
    model::Catalog,
    schedule::{Demand, FrameInput, Scheduler, Section},
};
use std::collections::{BTreeSet, HashMap, HashSet};

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
pub(super) fn changed_boundaries(
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
            // Known air supplies a contact proof; an unavailable halo supplies none.
            (Some(_), None) | (None, Some(_)) => true,
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
pub(super) fn invalidate_neighbors(
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

#[derive(Default)]
pub(crate) struct ChunkManager {
    scheduler: Scheduler,
    sections: HashMap<Section, SectionData>,
    available: HashMap<[i32; 3], u8>,
    renderable: HashSet<[i32; 3]>,
    compile_queue: CompileQueue,
    tinted: HashSet<Section>,
    biomes: biome::Cache,
    biome_sources: biome_source::Cache,
    #[cfg(test)]
    availability_updates: usize,
}

pub(crate) struct CompileBatch {
    pub selection: Selection,
    pub removed: BTreeSet<Section>,
    pub reset_catalog: bool,
    pub changed: usize,
}

impl ChunkManager {
    pub fn plan(&mut self, input: &FrameInput) -> Demand {
        self.scheduler.plan(input)
    }

    pub fn active_len(&self) -> usize {
        self.scheduler.active.len()
    }

    pub fn is_active(&self, section: &Section) -> bool {
        self.scheduler.active.contains(section)
    }

    /// Synchronous workers borrow the owned source map only for their joined compile phase.
    pub fn sources(&self) -> &HashMap<Section, SectionData> {
        &self.sections
    }

    pub fn renderable_cells(&self) -> &HashSet<[i32; 3]> {
        &self.renderable
    }

    pub fn pending_cells(&self) -> usize {
        self.compile_queue.len()
    }

    pub fn biome_sources(&self) -> &biome_source::Cache {
        &self.biome_sources
    }

    pub fn biome_sources_mut(&mut self) -> &mut biome_source::Cache {
        &mut self.biome_sources
    }

    pub fn biomes_mut(&mut self) -> &mut biome::Cache {
        &mut self.biomes
    }

    /// All fields are already decoded and validated; no host access occurs in this phase.
    pub fn accept_sources(
        &mut self,
        input: &FrameInput,
        demand: Demand,
        received: HashMap<Section, Option<SectionData>>,
        catalog: &Catalog,
        cell_budget: usize,
    ) -> CompileBatch {
        if demand.reset_catalog {
            self.biomes = biome::Cache::default();
            self.biome_sources = biome_source::Cache::default();
        }
        #[cfg(test)]
        {
            self.availability_updates = 0;
        }
        let mut changed = 0;
        // Membership changed during planning; only previously available sections contribute.
        // Source responses below then apply their availability changes to this same batch.
        let mut availability_cells = HashSet::new();
        for &key in &demand.removed {
            if self.sections.contains_key(&key) {
                self.update_availability(key, false, &mut availability_cells);
            }
        }
        for &key in &demand.compile {
            if self.sections.contains_key(&key) {
                self.update_availability(key, true, &mut availability_cells);
            }
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
            self.compile_queue.remove(key);
            self.biomes.forget(key);
            self.biome_sources.forget(key);
        }
        for key in demand.forget {
            self.compile_queue.remove(key);
            self.biomes.forget(key);
            self.biome_sources.forget(key);
            if let Some(old) = self.sections.remove(&key) {
                invalidate_neighbors(
                    key,
                    || changed_boundaries(Some(&old), None, catalog),
                    &self.scheduler.active,
                    &mut compile,
                );
            }
        }
        let mut removed: BTreeSet<_> = demand.removed.into_iter().collect();
        if demand.reset_catalog {
            // IDs may be reused for different UV/model/optical semantics. Only sections
            // rebuilt in this publication may coexist with the replacement resources.
            removed.extend(self.sections.keys().copied());
        }
        for (key, data) in received {
            let Some(data) = data else {
                self.compile_queue.remove(key);
                if let Some(old) = self.sections.remove(&key) {
                    if self.scheduler.active.contains(&key) {
                        removed.insert(key);
                        self.update_availability(key, false, &mut availability_cells);
                    }
                    invalidate_neighbors(
                        key,
                        || changed_boundaries(Some(&old), None, catalog),
                        &self.scheduler.active,
                        &mut compile,
                    );
                }
                continue;
            };
            let previous = self.sections.get(&key);
            let newly_available = previous.is_none() && self.scheduler.active.contains(&key);
            if previous == Some(&data) && !demand.reset_catalog {
                // A host event may have changed a neighbor; preserve its independently planned compile.
            } else {
                changed += 1;
                if self.scheduler.active.contains(&key) {
                    compile.insert(key);
                }
                invalidate_neighbors(
                    key,
                    || changed_boundaries(self.sections.get(&key), Some(&data), catalog),
                    &self.scheduler.active,
                    &mut compile,
                );
            }
            if newly_available {
                self.update_availability(key, true, &mut availability_cells);
            }
            self.sections.insert(key, data);
        }
        let contacts = !availability_cells.is_empty() && catalog.has_contacts();
        for cell in availability_cells {
            let changed = if self.available.get(&cell) == Some(&64) {
                self.renderable.insert(cell)
            } else {
                self.renderable.remove(&cell)
            };
            if changed && contacts {
                // A one-cell halo can observe this ownership change only within these sections.
                for x in cell[0] * 4 - 1..=cell[0] * 4 + 4 {
                    for y in cell[1] * 4 - 1..=cell[1] * 4 + 4 {
                        for z in cell[2] * 4 - 1..=cell[2] * 4 + 4 {
                            let key = Section(x, y, z);
                            if self.scheduler.active.contains(&key) {
                                compile.insert(key);
                            }
                        }
                    }
                }
            }
        }
        compile.retain(|s| self.scheduler.active.contains(s) && self.sections.contains_key(s));
        // Keep pending work separate from membership entry/availability accounting above.
        // All source and neighbor invalidations are closed before admitting whole cells.
        for key in compile {
            self.compile_queue.insert(key);
        }
        let selection = self.compile_queue.select(cell_budget);
        for key in &selection.sections {
            removed.remove(key);
        }
        CompileBatch {
            selection,
            removed,
            reset_catalog: demand.reset_catalog,
            changed,
        }
    }

    /// Called after the SourceScene publication succeeds, never merely after worker completion.
    pub fn published(
        &mut self,
        selection: &Selection,
        removed: &BTreeSet<Section>,
        tinted: impl IntoIterator<Item = Section>,
    ) {
        for key in selection.sections.iter().chain(removed) {
            self.tinted.remove(key);
        }
        self.tinted.extend(tinted);
        self.compile_queue.complete(selection);
    }

    #[cfg(test)]
    pub fn active_sections(&self) -> &HashSet<Section> {
        &self.scheduler.active
    }

    #[cfg(test)]
    pub fn cached_sections(&self) -> &HashSet<Section> {
        &self.scheduler.cache
    }

    #[cfg(test)]
    pub fn availability(&self) -> &HashMap<[i32; 3], u8> {
        &self.available
    }

    #[cfg(test)]
    pub fn availability_updates(&self) -> usize {
        self.availability_updates
    }

    #[cfg(test)]
    pub fn pending_sections(&self) -> Vec<Section> {
        self.compile_queue.select(usize::MAX).sections
    }

    fn update_availability(
        &mut self,
        key: Section,
        present: bool,
        touched: &mut HashSet<[i32; 3]>,
    ) {
        let cell = [
            key.0.div_euclid(4),
            key.1.div_euclid(4),
            key.2.div_euclid(4),
        ];
        if present {
            *self.available.entry(cell).or_default() += 1;
        } else {
            let count = self
                .available
                .get_mut(&cell)
                .expect("available active section");
            *count -= 1;
            if *count == 0 {
                self.available.remove(&cell);
            }
        }
        touched.insert(cell);
        #[cfg(test)]
        {
            self.availability_updates += 1;
        }
    }
}
