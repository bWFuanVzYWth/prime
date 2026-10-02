//! Version adaptation and prototype defaults live here, not in the Java field router.
#[cfg(test)]
use crate::wire::Reader;
use crate::{
    fluid::{Fluid, FluidMaterial},
    shape::{Face, FaceId},
};
use prime_scene::compiled::CompiledQuad;
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, Default)]
pub(crate) struct StateMasks {
    pub emission: u8,
    pub occlusion: u16,
    pub contact: u8,
}
#[derive(Clone, Default)]
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
    pub masks: StateMasks,
}
// Resource equality excludes catalog-owned derived masks. A repeated immutable source
// descriptor is compared before its derived values have been prepared.
impl PartialEq for State {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
            && self.flags == other.flags
            && self.model == other.model
            && self.name == other.name
            && self.faces == other.faces
            && self.support == other.support
            && self.fluid == other.fluid
            && self.emission == other.emission
            && self.placement == other.placement
    }
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
    pub volumes: HashMap<u32, crate::volume::Volume>,
    pub glass_references: HashMap<u32, [f32; 4]>,
    pub optical_materials: HashMap<(u32, u32), (u32, prime_scene::surface::Medium, bool)>,
    medium_ids: HashMap<(String, [u32; 4], Option<u32>), u32>,
    contact_capable: bool,
    parents: HashMap<u32, HashSet<u32>>,
    state_users: HashMap<u32, HashSet<u32>>,
    sprite_models: HashMap<u32, HashSet<u32>>,
    volume_quads: HashMap<u32, Option<Vec<Quad>>>,
    #[cfg(test)]
    pub derived_models: usize,
    #[cfg(test)]
    pub derived_states: usize,
    #[cfg(test)]
    pub volume_expansions: usize,
}

#[cfg(test)]
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
            masks: StateMasks::default(),
        },
    ))
}
#[cfg(test)]
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
    // Replacement and test fixtures enumerate resource IDs once. Production appends pass only
    // newly admitted IDs; immutable definitions retain all unrelated allocations and proofs.
    pub fn prepare(&mut self) {
        let models = self
            .models
            .keys()
            .filter(|id| !self.face_masks.contains_key(id))
            .copied()
            .collect::<Vec<_>>();
        let states = self.states.keys().copied().collect::<Vec<_>>();
        self.prepare_delta(&states, &models, &[]);
    }
    pub fn prepare_delta(&mut self, new_states: &[u32], new_models: &[u32], new_sprites: &[u32]) {
        for &id in new_models {
            match &self.models[&id] {
                Model::Alias(child) => {
                    self.parents.entry(*child).or_default().insert(id);
                }
                Model::Multipart(children) => {
                    for &child in children {
                        self.parents.entry(child).or_default().insert(id);
                    }
                }
                Model::Weighted(children, _) => {
                    for &(_, child) in children {
                        self.parents.entry(child).or_default().insert(id);
                    }
                }
                Model::Mesh(quads) => {
                    for q in quads {
                        self.sprite_models.entry(q.sprite).or_default().insert(id);
                    }
                    self.prepared
                        .entry(id)
                        .or_insert_with(|| crate::surfaces::prepare(quads));
                }
                Model::Unknown => {}
            }
        }
        for &id in new_states {
            self.state_users
                .entry(self.states[&id].model)
                .or_default()
                .insert(id);
        }
        let mut changed = HashSet::new();
        let mut queue = new_models.to_vec();
        for sprite in new_sprites {
            if let Some(models) = self.sprite_models.get(sprite) {
                queue.extend(models);
            }
        }
        while let Some(id) = queue.pop() {
            if changed.insert(id)
                && let Some(parents) = self.parents.get(&id)
            {
                queue.extend(parents);
            }
        }
        let mut states = new_states.iter().copied().collect::<HashSet<_>>();
        for id in &changed {
            if let Some(users) = self.state_users.get(id) {
                states.extend(users);
            }
            self.combined.remove(id);
            self.volume_quads.remove(id);
            self.volumes.remove(id);
        }
        // State-only appends may introduce the first geometry/volume consumer of an existing root.
        let roots = states
            .iter()
            .map(|id| self.states[id].model)
            .collect::<HashSet<_>>();
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
        for &id in &roots {
            if matches!(
                self.models.get(&id),
                Some(Model::Multipart(_) | Model::Alias(_))
            ) && !self.combined.contains_key(&id)
            {
                let mut quads = Vec::new();
                if deterministic(id, &self.models, 0, &mut quads).is_some() {
                    self.prepared.insert(id, crate::surfaces::prepare(&quads));
                    self.combined.insert(id, quads);
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
        let volume_roots = states
            .iter()
            .filter_map(|id| {
                let state = &self.states[id];
                (state.flags & 768 != 0 || state.fluid.kind != 0 && state.flags & 16 == 0)
                    .then_some(state.model)
            })
            .collect::<HashSet<_>>();
        #[cfg(test)]
        {
            self.derived_models = changed.len();
            self.derived_states = states.len();
            self.volume_expansions = 0;
        }
        for id in volume_roots {
            if self.volume_quads.contains_key(&id) {
                continue;
            }
            let mut quads = Vec::new();
            let valid = collect(id, &self.models, &self.prepared, 0, &mut quads).is_some();
            if valid && let Some(volume) = crate::volume::Volume::prepare(&quads) {
                self.volumes.insert(id, volume);
            }
            self.volume_quads.insert(id, valid.then_some(quads));
            #[cfg(test)]
            {
                self.volume_expansions += 1;
            }
        }
        // Each model expansion is shared, but optical identity/family remains state-specific.
        for id in &states {
            let state = &self.states[id];
            let Some(Some(quads)) = self.volume_quads.get(&state.model) else {
                continue;
            };
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
            for q in quads {
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
        let mut cache = HashMap::with_capacity(changed.len());
        for &id in &changed {
            self.face_masks
                .insert(id, mask(id, &self.models, &mut cache, 0));
        }
        for id in states {
            let state = &self.states[&id];
            let mut occlusion = if state.same_block_culls() { 1 << 12 } else { 0 };
            for (face, id) in state.faces.iter().enumerate() {
                occlusion |= match id.0 {
                    0 => 0,
                    1 => 1 << (face ^ 1),
                    _ => 1 << ((face ^ 1) + 6),
                };
            }
            let masks = StateMasks {
                emission: self.face_mask(state) as u8 | if state.fluid.kind != 0 { 128 } else { 0 },
                occlusion,
                contact: u8::from(self.contact_candidate(state))
                    | (u8::from(!state.air() && (state.model != 0 || state.fluid.kind != 0)) << 1)
                    | (u8::from(state.flags & 768 != 0 || state.fluid.kind != 0) << 2)
                    | (u8::from(matches!(
                        state.name.as_str(),
                        "minecraft:fire" | "minecraft:soul_fire"
                    )) << 3),
            };
            self.states.get_mut(&id).unwrap().masks = masks;
        }
        // Definitions only grow within a generation; replacement starts from Default.
        self.contact_capable |= !self.fluids.is_empty()
            || new_states.iter().any(|id| self.states[id].flags & 768 != 0)
            || changed
                .iter()
                .any(|id| self.face_masks.get(id).is_some_and(|v| v & 0x600 != 0));
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
    #[cfg(test)]
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

#[cfg(test)]
mod preparation_tests {
    use super::*;

    fn quad(face: u32, layer: usize) -> Quad {
        Quad {
            positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
            uvs: [[0.; 2]; 4],
            face,
            layer,
            tint: -1,
            sprite: 0,
            emission: 0,
        }
    }

    #[test]
    fn shared_volume_sources_keep_each_optical_family_and_survive_state_only_appends() {
        let mut catalog = Catalog::default();
        catalog.models.insert(1, Model::Mesh(vec![quad(6, 2)]));
        catalog
            .glass_references
            .insert(crate::sprite::texture(0), [1.; 4]);
        for (id, name) in [(1, "test:clear_glass"), (2, "test:other_glass_pane")] {
            catalog.states.insert(
                id,
                State {
                    id,
                    model: 1,
                    flags: 256,
                    name: name.into(),
                    ..Default::default()
                },
            );
        }
        catalog.prepare();
        assert_eq!(catalog.volume_expansions, 1);
        let quads = catalog.volume_quads[&1].as_ref().unwrap().as_ptr();
        let prepared = catalog.prepared_for_test(1);
        let a = catalog.optical_materials[&(1, crate::sprite::texture(0))];
        let b = catalog.optical_materials[&(2, crate::sprite::texture(0))];
        assert_ne!(a.0, b.0);
        assert!(a.2 && b.2); // Same planar model, distinct families, both thin surfaces.
        catalog.states.insert(
            3,
            State {
                id: 3,
                model: 1,
                flags: 256,
                name: "test:clear_glass_pane".into(),
                ..Default::default()
            },
        );
        catalog.prepare_delta(&[3], &[], &[]);
        assert_eq!(
            (
                catalog.derived_models,
                catalog.derived_states,
                catalog.volume_expansions
            ),
            (0, 1, 0)
        );
        assert_eq!(catalog.volume_quads[&1].as_ref().unwrap().as_ptr(), quads);
        assert_eq!(catalog.prepared_for_test(1), prepared);
        assert_eq!(
            catalog.optical_materials[&(3, crate::sprite::texture(0))].0,
            a.0
        );
        catalog.models.insert(99, Model::Mesh(vec![quad(1, 0)]));
        catalog.prepare_delta(&[], &[99], &[]);
        assert_eq!(
            (
                catalog.derived_models,
                catalog.derived_states,
                catalog.volume_expansions
            ),
            (1, 0, 0)
        );
        assert_eq!(catalog.volume_quads[&1].as_ref().unwrap().as_ptr(), quads);
    }

    #[test]
    fn late_children_update_only_reverse_closure_and_keep_weighted_and_cycle_fallbacks() {
        let mut catalog = Catalog::default();
        catalog.models.insert(1, Model::Alias(2));
        catalog.models.insert(2, Model::Multipart(vec![3, 4]));
        catalog.models.insert(3, Model::Alias(5)); // Child 5 arrives later.
        catalog.models.insert(4, Model::Mesh(vec![quad(1, 0)]));
        catalog.models.insert(6, Model::Alias(7));
        catalog.models.insert(7, Model::Alias(6));
        catalog
            .models
            .insert(8, Model::Weighted(vec![(1, 5), (2, 4)], 3));
        for id in [1, 6, 8] {
            catalog.states.insert(
                id,
                State {
                    id,
                    model: id,
                    flags: if id == 1 { 256 } else { 0 },
                    name: "test:late_glass".into(),
                    ..Default::default()
                },
            );
        }
        catalog
            .glass_references
            .insert(crate::sprite::texture(0), [1.; 4]);
        catalog.prepare();
        assert!(!catalog.combined.contains_key(&1));
        assert!(catalog.volume_quads[&1].is_none());
        assert_eq!(catalog.face_mask(&catalog.states[&6]), 127);
        let unrelated = catalog.prepared_for_test(4);
        catalog.models.insert(5, Model::Mesh(vec![quad(2, 1)]));
        catalog.prepare_delta(&[], &[5], &[]);
        assert_eq!((catalog.derived_models, catalog.derived_states), (5, 2)); // 5,3,2,1,8.
        assert_eq!(catalog.volume_expansions, 1);
        assert_eq!(catalog.volume_quads[&1].as_ref().unwrap().len(), 2);
        assert!(
            catalog
                .optical_materials
                .contains_key(&(1, crate::sprite::texture(0)))
        );
        assert_eq!(catalog.prepared_for_test(4), unrelated);
        assert_eq!(
            catalog.combined[&1]
                .iter()
                .map(|q| q.face)
                .collect::<Vec<_>>(),
            [2, 1]
        );
        assert!(!catalog.combined.contains_key(&8));
        for id in [1, 8] {
            assert_eq!(catalog.states[&id].masks.emission, (1 << 1) | (1 << 2));
            assert_eq!(catalog.states[&id].masks.contact & 1, 1); // New cutout child contact proof.
        }
        assert_eq!(catalog.face_mask(&catalog.states[&6]), 127);
    }
}
