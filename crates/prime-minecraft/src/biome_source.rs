//! Version-bound vanilla color evaluation from owned host source fields.
#[cfg(test)]
use crate::wire::Reader;
use crate::{
    biome::{Resolver, Sample},
    column_cache::ColumnCache,
    schedule::Section,
};
use std::{
    collections::{HashMap, HashSet},
    simd::{Simd, num::SimdUint},
};

pub(crate) struct Definitions {
    seed: u64,
    permutation: [u8; 256],
    offset: [f64; 2],
    input_scale: f64,
    value_scale: f64,
    maps: [Vec<u32>; 3],
}
impl Definitions {
    pub fn from_typed(
        v: &prime_abi::PrimeMcBiomeDefinitions,
        maps: &[u32],
    ) -> Result<Self, String> {
        let mut seen = [false; 256];
        let mut permutation = [0; 256];
        for (dst, &n) in permutation.iter_mut().zip(&v.permutation) {
            if n > 255 || seen[n as usize] {
                return Err("invalid biome noise permutation".into());
            }
            seen[n as usize] = true;
            *dst = n as u8;
        }
        if v.offset
            .iter()
            .chain([&v.input_scale, &v.value_scale])
            .any(|p| !p.is_finite())
        {
            return Err("nonfinite biome noise field".into());
        }
        let mut tables = std::array::from_fn(|_| Vec::new());
        for (dst, span) in tables.iter_mut().zip([v.grass, v.foliage, v.dry_foliage]) {
            let values = prime_abi::minecraft::range(maps, span)?;
            if values.len() > 65536 {
                return Err("oversized biome color table".into());
            }
            *dst = values.to_vec();
        }
        Ok(Self {
            seed: v.seed,
            permutation,
            offset: v.offset,
            input_scale: v.input_scale,
            value_scale: v.value_scale,
            maps: tables,
        })
    }
    #[cfg(test)]
    pub fn read(r: &mut Reader<'_>) -> Result<Self, String> {
        let seed = r.u64()?;
        let mut permutation = [0; 256];
        let mut seen = [false; 256];
        for p in &mut permutation {
            let n = r.u32()?;
            if n > 255 || seen[n as usize] {
                return Err("invalid biome noise permutation".into());
            }
            *p = n as u8;
            seen[n as usize] = true;
        }
        let offset = [r.f64()?, r.f64()?];
        let input_scale = r.f64()?;
        let value_scale = r.f64()?;
        let mut maps = std::array::from_fn(|_| Vec::new());
        for map in &mut maps {
            let count = r.count(4)?;
            if count > 65536 {
                return Err("oversized biome color table".into());
            }
            *map = r.u32s(count)?;
        }
        Ok(Self {
            seed,
            permutation,
            offset,
            input_scale,
            value_scale,
            maps,
        })
    }
    fn color(&self, temperature: f32, downfall: f32, map: usize) -> u32 {
        let t = f64::from(temperature.clamp(0., 1.));
        let humidity = f64::from(downfall.clamp(0., 1.)) * t;
        let index = ((1. - t) * 255.) as usize | (((1. - humidity) * 255.) as usize) << 8;
        self.maps[map]
            .get(index)
            .copied()
            .unwrap_or([0xffff00ff, 0xff48b518, 0xff5c3c32][map])
    }
    fn swamp(&self, version: u32, x: i32, z: i32) -> u32 {
        let x = f64::from(x) * 0.0225;
        let z = f64::from(z) * 0.0225;
        let noise = if version == 262 {
            // The vanilla single octave uses getValue(..., false): no random offset.
            0. + simplex(
                &self.permutation,
                x * self.input_scale,
                z * self.input_scale,
            ) * self.value_scale
        } else {
            // 26.3 SimplexNoise.get returns float before the modifier's double comparison.
            f64::from(simplex(&self.permutation, x + self.offset[0], z + self.offset[1]) as f32)
        };
        if noise < -0.1 { 0xff4c763c } else { 0xff6a7039 }
    }
}

#[derive(Clone, Copy, Default)]
struct BiomeColor {
    colors: [u32; 4],
    swamp: bool,
}
impl BiomeColor {
    fn from_typed(v: &prime_abi::PrimeMcBiome, definitions: &Definitions) -> Result<Self, String> {
        prime_abi::minecraft::finite(&[v.temperature, v.downfall])?;
        if v.flags & !7 != 0 || v.modifier > 2 {
            return Err("invalid biome color fields".into());
        }
        let mut colors = std::array::from_fn(|i| {
            if i == 3 {
                v.water
            } else if v.flags & (1 << i) != 0 {
                v.overrides[i]
            } else {
                definitions.color(v.temperature, v.downfall, i)
            }
        });
        if v.modifier == 1 {
            colors[0] = 0xff000000 | (((colors[0] & 0xfefefe) + 0x28340a) >> 1);
        }
        Ok(Self {
            colors,
            swamp: v.modifier == 2,
        })
    }
    fn color(self, definitions: &Definitions, version: u32, sample: Sample) -> u32 {
        if self.swamp && sample.resolver == Resolver::Grass {
            definitions.swamp(version, sample.position[0], sample.position[2])
        } else {
            self.colors[sample.resolver as usize - 1]
        }
    }
    #[cfg(test)]
    fn read(r: &mut Reader<'_>, definitions: &Definitions) -> Result<Self, String> {
        let temperature = r.f32()?;
        let downfall = r.f32()?;
        let water = r.u32()?;
        let overrides = [r.u32()?, r.u32()?, r.u32()?];
        let flags = r.u32()?;
        let modifier = r.u32()?;
        if flags & !7 != 0 || modifier > 2 {
            return Err("invalid biome color fields".into());
        }
        let mut colors = std::array::from_fn(|i| {
            if i == 3 {
                water
            } else if flags & (1 << i) != 0 {
                overrides[i]
            } else {
                definitions.color(temperature, downfall, i)
            }
        });
        if modifier == 1 {
            colors[0] = 0xff000000 | (((colors[0] & 0xfefefe) + 0x28340a) >> 1);
        }
        Ok(Self {
            colors,
            swamp: modifier == 2,
        })
    }
}

#[derive(Clone)]
struct Palette {
    valid: u64,
    colors: [BiomeColor; 64],
}
impl Default for Palette {
    fn default() -> Self {
        Self {
            valid: 0,
            colors: [BiomeColor::default(); 64],
        }
    }
}
#[derive(Default)]
pub(crate) struct Cache {
    pub definitions: Option<Definitions>,
    palettes: ColumnCache<Box<Palette>>,
    zoom: ZoomCache,
}
pub(crate) struct Request {
    pub section: Section,
    pub mask: u64,
    tile: usize,
}
struct Tile {
    section: Section,
    palette: Box<Palette>,
    wanted: u64,
}
pub(crate) struct Plan {
    pub requests: Vec<Request>,
    tiles: Vec<Tile>,
    locations: Vec<usize>,
}
pub(crate) struct Response {
    colors: Vec<BiomeColor>,
    cells: Vec<usize>,
}
impl Cache {
    pub fn typed_response(
        &self,
        plan: &Plan,
        values: &prime_abi::minecraft::Biomes<'_>,
    ) -> Result<Response, String> {
        let definitions = self
            .definitions
            .as_ref()
            .ok_or("missing biome definitions")?;
        let colors = values
            .biomes()
            .iter()
            .map(|v| BiomeColor::from_typed(v, definitions))
            .collect::<Result<Vec<_>, _>>()?;
        let count: usize = plan
            .requests
            .iter()
            .map(|r| r.mask.count_ones() as usize)
            .sum();
        if values.indices().len() != count
            || values
                .indices()
                .iter()
                .any(|&id| id as usize >= colors.len())
        {
            return Err("invalid biome cell indices".into());
        }
        Ok(Response {
            colors,
            cells: values.indices().iter().map(|&i| i as usize).collect(),
        })
    }
    pub fn invalidate(&mut self, all: bool, columns: &HashSet<(i32, i32)>) {
        if all {
            self.definitions = None;
            self.palettes.clear();
        } else if !columns.is_empty() {
            for &column in columns {
                self.palettes.remove_column(column);
            }
        }
    }
    pub fn forget(&mut self, consumer: Section) {
        // The filter and zoom can touch horizontal neighbors and either vertical neighbor.
        for section in consumer.halo() {
            self.palettes.remove(&section);
            self.zoom.pages.remove(&section);
        }
    }
    pub fn prepare(&mut self, samples: &[Sample]) -> Plan {
        let definitions = self.definitions.as_ref().unwrap();
        let positions = self.zoom.resolve(definitions.seed, samples);
        let mut plan = Plan {
            requests: Vec::new(),
            tiles: Vec::new(),
            locations: Vec::with_capacity(samples.len()),
        };
        let mut indices = HashMap::new();
        let mut previous = None;
        for [x, y, z] in positions {
            let section = Section(x >> 2, y >> 2, z >> 2);
            let index = match previous {
                Some((key, index)) if key == section => index,
                _ => {
                    let index = *indices.entry(section).or_insert_with(|| {
                        let index = plan.tiles.len();
                        plan.tiles.push(Tile {
                            section,
                            palette: self.palettes.get(&section).cloned().unwrap_or_default(),
                            wanted: 0,
                        });
                        index
                    });
                    previous = Some((section, index));
                    index
                }
            };
            let local = ((x & 3) | (z & 3) << 2 | (y & 3) << 4) as usize;
            plan.tiles[index].wanted |= 1 << local;
            plan.locations.push(index * 64 + local);
        }
        for (tile, data) in plan.tiles.iter().enumerate() {
            let mask = data.wanted & !data.palette.valid;
            if mask != 0 {
                plan.requests.push(Request {
                    section: data.section,
                    mask,
                    tile,
                });
            }
        }
        plan
    }
    #[cfg(test)]
    pub fn read_response(&self, plan: &Plan, r: &mut Reader<'_>) -> Result<Response, String> {
        let count = r.count(32)?;
        let mut colors = Vec::with_capacity(count);
        let definitions = self.definitions.as_ref().unwrap();
        for _ in 0..count {
            colors.push(BiomeColor::read(r, definitions)?);
        }
        let count = plan
            .requests
            .iter()
            .map(|p| p.mask.count_ones() as usize)
            .sum();
        let mut cells = Vec::with_capacity(count);
        for _ in 0..count {
            let id = r.u32()? as usize;
            if id >= colors.len() {
                return Err("undefined source biome".into());
            }
            cells.push(id);
        }
        Ok(Response { colors, cells })
    }
    pub fn finish(
        &mut self,
        mut plan: Plan,
        response: Option<Response>,
        samples: &[Sample],
        version: u32,
    ) -> Vec<u32> {
        if let Some(response) = response {
            let mut ids = response.cells.into_iter();
            for request in &plan.requests {
                let palette = &mut plan.tiles[request.tile].palette;
                let mut bits = request.mask;
                while bits != 0 {
                    let local = bits.trailing_zeros() as usize;
                    bits &= bits - 1;
                    palette.colors[local] = response.colors[ids.next().unwrap()];
                }
                palette.valid |= request.mask;
            }
        }
        let definitions = self.definitions.as_ref().unwrap();
        let colors = plan
            .locations
            .iter()
            .zip(samples)
            .map(|(&location, sample)| {
                let biome = plan.tiles[location / 64].palette.colors[location & 63];
                biome.color(definitions, version, *sample)
            })
            .collect();
        // Only pages receiving fully validated host fields need publication into the cache.
        let mut changed = plan.requests.into_iter().map(|r| r.tile).peekable();
        for (index, tile) in plan.tiles.into_iter().enumerate() {
            if changed.peek() == Some(&index) {
                changed.next();
                self.palettes.insert(tile.section, tile.palette);
            }
        }
        colors
    }
}

/// Each quart cube has the same eight jittered corners for all 64 block positions.
/// Seed-only winners survive biome/colormap changes; consumer retirement bounds their storage.
#[derive(Default)]
struct ZoomCache {
    seed: Option<u64>,
    pages: HashMap<Section, Box<ZoomPage>>,
}
struct ZoomPage {
    cubes: [Option<Box<ZoomCube>>; 64],
}
impl Default for ZoomPage {
    fn default() -> Self {
        Self {
            cubes: std::array::from_fn(|_| None),
        }
    }
}
struct ZoomCube {
    jitter: [[f64; 8]; 3],
    winners: [u8; 64],
}
impl ZoomCube {
    fn new(seed: u64, base: [i32; 3]) -> Self {
        type Corners = Simd<u64, 8>;
        let next = |state: Corners, salt| {
            state
                * (state * Corners::splat(6364136223846793005)
                    + Corners::splat(1442695040888963407))
                + salt
        };
        let fiddle = |state: Corners| {
            // The masked value is at most 1023; narrow before conversion to avoid emulated u64→f64.
            let value = ((state >> 24) & Corners::splat(1023))
                .cast::<u32>()
                .cast::<f64>();
            ((value / Simd::splat(1024.)) - Simd::splat(0.5)) * Simd::splat(0.9)
        };
        let quart: [_; 3] = std::array::from_fn(|axis| {
            Corners::from_array(std::array::from_fn(|corner| {
                (base[axis] + ((corner >> (2 - axis)) & 1) as i32) as i64 as u64
            }))
        });
        let mut state = Corners::splat(seed);
        for _ in 0..2 {
            for coordinate in quart {
                state = next(state, coordinate);
            }
        }
        let x = fiddle(state).to_array();
        state = next(state, Corners::splat(seed));
        let y = fiddle(state).to_array();
        state = next(state, Corners::splat(seed));
        Self {
            jitter: [x, y, fiddle(state).to_array()],
            winners: [8; 64],
        }
    }
    fn resolve(&mut self, fraction: [i32; 3]) -> u8 {
        let local = (fraction[0] | fraction[2] << 2 | fraction[1] << 4) as usize;
        if self.winners[local] < 8 {
            return self.winners[local];
        }
        let f = fraction.map(|v| f64::from(v) / 4.);
        type Corners = Simd<f64, 8>;
        let distance = |axis, delta| {
            (Corners::splat(f[axis]) - Corners::from_array(delta))
                + Corners::from_array(self.jitter[axis])
        };
        let x = distance(0, [0., 0., 0., 0., 1., 1., 1., 1.]);
        let y = distance(1, [0., 0., 1., 1., 0., 0., 1., 1.]);
        let z = distance(2, [0., 1., 0., 1., 0., 1., 0., 1.]);
        // Preserve vanilla's (z² + y²) + x² order, without FMA. Lowest lane wins ties.
        let distance = z * z + y * y + x * x;
        let mut best = f64::INFINITY;
        let mut winner = 0;
        for (corner, distance) in distance.to_array().into_iter().enumerate() {
            if distance < best {
                best = distance;
                winner = corner as u8;
            }
        }
        self.winners[local] = winner;
        winner
    }
}
impl ZoomCache {
    fn resolve(&mut self, seed: u64, samples: &[Sample]) -> Vec<[i32; 3]> {
        if self.seed != Some(seed) {
            self.pages.clear();
            self.seed = Some(seed);
        }
        let mut indices = HashMap::new();
        let mut pages: Vec<(Section, Box<ZoomPage>)> = Vec::new();
        let mut previous = None;
        let mut positions = Vec::with_capacity(samples.len());
        for sample in samples {
            let shifted = sample.position.map(|v| v.wrapping_sub(2));
            let base = shifted.map(|v| v >> 2);
            let section = Section(shifted[0] >> 4, shifted[1] >> 4, shifted[2] >> 4);
            let index = match previous {
                Some((key, index)) if key == section => index,
                _ => {
                    let index = *indices.entry(section).or_insert_with(|| {
                        let index = pages.len();
                        pages.push((section, self.pages.remove(&section).unwrap_or_default()));
                        index
                    });
                    previous = Some((section, index));
                    index
                }
            };
            let local = ((base[0] & 3) | (base[2] & 3) << 2 | (base[1] & 3) << 4) as usize;
            let cube = pages[index].1.cubes[local]
                .get_or_insert_with(|| Box::new(ZoomCube::new(seed, base)));
            let winner = i32::from(cube.resolve(shifted.map(|v| v & 3)));
            positions.push([
                base[0] + (winner >> 2),
                base[1] + ((winner >> 1) & 1),
                base[2] + (winner & 1),
            ]);
        }
        self.pages.extend(pages);
        positions
    }
}
#[cfg(test)]
fn next(state: u64, salt: u64) -> u64 {
    state
        .wrapping_mul(
            state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407),
        )
        .wrapping_add(salt)
}
#[cfg(test)]
fn fiddle(state: u64) -> f64 {
    ((((state >> 24) & 1023) as f64 / 1024.) - 0.5) * 0.9
}

fn simplex(permutation: &[u8; 256], x: f64, y: f64) -> f64 {
    const GRADIENT: [[f64; 2]; 12] = [
        [1., 1.],
        [-1., 1.],
        [1., -1.],
        [-1., -1.],
        [1., 0.],
        [-1., 0.],
        [1., 0.],
        [-1., 0.],
        [0., 1.],
        [0., -1.],
        [0., 1.],
        [0., -1.],
    ];
    let f2 = 0.5 * (3f64.sqrt() - 1.);
    let g2 = (3. - 3f64.sqrt()) / 6.;
    let skew = (x + y) * f2;
    let i = (x + skew).floor() as i32;
    let j = (y + skew).floor() as i32;
    let unskew = f64::from(i + j) * g2;
    let x0 = x - (f64::from(i) - unskew);
    let y0 = y - (f64::from(j) - unskew);
    let (di, dj) = if x0 > y0 { (1, 0) } else { (0, 1) };
    let p = |v: i32| i32::from(permutation[(v & 255) as usize]);
    let corner = |gi: i32, x: f64, y: f64| {
        let a = 0.5 - x * x - y * y - 0.;
        if a < 0. {
            0.
        } else {
            let a = a * a;
            let g = GRADIENT[gi as usize % 12];
            a * a * (g[0] * x + g[1] * y + 0.)
        }
    };
    let n0 = corner(p(i + p(j)), x0, y0);
    let n1 = corner(
        p(i + di + p(j + dj)),
        x0 - f64::from(di) + g2,
        y0 - f64::from(dj) + g2,
    );
    let n2 = corner(p(i + 1 + p(j + 1)), x0 - 1. + 2. * g2, y0 - 1. + 2. * g2);
    70. * (n0 + n1 + n2)
}

#[cfg(test)]
pub(crate) fn check_math_fixture(bytes: &[u8]) {
    let pages = [bytes];
    let mut r = Reader::new(&pages).unwrap();
    let (version, _, _) = r.header(90).unwrap();
    let definitions = Definitions::read(&mut r).unwrap();
    let count = r.count(32).unwrap();
    assert_eq!(count, 65536);
    let mut zoom = ZoomCache::default();
    let mut samples = Vec::new();
    let mut results = Vec::new();
    let mut last_seed = 0;
    for _ in 0..count {
        let seed = r.u64().unwrap();
        let position = std::array::from_fn(|_| r.i32().unwrap());
        let expected = std::array::from_fn(|_| r.i32().unwrap());
        if seed != last_seed && !samples.is_empty() {
            assert_eq!(
                zoom.resolve(last_seed, &samples),
                results,
                "MC {version}, seed {last_seed}"
            );
            assert_eq!(
                zoom.resolve(last_seed, &samples),
                results,
                "cached MC {version}"
            );
            samples.clear();
            results.clear();
        }
        last_seed = seed;
        samples.push(Sample {
            position,
            resolver: Resolver::Grass,
        });
        results.push(expected);
    }
    assert_eq!(zoom.resolve(last_seed, &samples), results);
    assert_eq!(zoom.resolve(last_seed, &samples), results);
    let count = r.count(36).unwrap();
    assert_eq!(count, 30);
    for i in 0..count {
        let biome = BiomeColor::read(&mut r, &definitions).unwrap();
        for _ in 0..r.count(24).unwrap() {
            let position = [r.i32().unwrap(), 0, r.i32().unwrap()];
            for resolver in [
                Resolver::Grass,
                Resolver::Foliage,
                Resolver::DryFoliage,
                Resolver::Water,
            ] {
                let expected = r.u32().unwrap();
                assert_eq!(
                    biome.color(&definitions, version, Sample { position, resolver }),
                    expected,
                    "MC {version}: biome {i} {resolver:?} {position:?}"
                );
            }
        }
    }
    r.finish().unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn changed_quart_columns_remove_all_vertical_palettes_without_visiting_far_columns() {
        let mut cache = Cache::default();
        for x in -1000..=1000 {
            for y in [-64, -1, 0, 64] {
                cache.palettes.insert(Section(x, y, -x), Default::default());
            }
        }
        cache.invalidate(false, &HashSet::from([(0, 0), (-1, 1)]));
        assert_eq!(cache.palettes.visited_pages, 8);
        for y in [-64, -1, 0, 64] {
            assert!(cache.palettes.get(&Section(0, y, 0)).is_none());
            assert!(cache.palettes.get(&Section(-1, y, 1)).is_none());
            assert!(cache.palettes.get(&Section(1000, y, -1000)).is_some());
        }
    }
    #[test]
    fn vector_zoom_matches_ordered_scalar_distances_and_ties() {
        for seed in [0, 1, 42, u64::MAX, 0x1357_2468_ffff_0000] {
            for base in [[0, 0, 0], [-1, -2, -3], [-1_000_000, 300, 2_000_000]] {
                let mut cube = ZoomCube::new(seed, base);
                for (corner, _) in cube.jitter[0].iter().enumerate() {
                    let quart: [_; 3] = std::array::from_fn(|axis| {
                        base[axis] + ((corner >> (2 - axis)) & 1) as i32
                    });
                    let mut state = seed;
                    for _ in 0..2 {
                        for coordinate in quart {
                            state = next(state, coordinate as i64 as u64);
                        }
                    }
                    for jitter in &cube.jitter {
                        assert_eq!(jitter[corner].to_bits(), fiddle(state).to_bits());
                        state = next(state, seed);
                    }
                }
                for y in 0..4 {
                    for z in 0..4 {
                        for x in 0..4 {
                            let f = [x, y, z].map(|v| f64::from(v) / 4.);
                            let mut best = f64::INFINITY;
                            let mut expected = 0;
                            for corner in 0..8 {
                                let x =
                                    (f[0] - ((corner >> 2) & 1) as f64) + cube.jitter[0][corner];
                                let y =
                                    (f[1] - ((corner >> 1) & 1) as f64) + cube.jitter[1][corner];
                                let z = (f[2] - (corner & 1) as f64) + cube.jitter[2][corner];
                                let distance = z * z + y * y + x * x;
                                if distance < best {
                                    best = distance;
                                    expected = corner as u8;
                                }
                            }
                            assert_eq!(cube.resolve([x, y, z]), expected);
                            assert_eq!(cube.resolve([x, y, z]), expected);
                        }
                    }
                }
            }
        }
        let mut tie = ZoomCube {
            jitter: [[0.; 8]; 3],
            winners: [8; 64],
        };
        assert_eq!(tie.resolve([2, 2, 2]), 0);
        assert_eq!(tie.resolve([2, 2, 3]), 1);
        assert_eq!(tie.resolve([3, 2, 2]), 4);
    }

    #[test]
    fn source_pages_reuse_quarts_across_resolvers_and_invalidate_only_changed_columns() {
        let mut cache = Cache {
            definitions: Some(Definitions {
                seed: 0,
                permutation: std::array::from_fn(|i| i as u8),
                offset: [0.; 2],
                input_scale: 1.,
                value_scale: 1.,
                maps: std::array::from_fn(|_| Vec::new()),
            }),
            ..Default::default()
        };
        let samples: Vec<_> = (0..48)
            .flat_map(|x| {
                [Resolver::Grass, Resolver::Water].map(move |resolver| Sample {
                    position: [x, 8, 8],
                    resolver,
                })
            })
            .collect();
        let plan = cache.prepare(&samples);
        let cells: usize = plan
            .requests
            .iter()
            .map(|p| p.mask.count_ones() as usize)
            .sum();
        assert!(cells < samples.len() / 2);
        let response = Response {
            colors: vec![BiomeColor {
                colors: [0x80706050; 4],
                swamp: false,
            }],
            cells: vec![0; cells],
        };
        assert_eq!(
            cache.finish(plan, Some(response), &samples, 262),
            vec![0x80706050; samples.len()]
        );
        let plan = cache.prepare(&samples);
        assert!(plan.requests.is_empty());
        assert_eq!(
            cache.finish(plan, None, &samples, 263),
            vec![0x80706050; samples.len()]
        );
        cache.invalidate(false, &HashSet::from([(1, 0)]));
        let plan = cache.prepare(&samples);
        assert!(!plan.requests.is_empty());
        assert!(
            plan.requests
                .iter()
                .all(|r| r.section.0 == 1 && r.section.2 == 0)
        );
        cache.forget(Section(0, 0, 0));
        assert!(cache.palettes.keys().all(|s| s.0 >= 2));
        assert!(cache.zoom.pages.keys().all(|s| s.0 >= 2));
        let zoom_pages = cache.zoom.pages.len();
        cache.invalidate(true, &HashSet::new());
        assert!(cache.definitions.is_none() && cache.palettes.is_empty());
        assert_eq!(cache.zoom.pages.len(), zoom_pages); // Winners depend only on the source seed.
    }
}
