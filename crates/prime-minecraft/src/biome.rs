//! Exact vanilla box filtering. The host supplies actual zoomed biome resolver samples;
//! this module owns deduplication, integer sums and dependency-scoped cached outputs.
use crate::{schedule::Section, tint::Request};
use std::collections::{BTreeMap, HashMap, HashSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u32)]
pub(crate) enum Resolver {
    Grass = 1,
    Foliage = 2,
    DryFoliage = 3,
    Water = 4,
}
#[derive(Clone, Copy)]
pub(crate) enum Recipe {
    Color(u32),
    Biome { resolver: Resolver, below: bool },
}
impl Recipe {
    pub fn read(kind: u32, value: u32) -> Result<Self, String> {
        use Resolver::*;
        match (kind, value) {
            (0, color) => Ok(Self::Color(color)),
            (1, 0) => Ok(Self::Biome {
                resolver: Grass,
                below: false,
            }),
            (2, 0) => Ok(Self::Biome {
                resolver: Foliage,
                below: false,
            }),
            (3, 0) => Ok(Self::Biome {
                resolver: DryFoliage,
                below: false,
            }),
            (4, 0) => Ok(Self::Biome {
                resolver: Water,
                below: false,
            }),
            (5, 0..=1) => Ok(Self::Biome {
                resolver: Grass,
                below: value == 1,
            }),
            _ => Err("invalid tint source recipe".into()),
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct Sample {
    pub position: [i32; 3],
    pub resolver: Resolver,
}
impl Sample {
    fn key(self) -> (Section, u16) {
        let [x, y, z] = self.position;
        (
            Section(x >> 4, y >> 4, z >> 4),
            (((self.resolver as u32) << 12)
                | ((y & 15) << 8) as u32
                | ((z & 15) << 4) as u32
                | (x & 15) as u32) as u16,
        )
    }
}
#[derive(Default)]
pub(crate) struct Cache {
    radius: Option<i32>,
    sections: HashMap<Section, HashMap<u16, u32>>,
    sources: HashMap<Section, Box<SourcePage>>,
}

struct SourcePage {
    // Allocate only sampled resolver/Y planes, rather than all 4 * 4096 colors.
    planes: [Option<Box<SourcePlane>>; 64],
}
impl Default for SourcePage {
    fn default() -> Self {
        Self {
            planes: std::array::from_fn(|_| None),
        }
    }
}
struct SourcePlane {
    colors: [u32; 256],
    valid: [u64; 4],
}
impl Default for SourcePlane {
    fn default() -> Self {
        Self {
            colors: [0; 256],
            valid: [0; 4],
        }
    }
}
type SourceKey = (Resolver, i32, i32, i32);
struct SourceIndices {
    indices: [usize; 256],
    missing: [u64; 4],
}
fn plane_index(resolver: Resolver, y: i32) -> usize {
    (resolver as usize - 1) * 16 + (y & 15) as usize
}

/// Both supported MC BiomeManagers choose one of the eight quart corners at
/// (block - 2) >> 2 and +1. A changed column affects samples in [-2, 17], then
/// the exact box filter expands that horizontal dependency by its blend radius.
fn dependency_masks(columns: &HashSet<(i32, i32)>, margin: i32) -> HashMap<(i32, i32), [u64; 4]> {
    let mut masks = HashMap::<_, [u64; 4]>::new();
    for &(x, z) in columns {
        for dz in -1..=1 {
            for dx in -1..=1 {
                let x0 = (-dx * 16 - margin).max(0);
                let x1 = (-dx * 16 + 15 + margin).min(15);
                let z0 = (-dz * 16 - margin).max(0);
                let z1 = (-dz * 16 + 15 + margin).min(15);
                let mask = masks.entry((x + dx, z + dz)).or_default();
                let row = ((1u64 << (x1 - x0 + 1)) - 1) << x0;
                for local_z in z0..=z1 {
                    mask[local_z as usize / 4] |= row << ((local_z & 3) * 16);
                }
            }
        }
    }
    masks
}
impl Cache {
    pub fn invalidate(&mut self, all: bool, columns: &HashSet<(i32, i32)>) {
        if all {
            self.sections.clear();
            self.sources.clear();
        } else if !columns.is_empty() {
            let mixed = dependency_masks(columns, self.radius.unwrap_or(0) + 2);
            self.sections.retain(|s, colors| {
                if let Some(mask) = mixed.get(&(s.0, s.2)) {
                    colors.retain(|&local, _| {
                        let xz = (local & 255) as usize;
                        mask[xz / 64] & (1 << (xz & 63)) == 0
                    });
                }
                !colors.is_empty()
            });
            let raw = dependency_masks(columns, 2);
            self.sources.retain(|s, page| {
                if let Some(mask) = raw.get(&(s.0, s.2)) {
                    for plane in &mut page.planes {
                        if let Some(p) = plane {
                            for (valid, &remove) in p.valid.iter_mut().zip(mask) {
                                *valid &= !remove;
                            }
                            if p.valid == [0; 4] {
                                *plane = None;
                            }
                        }
                    }
                }
                page.planes.iter().any(Option::is_some)
            });
        }
    }
    pub fn forget(&mut self, section: Section) {
        self.sections.remove(&section);
        // An upper double-height plant at local Y=0 queries the section below.
        // Retire that dependency too, including a query below the world's section range.
        self.sections
            .remove(&Section(section.0, section.1 - 1, section.2));
        // Raw samples include the horizontal filter halo and the upper-plant query
        // below the consumer. Retiring this superset bounds storage to live consumers;
        // shared neighboring pages may be recomputed, but never survive all owners.
        for y in section.1 - 1..=section.1 {
            for z in section.2 - 1..=section.2 + 1 {
                for x in section.0 - 1..=section.0 + 1 {
                    self.sources.remove(&Section(x, y, z));
                }
            }
        }
    }
    pub fn prepare(
        &mut self,
        requests: impl Iterator<Item = Request>,
        recipes: &[Recipe],
        radius: i32,
    ) -> Plan {
        if self.radius != Some(radius) {
            self.sections.clear();
            self.radius = Some(radius);
        }
        let mut tiles: BTreeMap<(Resolver, i32, i32, i32), Tile> = BTreeMap::new();
        let mut colors = Vec::with_capacity(recipes.len());
        let mut hits = 0;
        for (index, (request, &recipe)) in requests.zip(recipes).enumerate() {
            let (resolver, below) = match recipe {
                Recipe::Color(color) => {
                    colors.push(color);
                    continue;
                }
                Recipe::Biome { resolver, below } => (resolver, below),
            };
            let mut query = Sample {
                position: request.position,
                resolver,
            };
            query.position[1] -= i32::from(below);
            let (section, local) = query.key();
            if let Some(&value) = self.sections.get(&section).and_then(|s| s.get(&local)) {
                colors.push(value);
                hits += 1;
                continue;
            }
            colors.push(0);
            let [x, y, z] = query.position;
            let tile = tiles
                .entry((query.resolver, y, x >> 4, z >> 4))
                .or_insert_with(|| Tile {
                    min: [x, z],
                    max: [x, z],
                    wanted: Vec::new(),
                    samples: Vec::new(),
                    width: 0,
                });
            for (i, v) in [x, z].into_iter().enumerate() {
                tile.min[i] = tile.min[i].min(v);
                tile.max[i] = tile.max[i].max(v);
            }
            tile.wanted.push((index, query));
        }
        // Index a whole source row fragment at once, rather than hashing every sample.
        // The value is separately allocated so growing the map does not move 256 indices.
        let mut unique: HashMap<SourceKey, Box<SourceIndices>> = HashMap::new();
        let mut samples = Vec::new();
        let mut values = Vec::new();
        let mut missing_slots = Vec::new();
        let mut cached_samples = 0;
        let mut output = Vec::with_capacity(tiles.len());
        for ((resolver, y, _, _), mut tile) in tiles {
            tile.min = tile.min.map(|p| p - radius);
            tile.max = tile.max.map(|p| p + radius);
            tile.width = (tile.max[0] - tile.min[0] + 1) as usize;
            let height = (tile.max[1] - tile.min[1] + 1) as usize;
            // Queries occupy one 16x16 tile; the validated maximum radius is 7.
            // Keep exactly the union of requested squares, including holes in sparse tiles.
            let mut rows = [0u32; 30];
            let side = (radius * 2 + 1) as usize;
            for (_, query) in &tile.wanted {
                let x = (query.position[0] - radius - tile.min[0]) as usize;
                let z = (query.position[2] - radius - tile.min[1]) as usize;
                let mask = ((1u32 << side) - 1) << x;
                for row in &mut rows[z..z + side] {
                    *row |= mask;
                }
            }
            tile.samples.resize(tile.width * height, usize::MAX);
            for (row, &mask) in rows[..height].iter().enumerate() {
                let z = tile.min[1] + row as i32;
                let mut pending = mask;
                while pending != 0 {
                    let x = tile.min[0] + pending.trailing_zeros() as i32;
                    let end = ((x | 15) - tile.min[0] + 1).min(tile.width as i32);
                    let mut fragment = pending & ((1u32 << end) - 1);
                    pending &= !fragment;
                    let indices =
                        unique
                            .entry((resolver, y, x >> 4, z >> 4))
                            .or_insert_with(|| {
                                Box::new(SourceIndices {
                                    indices: [usize::MAX; 256],
                                    missing: [0; 4],
                                })
                            });
                    let cached = self
                        .sources
                        .get(&Section(x >> 4, y >> 4, z >> 4))
                        .and_then(|page| page.planes[plane_index(resolver, y)].as_ref());
                    while fragment != 0 {
                        let column = fragment.trailing_zeros() as usize;
                        fragment &= fragment - 1;
                        let x = tile.min[0] + column as i32;
                        let local = ((z & 15) * 16 + (x & 15)) as usize;
                        let index = &mut indices.indices[local];
                        if *index == usize::MAX {
                            *index = samples.len() + cached_samples;
                            if let Some(plane) =
                                cached.filter(|p| p.valid[local / 64] & (1 << (local & 63)) != 0)
                            {
                                // A cold batch reads the validated response directly. Allocate a
                                // merged array only when a real cross-batch sample hit requires it.
                                if cached_samples == 0 {
                                    values.resize(samples.len(), 0);
                                    missing_slots.extend(0..samples.len());
                                }
                                values.push(plane.colors[local]);
                                cached_samples += 1;
                            } else {
                                indices.missing[local / 64] |= 1 << (local & 63);
                                if cached_samples != 0 {
                                    missing_slots.push(*index);
                                    values.push(0);
                                }
                                samples.push(Sample {
                                    position: [x, y, z],
                                    resolver,
                                });
                            }
                        }
                        tile.samples[row * tile.width + column] = *index;
                    }
                }
            }
            output.push(tile);
        }
        Plan {
            samples,
            colors,
            tiles: output,
            radius,
            hits,
            cached_samples,
            values,
            missing_slots,
            unique,
        }
    }
    pub fn finish(&mut self, mut plan: Plan, samples: &[u32]) -> Vec<u32> {
        let values = if plan.cached_samples == 0 {
            samples
        } else {
            for (&slot, &value) in plan.missing_slots.iter().zip(samples) {
                plan.values[slot] = value;
            }
            &plan.values
        };
        // Publish only fully validated host responses, one map lookup per source plane.
        for ((resolver, y, x, z), indices) in plan.unique {
            if indices.missing == [0; 4] {
                continue;
            }
            let page = self.sources.entry(Section(x, y >> 4, z)).or_default();
            let plane = page.planes[plane_index(resolver, y)].get_or_insert_with(Box::default);
            for (word, &mask) in indices.missing.iter().enumerate() {
                plane.valid[word] |= mask;
                let mut pending = mask;
                while pending != 0 {
                    let local = word * 64 + pending.trailing_zeros() as usize;
                    pending &= pending - 1;
                    plane.colors[local] = values[indices.indices[local]];
                }
            }
        }
        let mut prefix = Vec::<[u32; 3]>::new();
        for tile in plan.tiles {
            let stride = tile.width + 1;
            let height = tile.samples.len() / tile.width;
            prefix.clear();
            prefix.resize(
                if plan.radius == 0 {
                    0
                } else {
                    stride * (height + 1)
                },
                [0; 3],
            );
            for y in 0..if plan.radius == 0 { 0 } else { height } {
                let mut row = [0; 3];
                for x in 0..tile.width {
                    let index = tile.samples[y * tile.width + x];
                    // Holes lie outside every requested filter square. Their zero contribution
                    // cancels in the integral image without querying a fictitious source value.
                    let color = if index == usize::MAX {
                        0
                    } else {
                        values[index]
                    };
                    for (c, shift) in [16, 8, 0].into_iter().enumerate() {
                        row[c] += (color >> shift) & 255;
                        prefix[(y + 1) * stride + x + 1][c] =
                            prefix[y * stride + x + 1][c] + row[c];
                    }
                }
            }
            let side = (plan.radius * 2 + 1) as usize;
            let count = (side * side) as u32;
            for (index, query) in tile.wanted {
                let x = (query.position[0] - plan.radius - tile.min[0]) as usize;
                let z = (query.position[2] - plan.radius - tile.min[1]) as usize;
                let color = if plan.radius == 0 {
                    // With blending disabled MC returns the resolver's entire ARGB unchanged.
                    values[tile.samples[z * tile.width + x]]
                } else {
                    let mut color = 0xff00_0000;
                    for (c, shift) in [16, 8, 0].into_iter().enumerate() {
                        let sum = prefix[(z + side) * stride + x + side][c]
                            + prefix[z * stride + x][c]
                            - prefix[(z + side) * stride + x][c]
                            - prefix[z * stride + x + side][c];
                        color |= (sum / count) << shift;
                    }
                    color
                };
                let (section, local) = query.key();
                self.sections
                    .entry(section)
                    .or_default()
                    .insert(local, color);
                plan.colors[index] = color;
            }
        }
        plan.colors
    }
}
struct Tile {
    min: [i32; 2],
    max: [i32; 2],
    width: usize,
    wanted: Vec<(usize, Sample)>,
    samples: Vec<usize>,
}
pub(crate) struct Plan {
    pub samples: Vec<Sample>,
    pub colors: Vec<u32>,
    tiles: Vec<Tile>,
    radius: i32,
    pub hits: usize,
    pub cached_samples: usize,
    values: Vec<u32>,
    missing_slots: Vec<usize>,
    unique: HashMap<SourceKey, Box<SourceIndices>>,
}

#[cfg(test)]
mod tests {
    use super::*;
    fn color(q: Sample) -> u32 {
        let [x, y, z] = q.position;
        (x.wrapping_mul(741103597) ^ y.wrapping_mul(341873128) ^ z.wrapping_mul(132897987))
            .cast_unsigned()
            ^ q.resolver as u32
    }
    #[test]
    fn raw_samples_survive_blend_changes_but_not_global_invalidation_or_retirement() {
        let request = Request {
            position: [-1, 0, -1],
            state: 0,
            slot: 0,
        };
        for resolver in [
            Resolver::Grass,
            Resolver::Foliage,
            Resolver::DryFoliage,
            Resolver::Water,
        ] {
            let recipes = [Recipe::Biome {
                resolver,
                below: true,
            }];
            let mut cache = Cache::default();
            let plan = cache.prepare(std::iter::once(request), &recipes, 7);
            // Merely planning or abandoning a response must not populate the cache.
            assert_eq!(
                cache.prepare(std::iter::once(request), &recipes, 7).samples,
                plan.samples
            );
            let values: Vec<_> = plan.samples.iter().copied().map(color).collect();
            cache.finish(plan, &values);
            for radius in (0..7).rev() {
                let plan = cache.prepare(std::iter::once(request), &recipes, radius);
                assert_eq!(plan.hits, 0);
                assert!(plan.samples.is_empty());
                assert_eq!(plan.cached_samples, (2 * radius + 1).pow(2) as usize);
                let mut cold = Cache::default();
                let reference = cold.prepare(std::iter::once(request), &recipes, radius);
                let values: Vec<_> = reference.samples.iter().copied().map(color).collect();
                assert_eq!(cache.finish(plan, &[]), cold.finish(reference, &values));
            }
            // An actual callback result at the same position must never reuse a biome color.
            for value in [0x8070903f, 0x12345678] {
                let plan = cache.prepare(std::iter::once(request), &[Recipe::Color(value)], 0);
                assert_eq!(cache.finish(plan, &[]), [value]);
            }
            cache.forget(Section(-1, 0, -1));
            assert!(cache.sources.is_empty());
            let plan = cache.prepare(std::iter::once(request), &recipes, 7);
            assert_eq!(plan.samples.len(), 225);
            let values: Vec<_> = plan.samples.iter().copied().map(color).collect();
            cache.finish(plan, &values);
            cache.invalidate(true, &HashSet::new());
            let plan = cache.prepare(std::iter::once(request), &recipes, 7);
            assert_eq!(plan.samples.len(), 225);
        }
    }

    #[test]
    fn column_changes_preserve_unaffected_samples_and_match_a_cold_rebuild() {
        let mut requests = Vec::new();
        let mut recipes = Vec::new();
        for resolver in [
            Resolver::Grass,
            Resolver::Foliage,
            Resolver::DryFoliage,
            Resolver::Water,
        ] {
            for y in [-17, 17] {
                for z in (-26..=41).step_by(3) {
                    for x in (-26..=41).step_by(3) {
                        requests.push(Request {
                            position: [x, y, z],
                            state: 0,
                            slot: 0,
                        });
                        recipes.push(Recipe::Biome {
                            resolver,
                            below: y < 0,
                        });
                    }
                }
            }
        }
        // Worst-case zoom dependence: every sample that could read the changed quart
        // column changes. Check positive/negative columns, edges/corners and all radii.
        for column in [(-1, -1), (0, 0), (1, 1)] {
            let affected = |q: Sample| {
                (column.0 * 16 - 2..=column.0 * 16 + 17).contains(&q.position[0])
                    && (column.1 * 16 - 2..=column.1 * 16 + 17).contains(&q.position[2])
            };
            let updated = |q| color(q) ^ if affected(q) { 0x7fffffff } else { 0 };
            for radius in 0..=7 {
                let mut cache = Cache::default();
                let plan = cache.prepare(requests.iter().copied(), &recipes, radius);
                let expected_misses = plan.samples.iter().filter(|&&q| affected(q)).count();
                let values: Vec<_> = plan.samples.iter().copied().map(color).collect();
                cache.finish(plan, &values);
                cache.invalidate(false, &HashSet::from([column]));
                let plan = cache.prepare(requests.iter().copied(), &recipes, radius);
                assert!(plan.hits > 0);
                assert!(radius == 0 || plan.cached_samples > 0);
                assert_eq!(plan.samples.len(), expected_misses);
                assert!(plan.samples.iter().copied().all(affected));
                let values: Vec<_> = plan.samples.iter().copied().map(updated).collect();
                let actual = cache.finish(plan, &values);
                let mut cold = Cache::default();
                let reference = cold.prepare(requests.iter().copied(), &recipes, radius);
                let values: Vec<_> = reference.samples.iter().copied().map(updated).collect();
                assert_eq!(actual, cold.finish(reference, &values));
            }
        }
    }
    #[test]
    fn tiled_prefix_sums_match_scalar_integers_and_cache_dependencies() {
        let requests: Vec<_> = (-17..=17)
            .flat_map(|x| {
                (-17..=17).map(move |z| Request {
                    position: [x, -1, z],
                    state: 0,
                    slot: 0,
                })
            })
            .collect();
        for radius in 0..=7 {
            let recipes = vec![
                Recipe::Biome {
                    resolver: Resolver::Grass,
                    below: false
                };
                requests.len()
            ];
            let mut cache = Cache::default();
            let plan = cache.prepare(requests.iter().copied(), &recipes, radius);
            let unique: HashSet<_> = plan.samples.iter().collect();
            assert_eq!(unique.len(), plan.samples.len());
            let samples: Vec<_> = plan.samples.iter().copied().map(color).collect();
            let actual = cache.finish(plan, &samples);
            for (request, value) in requests.iter().zip(actual) {
                let [x, y, z] = request.position;
                let mut sum = [0; 3];
                for dz in -radius..=radius {
                    for dx in -radius..=radius {
                        let c = color(Sample {
                            position: [x + dx, y, z + dz],
                            resolver: Resolver::Grass,
                        });
                        for (n, shift) in [16, 8, 0].into_iter().enumerate() {
                            sum[n] += (c >> shift) & 255;
                        }
                    }
                }
                let expected = if radius == 0 {
                    color(Sample {
                        position: request.position,
                        resolver: Resolver::Grass,
                    })
                } else {
                    let count = (2 * radius + 1).pow(2) as u32;
                    0xff00_0000 | (sum[0] / count) << 16 | (sum[1] / count) << 8 | (sum[2] / count)
                };
                assert_eq!(value, expected);
            }
            let cached = cache.prepare(requests.iter().copied(), &recipes, radius);
            assert!(cached.samples.is_empty());
            assert_eq!(cached.hits, requests.len());
            cache.invalidate(false, &HashSet::from([(0, 0)]));
            let invalid = cache.prepare(requests.iter().copied(), &recipes, radius);
            assert!(!invalid.samples.is_empty());
            assert!(invalid.hits > 0);
            cache.forget(Section(-1, -1, -1));
            let forgotten = cache.prepare(requests.iter().copied(), &recipes, radius);
            assert!(forgotten.hits < invalid.hits);
            assert_eq!(
                cache
                    .prepare(requests.iter().copied(), &recipes, (radius + 1) % 8)
                    .hits,
                0
            );
            cache.invalidate(true, &HashSet::new());
            assert_eq!(
                cache
                    .prepare(requests.iter().copied(), &recipes, radius)
                    .hits,
                0
            );
        }
    }

    #[test]
    fn shifted_query_cache_retires_with_its_consumer() {
        let request = Request {
            position: [0, 0, 0],
            state: 0,
            slot: 0,
        };
        let recipes = [Recipe::Biome {
            resolver: Resolver::Grass,
            below: true,
        }];
        let mut cache = Cache::default();
        let plan = cache.prepare(std::iter::once(request), &recipes, 2);
        let samples: Vec<_> = plan.samples.iter().copied().map(color).collect();
        cache.finish(plan, &samples);
        assert_eq!(cache.prepare(std::iter::once(request), &recipes, 2).hits, 1);
        cache.forget(Section(0, 0, 0));
        assert_eq!(cache.prepare(std::iter::once(request), &recipes, 2).hits, 0);
    }

    #[test]
    fn sparse_sampling_is_the_exact_union_for_every_radius_resolver_and_height() {
        let mut requests = Vec::new();
        let mut recipes = Vec::new();
        for resolver in [
            Resolver::Grass,
            Resolver::Foliage,
            Resolver::DryFoliage,
            Resolver::Water,
        ] {
            for position in [
                [-17, -16, -17],
                [-16, -1, -16],
                [-1, -1, -1],
                [0, 0, 0],
                [15, 0, 15],
                [16, 17, 16],
            ] {
                for below in [false, true] {
                    requests.push(Request {
                        position,
                        state: 0,
                        slot: 0,
                    });
                    recipes.push(Recipe::Biome { resolver, below });
                }
            }
        }
        requests.push(requests[0]);
        recipes.push(recipes[0]);
        requests.push(requests[0]);
        recipes.push(Recipe::Color(0x12345678));
        for radius in 0..=7i32 {
            let mut needed = HashSet::new();
            let expected: Vec<_> = requests
                .iter()
                .zip(&recipes)
                .map(|(r, recipe)| {
                    let Recipe::Biome { resolver, below } = *recipe else {
                        let Recipe::Color(c) = *recipe else {
                            unreachable!()
                        };
                        return c;
                    };
                    let [x, y, z] = r.position;
                    let y = y - i32::from(below);
                    let mut sum = [0u32; 3];
                    for dz in -radius..=radius {
                        for dx in -radius..=radius {
                            let q = Sample {
                                position: [x + dx, y, z + dz],
                                resolver,
                            };
                            needed.insert(q);
                            for (c, shift) in [16, 8, 0].into_iter().enumerate() {
                                sum[c] += (color(q) >> shift) & 255;
                            }
                        }
                    }
                    if radius == 0 {
                        color(Sample {
                            position: [x, y, z],
                            resolver,
                        })
                    } else {
                        let count = (radius * 2 + 1).pow(2) as u32;
                        0xff00_0000
                            | (sum[0] / count) << 16
                            | (sum[1] / count) << 8
                            | (sum[2] / count)
                    }
                })
                .collect();
            let mut cache = Cache::default();
            let plan = cache.prepare(requests.iter().copied(), &recipes, radius);
            assert_eq!(plan.samples.len(), needed.len());
            assert_eq!(plan.samples.iter().copied().collect::<HashSet<_>>(), needed);
            let repeat = Cache::default().prepare(requests.iter().copied(), &recipes, radius);
            assert_eq!(
                plan.samples, repeat.samples,
                "deterministic source callback order"
            );
            let values: Vec<_> = plan.samples.iter().copied().map(color).collect();
            assert_eq!(cache.finish(plan, &values), expected);
            let cached = cache.prepare(requests.iter().copied(), &recipes, radius);
            assert!(cached.samples.is_empty());
            assert_eq!(cache.finish(cached, &[]), expected);
        }
    }
}
