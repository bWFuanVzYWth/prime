//! Mip-zero cutout support proofs. Minecraft's integer texel grids normally need one bit per
//! microtriangle; unproved coverage remains unknown and executes the existing shader test.
//! Resource preparation produces immutable template blocks; geometry updates only select their
//! global indices. This module owns no Vulkan resource or geometry-dependent production cache.
//! Aligned-grid proofs cover interiors. Shared texel-edge hardware/f32 ties may differ from
//! shader floor; the documented performance policy accepts these without border fallback.
use crate::packing;
use prime_scene::{
    Texture,
    surface::{LayerMode, RepeatUv, SurfaceFace},
};
use std::collections::{BTreeMap, BTreeSet, HashMap};
#[cfg(test)]
use std::sync::Arc;

pub(crate) const TRANSPARENT: u32 = u32::MAX;
pub(crate) const OPAQUE: u32 = u32::MAX - 1;
pub(crate) const UNKNOWN: u32 = u32::MAX - 3;
pub(crate) const TWO_STATE: u16 = 1;
pub(crate) const FOUR_STATE: u16 = 2;
const UNKNOWN_STATE: u8 = 3;
// Bound optional preprocessing, including pathological resource-pack dimensions/mappings.
const MAX_TWO_LEVEL: u32 = 10;
const MAX_FOUR_LEVEL: u32 = 8;
const MAX_RANGE_TEXELS: u32 = 4096;
#[cfg(test)]
const CACHE_BYTES: usize = 64 * 1024 * 1024;
#[cfg(test)]
const CACHE_ENTRIES: usize = 32768;
#[cfg(test)]
const CACHE_ENTRY_BYTES: usize = 512;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Block {
    pub offset: u32,
    pub level: u16,
    pub format: u16,
}
pub(crate) struct Data {
    pub blocks: Vec<u8>,
    pub triangles: Vec<Block>,
    #[cfg(test)]
    pub indices: Vec<u32>,
    #[cfg(test)]
    pub textures: BTreeSet<u32>,
    #[cfg(test)]
    pub stats: Stats,
}
pub(crate) struct Binding {
    pub indices: Vec<u32>,
    pub textures: BTreeSet<u32>,
    #[cfg(test)]
    pub stats: Stats,
}
#[cfg(test)]
#[derive(Default, Debug)]
pub(crate) struct Stats {
    pub two_state_triangles: usize,
    pub four_state_triangles: usize,
    pub transparent_triangles: usize,
    pub opaque_triangles: usize,
    pub unknown_triangles: usize,
    pub cache_hits: usize,
}

#[derive(PartialEq, Eq, Hash)]
struct TemplateKey {
    texture: u32,
    uv: [[u32; 2]; 3],
}
struct Sprite {
    alpha: [u8; 2],
    available: bool,
}
/// A finite resource family: complete sprites, square tile spans 1/2/4, both halves and all
/// eight UV orientations. Stable global indices remain valid for this resource generation.
/// Runtime misses retain the shader coverage test; they never add templates or scan pixels.
pub(crate) struct Templates {
    pub data: Data,
    sprites: BTreeMap<u32, Sprite>,
    lookup: HashMap<TemplateKey, u32>,
}
impl Templates {
    pub fn prepare(textures: &BTreeMap<u32, Texture>, max_two: u32, max_four: u32) -> Self {
        let mut result = Self {
            data: Data {
                blocks: Vec::new(),
                triangles: Vec::new(),
                #[cfg(test)]
                indices: Vec::new(),
                #[cfg(test)]
                textures: BTreeSet::new(),
                #[cfg(test)]
                stats: Stats::default(),
            },
            sprites: BTreeMap::new(),
            lookup: HashMap::new(),
        };
        let mut intern = HashMap::<Baked, u32>::new();
        let unit = [[0., 0.], [1., 0.], [1., 1.], [0., 1.]];
        for (&texture, _) in textures.iter().filter(|(_, t)| t.region.is_some()) {
            let source = SourceKey::new(texture, 1, [unit[0], unit[1], unit[2]], [1.; 3], None);
            let source = source.source(textures);
            let available = source.available();
            let mut alpha = [255, 0];
            if available {
                let [width, height] = source.extent();
                for y in 0..height {
                    for x in 0..width {
                        let range = source.alpha_range(x, y);
                        alpha[0] = alpha[0].min(range[0]);
                        alpha[1] = alpha[1].max(range[1]);
                    }
                }
            }
            result.sprites.insert(texture, Sprite { alpha, available });
            #[cfg(test)]
            result.data.textures.insert(texture);
            if !available {
                continue;
            }
            // Uniform coverage needs no descriptors. The extrema also allow arbitrary runtime
            // mappings/tints to use a special index when the entire sprite proves that state.
            if coverage(alpha[0], alpha[1], 1., 1.) != UNKNOWN_STATE {
                continue;
            }
            for size in [1., 2., 4.] {
                for reflected in [false, true] {
                    for rotation in 0..4 {
                        let uv = unit.map(|v| {
                            let v = if reflected { [1. - v[0], v[1]] } else { v };
                            let v = match rotation {
                                0 => v,
                                1 => [1. - v[1], v[0]],
                                2 => [1. - v[0], 1. - v[1]],
                                _ => [v[1], 1. - v[0]],
                            };
                            v.map(|c| c * size)
                        });
                        for corners in [[0, 1, 2], [2, 3, 0]] {
                            let source = SourceKey::new(
                                texture,
                                1,
                                corners.map(|c| uv[c]),
                                [1.; 3],
                                Some(identity_repeat()),
                            );
                            let template =
                                template_key(&source).expect("canonical resource template");
                            if result.lookup.contains_key(&template) {
                                continue;
                            }
                            let index = match bake(
                                &[Some(source.source(textures)), None],
                                max_two,
                                max_four,
                            ) {
                                Err(index) => index,
                                Ok(block) => {
                                    if let Some(&index) = intern.get(&block) {
                                        index
                                    } else if let (Ok(offset), Ok(index)) = (
                                        u32::try_from(result.data.blocks.len()),
                                        u32::try_from(result.data.triangles.len()),
                                    ) {
                                        result.data.triangles.push(Block {
                                            offset,
                                            level: block.level,
                                            format: block.format,
                                        });
                                        result.data.blocks.extend_from_slice(&block.states);
                                        intern.insert(block, index);
                                        index
                                    } else {
                                        UNKNOWN
                                    }
                                }
                            };
                            result.lookup.insert(template, index);
                        }
                    }
                }
            }
        }
        result
    }
    pub fn contains_texture(&self, id: u32) -> bool {
        self.sprites.contains_key(&id)
    }
    pub fn dependencies_changed(&self, changed: &BTreeSet<u32>) -> bool {
        changed.iter().any(|&id| self.contains_texture(id))
    }
    fn select(&self, source: &SourceKey) -> u32 {
        if source.flags == 0 {
            return OPAQUE;
        }
        let alpha = source.alpha.map(f32::from_bits);
        if source.flags != 1
            || alpha
                .iter()
                .any(|a| !a.is_finite() || !(0.0..=1.0).contains(a))
        {
            return UNKNOWN;
        }
        let min = f64::from(alpha.into_iter().fold(f32::INFINITY, f32::min));
        let max = f64::from(alpha.into_iter().fold(f32::NEG_INFINITY, f32::max));
        if max < 0.099999 {
            return TRANSPARENT;
        }
        let sprite = if source.texture == 0 {
            None
        } else {
            let Some(sprite) = self.sprites.get(&source.texture).filter(|s| s.available) else {
                return UNKNOWN;
            };
            Some(sprite)
        };
        let range = sprite.map_or([255; 2], |s| s.alpha);
        match coverage(range[0], range[1], min, max) {
            0 => return TRANSPARENT,
            1 => return OPAQUE,
            _ => {}
        }
        if alpha != [1.; 3] {
            return UNKNOWN;
        }
        let Some(template) = template_key(source) else {
            return UNKNOWN;
        };
        self.lookup.get(&template).copied().unwrap_or(UNKNOWN)
    }
    pub fn bind(&self, plan: &packing::Plan<'_>, group: &packing::Group) -> Option<Binding> {
        let mut binding = Binding {
            indices: Vec::with_capacity(group.count as usize * 2),
            textures: BTreeSet::new(),
            #[cfg(test)]
            stats: Stats::default(),
        };
        let mut useful = false;
        for face in plan.faces(group) {
            for half in 0..2 {
                let key = key(&face, half);
                let index = key.as_ref().map_or(UNKNOWN, |key| {
                    let base = self.select(&key.0);
                    if key
                        .1
                        .as_ref()
                        .is_none_or(|layer| self.select(layer) == base)
                    {
                        base
                    } else {
                        UNKNOWN
                    }
                });
                if index != UNKNOWN {
                    useful = true;
                    let key = key.as_ref().unwrap();
                    for source in [Some(&key.0), key.1.as_ref()].into_iter().flatten() {
                        if source.texture_dependent() && self.contains_texture(source.texture) {
                            binding.textures.insert(source.texture);
                        }
                    }
                }
                #[cfg(test)]
                record(index, &self.data.triangles, &mut binding.stats);
                binding.indices.push(index);
            }
        }
        useful.then_some(binding)
    }
}
fn identity_repeat() -> RepeatUv {
    RepeatUv {
        origin: [0.; 2],
        du: [1., 0.],
        dv: [0., 1.],
        axes: 3,
    }
}
fn template_key(source: &SourceKey) -> Option<TemplateKey> {
    let mut uv = source.uv.map(|v| v.map(f32::from_bits));
    if uv.iter().flatten().any(|c| !c.is_finite()) {
        return None;
    }
    if let Some(r) = &source.repeat {
        if r.axes > 3 {
            return None;
        }
        let origin = r.origin.map(f32::from_bits);
        let du = r.du.map(f32::from_bits);
        let dv = r.dv.map(f32::from_bits);
        // A complete sprite can be reflected/transposed before repeating. Every output axis
        // must cover exactly [0,1], and the two output axes must use different source axes.
        let mut used = 0;
        for axis in 0..2 {
            let (input, coefficient) = if du[axis] == 0. && dv[axis].abs() == 1. {
                (1, dv[axis])
            } else if dv[axis] == 0. && du[axis].abs() == 1. {
                (0, du[axis])
            } else {
                return None;
            };
            if used & (1 << input) != 0 || origin[axis] != if coefficient < 0. { 1. } else { 0. } {
                return None;
            }
            used |= 1 << input;
            if r.axes & (1 << input) == 0 && uv.iter().any(|v| !(0.0..=1.0).contains(&v[input])) {
                return None;
            }
        }
        uv = uv.map(|v| std::array::from_fn(|a| origin[a] + du[a] * v[0] + dv[a] * v[1]));
    } else if uv.iter().flatten().any(|c| !(0.0..=1.0).contains(c)) {
        // Sprite sampling clamps; an out-of-window source is not the periodic template.
        return None;
    }
    for axis in 0..2 {
        if uv
            .iter()
            .any(|v| v[axis].fract() != 0. || v[axis].abs() > 1_048_576.)
        {
            return None;
        }
        let origin = uv[0][axis];
        for v in &mut uv {
            v[axis] -= origin;
        }
    }
    Some(TemplateKey {
        texture: source.texture,
        uv: uv.map(|v| v.map(|x| if x == 0. { 0 } else { x.to_bits() })),
    })
}
#[cfg(test)]
fn record(index: u32, blocks: &[Block], stats: &mut Stats) {
    match index {
        TRANSPARENT => stats.transparent_triangles += 1,
        OPAQUE => stats.opaque_triangles += 1,
        UNKNOWN => stats.unknown_triangles += 1,
        index if blocks[index as usize].format == TWO_STATE => stats.two_state_triangles += 1,
        _ => stats.four_state_triangles += 1,
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct RepeatKey {
    origin: [u32; 2],
    du: [u32; 2],
    dv: [u32; 2],
    axes: u32,
}
#[derive(Clone, PartialEq, Eq, Hash)]
struct SourceKey {
    texture: u32,
    flags: u32,
    uv: [[u32; 2]; 3],
    alpha: [u32; 3],
    repeat: Option<RepeatKey>,
}
#[derive(Clone, PartialEq, Eq, Hash)]
struct FaceKey(SourceKey, Option<SourceKey>);
#[derive(Debug, PartialEq, Eq, Hash)]
struct Baked {
    level: u16,
    format: u16,
    states: Vec<u8>,
}
#[cfg(test)]
#[derive(Clone)]
enum Proof {
    Special(u32),
    Block(Arc<Baked>),
}
/// One immutable texture snapshot and device limits, reused across the current scene update.
/// Packed proof bytes are bounded; GPU block indices and ownership remain local to each BLAS.
#[cfg(test)]
pub(crate) struct Cache<'a> {
    textures: &'a BTreeMap<u32, Texture>,
    max_two: u32,
    max_four: u32,
    proofs: HashMap<FaceKey, Proof>,
    bytes: usize,
    budget: usize,
    bakes: u64,
    hits: u64,
}
#[cfg(test)]
impl<'a> Cache<'a> {
    pub fn new(textures: &'a BTreeMap<u32, Texture>, max_two: u32, max_four: u32) -> Self {
        Self {
            textures,
            max_two,
            max_four,
            proofs: HashMap::new(),
            bytes: 0,
            budget: CACHE_BYTES,
            bakes: 0,
            hits: 0,
        }
    }
    fn proof(&mut self, key: &FaceKey) -> (Proof, bool) {
        if let Some(proof) = self.proofs.get(key) {
            self.hits += 1;
            return (proof.clone(), true);
        }
        self.bakes += 1;
        let sources = [
            Some(key.0.source(self.textures)),
            key.1.as_ref().map(|l| l.source(self.textures)),
        ];
        let proof = match bake(&sources, self.max_two, self.max_four) {
            Err(special) => Proof::Special(special),
            Ok(block) => Proof::Block(Arc::new(block)),
        };
        let cost = CACHE_ENTRY_BYTES
            + match &proof {
                Proof::Special(_) => 0,
                Proof::Block(block) => block.states.len(),
            };
        if self.proofs.len() < CACHE_ENTRIES && cost <= self.budget - self.bytes {
            self.bytes += cost;
            self.proofs.insert(key.clone(), proof.clone());
        }
        (proof, false)
    }
    pub fn build(&mut self, plan: &packing::Plan<'_>, group: &packing::Group) -> Option<Data> {
        build_cached(plan, group, self)
    }
    /// Newly baked source keys and proofs reused from another group in this update.
    pub fn stats(&self) -> [u64; 2] {
        [self.bakes, self.hits]
    }
}
struct Source<'a> {
    texture_id: u32,
    texture: Option<&'a Texture>,
    flags: u32,
    uv: [[f64; 2]; 3],
    alpha: [f32; 3],
    repeat: Option<RepeatUv>,
}
impl SourceKey {
    fn texture_dependent(&self) -> bool {
        self.flags == 1 && self.alpha.iter().any(|&a| f32::from_bits(a) >= 0.099999)
    }
    fn new(
        texture: u32,
        flags: u32,
        mut uv: [[f32; 2]; 3],
        alpha: [f32; 3],
        repeat: Option<RepeatUv>,
    ) -> Self {
        if let Some(r) = repeat {
            for axis in 0..2 {
                let phase = uv[0][axis].fract();
                if r.axes & (1 << axis) != 0
                    && uv
                        .iter()
                        .all(|v| v[axis].abs() <= 1_048_576.0 && v[axis].fract() == phase)
                {
                    // MC's projected integer tile origins carry no coverage identity. Keep
                    // the exact representable phase while sharing the same repeated pattern.
                    let origin = uv[0][axis].floor();
                    for vertex in &mut uv {
                        vertex[axis] -= origin;
                    }
                }
            }
        }
        Self {
            texture,
            flags,
            uv: uv.map(|u| u.map(f32::to_bits)),
            alpha: alpha.map(f32::to_bits),
            repeat: repeat.map(|r| RepeatKey {
                origin: r.origin.map(f32::to_bits),
                du: r.du.map(f32::to_bits),
                dv: r.dv.map(f32::to_bits),
                axes: r.axes,
            }),
        }
    }
    fn source<'a>(&self, textures: &'a BTreeMap<u32, Texture>) -> Source<'a> {
        Source {
            texture_id: self.texture,
            texture: textures.get(&self.texture),
            flags: self.flags,
            uv: self.uv.map(|v| v.map(|x| f64::from(f32::from_bits(x)))),
            alpha: self.alpha.map(f32::from_bits),
            repeat: self.repeat.as_ref().map(|r| RepeatUv {
                origin: r.origin.map(f32::from_bits),
                du: r.du.map(f32::from_bits),
                dv: r.dv.map(f32::from_bits),
                axes: r.axes,
            }),
        }
    }
}
fn key(face: &SurfaceFace, half: usize) -> Option<FaceKey> {
    if face.optics.is_some_and(|o| o.transmit) {
        return None;
    }
    let q = face.geometry.triangle(half);
    let base = SourceKey::new(
        q.texture_id,
        q.flags,
        q.uvs,
        q.colors.map(|c| c[3]),
        face.repeat,
    );
    let layer = match &face.detail {
        None => None,
        Some(d) if d.mode == LayerMode::Bilateral => {
            let l = &d.layer;
            let corners = [[0, 1, 2], [2, 3, 0]][half];
            Some(SourceKey::new(
                l.texture_id,
                l.flags,
                corners.map(|i| l.uvs[i]),
                corners.map(|i| l.colors[i][3]),
                l.repeat,
            ))
        }
        Some(_) => return None,
    };
    Some(FaceKey(base, layer))
}

/// Build only when some hardware coverage can be proved. All unknown batches retain the
/// current BLAS path. Hardware primitive numbering is always two triangles per packed record.
#[cfg(test)]
pub(crate) fn build(
    plan: &packing::Plan<'_>,
    group: &packing::Group,
    textures: &BTreeMap<u32, Texture>,
    max_two: u32,
    max_four: u32,
) -> Option<Data> {
    Cache::new(textures, max_two, max_four).build(plan, group)
}
#[cfg(test)]
fn build_cached(
    plan: &packing::Plan<'_>,
    group: &packing::Group,
    shared: &mut Cache<'_>,
) -> Option<Data> {
    let mut data = Data {
        blocks: Vec::new(),
        triangles: Vec::new(),
        indices: Vec::with_capacity(group.count as usize * 2),
        textures: BTreeSet::new(),
        stats: Stats::default(),
    };
    let mut cache = HashMap::new();
    let mut intern = HashMap::new();
    let mut useful = false;
    for face in plan.faces(group) {
        for half in 0..2 {
            let Some(key) = key(&face, half) else {
                data.indices.push(UNKNOWN);
                data.stats.unknown_triangles += 1;
                continue;
            };
            let dependencies = [
                Some((key.0.texture, key.0.texture_dependent())),
                key.1
                    .as_ref()
                    .map(|layer| (layer.texture, layer.texture_dependent())),
            ];
            let index = if let Some(&index) = cache.get(&key) {
                data.stats.cache_hits += 1;
                index
            } else {
                let (proof, hit) = shared.proof(&key);
                data.stats.cache_hits += usize::from(hit);
                let index = match proof {
                    Proof::Special(special) => special,
                    Proof::Block(block) => {
                        if let Some(&index) = intern.get(&*block) {
                            index
                        } else {
                            // Blocks have only the minimum byte rounding required by the ABI.
                            let offset = u32::try_from(data.blocks.len()).ok()?;
                            let index = u32::try_from(data.triangles.len()).ok()?;
                            data.triangles.push(Block {
                                offset,
                                level: block.level,
                                format: block.format,
                            });
                            data.blocks.extend_from_slice(&block.states);
                            intern.insert(block, index);
                            index
                        }
                    }
                };
                cache.insert(key, index);
                index
            };
            useful |= index != UNKNOWN;
            if index != UNKNOWN {
                for (texture, depends) in dependencies.into_iter().flatten() {
                    if depends && texture != 0 {
                        data.textures.insert(texture);
                    }
                }
            }
            match index {
                TRANSPARENT => data.stats.transparent_triangles += 1,
                OPAQUE => data.stats.opaque_triangles += 1,
                UNKNOWN => data.stats.unknown_triangles += 1,
                index if data.triangles[index as usize].format == TWO_STATE => {
                    data.stats.two_state_triangles += 1;
                }
                _ => data.stats.four_state_triangles += 1,
            }
            data.indices.push(index);
        }
    }
    useful.then_some(data)
}

impl Source<'_> {
    fn extent(&self) -> [u32; 2] {
        self.texture.map_or([1, 1], |t| {
            t.region.map_or([t.width, t.height], |r| [r[2], r[3]])
        })
    }
    fn available(&self) -> bool {
        self.flags == 0
            || self.flags == 1
                && (self.texture_id == 0 || self.texture.is_some())
                && self.uv.as_flattened().iter().all(|v| v.is_finite())
                && self
                    .alpha
                    .iter()
                    .all(|a| a.is_finite() && (0.0..=1.0).contains(a))
                && self.repeat.is_none_or(|r| {
                    r.axes <= 3
                        && [r.origin, r.du, r.dv]
                            .as_flattened()
                            .iter()
                            .all(|x| x.is_finite())
                })
                && self.texture.is_none_or(|t| {
                    self.alpha.iter().all(|&a| a < 0.099999)
                        || t.sampling.as_ref().is_none_or(|s| {
                            let r = t.region.unwrap_or([0, 0, t.width, t.height]);
                            !s.coverage_frames.is_empty()
                                || s.blend == 0.0 && s.next == [r[0], r[1]]
                        })
                })
    }
    fn affine(&self, uv: [f64; 2]) -> [f64; 2] {
        self.repeat.map_or(uv, |r| {
            std::array::from_fn(|i| {
                f64::from(r.origin[i]) + f64::from(r.du[i]) * uv[0] + f64::from(r.dv[i]) * uv[1]
            })
        })
    }
    /// Repeating axis-aligned crops preserve the pixel grid when each repeat seam maps to an
    /// integer texel boundary. The triangle's dyadic proof separately aligns every pixel cut.
    /// Other repeated transforms use the conservative interval classifier below.
    fn grid_repeat(&self) -> bool {
        self.repeat.is_none_or(|r| {
            r.axes == 0
                || self.extent().into_iter().enumerate().all(|(i, extent)| {
                    let [a, b] = [r.du[i], r.dv[i]];
                    (a == 0.0 || b == 0.0)
                        && [r.origin[i], a, b]
                            .into_iter()
                            .all(|x| (f64::from(x) * f64::from(extent)).fract() == 0.0)
                })
        })
    }
    fn grid(&self, level: u32) -> bool {
        if self.flags == 0 {
            return true;
        }
        let extent = self.extent();
        self.grid_repeat()
            && extent.into_iter().all(u32::is_power_of_two)
            && (0..2).all(|axis| {
                let v = self
                    .uv
                    .map(|uv| self.affine(uv)[axis] * f64::from(extent[axis]));
                if v.iter().any(|x| x.fract() != 0.0 || x.abs() > 1_048_576.0) {
                    return false;
                }
                let min = v.into_iter().fold(f64::INFINITY, f64::min);
                let max = v.into_iter().fold(f64::NEG_INFINITY, f64::max);
                let delta = (max - min) as u32;
                (v[0] == v[1] || v[0] == v[2] || v[1] == v[2])
                    && (delta == 0 || delta.is_power_of_two())
                    && delta <= 1 << level
            })
    }
    fn level(&self) -> u32 {
        let extent = self.extent();
        let mut span = 1.0_f64;
        for (axis, size) in extent.into_iter().enumerate() {
            let v = self.uv.map(|uv| self.affine(uv)[axis]);
            let min = v.into_iter().fold(f64::INFINITY, f64::min);
            let max = v.into_iter().fold(f64::NEG_INFINITY, f64::max);
            span = span.max((max - min) * f64::from(size));
        }
        span.log2().ceil().max(0.0) as u32
    }
    fn texel(&self, x: u32, y: u32) -> u8 {
        let Some(t) = self.texture else { return 255 };
        let r = t.region.unwrap_or([0, 0, t.width, t.height]);
        t.pixels[((r[1] + y) as usize * t.width as usize + (r[0] + x) as usize) * 4 + 3]
    }
    fn alpha_range(&self, x: u32, y: u32) -> [u8; 2] {
        let Some(t) = self.texture else {
            return [255; 2];
        };
        let frames = t
            .sampling
            .as_ref()
            .map(|s| &s.coverage_frames[..])
            .unwrap_or(&[]);
        if frames.is_empty() {
            return [self.texel(x, y); 2];
        }
        let mut min = 255;
        let mut max = 0;
        for origin in frames {
            let alpha = t.pixels
                [((origin[1] + y) as usize * t.width as usize + (origin[0] + x) as usize) * 4 + 3];
            min = min.min(alpha);
            max = max.max(alpha);
            if min == 0 && max == 255 {
                break;
            }
        }
        [min, max]
    }
    fn pixel(&self, mut uv: [f64; 2]) -> [u32; 2] {
        if let Some(r) = self.repeat {
            for (i, value) in uv.iter_mut().enumerate() {
                if r.axes & (1 << i) != 0 {
                    *value -= value.floor();
                }
            }
        }
        let uv = self.affine(uv);
        let sprite = self.texture.is_some_and(|t| t.region.is_some());
        let pixel: [u32; 2] = std::array::from_fn(|axis| {
            let local = if sprite {
                uv[axis].clamp(0.0, 1.0)
            } else {
                uv[axis] - uv[axis].floor()
            };
            ((local * f64::from(self.extent()[axis])) as u32).min(self.extent()[axis] - 1)
        });
        pixel
    }
    fn grid_state(&self, bary: [f64; 2]) -> u8 {
        if self.flags == 0 {
            return 1;
        }
        let uv = std::array::from_fn(|axis| {
            self.uv[0][axis]
                + (self.uv[1][axis] - self.uv[0][axis]) * bary[0]
                + (self.uv[2][axis] - self.uv[0][axis]) * bary[1]
        });
        let pixel = self.pixel(uv);
        let [min, max] = self.alpha_range(pixel[0], pixel[1]);
        let tint = f64::from(self.alpha[0]);
        coverage(min, max, tint, tint)
    }
    #[cfg(test)]
    fn sample(&self, uv: [f64; 2]) -> u8 {
        let p = self.pixel(uv);
        self.texel(p[0], p[1])
    }
    fn state(&self, vertices: [[f64; 2]; 3], exact: bool) -> u8 {
        if self.flags == 0 {
            return 1;
        }
        if self.alpha.iter().all(|&a| a < 0.099999) {
            return 0;
        }
        let alphas = vertices.map(|b| {
            f64::from(self.alpha[0]) * (1.0 - b[0] - b[1])
                + f64::from(self.alpha[1]) * b[0]
                + f64::from(self.alpha[2]) * b[1]
        });
        let min_tint = alphas.into_iter().fold(f64::INFINITY, f64::min);
        let max_tint = alphas.into_iter().fold(f64::NEG_INFINITY, f64::max);
        let interpolate = |b: [f64; 2]| {
            std::array::from_fn(|i| {
                self.uv[0][i] * (1.0 - b[0] - b[1]) + self.uv[1][i] * b[0] + self.uv[2][i] * b[1]
            })
        };
        if exact {
            // Integer grids prove the interior texel. The performance contract accepts
            // hardware/f32 shared-edge ties instead of adding unknown boundary texels.
            let centroid: [f64; 2] =
                std::array::from_fn(|i| vertices.iter().map(|v| v[i]).sum::<f64>() / 3.0);
            let pixel = self.pixel(interpolate(centroid));
            let [min, max] = self.alpha_range(pixel[0], pixel[1]);
            return coverage(min, max, min_tint, max_tint);
        }
        let uv = vertices.map(interpolate);
        let mut affine_uv = Some(uv);
        let mut bounds: [[f64; 2]; 2] = std::array::from_fn(|axis| {
            [
                uv.map(|v| v[axis])
                    .into_iter()
                    .fold(f64::INFINITY, f64::min),
                uv.map(|v| v[axis])
                    .into_iter()
                    .fold(f64::NEG_INFINITY, f64::max),
            ]
        });
        if let Some(r) = self.repeat {
            for (axis, bound) in bounds.iter_mut().enumerate() {
                if r.axes & (1 << axis) != 0 {
                    if bound[0].floor() == bound[1].floor() {
                        if let Some(triangle) = &mut affine_uv {
                            for vertex in triangle {
                                vertex[axis] -= bound[0].floor();
                            }
                        }
                    } else {
                        affine_uv = None;
                    }
                    *bound = fractional(*bound);
                }
            }
            bounds = std::array::from_fn(|axis| {
                let [a, b] = [f64::from(r.du[axis]), f64::from(r.dv[axis])];
                let origin = f64::from(r.origin[axis]);
                [
                    origin
                        + a * bounds[0][usize::from(a < 0.0)]
                        + b * bounds[1][usize::from(b < 0.0)],
                    origin
                        + a * bounds[0][usize::from(a >= 0.0)]
                        + b * bounds[1][usize::from(b >= 0.0)],
                ]
            });
            affine_uv = affine_uv.map(|triangle| triangle.map(|uv| self.affine(uv)));
        }
        let extent = self.extent();
        let sprite = self.texture.is_some_and(|t| t.region.is_some());
        if !sprite {
            for (axis, bound) in bounds.iter().enumerate() {
                if bound[0].floor() == bound[1].floor() {
                    if let Some(triangle) = &mut affine_uv {
                        for vertex in triangle {
                            vertex[axis] -= bound[0].floor();
                        }
                    }
                } else {
                    affine_uv = None;
                }
            }
        }
        let triangle = affine_uv.map(|triangle| {
            triangle.map(|uv| std::array::from_fn(|axis| uv[axis] * f64::from(extent[axis])))
        });
        let pixels: [[u32; 2]; 2] = std::array::from_fn(|axis| {
            let local = if sprite {
                bounds[axis].map(|v| v.clamp(0.0, 1.0))
            } else {
                fractional(bounds[axis])
            };
            local.map(|v| ((v * f64::from(extent[axis])) as u32).min(extent[axis] - 1))
        });
        let count =
            u64::from(pixels[0][1] - pixels[0][0] + 1) * u64::from(pixels[1][1] - pixels[1][0] + 1);
        if count > u64::from(MAX_RANGE_TEXELS) {
            return UNKNOWN_STATE;
        }
        let mut min = 255;
        let mut max = 0;
        for y in pixels[1][0]..=pixels[1][1] {
            for x in pixels[0][0]..=pixels[0][1] {
                if let Some(triangle) = triangle {
                    let pixel = [x, y];
                    let box_bounds = std::array::from_fn(|axis| {
                        let mut bound = [f64::from(pixel[axis]), f64::from(pixel[axis] + 1)];
                        // Edge texels' preimages include the entire clamped tail. Testing the
                        // unclamped triangle against that preimage also covers clipped corners.
                        if sprite && pixel[axis] == 0 {
                            bound[0] = bound[0].min(bounds[axis][0] * f64::from(extent[axis]));
                        }
                        if sprite && pixel[axis] + 1 == extent[axis] {
                            bound[1] = bound[1].max(bounds[axis][1] * f64::from(extent[axis]));
                        }
                        bound
                    });
                    if !triangle_box_overlap(triangle, box_bounds) {
                        continue;
                    }
                }
                let alpha = self.alpha_range(x, y);
                min = min.min(alpha[0]);
                max = max.max(alpha[1]);
                if coverage(min, max, min_tint, max_tint) == UNKNOWN_STATE {
                    return UNKNOWN_STATE;
                }
            }
        }
        coverage(min, max, min_tint, max_tint)
    }
}
fn triangle_box_overlap(triangle: [[f64; 2]; 3], bounds: [[f64; 2]; 2]) -> bool {
    let area = (triangle[1][0] - triangle[0][0]) * (triangle[2][1] - triangle[0][1])
        - (triangle[1][1] - triangle[0][1]) * (triangle[2][0] - triangle[0][0]);
    if area == 0.0 {
        // Degenerate UVs can still map a nondegenerate geometric triangle to a line/point.
        return true;
    }
    let sign = area.signum();
    for edge in 0..3 {
        let a = triangle[edge];
        let b = triangle[(edge + 1) % 3];
        let normal = [-(b[1] - a[1]) * sign, (b[0] - a[0]) * sign];
        let support =
            std::array::from_fn::<_, 2, _>(|axis| bounds[axis][usize::from(normal[axis] >= 0.0)]);
        let distance = normal[0] * (support[0] - a[0]) + normal[1] * (support[1] - a[1]);
        if distance < -1e-10 * (normal[0].abs() + normal[1].abs()).max(1.0) {
            return false;
        }
    }
    true
}
fn fractional([min, max]: [f64; 2]) -> [f64; 2] {
    if min.floor() == max.floor() {
        [min - min.floor(), max - max.floor()]
    } else {
        [0.0, 1.0]
    }
}
fn coverage(min: u8, max: u8, tint_min: f64, tint_max: f64) -> u8 {
    // A small guard retains the shader for threshold-adjacent interpolated tint. UNORM8 alpha
    // with full tint is separated from 0.1 by much more than this rounding allowance.
    if f64::from(min) / 255.0 * tint_min >= 0.100001 {
        1
    } else if f64::from(max) / 255.0 * tint_max < 0.099999 {
        0
    } else {
        UNKNOWN_STATE
    }
}
fn bake(input: &[Option<Source<'_>>; 2], max_two: u32, max_four: u32) -> Result<Baked, u32> {
    let sources: Vec<_> = input.iter().flatten().collect();
    if sources.iter().any(|s| !s.available()) {
        return Err(UNKNOWN);
    }
    // Prove a uniform sheet before allocating or visiting its fine grid. A mixed range stops
    // at the first disagreement; high-span inputs keep the same bounded scan policy.
    let whole = [[0., 0.], [1., 0.], [0., 1.]];
    let uniform = sources[0].state(whole, false);
    if uniform != UNKNOWN_STATE
        && sources
            .iter()
            .skip(1)
            .all(|s| s.state(whole, false) == uniform)
    {
        return Err(if uniform == 0 { TRANSPARENT } else { OPAQUE });
    }
    let base = sources
        .iter()
        .map(|s| s.level())
        .max()
        .unwrap_or(0)
        .min(MAX_TWO_LEVEL);
    let two_level = base.min(max_two);
    let exact = sources.iter().all(|s| s.grid(two_level));
    let level = if exact {
        two_level
    } else {
        (base + 1).min(MAX_FOUR_LEVEL).min(max_four)
    };
    let n = 1 << level;
    let mut states = vec![0; n * n];
    let mut any_unknown = false;
    let uniform_tint = exact
        && sources
            .iter()
            .all(|s| s.flags == 0 || s.alpha.iter().all(|&a| a == s.alpha[0]));
    let inverse = 1.0 / n as f64;
    for row in 0..n {
        for cell in 0..2 * (n - row) - 1 {
            let (centroid, state) = if uniform_tint {
                // MC's normal full-alpha tint needs neither corner interpolation nor a
                // three-vertex support box after the shared integer grid proof.
                let offset = if cell & 1 == 0 { 1.0 / 3.0 } else { 2.0 / 3.0 };
                let centroid = [
                    (cell / 2) as f64 * inverse + offset * inverse,
                    row as f64 * inverse + offset * inverse,
                ];
                let state = sources[0].grid_state(centroid);
                let state = if sources
                    .iter()
                    .skip(1)
                    .all(|s| s.grid_state(centroid) == state)
                {
                    state
                } else {
                    UNKNOWN_STATE
                };
                (centroid, state)
            } else {
                let vertices = micro_vertices(row, cell, n);
                let state = sources[0].state(vertices, exact);
                let state = if sources
                    .iter()
                    .skip(1)
                    .all(|s| s.state(vertices, exact) == state)
                {
                    state
                } else {
                    UNKNOWN_STATE
                };
                let centroid: [f64; 2] =
                    std::array::from_fn(|i| vertices.iter().map(|v| v[i]).sum::<f64>() / 3.0);
                (centroid, state)
            };
            let index = bird_index(centroid[0], centroid[1], level);
            states[index] = state;
            any_unknown |= state == UNKNOWN_STATE;
        }
    }
    if states.iter().all(|&s| s == states[0]) {
        return Err(match states[0] {
            0 => TRANSPARENT,
            1 => OPAQUE,
            _ => UNKNOWN,
        });
    }
    if any_unknown && level > max_four {
        // Exact texel support can still have uncertain interpolated tint or directional sides.
        // Reclassify the coarser device-supported grid conservatively rather than mislabel it.
        return bake(input, max_two.min(max_four), max_four);
    }
    let four_state = any_unknown || level > max_two;
    let bits = if four_state { 2 } else { 1 };
    let mut packed = vec![0; (states.len() * bits).div_ceil(8)];
    for (index, state) in states.into_iter().enumerate() {
        packed[index * bits / 8] |= state << (index * bits % 8);
    }
    Ok(Baked {
        level: level as u16,
        format: if four_state { FOUR_STATE } else { TWO_STATE },
        states: packed,
    })
}
fn micro_vertices(row: usize, cell: usize, n: usize) -> [[f64; 2]; 3] {
    let x = (cell / 2) as f64 / n as f64;
    let y = row as f64 / n as f64;
    let d = 1.0 / n as f64;
    if cell & 1 == 0 {
        [[x, y], [x + d, y], [x, y + d]]
    } else {
        [[x + d, y], [x + d, y + d], [x, y + d]]
    }
}
/// Khronos' normative barycentric-to-bird-curve mapping, evaluated at cell interiors.
fn bird_index(u: f64, v: f64, level: u32) -> usize {
    let scale = 1_u32 << level;
    let fu = u.clamp(0.0, 1.0) * f64::from(scale);
    let fv = v.clamp(0.0, 1.0) * f64::from(scale);
    let mut iu = (fu as u32).min(scale - 1);
    let iv = (fv as u32).min(scale - 1);
    let iuv = iu + iv;
    if iuv >= scale {
        iu -= iuv - scale + 1;
    }
    let mut iw = !(iu + iv);
    if fu.fract() + fv.fract() >= 1.0 && iuv < scale - 1 {
        iw = iw.wrapping_sub(1);
    }
    let b0 = !(iu ^ iw) & (scale - 1);
    let t = (iu ^ iv) & b0;
    let mut f = t;
    f ^= f >> 1;
    f ^= f >> 2;
    f ^= f >> 4;
    f ^= f >> 8;
    let b1 = ((f ^ iu) & !b0) | t;
    (interleave(b0) | interleave(b1) << 1) as usize
}
fn interleave(mut x: u32) -> u32 {
    x = (x | x << 8) & 0x00ff00ff;
    x = (x | x << 4) & 0x0f0f0f0f;
    x = (x | x << 2) & 0x33333333;
    (x | x << 1) & 0x55555555
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::{
        TextureSampling,
        geometry::{CompiledQuad, TriangleView},
        surface::{SurfaceDetail, SurfaceLayer},
    };
    use std::sync::Arc;

    fn texture(n: u32, alpha: impl Fn(u32, u32) -> u8) -> Texture {
        let mut pixels = Vec::new();
        for y in 0..n {
            for x in 0..n {
                pixels.extend_from_slice(&[255, 255, 255, alpha(x, y)]);
            }
        }
        Texture {
            width: n,
            height: n,
            pixels: pixels.into(),
            region: Some([0, 0, n, n]),
            sampling: Some(Arc::new(TextureSampling {
                levels: vec![],
                next: [0, 0],
                blend: 0.0,
                coverage_frames: Arc::default(),
            })),
            material: None,
        }
    }
    fn face() -> SurfaceFace {
        SurfaceFace::from_quad(CompiledQuad {
            positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            color: [1.; 4],
            texture_id: 7,
            flags: 1,
        })
    }
    fn data(faces: &[SurfaceFace], textures: &BTreeMap<u32, Texture>) -> Option<Data> {
        let plan = packing::Plan::new(
            [packing::Input {
                triangles: TriangleView::Surfaces {
                    values: faces,
                    first: 0,
                    count: faces.len() * 2,
                },
                offset: None,
                flags: None,
            }],
            false,
        )
        .unwrap();
        build(&plan, &plan.groups[0], textures, 10, 8)
    }
    fn read(data: &Data, triangle: usize, bary: [f64; 2]) -> u8 {
        let index = data.indices[triangle];
        match index {
            TRANSPARENT => 0,
            OPAQUE => 1,
            UNKNOWN => UNKNOWN_STATE,
            index => {
                let b = data.triangles[index as usize];
                let bits = if b.format == TWO_STATE { 1 } else { 2 };
                let i = bird_index(bary[0], bary[1], u32::from(b.level));
                (data.blocks[b.offset as usize + i * bits / 8] >> (i * bits % 8))
                    & ((1 << bits) - 1)
            }
        }
    }
    fn verify_known(data: &Data, faces: &[SurfaceFace], textures: &BTreeMap<u32, Texture>) {
        // Several interior points in every cell, independent of the centroid used to bake.
        for (face_index, face) in faces.iter().enumerate() {
            for half in 0..2 {
                let key = key(face, half).unwrap();
                let mut source = key.0.source(textures);
                // The oracle consumes original source UVs, independently of cache key
                // normalization of repeated integer tile origins.
                source.uv = face.geometry.triangle(half).uvs.map(|uv| uv.map(f64::from));
                for row in 0..64 {
                    for cell in 0..2 * (64 - row) - 1 {
                        let v = micro_vertices(row, cell, 64);
                        for weights in [
                            [0.17, 0.31, 0.52],
                            [0.743, 0.131, 0.126],
                            [0.113, 0.731, 0.156],
                        ] {
                            let b = std::array::from_fn(|axis| {
                                (0..3).map(|i| v[i][axis] * weights[i]).sum::<f64>()
                            });
                            let state = read(data, face_index * 2 + half, b);
                            if state == UNKNOWN_STATE {
                                continue;
                            }
                            let uv = std::array::from_fn(|axis| {
                                source.uv[0][axis] * (1.0 - b[0] - b[1])
                                    + source.uv[1][axis] * b[0]
                                    + source.uv[2][axis] * b[1]
                            });
                            let tint = f64::from(source.alpha[0]) * (1.0 - b[0] - b[1])
                                + f64::from(source.alpha[1]) * b[0]
                                + f64::from(source.alpha[2]) * b[1];
                            let expected =
                                u8::from(f64::from(source.sample(uv)) / 255.0 * tint >= 0.1);
                            assert_eq!(state, expected, "face={face_index} half={half} bary={b:?}");
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn bird_curve_indices_cover_each_microtriangle_once_with_normative_level_one_order() {
        assert_eq!(bird_index(1.0 / 6.0, 1.0 / 6.0, 1), 0);
        assert_eq!(bird_index(1.0 / 3.0, 1.0 / 3.0, 1), 1);
        assert_eq!(bird_index(2.0 / 3.0, 1.0 / 6.0, 1), 2);
        assert_eq!(bird_index(1.0 / 6.0, 2.0 / 3.0, 1), 3);
        for level in 0..=7 {
            let n = 1 << level;
            let mut visited = vec![false; n * n];
            let mut area = 0.0;
            for row in 0..n {
                for cell in 0..2 * (n - row) - 1 {
                    let v = micro_vertices(row, cell, n);
                    let center: [f64; 2] =
                        std::array::from_fn(|axis| v.iter().map(|p| p[axis]).sum::<f64>() / 3.0);
                    let index = bird_index(center[0], center[1], level);
                    assert!(!visited[index]);
                    visited[index] = true;
                    area += ((v[1][0] - v[0][0]) * (v[2][1] - v[0][1])
                        - (v[1][1] - v[0][1]) * (v[2][0] - v[0][0]))
                        .abs()
                        * 0.5;
                }
            }
            assert!(visited.into_iter().all(|v| v));
            assert_eq!(area, 0.5);
        }
    }

    #[test]
    fn minecraft_grid_rotation_reflection_and_repeat_use_exact_two_state() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x * 3 + y * 7) % 5 < 2 { 0 } else { 255 }),
        )]);
        let mut faces = vec![face(), face(), face()];
        faces[1].geometry.uvs = [[1., 0.], [1., 1.], [0., 1.], [0., 0.]];
        faces[2].geometry.uvs = [[0., 0.], [4., 0.], [4., 2.], [0., 2.]];
        faces[2].repeat = Some(RepeatUv {
            origin: [1., 0.],
            du: [-1., 0.],
            dv: [0., 1.],
            axes: 3,
        });
        let data = data(&faces, &textures).unwrap();
        assert!(data.triangles.iter().all(|b| b.format == TWO_STATE));
        assert_eq!(data.stats.two_state_triangles, 6);
        verify_known(&data, &faces, &textures);
    }

    #[test]
    fn misaligned_mapping_retains_edge_unknowns_and_proves_other_microtriangles() {
        let textures =
            BTreeMap::from([(7, texture(4, |x, y| if (x + y) % 2 == 0 { 255 } else { 0 }))]);
        let mut f = face();
        f.geometry.uvs = [[0.07, 0.03], [0.93, 0.09], [0.86, 0.94], [0.02, 0.81]];
        let data = data(&[f.clone()], &textures).unwrap();
        assert!(data.triangles.iter().any(|b| b.format == FOUR_STATE));
        assert!((0..32).any(|i| read(&data, 0, [f64::from(i) / 64.0, 0.013]) == UNKNOWN_STATE));
        verify_known(&data, &[f], &textures);
    }

    #[test]
    fn pixel_overlap_excludes_unrelated_aabb_corner_and_keeps_intersecting_corner() {
        let make =
            |hole| BTreeMap::from([(7, texture(4, |x, y| if [x, y] == hole { 0 } else { 255 }))]);
        let k = SourceKey::new(
            7,
            1,
            [[0.01, 0.01], [0.99, 0.01], [0.01, 0.99]],
            [1.; 3],
            None,
        );
        let v = [[0., 0.], [1., 0.], [0., 1.]];
        assert_eq!(k.source(&make([3, 3])).state(v, false), 1);
        assert_eq!(k.source(&make([1, 1])).state(v, false), UNKNOWN_STATE);
    }

    #[test]
    fn alpha_threshold_tint_and_constant_mappings_keep_the_existing_cutout_rule() {
        assert_eq!(coverage(25, 25, 1., 1.), 0);
        assert_eq!(coverage(26, 26, 1., 1.), 1);
        assert_eq!(coverage(255, 255, 0.1, 0.1), UNKNOWN_STATE);
        let textures = BTreeMap::from([(7, texture(4, |_, _| 255))]);
        let mut f = face();
        f.geometry.uvs = [[0.375, 0.375]; 4];
        assert!(
            data(&[f.clone()], &textures)
                .unwrap()
                .indices
                .iter()
                .all(|&i| i == OPAQUE)
        );
        f.geometry.colors = [
            [1., 1., 1., 0.05],
            [1., 1., 1., 0.25],
            [1., 1., 1., 0.25],
            [1., 1., 1., 0.05],
        ];
        let data = data(&[f.clone()], &textures).unwrap();
        verify_known(&data, &[f], &textures);
    }

    #[test]
    fn bilateral_equal_coverage_uses_two_state_and_directional_disagreement_is_unknown() {
        let textures =
            BTreeMap::from([(7, texture(4, |x, y| if (x + y) % 2 == 0 { 255 } else { 0 }))]);
        let mut f = face();
        let layer = SurfaceLayer {
            material_thin: false,
            colors: f.geometry.colors,
            uvs: f.geometry.uvs,
            texture_id: 7,
            flags: 1,
            repeat: None,
            emission: Default::default(),
        };
        f.detail = Some(Arc::new(SurfaceDetail {
            mode: LayerMode::Bilateral,
            layer,
        }));
        assert!(
            data(&[f.clone()], &textures)
                .unwrap()
                .triangles
                .iter()
                .all(|b| b.format == TWO_STATE)
        );
        Arc::make_mut(f.detail.as_mut().unwrap()).layer.colors = [[1., 1., 1., 0.]; 4];
        let data = data(&[f], &textures).unwrap();
        assert!(data.triangles.iter().all(|b| b.format == FOUR_STATE));
        assert_eq!(read(&data, 0, [0.04, 0.04]), UNKNOWN_STATE);
    }

    #[test]
    fn animation_unknown_missing_texture_and_content_interning() {
        let mut t = texture(4, |x, y| if (x + y) % 2 == 0 { 255 } else { 0 });
        let mut textures = BTreeMap::from([(7, t.clone())]);
        let result = data(&[face(), face()], &textures).unwrap();
        assert_eq!(result.stats.cache_hits, 2);
        assert_eq!(&result.indices[..2], &result.indices[2..]);
        assert!(data(&[face()], &BTreeMap::new()).is_none());
        Arc::make_mut(t.sampling.as_mut().unwrap()).blend = 0.5;
        textures.insert(7, t);
        assert!(data(&[face()], &textures).is_none());
    }

    #[test]
    fn atlas_window_uses_backing_stride_and_raw_texture_wraps() {
        let mut t = texture(8, |x, y| if (x + 3 * y) % 3 == 0 { 0 } else { 255 });
        t.region = Some([2, 1, 4, 4]);
        Arc::make_mut(t.sampling.as_mut().unwrap()).next = [2, 1];
        let textures = BTreeMap::from([(7, t.clone())]);
        verify_known(&data(&[face()], &textures).unwrap(), &[face()], &textures);
        t.region = None;
        t.sampling = None;
        let textures = BTreeMap::from([(7, t)]);
        let mut f = face();
        f.geometry.uvs = [[-1., -1.], [1., -1.], [1., 1.], [-1., 1.]];
        let data = data(&[f.clone()], &textures).unwrap();
        assert!(data.triangles.iter().all(|b| b.format == TWO_STATE));
        verify_known(&data, &[f], &textures);
    }

    #[test]
    fn format_limits_and_known_only_texture_dependencies_are_preserved() {
        let mut animated = texture(4, |_, _| 255);
        Arc::make_mut(animated.sampling.as_mut().unwrap()).blend = 0.5;
        let textures = BTreeMap::from([
            (7, texture(4, |x, y| if (x + y) % 2 == 0 { 255 } else { 0 })),
            (8, animated),
        ]);
        let mut f = face();
        let mut other = face();
        other.geometry.texture_id = 8;
        let result = data(&[f.clone(), other], &textures).unwrap();
        assert_eq!(result.textures, BTreeSet::from([7]));
        assert_eq!(result.stats.unknown_triangles, 2);
        f.geometry.colors[0][3] = 0.02;
        f.geometry.colors[1][3] = 0.8;
        let faces = [f];
        let plan = packing::Plan::new(
            [packing::Input {
                triangles: TriangleView::Surfaces {
                    values: &faces,
                    first: 0,
                    count: 2,
                },
                offset: None,
                flags: None,
            }],
            false,
        )
        .unwrap();
        for two in 0..=4 {
            for four in 0..=4 {
                if let Some(result) = build(&plan, &plan.groups[0], &textures, two, four) {
                    for b in result.triangles {
                        assert!(
                            u32::from(b.level) <= if b.format == TWO_STATE { two } else { four }
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn packed_half_quad_uses_the_second_triangle_corner_order() {
        let textures = BTreeMap::from([(
            7,
            texture(8, |x, y| if (x * 3 + y) % 7 < 3 { 255 } else { 0 }),
        )]);
        let faces = [face()];
        let whole = data(&faces, &textures).unwrap();
        let plan = packing::Plan::new(
            [packing::Input {
                triangles: TriangleView::Surfaces {
                    values: &faces,
                    first: 1,
                    count: 1,
                },
                offset: None,
                flags: None,
            }],
            false,
        )
        .unwrap();
        let half = build(&plan, &plan.groups[0], &textures, 8, 8).unwrap();
        assert_eq!(half.indices.len(), 2);
        for row in 0..16 {
            for cell in 0..2 * (16 - row) - 1 {
                let v = micro_vertices(row, cell, 16);
                let b: [f64; 2] =
                    std::array::from_fn(|axis| v.iter().map(|p| p[axis]).sum::<f64>() / 3.0);
                assert_eq!(read(&half, 0, b), read(&whole, 1, b));
            }
        }
    }

    #[test]
    fn high_resolution_rectangular_dyadic_resource_pack_keeps_two_state_and_memoizes() {
        let mut t = texture(512, |x, y| if (x + y * 3) % 7 < 3 { 255 } else { 0 });
        t.height = 256;
        t.pixels = t.pixels[..512 * 256 * 4].into();
        t.region = Some([0, 0, 512, 256]);
        let textures = BTreeMap::from([(7, t)]);
        let faces = [face(), face()];
        let result = data(&faces, &textures).unwrap();
        assert!(
            result
                .triangles
                .iter()
                .all(|b| b.format == TWO_STATE && b.level == 9)
        );
        assert_eq!(result.stats.cache_hits, 2);
        assert_eq!(&result.indices[..2], &result.indices[2..]);
        verify_known(&result, &faces, &textures);
    }

    #[test]
    fn complete_animation_frames_prove_stable_pixels_and_retain_only_changing_pixels_unknown() {
        let mut t = texture(8, |x, y| if (x % 4 + y % 4) % 3 == 0 { 255 } else { 0 });
        t.region = Some([0, 0, 4, 4]);
        let sampling = Arc::make_mut(t.sampling.as_mut().unwrap());
        sampling.coverage_frames = Arc::from([[0, 0], [4, 0], [0, 4], [4, 4]]);
        sampling.next = [4, 0];
        sampling.blend = 0.73;
        let mut textures = BTreeMap::from([(7, t.clone())]);
        let stable = data(&[face()], &textures).unwrap();
        assert!(stable.triangles.iter().all(|b| b.format == TWO_STATE));
        let mut pixels = t.pixels.to_vec();
        pixels[(4 * 8 + 4) * 4 + 3] = 0;
        t.pixels = pixels.into();
        textures.insert(7, t.clone());
        let changing = data(&[face()], &textures).unwrap();
        assert!(changing.triangles.iter().all(|b| b.format == FOUR_STATE));
        assert_eq!(read(&changing, 0, [0.02, 0.03]), UNKNOWN_STATE);
        // Known states hold for every declared frame; interpolation and RGBA8 quantization
        // stay inside these byte extrema as well.
        for origin in [[0, 0], [4, 0], [0, 4], [4, 4]] {
            t.region = Some([origin[0], origin[1], 4, 4]);
            textures.insert(7, t.clone());
            verify_known(&changing, &[face()], &textures);
        }
    }

    #[test]
    fn one_axis_repeated_reflected_grid_preserves_the_other_axes_clamped_tail() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x * 3 + y * 7) % 5 < 2 { 0 } else { 255 }),
        )]);
        let mut faces = [face(), face()];
        faces[0].geometry.uvs = [[-1., -1.], [3., -1.], [3., 1.], [-1., 1.]];
        faces[0].repeat = Some(RepeatUv {
            origin: [1., 0.],
            du: [-1., 0.],
            dv: [0., 1.],
            axes: 1,
        });
        faces[1].geometry.uvs = [[-1., -1.], [1., -1.], [1., 3.], [-1., 3.]];
        faces[1].repeat = Some(RepeatUv {
            origin: [0., 1.],
            du: [1., 0.],
            dv: [0., -1.],
            axes: 2,
        });
        let result = data(&faces, &textures).unwrap();
        assert!(result.triangles.iter().all(|b| b.format == TWO_STATE));
        verify_known(&result, &faces, &textures);
    }

    #[test]
    fn vertex_alpha_alone_can_prove_transparency_without_animation_or_texture_dependencies() {
        let mut t = texture(4, |_, _| 255);
        Arc::make_mut(t.sampling.as_mut().unwrap()).blend = 0.5;
        let textures = BTreeMap::from([(7, t)]);
        let mut f = face();
        f.geometry.colors = [[1., 1., 1., 0.05]; 4];
        let result = data(&[f], &textures).unwrap();
        assert_eq!(result.indices, [TRANSPARENT; 2]);
        assert!(result.textures.is_empty());
    }

    #[test]
    fn mathematical_bird_tie_does_not_claim_shader_texel_boundary_equivalence() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x + 3 * y) % 7 < 3 { 255 } else { 0 }),
        )]);
        let f = face();
        let result = data(std::slice::from_ref(&f), &textures).unwrap();
        let bary = [0.015625, 0.1875];
        // Half 1 reconstructs UV = [1 - u - v, 1 - v]. At v=3/16 the shader's
        // floor selects texel row 13 while the normative bird tie owns row 12's cell.
        let source = key(&f, 1).unwrap().0.source(&textures);
        let reference = source.sample([1.0 - bary[0] - bary[1], 1.0 - bary[1]]);
        assert_eq!(read(&result, 1, bary), 0);
        assert_eq!(reference, 255);
        println!(
            "math boundary: half=1 bary={bary:?} bird={} OMM=transparent shader texel=[12,13] alpha={reference}",
            bird_index(bary[0], bary[1], 4)
        );

        // Cyclic vertex orders preserve winding. Ordinary quads can have a favorable order;
        // mirrored mappings still need a negative axis for the triangle lacking the minimum
        // UV corner. Retain diagnostics rather than silently treating ties as interior proof.
        for mirrored in [false, true] {
            for half in 0..2 {
                let uv = f
                    .geometry
                    .triangle(half)
                    .uvs
                    .map(|uv| if mirrored { [1.0 - uv[0], uv[1]] } else { uv });
                for rotation in 0..3 {
                    let k = SourceKey::new(
                        7,
                        1,
                        std::array::from_fn(|i| uv[(i + rotation) % 3]),
                        [1.; 3],
                        None,
                    );
                    let input = [Some(k.source(&textures)), None];
                    let b = bake(&input, 10, 8).unwrap();
                    let source = input[0].as_ref().unwrap();
                    let mut mismatches = 0;
                    for row in 1..16 {
                        for column in 1..16 - row {
                            let points = [
                                [(f64::from(column) + 0.3125) / 16.0, f64::from(row) / 16.0],
                                [f64::from(column) / 16.0, (f64::from(row) + 0.3125) / 16.0],
                                [
                                    (f64::from(column) + 0.3125) / 16.0,
                                    (f64::from(row) + 0.6875) / 16.0,
                                ],
                            ];
                            for point in points {
                                let index = bird_index(point[0], point[1], u32::from(b.level));
                                let state = (b.states[index / 8] >> (index % 8)) & 1;
                                let uv = std::array::from_fn(|axis| {
                                    source.uv[0][axis] * (1.0 - point[0] - point[1])
                                        + source.uv[1][axis] * point[0]
                                        + source.uv[2][axis] * point[1]
                                });
                                mismatches +=
                                    usize::from(state != u8::from(source.sample(uv) >= 26));
                            }
                        }
                    }
                    println!(
                        "math boundary orientation: mirrored={mirrored} half={half} cyclic_rotation={rotation} mismatches={mismatches}"
                    );
                    if mirrored && half == 1 {
                        assert!(mismatches > 0);
                    }
                }
            }
        }
    }

    #[test]
    fn repeated_integer_texel_crops_rotation_and_mirror_use_two_state() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x * 3 + y * 7) % 5 < 2 { 255 } else { 0 }),
        )]);
        let mut faces = [face(), face(), face()];
        faces[0].geometry.uvs = [[17.5, -8.5], [33.5, -8.5], [33.5, 7.5], [17.5, 7.5]];
        faces[0].repeat = Some(RepeatUv {
            origin: [0.25, 0.75],
            du: [0.125, 0.],
            dv: [0., -0.5],
            axes: 3,
        });
        faces[1].geometry.uvs = [[0., -0.5], [16., -0.5], [16., 1.5], [0., 1.5]];
        faces[1].repeat = Some(RepeatUv {
            origin: [0.25, 0.25],
            du: [0.125, 0.],
            dv: [0., 0.5],
            axes: 1,
        });
        faces[2].geometry.uvs = [[0., 0.], [8., 0.], [8., 8.], [0., 8.]];
        faces[2].repeat = Some(RepeatUv {
            origin: [0.25, 0.25],
            du: [0., 0.5],
            dv: [0.125, 0.],
            axes: 3,
        });
        let result = data(&faces, &textures).unwrap();
        assert!(result.triangles.iter().all(|b| b.format == TWO_STATE));
        verify_known(&result, &faces, &textures);
        let mut unsupported = faces[2].clone();
        unsupported.repeat.as_mut().unwrap().origin[0] = 0.27;
        assert!(!key(&unsupported, 0).unwrap().0.source(&textures).grid(8));
        unsupported.repeat.as_mut().unwrap().origin[0] = 0.25;
        unsupported.repeat.as_mut().unwrap().du[0] = 0.1;
        assert!(!key(&unsupported, 0).unwrap().0.source(&textures).grid(8));
    }

    #[test]
    fn update_cache_shares_proofs_without_sharing_blas_indices_and_preserves_repeat_phase() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x + y * 3) % 7 < 3 { 255 } else { 0 }),
        )]);
        let mut f = face();
        f.geometry.uvs = [[0.5, 0.5], [4.5, 0.5], [4.5, 4.5], [0.5, 4.5]];
        f.repeat = Some(RepeatUv {
            origin: [0.25, 0.25],
            du: [0.5, 0.],
            dv: [0., 0.5],
            axes: 3,
        });
        let shifted = {
            let mut f = f.clone();
            f.geometry.uvs = f.geometry.uvs.map(|uv| [uv[0] + 32., uv[1] + 32.]);
            f
        };
        let first = [f];
        let second = [shifted];
        let make_plan = |faces| {
            packing::Plan::new(
                [packing::Input {
                    triangles: TriangleView::Surfaces {
                        values: faces,
                        first: 0,
                        count: 2,
                    },
                    offset: None,
                    flags: None,
                }],
                false,
            )
            .unwrap()
        };
        let plans = [make_plan(&first), make_plan(&second)];
        let mut cache = Cache::new(&textures, 10, 8);
        let a = cache.build(&plans[0], &plans[0].groups[0]).unwrap();
        assert_eq!(cache.stats(), [2, 0]);
        let b = cache.build(&plans[1], &plans[1].groups[0]).unwrap();
        assert_eq!(cache.stats(), [2, 2]);
        assert_eq!(a.blocks, b.blocks);
        assert_eq!(a.indices, b.indices);
        verify_known(&b, &second, &textures);
    }

    #[test]
    fn update_cache_budget_preserves_local_reuse_and_caches_unknown_proofs() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x + y * 3) % 7 < 3 { 255 } else { 0 }),
        )]);
        let faces = [face(), face()];
        let plan = packing::Plan::new(
            [packing::Input {
                triangles: TriangleView::Surfaces {
                    values: &faces,
                    first: 0,
                    count: 4,
                },
                offset: None,
                flags: None,
            }],
            false,
        )
        .unwrap();
        let mut cache = Cache::new(&textures, 10, 8);
        cache.budget = 0;
        let a = cache.build(&plan, &plan.groups[0]).unwrap();
        let b = cache.build(&plan, &plan.groups[0]).unwrap();
        assert!(cache.proofs.is_empty());
        assert_eq!(cache.bytes, 0);
        assert_eq!(cache.stats(), [4, 0]);
        assert_eq!(a.stats.cache_hits, 2);
        assert_eq!(a.blocks, b.blocks);
        let mut partial = Cache::new(&textures, 10, 8);
        partial.budget = CACHE_ENTRY_BYTES + 32;
        let a = partial.build(&plan, &plan.groups[0]).unwrap();
        let b = partial.build(&plan, &plan.groups[0]).unwrap();
        assert_eq!(partial.proofs.len(), 1);
        assert_eq!(partial.bytes, partial.budget);
        assert_eq!(partial.stats(), [3, 1]);
        assert_eq!(a.blocks, b.blocks);
        assert_eq!(a.indices, b.indices);
        let mut animated = textures[&7].clone();
        Arc::make_mut(animated.sampling.as_mut().unwrap()).blend = 0.5;
        let unknown_textures = BTreeMap::from([(7, animated)]);
        let mut cache = Cache::new(&unknown_textures, 10, 8);
        assert!(cache.build(&plan, &plan.groups[0]).is_none());
        assert!(cache.build(&plan, &plan.groups[0]).is_none());
        assert_eq!(cache.stats(), [2, 2]);
        assert_eq!(cache.bytes, 2 * CACHE_ENTRY_BYTES);
    }

    #[test]
    fn uniform_alpha_grid_loop_matches_the_full_support_classifier() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (x + 3 * y) % 7 < 3 { 255 } else { 0 }),
        )]);
        for alpha in [1.0, 0.2, 0.05, 0.1] {
            let k = SourceKey::new(
                7,
                1,
                [[0., 0.], [8., 0.], [8., 8.]],
                [alpha; 3],
                Some(RepeatUv {
                    origin: [0.25, 0.75],
                    du: [0.125, 0.],
                    dv: [0., -0.5],
                    axes: 3,
                }),
            );
            let source = k.source(&textures);
            let level = source.level();
            assert!(source.grid(level));
            let n = 1 << level;
            for row in 0..n {
                for cell in 0..2 * (n - row) - 1 {
                    let vertices = micro_vertices(row, cell, n);
                    let centroid = std::array::from_fn(|axis| {
                        vertices.iter().map(|v| v[axis]).sum::<f64>() / 3.0
                    });
                    assert_eq!(source.grid_state(centroid), source.state(vertices, true));
                }
            }
        }
    }

    fn template_data(templates: &Templates, faces: &[SurfaceFace]) -> Option<Data> {
        let plan = packing::Plan::new(
            [packing::Input {
                triangles: TriangleView::Surfaces {
                    values: faces,
                    first: 0,
                    count: faces.len() * 2,
                },
                offset: None,
                flags: None,
            }],
            false,
        )
        .unwrap();
        let binding = templates.bind(&plan, &plan.groups[0])?;
        Some(Data {
            blocks: templates.data.blocks.clone(),
            triangles: templates.data.triangles.clone(),
            indices: binding.indices,
            textures: binding.textures,
            stats: binding.stats,
        })
    }
    fn oriented_uv(reflected: bool, rotation: usize) -> [[f32; 2]; 4] {
        [[0., 0.], [1., 0.], [1., 1.], [0., 1.]].map(|v| {
            let v = if reflected { [1. - v[0], v[1]] } else { v };
            match rotation {
                0 => v,
                1 => [1. - v[1], v[0]],
                2 => [1. - v[0], 1. - v[1]],
                _ => [v[1], 1. - v[0]],
            }
        })
    }

    #[test]
    fn resource_templates_bind_all_square_sizes_orientations_halves_and_repeat_transforms() {
        let textures = BTreeMap::from([(
            7,
            texture(16, |x, y| if (3 * x + 7 * y) % 11 < 4 { 255 } else { 0 }),
        )]);
        let templates = Templates::prepare(&textures, 10, 8);
        assert!(templates.data.indices.is_empty());
        // Half 1 has the same ordered periodic coordinates as a rotated half 0.
        assert_eq!(templates.lookup.len(), 24);
        let mut faces = Vec::new();
        for reflected in [false, true] {
            for rotation in 0..4 {
                let mut ordinary = face();
                ordinary.geometry.uvs = oriented_uv(reflected, rotation);
                faces.push(ordinary);
                for size in [1., 2., 4.] {
                    for mapping_reflected in [false, true] {
                        for mapping_rotation in 0..4 {
                            let mapping = oriented_uv(mapping_reflected, mapping_rotation);
                            let mut f = face();
                            f.geometry.uvs = oriented_uv(reflected, rotation)
                                .map(|v| [17. + v[0] * size, -31. + v[1] * size]);
                            f.repeat = Some(RepeatUv {
                                origin: mapping[0],
                                du: std::array::from_fn(|a| mapping[1][a] - mapping[0][a]),
                                dv: std::array::from_fn(|a| mapping[3][a] - mapping[0][a]),
                                axes: 3,
                            });
                            faces.push(f);
                        }
                    }
                }
            }
        }
        let data = template_data(&templates, &faces).unwrap();
        assert!(
            data.indices
                .iter()
                .all(|&i| i < templates.data.triangles.len() as u32)
        );
        assert_eq!(data.stats.two_state_triangles, faces.len() * 2);
        verify_known(&data, &faces, &textures);
    }

    #[test]
    fn resource_bind_reuses_global_indices_after_movement_without_texture_owners_or_new_blocks() {
        let t = texture(16, |x, y| if (x + 3 * y) % 7 < 3 { 255 } else { 0 });
        let owner = Arc::downgrade(&t.pixels);
        let textures = BTreeMap::from([(7, t)]);
        let templates = Templates::prepare(&textures, 10, 8);
        let original = template_data(&templates, &[face()]).unwrap();
        let packed = templates.data.blocks.clone();
        let count = templates.data.triangles.len();
        let mut f = face();
        f.repeat = Some(identity_repeat());
        for cell in 0..128 {
            f.geometry.positions = f
                .geometry
                .positions
                .map(|p| [p[0] + 64., p[1] - 128., p[2] + 32.]);
            f.geometry.uvs =
                oriented_uv(false, 0).map(|v| [v[0] + cell as f32 * 64., v[1] + cell as f32 * 64.]);
            assert_eq!(
                template_data(&templates, &[f.clone()]).unwrap().indices,
                original.indices
            );
        }
        drop(textures);
        assert!(
            owner.upgrade().is_none(),
            "templates retain extrema and packed states, not source pixel owners"
        );
        assert_eq!(
            template_data(&templates, &[f]).unwrap().indices,
            original.indices
        );
        assert_eq!(templates.data.blocks, packed);
        assert_eq!(templates.data.triangles.len(), count);
        assert!(templates.dependencies_changed(&BTreeSet::from([7])));
        assert!(!templates.dependencies_changed(&BTreeSet::from([23])));
    }

    #[test]
    fn resource_template_misses_keep_crop_clamp_tint_and_bilateral_shader_fallbacks() {
        let t = texture(16, |x, y| if (3 * x + 7 * y) % 11 < 4 { 255 } else { 0 });
        let mut raw = t.clone();
        raw.region = None;
        let textures = BTreeMap::from([(7, t.clone()), (8, t), (9, raw)]);
        let templates = Templates::prepare(&textures, 10, 8);
        assert!(!templates.contains_texture(9));
        let mut faces = vec![face(); 7];
        faces[1].geometry.uvs = [[0., 0.], [4., 0.], [4., 2.], [0., 2.]];
        faces[1].repeat = Some(identity_repeat());
        faces[2].geometry.uvs = [[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]];
        faces[3].repeat = Some(RepeatUv {
            origin: [0.25; 2],
            du: [0.125, 0.],
            dv: [0., 0.5],
            axes: 3,
        });
        faces[4].geometry.colors = [[1., 1., 1., 0.2]; 4];
        faces[5].geometry.uvs = [[-1., 0.], [1., 0.], [1., 1.], [-1., 1.]];
        faces[6].geometry.texture_id = 9;
        let data = template_data(&templates, &faces).unwrap();
        assert_eq!(&data.indices[2..], &[UNKNOWN; 12]);
        assert_eq!(data.textures, BTreeSet::from([7]));
        let mut f = face();
        f.detail = Some(Arc::new(SurfaceDetail {
            mode: LayerMode::Bilateral,
            layer: SurfaceLayer {
                material_thin: false,
                colors: f.geometry.colors,
                uvs: f.geometry.uvs,
                texture_id: 8,
                flags: 1,
                repeat: None,
                emission: Default::default(),
            },
        }));
        let data = template_data(&templates, &[f.clone()]).unwrap();
        assert!(
            data.indices
                .iter()
                .all(|&i| i < templates.data.triangles.len() as u32)
        );
        assert_eq!(data.textures, BTreeSet::from([7, 8]));
        Arc::make_mut(f.detail.as_mut().unwrap()).layer.colors = [[1., 1., 1., 0.]; 4];
        assert!(template_data(&templates, &[f]).is_none());
    }

    #[test]
    fn resource_uniform_specials_prove_mapping_independent_tint_and_ignore_entity_resources() {
        let textures = BTreeMap::from([(7, texture(4, |_, _| 255)), (8, texture(4, |_, _| 0))]);
        let templates = Templates::prepare(&textures, 10, 8);
        assert!(templates.data.triangles.is_empty());
        let mut f = face();
        f.geometry.uvs = [[0.17, 2.4]; 4];
        f.geometry.colors = [[1., 1., 1., 0.2]; 4];
        assert_eq!(
            template_data(&templates, &[f.clone()]).unwrap().indices,
            [OPAQUE; 2]
        );
        f.geometry.colors[0][3] = 0.05;
        assert!(template_data(&templates, &[f.clone()]).is_none());
        f.geometry.texture_id = 8;
        assert_eq!(
            template_data(&templates, &[f.clone()]).unwrap().indices,
            [TRANSPARENT; 2]
        );
        f.geometry.texture_id = 999;
        f.geometry.colors = [[1., 1., 1., 0.05]; 4];
        let data = template_data(&templates, &[f]).unwrap();
        assert_eq!(data.indices, [TRANSPARENT; 2]);
        assert!(data.textures.is_empty());
    }

    #[test]
    fn resource_npot_and_complete_animation_prepare_four_state_once() {
        let mut animated = texture(8, |x, y| if (x % 4 + y % 4) % 3 == 0 { 255 } else { 0 });
        animated.region = Some([0, 0, 4, 4]);
        let sampling = Arc::make_mut(animated.sampling.as_mut().unwrap());
        sampling.coverage_frames = Arc::from([[0, 0], [4, 0], [0, 4], [4, 4]]);
        sampling.next = [4, 0];
        sampling.blend = 0.73;
        let mut textures = BTreeMap::from([
            (7, animated.clone()),
            (
                8,
                texture(7, |x, y| if (3 * x + 7 * y) % 11 < 4 { 255 } else { 0 }),
            ),
        ]);
        let stable = Templates::prepare(&textures, 10, 8);
        assert_eq!(
            template_data(&stable, &[face()])
                .unwrap()
                .stats
                .two_state_triangles,
            2
        );
        let mut npot = face();
        npot.geometry.texture_id = 8;
        let data = template_data(&stable, &[npot.clone()]).unwrap();
        assert_eq!(data.stats.four_state_triangles, 2);
        verify_known(&data, &[npot], &textures);
        let mut pixels = animated.pixels.to_vec();
        pixels[(4 * 8 + 4) * 4 + 3] = 0;
        animated.pixels = pixels.into();
        textures.insert(7, animated.clone());
        let changing = Templates::prepare(&textures, 10, 8);
        let data = template_data(&changing, &[face()]).unwrap();
        assert_eq!(data.stats.four_state_triangles, 2);
        for origin in [[0, 0], [4, 0], [0, 4], [4, 4]] {
            animated.region = Some([origin[0], origin[1], 4, 4]);
            textures.insert(7, animated.clone());
            verify_known(&data, &[face()], &textures);
        }
    }
}
