//! Version adaptation and prototype defaults live here, not in the Java field router.
use crate::{
    fluid::{Fluid, FluidMaterial},
    shape::{Face, FaceId},
    wire::Reader,
};
use prime_scene::Triangle;
use std::collections::HashMap;

#[derive(Clone, Default, PartialEq)]
pub(crate) struct State {
    pub id: u32,
    pub flags: u32,
    pub model: u32,
    pub name: String,
    pub faces: [FaceId; 6],
    pub support: u32,
    pub fluid: Fluid,
}
impl State {
    pub fn air(&self) -> bool {
        self.flags & 1 != 0 || self.flags & 16 != 0 && self.fluid.kind == 0
    }

    pub fn full(&self) -> bool {
        self.flags & 4 != 0
    }
    pub fn same_block_culls(&self) -> bool {
        self.name == "minecraft:water"
            || self.name == "minecraft:lava"
            || self.name.ends_with("glass")
    }
    pub fn same_boundary(&self, other: &Self) -> bool {
        self.faces == other.faces
            && self.fluid == other.fluid
            && self.flags & 228 == other.flags & 228
            && self.support == other.support
            && (!(self.same_block_culls() || other.same_block_culls()) || self.name == other.name)
    }
}
#[derive(Clone, PartialEq)]
pub(crate) struct Quad {
    pub positions: [[f32; 3]; 4],
    pub uvs: [[f32; 2]; 4],
    pub face: u32,
    pub tint: i32,
    pub layer: usize,
}
#[derive(PartialEq)]
pub(crate) enum Model {
    Unknown,
    Mesh(Vec<Quad>),
    Weighted(Vec<(u32, u32)>, u32),
    Multipart(Vec<u32>),
    Alias(u32),
}
#[derive(Default)]
pub(crate) struct Catalog {
    pub states: HashMap<u32, State>,
    pub models: HashMap<u32, Model>,
    pub faces: HashMap<FaceId, Face>,
    pub fluids: HashMap<u32, FluidMaterial>,
    pub fluid_math: crate::fluid::FluidMath,
    face_masks: HashMap<u32, u32>,
}

pub(crate) fn state(r: &mut Reader<'_>) -> Result<(u32, State), String> {
    let id = r.u32()?;
    let flags = r.u32()?;
    let model = r.u32()?;
    if flags & !255 != 0 {
        return Err("invalid raw state flags".into());
    }
    Ok((
        id,
        State {
            id,
            flags,
            model,
            name: r.string()?,
            faces: [
                FaceId(r.u32()?),
                FaceId(r.u32()?),
                FaceId(r.u32()?),
                FaceId(r.u32()?),
                FaceId(r.u32()?),
                FaceId(r.u32()?),
            ],
            support: r.u32()?,
            fluid: Fluid::read(r)?,
        },
    ))
}
pub(crate) fn model(r: &mut Reader<'_>) -> Result<(u32, Model), String> {
    let id = r.u32()?;
    if id == 0 {
        return Err("zero model definition".into());
    }
    let model = match r.u32()? {
        0 => Model::Unknown,
        1 => {
            let count = r.count(92)?;
            let mut quads = Vec::with_capacity(count);
            for _ in 0..count {
                let face = r.u32()?;
                let tint = r.i32()?;
                let layer = r.u32()?;
                if face > 6 || layer > 2 || tint < -1 {
                    return Err("invalid raw quad attributes".into());
                }
                let mut q = Quad {
                    positions: [[0.0; 3]; 4],
                    uvs: [[0.0; 2]; 4],
                    face,
                    tint,
                    layer: layer as usize,
                };
                for i in 0..4 {
                    q.positions[i] = [r.f32()?, r.f32()?, r.f32()?];
                    if q.positions[i].iter().any(|p| p.abs() > 4096.0) {
                        return Err("model coordinate out of range".into());
                    }
                    // MC UVPair packs U in the high IEEE754 half, V in the low half.
                    let uv = r.u64()?;
                    q.uvs[i] = [f32::from_bits((uv >> 32) as u32), f32::from_bits(uv as u32)];
                    if q.uvs[i].iter().any(|p| !p.is_finite()) {
                        return Err("nonfinite model UV".into());
                    }
                }
                quads.push(q);
            }
            Model::Mesh(quads)
        }
        2 => {
            let n = r.count(8)?;
            let mut items = Vec::with_capacity(n);
            let mut total = 0u32;
            for _ in 0..n {
                let weight = r.u32()?;
                let model = r.u32()?;
                total = total.checked_add(weight).ok_or("model weight overflow")?;
                items.push((weight, model));
            }
            if total == 0 || total > i32::MAX as u32 {
                return Err("invalid total model weight".into());
            }
            Model::Weighted(items, total)
        }
        3 => {
            let n = r.count(4)?;
            let mut children = Vec::with_capacity(n);
            for _ in 0..n {
                children.push(r.u32()?);
            }
            Model::Multipart(children)
        }
        4 => Model::Alias(r.u32()?),
        _ => return Err("unknown raw model layout".into()),
    };
    Ok((id, model))
}

pub(crate) struct Random(u64);
impl Random {
    pub fn new(seed: i64) -> Self {
        Self((seed as u64 ^ 0x5deece66d) & ((1u64 << 48) - 1))
    }
    fn next(&mut self, bits: u32) -> u32 {
        self.0 = self.0.wrapping_mul(0x5deece66d).wrapping_add(11) & ((1u64 << 48) - 1);
        (self.0 >> (48 - bits)) as u32
    }
    pub fn long(&mut self) -> i64 {
        ((i64::from(self.next(32) as i32)) << 32).wrapping_add(i64::from(self.next(32) as i32))
    }
    pub fn bound(&mut self, bound: u32) -> u32 {
        if bound.is_power_of_two() {
            return ((u64::from(bound) * u64::from(self.next(31))) >> 31) as u32;
        }
        loop {
            let bits = self.next(31);
            let v = bits % bound;
            if (bits.wrapping_sub(v).wrapping_add(bound - 1) as i32) >= 0 {
                return v;
            }
        }
    }
}
pub(crate) fn position_seed(x: i32, y: i32, z: i32) -> i64 {
    let n =
        i64::from(x.wrapping_mul(3129871)) ^ i64::from(z).wrapping_mul(116129781) ^ i64::from(y);
    n.wrapping_mul(n)
        .wrapping_mul(42317861)
        .wrapping_add(n.wrapping_mul(11))
        >> 16
}
#[derive(Clone, Copy, Default)]
pub(crate) struct Hacks {
    pub model: u64,
    pub tint: u64,
    pub offset: u64,
    pub fluid: u64,
}
impl std::ops::AddAssign for Hacks {
    fn add_assign(&mut self, other: Self) {
        self.model += other.model;
        self.tint += other.tint;
        self.offset += other.offset;
        self.fluid += other.fluid;
    }
}
impl Catalog {
    pub fn covers(&self, occluder: FaceId, source: FaceId) -> bool {
        if occluder.0 == 0 {
            return false;
        }
        if occluder.0 == 1 {
            return true;
        }
        if source.0 == 0 {
            return false;
        }
        if occluder == source {
            return true;
        }
        let occluder = &self.faces[&occluder];
        if source.0 == 1 {
            occluder.covers_height(1.)
        } else {
            occluder.covers(&self.faces[&source])
        }
    }
    pub fn fluid_occluded(&self, state: &State, direction: usize, height: f32) -> bool {
        if direction == 1 && height < 1. {
            return false;
        }
        let id = state.faces[direction ^ 1];
        let height = if direction < 2 { 1. } else { height };
        match id.0 {
            0 => false,
            1 => true,
            _ => self.faces[&id].covers_height(height),
        }
    }
    #[cfg(test)]
    pub fn hidden(&self, state: &State, neighbor: &State, face: usize) -> bool {
        (state.same_block_culls() && state.name == neighbor.name)
            || self.covers(neighbor.faces[face ^ 1], state.faces[face])
    }
    // Derived only when resource definitions change. Conservative unions retain weighted choices;
    // cycles/depth limits keep every face eligible and continue through the existing fallback.
    pub fn prepare(&mut self) {
        fn mask(
            id: u32,
            models: &HashMap<u32, Model>,
            cache: &mut HashMap<(u32, usize), u32>,
            depth: usize,
        ) -> u32 {
            if depth > 64 {
                return 127;
            }
            if let Some(&value) = cache.get(&(id, depth)) {
                return value;
            }
            let value = match models.get(&id) {
                Some(Model::Mesh(quads)) => quads.iter().fold(0, |m, q| m | (1 << q.face)),
                Some(Model::Alias(child)) => mask(*child, models, cache, depth + 1),
                Some(Model::Weighted(children, _)) => children
                    .iter()
                    .fold(0, |m, (_, id)| m | mask(*id, models, cache, depth + 1)),
                Some(Model::Multipart(children)) => children
                    .iter()
                    .fold(0, |m, id| m | mask(*id, models, cache, depth + 1)),
                _ => 63,
            };
            cache.insert((id, depth), value);
            value
        }
        let mut cache = HashMap::with_capacity(self.models.len());
        self.face_masks = self
            .models
            .keys()
            .map(|&id| (id, mask(id, &self.models, &mut cache, 0)))
            .collect();
    }
    pub fn face_mask(&self, state: &State) -> u32 {
        if state.air() || state.flags & 16 != 0 {
            0
        } else {
            self.face_masks.get(&state.model).copied().unwrap_or(63)
        }
    }
    #[allow(clippy::too_many_arguments)]
    pub fn emit(
        &self,
        state: &State,
        position: [i32; 3],
        visible: u32,
        layers: &mut [Vec<Triangle>; 3],
        hacks: &mut Hacks,
        tints: &mut crate::tint::Deferred,
    ) {
        let mut offset = position.map(|p| p.rem_euclid(16) as f32);
        if state.flags & 2 != 0 {
            let s = position_seed(position[0], 0, position[2]);
            offset[0] += (((s & 15) as f64 / 15.0 - 0.5) * 0.5) as f32;
            offset[2] += (((s >> 8 & 15) as f64 / 15.0 - 0.5) * 0.5) as f32;
            hacks.offset += 1;
        }
        if state.flags & 16 != 0 {
            return;
        }
        let mut random = Random::new(position_seed(position[0], position[1], position[2]));
        self.emit_model(
            state.model,
            offset,
            visible,
            &mut random,
            layers,
            hacks,
            tints,
            0,
        );
    }
    #[allow(clippy::too_many_arguments)]
    fn emit_model(
        &self,
        id: u32,
        offset: [f32; 3],
        visible: u32,
        random: &mut Random,
        layers: &mut [Vec<Triangle>; 3],
        hacks: &mut Hacks,
        tints: &mut crate::tint::Deferred,
        depth: u32,
    ) {
        if depth > 64 {
            hacks.model += 1;
            cube(offset, visible, [1.0, 0.0, 1.0, 1.0], 0, layers);
            return;
        }
        match self.models.get(&id) {
            Some(Model::Mesh(quads)) => {
                for q in quads {
                    if visible & (1 << q.face) != 0 {
                        let start = layers[q.layer].len();
                        emit_quad(q, offset, [1.; 4], 1, layers);
                        if q.tint >= 0 {
                            tints.patch(q.tint, q.layer, start, layers[q.layer].len());
                        }
                    }
                }
            }
            Some(Model::Alias(child)) => self.emit_model(
                *child,
                offset,
                visible,
                random,
                layers,
                hacks,
                tints,
                depth + 1,
            ),
            Some(Model::Weighted(items, total)) => {
                let mut choice = random.bound(*total);
                for &(weight, child) in items {
                    if choice < weight {
                        self.emit_model(
                            child,
                            offset,
                            visible,
                            random,
                            layers,
                            hacks,
                            tints,
                            depth + 1,
                        );
                        break;
                    }
                    choice -= weight;
                }
            }
            Some(Model::Multipart(children)) => {
                let seed = random.long();
                for &child in children {
                    *random = Random::new(seed);
                    self.emit_model(
                        child,
                        offset,
                        visible,
                        random,
                        layers,
                        hacks,
                        tints,
                        depth + 1,
                    );
                }
            }

            _ => {
                hacks.model += 1;
                cube(offset, visible, [1.0, 0.0, 1.0, 1.0], 0, layers);
            }
        }
    }
}
pub(crate) fn emit_quad(
    q: &Quad,
    offset: [f32; 3],
    color: [f32; 4],
    texture_id: u32,
    layers: &mut [Vec<Triangle>; 3],
) {
    for corners in [[0, 1, 2], [2, 3, 0]] {
        layers[q.layer].push(Triangle {
            positions: corners.map(|i| std::array::from_fn(|a| offset[a] + q.positions[i][a])),
            colors: [color; 3],
            uvs: corners.map(|i| q.uvs[i]),
            texture_id,
            flags: q.layer as u32,
        });
    }
}
pub(crate) fn cube(
    offset: [f32; 3],
    visible: u32,
    color: [f32; 4],
    layer: usize,
    layers: &mut [Vec<Triangle>; 3],
) {
    let faces = [
        [[0., 0., 0.], [1., 0., 0.], [1., 0., 1.], [0., 0., 1.]],
        [[0., 1., 1.], [1., 1., 1.], [1., 1., 0.], [0., 1., 0.]],
        [[1., 0., 0.], [0., 0., 0.], [0., 1., 0.], [1., 1., 0.]],
        [[0., 0., 1.], [1., 0., 1.], [1., 1., 1.], [0., 1., 1.]],
        [[0., 0., 0.], [0., 0., 1.], [0., 1., 1.], [0., 1., 0.]],
        [[1., 0., 1.], [1., 0., 0.], [1., 1., 0.], [1., 1., 1.]],
    ];
    for (face, positions) in faces.into_iter().enumerate() {
        if visible & (1 << face) != 0 {
            emit_quad(
                &Quad {
                    positions,
                    uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                    face: face as u32,
                    tint: -1,
                    layer,
                },
                offset,
                color,
                0,
                layers,
            );
        }
    }
}
