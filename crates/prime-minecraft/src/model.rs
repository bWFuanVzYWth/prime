//! Version adaptation and prototype defaults live here, not in the Java field router.
use crate::{
    fluid::{Fluid, FluidMaterial},
    shape::{Face, FaceId},
    wire::Reader,
};
use prime_scene::compiled::CompiledQuad;
use std::collections::{HashMap, HashSet};

#[derive(Clone, Default, PartialEq)]
pub(crate) struct State {
    pub id: u32,
    pub flags: u32,
    pub model: u32,
    pub name: String,
    pub faces: [FaceId; 6],
    pub support: u32,
    pub fluid: Fluid,
    pub emission: u32,
    pub placement: crate::placement::Placement,
}
impl State {
    pub fn air(&self) -> bool {
        self.flags & 1 != 0 || self.flags & 16 != 0 && self.fluid.kind == 0
    }

    pub fn same_block_culls(&self) -> bool {
        self.name == "minecraft:water"
            || self.name == "minecraft:lava"
            || self.name.ends_with("glass")
    }
    pub fn same_boundary(&self, other: &Self) -> bool {
        self.faces == other.faces
            && self.fluid == other.fluid
            && self.flags & 2020 == other.flags & 2020
            && self.support == other.support
            && self.model == other.model
            && self.emission == other.emission
            && self.placement == other.placement
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
    pub sprite: u32,
    pub emission: u32,
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
    pub sprites: HashMap<u32, crate::sprite::Sprite>,
    pub fluid_math: crate::fluid::FluidMath,
    face_masks: HashMap<u32, u32>,
    combined: HashMap<u32, Vec<Quad>>,
    prepared: HashMap<u32, Vec<crate::surfaces::Recipe>>,
    volume_prepared: HashSet<u32>,
    pub volumes: HashMap<u32, crate::volume::Volume>,
    pub glass_references: HashMap<u32, [f32; 4]>,
    pub optical_materials: HashMap<(u32, u32), (u32, prime_scene::surface::Medium, bool)>,
    medium_ids: HashMap<(String, [u32; 4], Option<u32>), u32>,
    contact_capable: bool,
}

pub(crate) fn state(r: &mut Reader<'_>) -> Result<(u32, State), String> {
    let id = r.u32()?;
    let flags = r.u32()?;
    let model = r.u32()?;
    if flags & !2047 != 0 {
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
            emission: {
                let value = r.u32()?;
                if value > 15 {
                    return Err("invalid source emission".into());
                }
                value
            },
            placement: crate::placement::Placement::read(r, flags & 2 != 0)?,
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
            let count = r.count(100)?;
            let mut quads = Vec::with_capacity(count);
            for _ in 0..count {
                let face = r.u32()?;
                let tint = r.i32()?;
                let layer = r.u32()?;
                let sprite = r.u32()?;
                let emission = r.u32()?;
                if face > 6 || layer > 2 || tint < -1 || emission > 15 {
                    return Err("invalid raw quad attributes".into());
                }
                let mut q = Quad {
                    positions: [[0.0; 3]; 4],
                    uvs: [[0.0; 2]; 4],
                    face,
                    tint,
                    layer: layer as usize,
                    sprite,
                    emission,
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
    pub offset_unknown: u64,
    pub seed_unknown: u64,
    pub fluid: u64,
    pub optics: u64,
    pub sprite: u64,
}
impl std::ops::AddAssign for Hacks {
    fn add_assign(&mut self, other: Self) {
        self.model += other.model;
        self.tint += other.tint;
        self.offset += other.offset;
        self.offset_unknown += other.offset_unknown;
        self.seed_unknown += other.seed_unknown;
        self.fluid += other.fluid;
        self.optics += other.optics;
        self.sprite += other.sprite;
    }
}
impl Catalog {
    #[cfg(test)]
    pub fn prepared_for_test(&self, id: u32) -> usize {
        self.prepared[&id].as_ptr() as usize
    }
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
        for (&id, model) in &self.models {
            if let Model::Mesh(quads) = model {
                self.prepared
                    .entry(id)
                    .or_insert_with(|| crate::surfaces::prepare(quads));
            }
        }
        fn deterministic(
            id: u32,
            models: &HashMap<u32, Model>,
            depth: u32,
            out: &mut Vec<Quad>,
        ) -> Option<()> {
            if depth > 64 {
                return None;
            }
            match models.get(&id)? {
                Model::Mesh(q) => out.extend_from_slice(q),
                Model::Alias(id) => deterministic(*id, models, depth + 1, out)?,
                Model::Multipart(children) => {
                    for id in children {
                        deterministic(*id, models, depth + 1, out)?;
                    }
                }
                _ => return None,
            }
            Some(())
        }
        // Only a fully deterministic root can be flattened. Weighted children retain the exact
        // original random call order; no host model/selector is called a second time.
        for state in self.states.values() {
            if matches!(
                self.models.get(&state.model),
                Some(Model::Multipart(_) | Model::Alias(_))
            ) && !self.combined.contains_key(&state.model)
            {
                let mut quads = Vec::new();
                if deterministic(state.model, &self.models, 0, &mut quads).is_some() {
                    self.prepared
                        .insert(state.model, crate::surfaces::prepare(&quads));
                    self.combined.insert(state.model, quads);
                }
            }
        }
        fn collect(
            id: u32,
            models: &HashMap<u32, Model>,
            prepared: &HashMap<u32, Vec<crate::surfaces::Recipe>>,
            depth: u32,
            out: &mut Vec<Quad>,
        ) -> Option<()> {
            if depth > 64 {
                return None;
            }
            match models.get(&id)? {
                Model::Mesh(quads) => {
                    for r in &prepared[&id] {
                        out.push(quads[r.source].clone());
                        if let Some(pair) = r.pair
                            && !pair.reverse
                        {
                            out.push(quads[pair.other].clone());
                        }
                    }
                }
                Model::Alias(child) => collect(*child, models, prepared, depth + 1, out)?,
                Model::Multipart(children) => {
                    for child in children {
                        collect(*child, models, prepared, depth + 1, out)?;
                    }
                }
                _ => return None,
            }
            Some(())
        }
        // Only transmissive/contained-fluid sources need volume proofs. Immutable definitions
        // share the grid; placements and the GPU never build or traverse a model object graph.
        for state in self
            .states
            .values()
            .filter(|s| s.flags & 768 != 0 || s.fluid.kind != 0 && s.flags & 16 == 0)
        {
            let mut quads = Vec::new();
            if collect(state.model, &self.models, &self.prepared, 0, &mut quads).is_none() {
                continue;
            }
            if self.volume_prepared.insert(state.model)
                && let Some(volume) = crate::volume::Volume::prepare(&quads)
            {
                self.volumes.insert(state.model, volume);
            }
            if state.flags & 768 == 0 || quads.is_empty() || quads.iter().any(|q| q.tint >= 0) {
                continue;
            }
            let closed = self.volumes.contains_key(&state.model);
            let planar = (0..3).any(|a| {
                quads
                    .iter()
                    .flat_map(|q| q.positions)
                    .all(|p| p[a] == quads[0].positions[0][a])
            });
            if !closed && !planar {
                continue;
            }
            let family = state.name.strip_suffix("_pane").unwrap_or(&state.name);
            for q in &quads {
                let texture = crate::sprite::texture(q.sprite);
                let Some(&reference) = self.glass_references.get(&texture) else {
                    continue;
                };
                let sprite = self.sprites.get(&q.sprite);
                let reference_code = sprite.and_then(|sprite| {
                    let frame = sprite.frames.first().map_or(0, |&(frame, _)| frame);
                    sprite.fresnel_code(frame, [0.5; 2])
                });
                let constant_code = sprite.and_then(crate::sprite::Sprite::fresnel_code_constant);
                let varying_ior = reference_code.is_some() && constant_code.is_none();
                // This midpoint initializes the medium. Uniform resources prove one IOR;
                // varying current G is sampled per hit and adjacent G follows the current
                // GPU page at fixed reference coordinates.
                let mut medium = crate::optics::glass(reference);
                medium.ior = crate::optics::fresnel_code_ior(reference_code.unwrap_or(0));
                let key = (
                    family.to_string(),
                    [
                        medium.ior,
                        medium.extinction[0],
                        medium.extinction[1],
                        medium.extinction[2],
                    ]
                    .map(f32::to_bits),
                    varying_ior.then_some(texture),
                );
                let next = self.medium_ids.len() as u32 + 2;
                let id = *self.medium_ids.entry(key).or_insert(next);
                let id = if varying_ior {
                    id | crate::optics::DYNAMIC_IOR_MEDIUM
                } else {
                    id
                };
                self.optical_materials
                    .insert((state.id, texture), (id, medium, !closed));
            }
        }
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
                Some(Model::Mesh(quads)) => quads
                    .iter()
                    .fold(0, |m, q| m | (1 << q.face) | (1 << (8 + q.layer))),
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
        self.refresh_contact_capability();
    }
    pub fn face_mask(&self, state: &State) -> u32 {
        if state.air() || state.flags & 16 != 0 {
            0
        } else {
            self.face_masks.get(&state.model).copied().unwrap_or(63) & 127
        }
    }
    pub fn contact_candidate(&self, state: &State) -> bool {
        state.fluid.kind != 0
            || state.flags & 768 != 0
            || self
                .face_masks
                .get(&state.model)
                .is_some_and(|v| v & 0x600 != 0)
            || matches!(
                state.name.as_str(),
                "minecraft:fire" | "minecraft:soul_fire"
            )
    }
    pub fn has_contacts(&self) -> bool {
        self.contact_capable
    }
    pub fn refresh_contact_capability(&mut self) {
        self.contact_capable = !self.fluids.is_empty()
            || self.states.values().any(|s| s.flags & 768 != 0)
            || self.face_masks.values().any(|v| v & 0x600 != 0);
    }
    #[allow(clippy::too_many_arguments)]
    pub fn emit(
        &self,
        state: &State,
        position: [i32; 3],
        visible: u32,
        layers: &mut [Vec<CompiledQuad>; 3],
        hacks: &mut Hacks,
        tints: &mut crate::tint::Deferred,
        surfaces: &mut [Vec<prime_scene::surface::SurfaceFace>; 3],
    ) {
        let mut offset = position.map(|p| p.rem_euclid(16) as f32);
        if state.flags & 2 != 0 {
            let displacement = state.placement.offset(position);
            for axis in 0..3 {
                if state.placement.offset == 3 {
                    offset[axis] += displacement[axis] as f32;
                } else {
                    offset[axis] = (f64::from(offset[axis]) + displacement[axis]) as f32;
                }
            }
            hacks.offset += 1;
            hacks.offset_unknown += u64::from(state.placement.offset == 3);
        }
        hacks.seed_unknown += u64::from(state.placement.seed == 6);
        if state.flags & 16 != 0 {
            return;
        }
        if let Some(quads) = self.combined.get(&state.model) {
            emit_prepared(
                self,
                state,
                quads,
                &self.prepared[&state.model],
                offset,
                visible,
                layers,
                tints,
                surfaces,
            );
            return;
        }
        let mut random = Random::new(state.placement.seed(position));
        self.emit_model(
            state,
            state.model,
            offset,
            visible,
            &mut random,
            layers,
            hacks,
            tints,
            surfaces,
            0,
        );
    }
    #[allow(clippy::too_many_arguments)]
    fn emit_model(
        &self,
        state: &State,
        id: u32,
        offset: [f32; 3],
        visible: u32,
        random: &mut Random,
        layers: &mut [Vec<CompiledQuad>; 3],
        hacks: &mut Hacks,
        tints: &mut crate::tint::Deferred,
        surfaces: &mut [Vec<prime_scene::surface::SurfaceFace>; 3],
        depth: u32,
    ) {
        if depth > 64 {
            hacks.model += 1;
            cube(offset, visible, [1.0, 0.0, 1.0, 1.0], 0, layers);
            return;
        }
        match self.models.get(&id) {
            Some(Model::Mesh(quads)) => {
                emit_prepared(
                    self,
                    state,
                    quads,
                    &self.prepared[&id],
                    offset,
                    visible,
                    layers,
                    tints,
                    surfaces,
                );
            }
            Some(Model::Alias(child)) => self.emit_model(
                state,
                *child,
                offset,
                visible,
                random,
                layers,
                hacks,
                tints,
                surfaces,
                depth + 1,
            ),
            Some(Model::Weighted(items, total)) => {
                let mut choice = random.bound(*total);
                for &(weight, child) in items {
                    if choice < weight {
                        self.emit_model(
                            state,
                            child,
                            offset,
                            visible,
                            random,
                            layers,
                            hacks,
                            tints,
                            surfaces,
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
                        state,
                        child,
                        offset,
                        visible,
                        random,
                        layers,
                        hacks,
                        tints,
                        surfaces,
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
#[allow(clippy::too_many_arguments)]
fn emit_prepared(
    catalog: &Catalog,
    state: &State,
    quads: &[Quad],
    recipes: &[crate::surfaces::Recipe],
    offset: [f32; 3],
    visible: u32,
    layers: &mut [Vec<CompiledQuad>; 3],
    tints: &mut crate::tint::Deferred,
    surfaces: &mut [Vec<prime_scene::surface::SurfaceFace>; 3],
) {
    for recipe in recipes {
        let a = &quads[recipe.source];
        let active = |q: &Quad| visible & (1 << q.face) != 0;
        if let Some(pair) = recipe.pair {
            let b = &quads[pair.other];
            if active(a)
                && active(b)
                && let Some((face, slots)) =
                    crate::surfaces::resolve(catalog, state, a, b, pair, offset)
            {
                let layer = face.flags() as usize;
                let start = surfaces[layer].len();
                surfaces[layer].push(face);
                for (side, slot) in slots.into_iter().enumerate() {
                    if slot >= 0 {
                        tints.patch(slot, 3 + side * 3 + layer, start, start + 1);
                    }
                }
                continue;
            }
            if active(a) {
                emit_source(
                    catalog,
                    state,
                    a,
                    offset,
                    layers,
                    tints,
                    surfaces,
                    recipe.two_sided,
                );
            }
            if active(b) {
                emit_source(catalog, state, b, offset, layers, tints, surfaces, false);
            }
            continue;
        }
        if active(a) {
            emit_source(
                catalog,
                state,
                a,
                offset,
                layers,
                tints,
                surfaces,
                recipe.two_sided,
            );
        }
    }
}
#[allow(clippy::too_many_arguments)]
fn emit_source(
    catalog: &Catalog,
    state: &State,
    q: &Quad,
    offset: [f32; 3],
    layers: &mut [Vec<CompiledQuad>; 3],
    tints: &mut crate::tint::Deferred,
    surfaces: &mut [Vec<prime_scene::surface::SurfaceFace>; 3],
    two_sided: bool,
) {
    let layer = crate::surfaces::flags(state, q);
    let emission = crate::surfaces::emission(catalog, state, q, two_sided);
    if emission != prime_scene::surface::Emission::default() {
        let mut face =
            prime_scene::surface::SurfaceFace::from_quad(crate::surfaces::closed(state, q, offset));
        face.emission = emission;
        let start = surfaces[layer].len();
        surfaces[layer].push(face);
        if q.tint >= 0 {
            tints.patch(q.tint, 3 + layer, start, start + 1);
        }
        return;
    }
    let start = layers[layer].len();
    layers[layer].push(crate::surfaces::closed(state, q, offset));
    if q.tint >= 0 {
        tints.patch(q.tint, layer, start, start + 1);
    }
}
pub(crate) fn emit_quad(
    q: &Quad,
    offset: [f32; 3],
    color: [f32; 4],
    texture_id: u32,
    layers: &mut [Vec<CompiledQuad>; 3],
) {
    layers[q.layer].push(CompiledQuad {
        positions: q
            .positions
            .map(|p| std::array::from_fn(|a| offset[a] + p[a])),
        color,
        uvs: q.uvs,
        texture_id,
        flags: q.layer as u32,
    });
}
pub(crate) fn cube(
    offset: [f32; 3],
    visible: u32,
    color: [f32; 4],
    layer: usize,
    layers: &mut [Vec<CompiledQuad>; 3],
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
                    sprite: 0,
                    emission: 0,
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
