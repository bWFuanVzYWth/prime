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
}
impl Cache {
    pub fn invalidate(&mut self, all: bool, columns: &HashSet<(i32, i32)>) {
        if all {
            self.sections.clear();
        } else if !columns.is_empty() {
            self.sections.retain(|s, _| !columns.contains(&(s.0, s.2)));
        }
    }
    pub fn forget(&mut self, section: Section) {
        self.sections.remove(&section);
        // An upper double-height plant at local Y=0 queries the section below.
        // Retire that dependency too, including a query below the world's section range.
        self.sections
            .remove(&Section(section.0, section.1 - 1, section.2));
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
        let mut unique = HashMap::new();
        let mut samples = Vec::new();
        let mut output = Vec::with_capacity(tiles.len());
        for ((resolver, y, _, _), mut tile) in tiles {
            tile.min = tile.min.map(|p| p - radius);
            tile.max = tile.max.map(|p| p + radius);
            tile.width = (tile.max[0] - tile.min[0] + 1) as usize;
            for z in tile.min[1]..=tile.max[1] {
                for x in tile.min[0]..=tile.max[0] {
                    let query = Sample {
                        position: [x, y, z],
                        resolver,
                    };
                    let index = *unique.entry(query).or_insert_with(|| {
                        let n = samples.len();
                        samples.push(query);
                        n
                    });
                    tile.samples.push(index);
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
        }
    }
    pub fn finish(&mut self, mut plan: Plan, samples: &[u32]) -> Vec<u32> {
        let mut prefix = Vec::<[u32; 3]>::new();
        for tile in plan.tiles {
            let stride = tile.width + 1;
            let height = tile.samples.len() / tile.width;
            prefix.clear();
            prefix.resize(stride * (height + 1), [0; 3]);
            for y in 0..height {
                let mut row = [0; 3];
                for x in 0..tile.width {
                    let color = samples[tile.samples[y * tile.width + x]];
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
                    samples[tile.samples[z * tile.width + x]]
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
}
