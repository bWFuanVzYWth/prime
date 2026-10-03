//! World-stable single-grid light proposals. Only changed light pages rebuild cells.
use crate::plan::Slots;
use std::{
    collections::{BTreeMap, BTreeSet},
    ops::Range,
};

pub(crate) const CELL_SIZE: f32 = 16.0;
pub(crate) const RADIUS: f32 = 24.0;
#[cfg(test)]
pub(crate) const GLOBAL_TAIL: f32 = 0.25;
pub(crate) type CellKey = [i32; 3];
const MAX_ALIAS: usize = 1 << 24;

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Light {
    /// Uniform-area centroid in page-local coordinates.
    pub center: [f32; 3],
    pub inv_area: f32,
    pub power: f32,
    /// Uniform-area second central moment; (|u|²+|v|²)/3 for a rectangle.
    /// Excludes the fixed cell-size softening term.
    pub extent: f32,
}

/// A key and world origin identify immutable light contents. Reusing them must
/// not mutate the slice; unchanged calls inspect page metadata, not every light.
#[derive(Clone, Copy)]
pub(crate) struct PageInput<'a> {
    pub key: u64,
    pub origin: [f64; 3],
    pub lights: &'a [Light],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Page {
    pub key: u64,
    pub origin: [f64; 3],
    pub first: u32,
    pub count: u32,
    pub pdf: f32,
    power: f64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub(crate) struct LightRef {
    pub page: u32,
    pub emitter: u32,
    /// Conditional probability within the page, not the global product.
    pub pmf: f32,
    pub inv_area: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub(crate) struct EmitterAlias {
    pub cut: f32,
    pub alias: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub(crate) struct Alias {
    /// Stable page slot in the world table; stable emitter ID in a local table.
    pub light: u32,
    /// Column relative to the start of this alias table.
    pub alias: u32,
    pub cut: f32,
    pub pdf: f32,
}

#[derive(Debug, Default)]
pub(crate) struct Changes {
    /// Both world alias and page metadata/PDFs must be published.
    pub world: bool,
    /// Refs and emitter_alias use the same stable contiguous ranges.
    pub emitter_ranges: Vec<Range<u32>>,
    /// Includes keys whose last light was removed; these are absent from cells.
    pub cells: Vec<CellKey>,
    pub repairs: AliasRepairs,
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct AliasRepairs {
    pub tables: u64,
    pub columns: u64,
    /// Largest table's probability mass transferred to preserve support.
    pub max_table_mass: f64,
}

impl AliasRepairs {
    pub fn add(&mut self, other: Self) {
        self.tables += other.tables;
        self.columns += other.columns;
        self.max_table_mass = self.max_table_mass.max(other.max_table_mass);
    }
}

#[derive(Default)]
pub(crate) struct LightGrid {
    pub pages: Vec<Option<Page>>,
    pub refs: Vec<LightRef>,
    pub emitter_alias: Vec<EmitterAlias>,
    pub world: Vec<Alias>,
    pub cells: BTreeMap<CellKey, Vec<Alias>>,
    by_key: BTreeMap<u64, u32>,
    free_pages: Vec<u32>,
    // Global IDs are not byte offsets. GPU addressing widens before multiplying
    // by a record stride; the default allocator reserves UINT_MAX as a sentinel.
    slots: Slots,
    lights: Vec<Light>,
}

struct Pending<'a> {
    input: PageInput<'a>,
    slot: u32,
    first: u32,
    reuse_range: bool,
    power: f64,
    aliases: Vec<EmitterAlias>,
    pdf: Vec<f32>,
}

impl LightGrid {
    pub(crate) fn page(&self, key: u64) -> Option<&Page> {
        self.by_key
            .get(&key)
            .and_then(|&slot| self.pages[slot as usize].as_ref())
    }

    /// Complete live-page set. Errors leave live tables unchanged. Allocation of
    /// new emitter IDs precedes removals, so rollback never restores freed IDs.
    pub(crate) fn update(&mut self, inputs: &[PageInput<'_>]) -> Result<Changes, String> {
        let mut trace = prime_diagnostics::scope("lg.cpu");
        trace.fail();
        trace.count("pg", inputs.len() as u64);
        let mut diff = prime_diagnostics::scope("lg.diff");
        diff.fail();
        let mut incoming = BTreeMap::new();
        for input in inputs {
            if incoming.insert(input.key, input).is_some() {
                return Err(format!("Duplicate light page key {}", input.key));
            }
        }
        let mut pending = Vec::new();
        let mut repairs = AliasRepairs::default();
        for &&input in incoming.values() {
            if let Some(old) = self.page(input.key)
                && old.origin == input.origin
            {
                if old.count as usize != input.lights.len() {
                    return Err(format!("Immutable light page {} changed length", input.key));
                }
                continue;
            }
            validate(input)?;
            let weights: Vec<_> = input.lights.iter().map(|l| f64::from(l.power)).collect();
            let (aliases, pdf) = alias_table(&weights, &mut repairs);
            pending.push(Pending {
                input,
                slot: 0,
                first: 0,
                reuse_range: false,
                power: weights.iter().sum(),
                aliases,
                pdf,
            });
        }
        let removed: Vec<_> = self
            .by_key
            .iter()
            .filter(|(key, slot)| {
                incoming
                    .get(*key)
                    .is_none_or(|input| self.pages[**slot as usize].unwrap().origin != input.origin)
            })
            .map(|(_, &slot)| slot)
            .collect();
        diff.count("add", pending.len() as u64);
        diff.count("rm", removed.len() as u64);
        diff.succeed();
        drop(diff);
        if removed.is_empty() && pending.is_empty() {
            trace.count("changed", 0);
            trace.succeed();
            return Ok(Changes::default());
        }
        trace.count("changed", 1);

        let mut world = prime_diagnostics::scope("lg.world");
        world.fail();
        // Plan page slots and the world PMF before changing any live ownership.
        let mut free_pages = self.free_pages.clone();
        for &slot in &removed {
            if !incoming.contains_key(&self.pages[slot as usize].unwrap().key) {
                free_pages.push(slot);
            }
        }
        let mut page_len = self.pages.len();
        for p in &mut pending {
            if let Some(&slot) = self.by_key.get(&p.input.key) {
                let old = self.pages[slot as usize].unwrap();
                p.slot = slot;
                p.reuse_range = old.count as usize == p.input.lights.len();
                p.first = old.first;
            } else if let Some(slot) = free_pages.pop() {
                p.slot = slot;
            } else {
                p.slot = u32::try_from(page_len).map_err(|_| "Too many light pages")?;
                page_len += 1;
            }
        }
        let removed_set: BTreeSet<_> = removed.iter().copied().collect();
        let mut roots: Vec<_> = self
            .pages
            .iter()
            .enumerate()
            .filter_map(|(slot, page)| {
                page.filter(|_| !removed_set.contains(&(slot as u32)))
                    .map(|page| (slot as u32, page.power))
            })
            .chain(pending.iter().map(|p| (p.slot, p.power)))
            .collect();
        roots.sort_unstable_by_key(|&(slot, _)| slot);
        if roots.len() > MAX_ALIAS {
            return Err("Global page alias exceeds the 24-bit column capacity".into());
        }
        world.count("pg", roots.len() as u64);
        let mut world_alias_scope = prime_diagnostics::scope("lg.world_alias");
        world_alias_scope.count("ent", roots.len() as u64);
        let (world_alias, world_pdf) = alias_table(
            &roots.iter().map(|&(_, power)| power).collect::<Vec<_>>(),
            &mut repairs,
        );
        drop(world_alias_scope);
        let live_count: u64 = self
            .pages
            .iter()
            .enumerate()
            .filter(|(slot, _)| !removed_set.contains(&(*slot as u32)))
            .filter_map(|(_, page)| page.as_ref())
            .map(|page| u64::from(page.count))
            .sum::<u64>()
            + pending
                .iter()
                .map(|p| p.input.lights.len() as u64)
                .sum::<u64>();
        // The usual scene-wide count proves this bound without another spatial
        // scan. Larger worlds validate only cells affected by this transaction.
        if live_count > MAX_ALIAS as u64 {
            let mut delta = BTreeMap::<CellKey, i64>::new();
            for &slot in &removed {
                let page = self.pages[slot as usize].unwrap();
                for light in &self.lights[page.first as usize..(page.first + page.count) as usize] {
                    for key in covering_cells(world_center(page.origin, light.center)) {
                        *delta.entry(key).or_default() -= 1;
                    }
                }
            }
            for p in &pending {
                for light in p.input.lights {
                    for key in covering_cells(world_center(p.input.origin, light.center)) {
                        *delta.entry(key).or_default() += 1;
                    }
                }
            }
            if delta.iter().any(|(key, change)| {
                self.cells.get(key).map_or(0, |e| e.len() as i64) + change > MAX_ALIAS as i64
            }) {
                return Err("Local light alias exceeds the 24-bit column capacity".into());
            }
        }
        world.count("emit", live_count);
        world.succeed();
        drop(world);
        let mut ids = prime_diagnostics::scope("lg.ids");
        ids.fail();
        ids.count("add", pending.len() as u64);
        let mut allocated = Vec::new();
        for p in &mut pending {
            if !p.reuse_range {
                let count = p.input.lights.len() as u32;
                match self.slots.allocate(count) {
                    Ok(first) => {
                        p.first = first;
                        allocated.push((first, count));
                    }
                    Err(error) => {
                        for (first, count) in allocated.into_iter().rev() {
                            self.slots.release(first, count);
                        }
                        return Err(format!("Light emitter IDs: {error}"));
                    }
                }
            }
        }

        ids.succeed();
        drop(ids);
        let mut apply = prime_diagnostics::scope("lg.apply");
        let mut dirty = BTreeSet::new();
        let mut ranges = Vec::new();
        let reused: BTreeSet<_> = pending
            .iter()
            .filter(|p| p.reuse_range)
            .map(|p| p.first)
            .collect();
        for slot in removed {
            let old = self.pages[slot as usize].take().unwrap();
            self.by_key.remove(&old.key);
            let range = old.first..old.first + old.count;
            for light in &self.lights[range.start as usize..range.end as usize] {
                dirty.extend(covering_cells(world_center(old.origin, light.center)));
            }
            self.refs[range.start as usize..range.end as usize].fill(LightRef::default());
            self.emitter_alias[range.start as usize..range.end as usize]
                .fill(EmitterAlias::default());
            if !reused.contains(&old.first) {
                self.slots.release(old.first, old.count);
            }
            ranges.push(range);
        }
        for key in &dirty {
            if let Some(entries) = self.cells.get_mut(key) {
                entries.retain(|e| self.refs[e.light as usize].pmf != 0.0);
            }
        }
        // Keep holes addressable for the dirty-range upload; no unchanged records
        // are moved when a high range is freed or subsequently reused.
        let length = self.refs.len().max(self.slots.end as usize);
        self.refs.resize(length, LightRef::default());
        self.emitter_alias.resize(length, EmitterAlias::default());
        self.lights.resize(length, Light::default());
        self.pages.resize(page_len, None);
        self.free_pages = free_pages;
        for p in pending {
            let count = p.input.lights.len() as u32;
            self.pages[p.slot as usize] = Some(Page {
                key: p.input.key,
                origin: p.input.origin,
                first: p.first,
                count,
                pdf: 0.0,
                power: p.power,
            });
            self.by_key.insert(p.input.key, p.slot);
            for (i, &light) in p.input.lights.iter().enumerate() {
                let id = p.first + i as u32;
                self.lights[id as usize] = light;
                self.refs[id as usize] = LightRef {
                    page: p.slot,
                    emitter: i as u32,
                    pmf: p.pdf[i],
                    inv_area: light.inv_area,
                };
                self.emitter_alias[id as usize] = p.aliases[i];
                for key in covering_cells(world_center(p.input.origin, light.center)) {
                    self.cells.entry(key).or_default().push(Alias {
                        light: id,
                        ..Alias::default()
                    });
                    dirty.insert(key);
                }
            }
            ranges.push(p.first..p.first + count);
        }
        self.world = roots
            .into_iter()
            .zip(world_alias)
            .zip(world_pdf)
            .map(|(((slot, _), alias), pdf)| {
                self.pages[slot as usize].as_mut().unwrap().pdf = pdf;
                Alias {
                    light: slot,
                    alias: alias.alias,
                    cut: alias.cut,
                    pdf,
                }
            })
            .collect();
        apply.count("dc", dirty.len() as u64);
        apply.count("ranges", ranges.len() as u64);
        drop(apply);
        let mut local = prime_diagnostics::scope("lg.cell_alias");
        local.count("dc", dirty.len() as u64);
        let mut alias_entries = 0_u64;
        for key in &dirty {
            let entries = self.cells.get_mut(key).unwrap();
            if entries.is_empty() {
                self.cells.remove(key);
                continue;
            }
            if local.enabled() {
                alias_entries += entries.len() as u64;
            }
            entries.sort_unstable_by_key(|e| e.light);
            let center = key.map(|v| (f64::from(v) + 0.5) * f64::from(CELL_SIZE));
            let weights: Vec<_> = entries
                .iter()
                .map(|e| {
                    let light = self.lights[e.light as usize];
                    let page = self.pages[self.refs[e.light as usize].page as usize].unwrap();
                    let position = world_center(page.origin, light.center);
                    let d2: f64 = (0..3).map(|a| (position[a] - center[a]).powi(2)).sum();
                    f64::from(light.power)
                        / d2.max(
                            1.0 + f64::from(CELL_SIZE).powi(2) * 0.25 + f64::from(light.extent),
                        )
                })
                .collect();
            let (aliases, pdf) = alias_table(&weights, &mut repairs);
            for ((entry, alias), pdf) in entries.iter_mut().zip(aliases).zip(pdf) {
                entry.alias = alias.alias;
                entry.cut = alias.cut;
                entry.pdf = pdf;
            }
        }
        local.count("ent", alias_entries);
        drop(local);
        ranges.sort_unstable_by_key(|r| r.start);
        let mut emitter_ranges: Vec<Range<u32>> = Vec::new();
        for range in ranges {
            if let Some(last) = emitter_ranges.last_mut()
                && range.start <= last.end
            {
                last.end = last.end.max(range.end);
            } else {
                emitter_ranges.push(range);
            }
        }
        trace.count("dc", dirty.len() as u64);
        trace.count("ranges", emitter_ranges.len() as u64);
        trace.succeed();
        Ok(Changes {
            world: true,
            emitter_ranges,
            cells: dirty.into_iter().collect(),
            repairs,
        })
    }
}

fn world_center(origin: [f64; 3], center: [f32; 3]) -> [f64; 3] {
    std::array::from_fn(|a| origin[a] + f64::from(center[a]))
}

fn validate(input: PageInput<'_>) -> Result<(), String> {
    if input.lights.len() > MAX_ALIAS {
        return Err(format!(
            "Light page {} exceeds the 24-bit alias column capacity",
            input.key
        ));
    }
    if input.origin.iter().any(|v| !v.is_finite()) || input.lights.is_empty() {
        return Err(format!("Invalid light page {} origin or count", input.key));
    }
    for light in input.lights {
        if light.center.iter().any(|v| !v.is_finite())
            || !light.power.is_finite()
            || light.power <= 0.0
            || !light.inv_area.is_finite()
            || light.inv_area <= 0.0
            || !light.extent.is_finite()
            || light.extent < 0.0
        {
            return Err(format!("Invalid emitter in light page {}", input.key));
        }
        for position in world_center(input.origin, light.center) {
            let lower = ((position - f64::from(RADIUS)) / f64::from(CELL_SIZE) - 0.5).ceil();
            let upper = ((position + f64::from(RADIUS)) / f64::from(CELL_SIZE) - 0.5).floor();
            if lower < f64::from(i32::MIN) || upper > f64::from(i32::MAX) {
                return Err("Light grid world cell coordinate exceeds i32".into());
            }
        }
    }
    Ok(())
}

fn covering_cells(point: [f64; 3]) -> impl Iterator<Item = CellKey> {
    let lower = point.map(|p| ((p - 24.0) / 16.0 - 0.5).ceil() as i32);
    let upper = point.map(|p| ((p + 24.0) / 16.0 - 0.5).floor() as i32);
    (lower[2]..=upper[2]).flat_map(move |z| {
        (lower[1]..=upper[1]).flat_map(move |y| {
            (lower[0]..=upper[0]).filter_map(move |x| {
                let cell = [x, y, z];
                let d2: f64 = (0..3)
                    .map(|a| ((f64::from(cell[a]) + 0.5) * 16.0 - point[a]).powi(2))
                    .sum();
                (d2 <= 24.0 * 24.0).then_some(cell)
            })
        })
    })
}

fn alias_table(weights: &[f64], repairs: &mut AliasRepairs) -> (Vec<EmitterAlias>, Vec<f32>) {
    debug_assert!(weights.len() <= MAX_ALIAS);
    let sum: f64 = weights.iter().sum();
    let mut mass: Vec<_> = weights
        .iter()
        .map(|w| w * weights.len() as f64 / sum)
        .collect();
    let mut small = Vec::new();
    let mut large = Vec::new();
    for (i, &p) in mass.iter().enumerate() {
        if p < 1.0 {
            small.push(i);
        } else {
            large.push(i);
        }
    }
    let mut table: Vec<_> = (0..weights.len())
        .map(|i| EmitterAlias {
            cut: 1.0,
            alias: i as u32,
        })
        .collect();
    while !small.is_empty() && !large.is_empty() {
        let a = small.pop().unwrap();
        let b = large.pop().unwrap();
        table[a] = EmitterAlias {
            cut: mass[a] as f32,
            alias: b as u32,
        };
        mass[b] += mass[a] - 1.0;
        if mass[b] < 1.0 {
            small.push(b);
        } else {
            large.push(b);
        }
    }
    let pdf = finalize_alias(&mut table, repairs);
    (table, pdf)
}

/// Give every column a reachable primary interval, then compute its exact
/// marginal on the 2^24 inputs. Only proposal probabilities change, not emission.
/// Keep multiply and residual as separate f32 operations, matching the shader.
fn finalize_alias(table: &mut [EmitterAlias], repairs: &mut AliasRepairs) -> Vec<f32> {
    const N: u32 = 1 << 24;
    let len = table.len();
    let value = |k: u32| (k as f32 * (1.0 / N as f32)) * len as f32;
    let lower = |mut lo: u32, mut hi: u32, threshold: f32, column: f32| {
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if value(mid) - column >= threshold {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        lo
    };
    let mut counts = vec![0u32; table.len()];
    let mut first = 0;
    let mut corrected = 0;
    let mut moved = 0;
    for (i, a) in table.iter_mut().enumerate() {
        let end = if i + 1 == len {
            N
        } else {
            lower(first, N, (i + 1) as f32, 0.0)
        };
        // With len <= 2^24 each column owns at least one input. The first
        // residual need not be zero (e.g. column 1 of a three-entry table).
        // Use a normal cutoff even at residual zero: GPUs may flush subnormals.
        debug_assert!(first < end);
        let minimum = (value(first) - i as f32).next_up().max(f32::MIN_POSITIVE);
        let old_cut = a.cut;
        a.cut = a.cut.max(minimum);
        let split = lower(first, end, a.cut, i as f32);
        if a.cut != old_cut {
            let old_cut = if old_cut < f32::MIN_POSITIVE {
                0.0
            } else {
                old_cut
            };
            moved += split - lower(first, end, old_cut, i as f32);
            corrected += 1;
        }
        counts[i] += split - first;
        counts[a.alias as usize] += end - split;
        first = end;
    }
    repairs.add(AliasRepairs {
        tables: u64::from(corrected != 0),
        columns: corrected,
        max_table_mass: f64::from(moved) / f64::from(N),
    });
    counts.into_iter().map(|n| n as f32 / N as f32).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn light(center: [f32; 3], power: f32) -> Light {
        Light {
            center,
            power,
            inv_area: 1.0,
            extent: 1.0 / 6.0,
        }
    }

    fn input(key: u64, origin: [f64; 3], lights: &[Light]) -> PageInput<'_> {
        PageInput {
            key,
            origin,
            lights,
        }
    }

    fn reverse(grid: &LightGrid, cell: CellKey, id: usize) -> f64 {
        let reference = grid.refs[id];
        let global =
            f64::from(grid.pages[reference.page as usize].unwrap().pdf) * f64::from(reference.pmf);
        let Some(local) = grid.cells.get(&cell) else {
            return global;
        };
        let p = local
            .binary_search_by_key(&(id as u32), |a| a.light)
            .ok()
            .map_or(0.0, |i| f64::from(local[i].pdf));
        f64::from(GLOBAL_TAIL) * global + f64::from(1.0 - GLOBAL_TAIL) * p
    }

    #[test]
    fn finite_alias_marginals_match_every_f32_selector() {
        assert_eq!(std::mem::size_of::<LightRef>(), 16);
        assert_eq!(std::mem::size_of::<EmitterAlias>(), 8);
        assert_eq!(std::mem::size_of::<Alias>(), 16);
        assert_eq!(std::mem::offset_of!(Alias, pdf), 12);
        let tables = [
            vec![EmitterAlias { cut: 1.0, alias: 0 }],
            vec![
                EmitterAlias { cut: 0.0, alias: 1 },
                EmitterAlias { cut: 1.0, alias: 1 },
            ],
            alias_table(&[1.0, 1e-30, 3.0], &mut AliasRepairs::default()).0,
            alias_table(&[1e-300, 1.0, 1e-40], &mut AliasRepairs::default()).0,
            alias_table(
                &[0.3, 1.0, 2.0, 4.0, 0.2, 0.8, 1e-6],
                &mut AliasRepairs::default(),
            )
            .0,
        ];
        for mut table in tables {
            let expected = finalize_alias(&mut table, &mut AliasRepairs::default());
            let mut counts = vec![0u32; table.len()];
            for k in 0..1u32 << 24 {
                let scaled = (k as f32 * (1.0 / 16777216.0)) * table.len() as f32;
                let column = (scaled as usize).min(table.len() - 1);
                let chosen = if scaled - (column as f32) < table[column].cut {
                    column
                } else {
                    table[column].alias as usize
                };
                counts[chosen] += 1;
            }
            assert!(
                counts
                    .iter()
                    .zip(&expected)
                    .all(|(&n, &p)| n as f32 / 16777216.0 == p)
            );
            assert!(counts.iter().all(|&n| n > 0));
            assert!(table.iter().all(|a| a.cut.is_normal()));
            let finalized = table.clone();
            let mut repeated = AliasRepairs::default();
            assert_eq!(finalize_alias(&mut table, &mut repeated), expected);
            assert_eq!(table, finalized);
            assert_eq!(repeated.columns, 0);
        }
    }

    #[test]
    fn forward_routes_and_reverse_mixture_have_normalized_global_support() {
        let a = [light([8.0, 8.0, 8.0], 1.0), light([14.0, 8.0, 8.0], 2.0)];
        let b = [light([-8.0, -8.0, -8.0], 3.0), light([80.0, 8.0, 8.0], 4.0)];
        let mut grid = LightGrid::default();
        grid.update(&[input(3, [0.0; 3], &a), input(7, [0.0; 3], &b)])
            .unwrap();
        for cell in [[0, 0, 0], [-1, -1, -1], [5, 0, 0], [999, 0, 0]] {
            let local = grid.cells.get(&cell);
            let tail = if local.is_some() {
                f64::from(GLOBAL_TAIL)
            } else {
                1.0
            };
            let mut forward = vec![0.0; grid.refs.len()];
            let mut world_table: Vec<_> = grid
                .world
                .iter()
                .map(|a| EmitterAlias {
                    cut: a.cut,
                    alias: a.alias,
                })
                .collect();
            for (world, pw) in grid.world.iter().zip(finalize_alias(
                &mut world_table,
                &mut AliasRepairs::default(),
            )) {
                let page = grid.pages[world.light as usize].unwrap();
                for (i, pl) in finalize_alias(
                    &mut grid.emitter_alias
                        [page.first as usize..(page.first + page.count) as usize]
                        .to_vec(),
                    &mut AliasRepairs::default(),
                )
                .into_iter()
                .enumerate()
                {
                    forward[page.first as usize + i] += tail * f64::from(pw) * f64::from(pl);
                }
            }
            if let Some(local) = local {
                assert!(local.windows(2).all(|w| w[0].light < w[1].light));
                let mut table: Vec<_> = local
                    .iter()
                    .map(|a| EmitterAlias {
                        cut: a.cut,
                        alias: a.alias,
                    })
                    .collect();
                for (a, p) in local
                    .iter()
                    .zip(finalize_alias(&mut table, &mut AliasRepairs::default()))
                {
                    forward[a.light as usize] += (1.0 - tail) * f64::from(p);
                }
            }
            assert!((forward.iter().sum::<f64>() - 1.0).abs() < 1e-12);
            for (id, &p) in forward.iter().enumerate() {
                assert!(p > 0.0);
                assert!((p - reverse(&grid, cell, id)).abs() < 1e-12);
            }
        }
    }

    #[test]
    fn incremental_changes_leave_unrelated_tables_and_stable_ids_untouched() {
        let a = [light([8.0; 3], 1.0), light([9.0; 3], 2.0)];
        let b = [light([1000.0, 8.0, 8.0], 3.0)];
        let pa = input(1, [0.0; 3], &a);
        let pb = input(2, [0.0; 3], &b);
        let mut grid = LightGrid::default();
        let first = grid.update(&[pa, pb]).unwrap();
        assert!(first.world && !first.cells.is_empty());
        let old_id = grid.page(1).unwrap().first;
        let far_id = grid.page(2).unwrap().first;
        let far_table = grid.cells[&[62, 0, 0]].clone();
        let repeated = grid.update(&[pb, pa]).unwrap();
        assert!(!repeated.world && repeated.cells.is_empty() && repeated.emitter_ranges.is_empty());
        let removed = grid.update(&[pb]).unwrap();
        assert!(!removed.cells.contains(&[62, 0, 0]));
        assert!(!grid.cells.contains_key(&[0, 0, 0]));
        assert_eq!(grid.cells[&[62, 0, 0]], far_table);
        assert_eq!(grid.page(2).unwrap().first, far_id);
        assert_eq!(grid.refs[old_id as usize].pmf, 0.0);
        grid.update(&[pb, input(9, [0.0; 3], &a)]).unwrap();
        assert_eq!(grid.page(9).unwrap().first, old_id);
        assert_eq!(grid.page(2).unwrap().first, far_id);
        assert_eq!(grid.cells[&[62, 0, 0]], far_table);
        let all_removed = grid.update(&[]).unwrap();
        assert!(all_removed.world && grid.world.is_empty() && grid.cells.is_empty());
        assert!(grid.refs.iter().all(|r| r.pmf == 0.0));
        assert!(!grid.update(&[]).unwrap().world);
    }

    #[test]
    fn diagnostics_preserve_tables_and_cover_changed_noop_and_failed_updates() {
        let a = [light([8.0; 3], 1.0), light([9.0; 3], 2.0)];
        let b = [light([1000.0, 8.0, 8.0], 3.0)];
        let pa = input(1, [0.0; 3], &a);
        let pb = input(2, [0.0; 3], &b);
        let recorder = prime_diagnostics::Recorder::new();
        let mut expected = LightGrid::default();
        expected.update(&[pa, pb]).unwrap();
        // An update without a capture context produced no records or codebook entries.
        let empty: serde_json::Value =
            serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        assert!(empty["cpu"].as_array().unwrap().is_empty());
        assert!(empty["dict"]["n"].as_array().unwrap().is_empty());
        let mut actual = LightGrid::default();
        let parent;
        {
            let _context = recorder.enter(301);
            let dispatch = prime_diagnostics::scope("test.update");
            parent = dispatch.id().unwrap();
            actual.update(&[pa, pb]).unwrap();
        }
        assert_eq!(actual.refs, expected.refs);
        assert_eq!(actual.emitter_alias, expected.emitter_alias);
        assert_eq!(actual.world, expected.world);
        assert_eq!(actual.cells, expected.cells);
        for key in [1, 2] {
            assert_eq!(
                actual.page(key).unwrap().first,
                expected.page(key).unwrap().first
            );
            assert_eq!(
                actual.page(key).unwrap().pdf.to_bits(),
                expected.page(key).unwrap().pdf.to_bits()
            );
        }
        {
            let _context = recorder.enter(302);
            assert!(!actual.update(&[pb, pa]).unwrap().world);
        }
        {
            let _context = recorder.enter(303);
            assert!(actual.update(&[pa, pa]).is_err());
        }
        assert_eq!(actual.refs, expected.refs);
        assert_eq!(actual.emitter_alias, expected.emitter_alias);
        assert_eq!(actual.world, expected.world);
        assert_eq!(actual.cells, expected.cells);
        let json: serde_json::Value =
            serde_json::from_str(&recorder.drain_json().unwrap()).unwrap();
        let names = json["dict"]["n"].as_array().unwrap();
        let keys = json["dict"]["k"].as_array().unwrap();
        let rows = json["cpu"].as_array().unwrap();
        let name =
            |row: &serde_json::Value| names[row["n"].as_u64().unwrap() as usize].as_str().unwrap();
        let field = |row: &serde_json::Value, key: &str| {
            row["a"]
                .as_array()
                .unwrap()
                .iter()
                .find_map(|pair| {
                    (keys[pair[0].as_u64().unwrap() as usize] == key)
                        .then(|| pair[1].as_u64().unwrap())
                })
                .unwrap()
        };
        let root = rows
            .iter()
            .find(|row| row["f"] == 301 && name(row) == "lg.cpu")
            .unwrap();
        assert_eq!(root["p"].as_u64(), Some(parent));
        assert_eq!(root["ok"], 1);
        assert_eq!(field(root, "pg"), 2);
        for stage in ["lg.diff", "lg.world", "lg.ids", "lg.apply", "lg.cell_alias"] {
            let row = rows
                .iter()
                .find(|row| row["f"] == 301 && name(row) == stage)
                .unwrap();
            assert_eq!(row["p"], root["i"]);
            assert_eq!(row["ok"], 1);
        }
        let local = rows
            .iter()
            .find(|row| row["f"] == 301 && name(row) == "lg.cell_alias")
            .unwrap();
        assert_eq!(
            field(local, "ent"),
            expected
                .cells
                .values()
                .map(|cell| cell.len() as u64)
                .sum::<u64>()
        );
        let noop: Vec<_> = rows.iter().filter(|row| row["f"] == 302).collect();
        assert_eq!(noop.len(), 2);
        assert!(
            noop.iter()
                .all(|row| matches!(name(row), "lg.cpu" | "lg.diff") && row["ok"] == 1)
        );
        let failed: Vec<_> = rows.iter().filter(|row| row["f"] == 303).collect();
        assert_eq!(failed.len(), 2);
        assert!(
            failed
                .iter()
                .all(|row| matches!(name(row), "lg.cpu" | "lg.diff") && row["ok"] == 0)
        );
    }

    #[test]
    fn world_cells_are_negative_safe_and_independent_of_local_coordinate_origin() {
        let mut grid = LightGrid::default();
        let a = [light([0.25, 8.0, 8.0], 1.0)];
        let origin = [-33.25, -16.0, 0.0];
        grid.update(&[input(7, origin, &a)]).unwrap();
        assert!(grid.cells.keys().any(|k| k[0] < 0 && k[1] < 0));
        let before = grid.cells.clone();
        let old_id = grid.page(7).unwrap().first;
        let shift = [4096.0, -2048.0, 128.0];
        // Re-express the same world light without changing any cell identity.
        let b = [Light {
            center: std::array::from_fn(|axis| a[0].center[axis] - shift[axis] as f32),
            ..a[0]
        }];
        grid.update(&[input(
            7,
            std::array::from_fn(|axis| origin[axis] + shift[axis]),
            &b,
        )])
        .unwrap();
        assert_eq!(grid.cells, before);
        assert_eq!(grid.page(7).unwrap().first, old_id);
        let far = input(8, [30_000_000.25, -30_000_000.0, 30_000_000.0], &a);
        grid.update(&[far]).unwrap();
        assert!(!grid.cells.is_empty());
        assert!(
            grid.cells
                .keys()
                .all(|k| k[0] > 1_000_000 && k[1] < -1_000_000)
        );
    }

    #[test]
    fn extreme_power_lights_keep_support_in_pages_world_and_local_tables() {
        for weak in 0..3 {
            let lights: Vec<_> = (0..3)
                .map(|i| {
                    light(
                        [8.0 + i as f32, 8.0, 8.0],
                        if i == weak { 1e-30 } else { 1e10 },
                    )
                })
                .collect();
            for split_pages in [false, true] {
                let inputs: Vec<_> = if split_pages {
                    lights
                        .iter()
                        .enumerate()
                        .map(|(i, light)| input(i as u64, [0.0; 3], std::slice::from_ref(light)))
                        .collect()
                } else {
                    vec![input(36, [0.0; 3], &lights)]
                };
                let mut grid = LightGrid::default();
                let changes = grid.update(&inputs).unwrap();
                assert!(changes.repairs.tables > 0 && changes.repairs.columns > 0);
                assert_eq!(changes.repairs.max_table_mass, 1.0 / 16777216.0);
                assert_eq!(grid.update(&inputs).unwrap().repairs.columns, 0);
                for cell in [[0, 0, 0], [999, 0, 0]] {
                    let probabilities: Vec<_> =
                        (0..lights.len()).map(|i| reverse(&grid, cell, i)).collect();
                    assert!(probabilities.iter().all(|&p| p > 0.0));
                    assert!((probabilities.iter().sum::<f64>() - 1.0).abs() < 1e-12);
                    assert_eq!(probabilities[weak], 1.0 / 16777216.0);
                }
            }
        }
    }

    #[test]
    fn malformed_inputs_fail_before_publishing_changes() {
        let a = [light([8.0; 3], 1.0)];
        let pa = input(1, [0.0; 3], &a);
        let mut grid = LightGrid::default();
        grid.update(&[pa]).unwrap();
        let cells = grid.cells.clone();
        let world = grid.world.clone();
        assert!(grid.update(&[pa, pa]).is_err());
        assert!(grid.update(&[input(1, [0.0; 3], &[])]).is_err());
        for power in [0.0, -1.0, f32::NAN, f32::INFINITY] {
            let bad = [light([8.0; 3], power)];
            assert!(grid.update(&[pa, input(2, [0.0; 3], &bad)]).is_err());
        }
        assert_eq!(grid.cells, cells);
        assert_eq!(grid.world, world);
        assert_eq!(grid.pages.iter().flatten().count(), 1);
        grid.slots = Slots::with_limit(2);
        grid.slots.allocate(1).unwrap(); // same existing live range, with a small test arena
        assert!(
            grid.update(&[pa, input(2, [0.0; 3], &a), input(3, [0.0; 3], &a)])
                .is_err()
        );
        assert_eq!(grid.slots.end, 1);
        assert_eq!(grid.cells, cells);
        assert_eq!(grid.world, world);
    }
}
