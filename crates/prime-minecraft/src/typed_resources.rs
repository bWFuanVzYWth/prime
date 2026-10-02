//! Resource generations are independent of world source epochs and compile admission.
use crate::{
    model::{Model, Quad, State},
    shape::FaceId,
    *,
};
use prime_abi::{
    minecraft::{Resources, finite, range, string},
    *,
};

fn state(v: &PrimeMcState, source: &Resources<'_>) -> Result<(u32, State), String> {
    if v.flags & !2047 != 0 || v.emission > 15 {
        return Err("invalid raw state flags/emission".into());
    }
    Ok((
        v.id,
        State {
            id: v.id,
            flags: v.flags,
            model: v.model,
            name: string(source.bytes(), v.name)?,
            faces: v.faces.map(FaceId),
            support: v.support,
            fluid: fluid::Fluid::from_fields(
                &string(source.bytes(), v.fluid_name)?,
                v.fluid_level,
                v.fluid_falling,
                v.fluid_material,
            )?,
            emission: v.emission,
            placement: placement::Placement::from_typed(v.placement, v.flags & 2 != 0)?,
            masks: model::StateMasks::default(),
        },
    ))
}
fn model(v: &PrimeMcModel, source: &Resources<'_>) -> Result<(u32, Model), String> {
    if v.id == 0 || v.reserved != 0 {
        return Err("invalid model identity".into());
    }
    let quads = range(source.quads(), v.quads)?;
    let children = range(source.children(), v.children)?;
    let value = match v.kind {
        0 if quads.is_empty() && children.is_empty() => Model::Unknown,
        1 if children.is_empty() => Model::Mesh(
            quads
                .iter()
                .map(|q| {
                    if q.face > 6
                        || q.layer > 2
                        || q.tint < -1
                        || q.emission > 15
                        || q.reserved != 0
                        || q.positions
                            .iter()
                            .any(|v| !v.is_finite() || v.abs() > 4096.)
                    {
                        return Err("invalid raw model quad".to_string());
                    }
                    let uvs = q
                        .uv_pairs
                        .map(|uv| [f32::from_bits((uv >> 32) as u32), f32::from_bits(uv as u32)]);
                    for uv in &uvs {
                        finite(uv)?;
                    }
                    Ok(Quad {
                        positions: std::array::from_fn(|i| {
                            std::array::from_fn(|j| q.positions[i * 3 + j])
                        }),
                        uvs,
                        face: q.face,
                        tint: q.tint,
                        layer: q.layer as usize,
                        sprite: q.sprite,
                        emission: q.emission,
                    })
                })
                .collect::<Result<_, String>>()?,
        ),
        2 if quads.is_empty() => {
            let total = children.iter().try_fold(0_u32, |n, c| {
                n.checked_add(c.weight).ok_or("model weight overflow")
            })?;
            if total == 0 || total > i32::MAX as u32 {
                return Err("invalid total model weight".into());
            }
            Model::Weighted(
                children.iter().map(|c| (c.weight, c.model)).collect(),
                total,
            )
        }
        3 if quads.is_empty() => Model::Multipart(children.iter().map(|c| c.model).collect()),
        4 if quads.is_empty() && children.is_empty() => Model::Alias(v.alias),
        _ => return Err("invalid raw model layout".into()),
    };
    Ok((v.id, value))
}
fn collect<K: Eq + std::hash::Hash, V>(
    items: impl Iterator<Item = Result<(K, V), String>>,
) -> Result<HashMap<K, V>, String> {
    let mut values = HashMap::new();
    for item in items {
        let (id, value) = item?;
        if values.insert(id, value).is_some() {
            return Err("duplicate resource definition".into());
        }
    }
    Ok(values)
}
fn unchanged<K: Eq + std::hash::Hash, V: PartialEq>(
    old: &HashMap<K, V>,
    new: &HashMap<K, V>,
) -> bool {
    new.iter()
        .all(|(id, value)| old.get(id).is_none_or(|previous| previous == value))
}

impl TerrainContext {
    pub fn resource_generation(&self) -> u64 {
        self.resource_generation
    }

    pub fn prepare_resources(
        &mut self,
        source: &Resources<'_>,
        scene: &mut SourceScene,
    ) -> Result<(), String> {
        let raw = source.raw();
        let generation = raw.identity.resource_generation;
        let replace = raw.flags & PRIME_MC_RESOURCE_REPLACE != 0;
        if self.awaiting_colors.is_some() || replace && self.pending.is_some() {
            return Err("resource replacement during source transaction".into());
        }
        if raw.reserved != 0
            || raw.flags & !PRIME_MC_RESOURCE_REPLACE != 0
            || raw.identity.epoch != scene.epoch()
            || !replace && raw.identity.game_version != self.resource_version
            || replace && generation <= self.resource_generation
            || !replace && generation != self.resource_generation
        {
            return Err("invalid resource generation transition".into());
        }
        let mut states = collect(source.states().iter().map(|v| state(v, source)))?;
        let mut models = collect(source.models().iter().map(|v| model(v, source)))?;
        let faces = collect(
            source
                .faces()
                .iter()
                .map(|v| shape::Face::from_typed(v, source)),
        )?;
        let fluids = collect(source.fluids().iter().map(fluid::FluidMaterial::from_typed))?;
        let sprites = collect(
            source
                .sprites()
                .iter()
                .map(|v| sprite::Sprite::from_typed(v, source)),
        )?;
        let old = (!replace).then_some(&self.catalog);
        for model in models.values_mut() {
            if let Model::Mesh(quads) = model {
                for quad in quads {
                    if quad.sprite != 0 {
                        let sprite = sprites
                            .get(&quad.sprite)
                            .or_else(|| old.and_then(|c| c.sprites.get(&quad.sprite)))
                            .ok_or("undefined source sprite")?;
                        if let Some(uvs) = sprite.local(quad.uvs) {
                            quad.uvs = uvs;
                        } else {
                            quad.sprite = 0;
                        }
                    }
                }
            }
        }
        if let Some(old) = old {
            if !unchanged(&old.states, &states)
                || !unchanged(&old.models, &models)
                || !unchanged(&old.faces, &faces)
                || !unchanged(&old.fluids, &fluids)
                || !unchanged(&old.sprites, &sprites)
            {
                return Err("resource definition changed without generation replacement".into());
            }
            states.retain(|id, _| !old.states.contains_key(id));
            models.retain(|id, _| !old.models.contains_key(id));
        }
        for value in states.values() {
            if value.faces.iter().any(|id| {
                id.0 > 1 && !faces.contains_key(id) && old.is_none_or(|c| !c.faces.contains_key(id))
            }) {
                return Err("undefined face profile".into());
            }
            if value.fluid.kind != 0
                && !fluids.contains_key(&value.fluid.material)
                && old.is_none_or(|c| !c.fluids.contains_key(&value.fluid.material))
            {
                return Err("undefined fluid material".into());
            }
        }
        for value in fluids.values() {
            if value.identities.iter().any(|id| {
                *id != 0
                    && !sprites.contains_key(id)
                    && old.is_none_or(|c| !c.sprites.contains_key(id))
            }) {
                return Err("undefined fluid sprite".into());
            }
        }
        // An isolated scene supplies the replacement atlas while deriving sprite views. Nothing
        // is visible in the live scene until the entire resource transaction has validated.
        let atlas = if replace {
            let image = sprite::Image::from_typed(&raw.atlas, source.bytes(), false)?;
            prime_scene::Texture {
                width: image.width,
                height: image.height,
                pixels: image.pixels,
                region: None,
                sampling: None,
                material: None,
            }
        } else {
            scene.texture(1).ok_or("resource atlas missing")?.clone()
        };
        let mut staging = SourceScene::default();
        staging.set_texture(1, atlas.clone())?;
        let mut textures = Vec::with_capacity(sprites.len() + 1);
        let mut references = Vec::with_capacity(sprites.len());
        for (&id, sprite) in &sprites {
            textures.push((sprite::texture(id), sprite.image(raw.tick, &staging)?));
            references.push((sprite::texture(id), sprite.reference(&staging)?));
        }
        if let Some(atlas) = labpbr::atlas(&staging, &sprites) {
            textures.push((1, atlas));
        } else if replace {
            textures.push((1, atlas));
        }
        let mut replacement = replace.then(Catalog::default);
        if let Some(catalog) = &mut replacement {
            catalog.states = states;
            catalog.models = models;
            catalog.faces = faces;
            catalog.fluids = fluids;
            catalog.sprites = sprites;
            catalog.glass_references.extend(references);
            catalog.prepare();
        } else {
            // Every fallible interpretation and cross-reference check precedes publication.
            scene.publish_resource_textures(generation, textures)?;
            let state_ids = states.keys().copied().collect::<Vec<_>>();
            let model_ids = models.keys().copied().collect::<Vec<_>>();
            let sprite_ids = sprites
                .keys()
                .filter(|id| !self.catalog.sprites.contains_key(id))
                .copied()
                .collect::<Vec<_>>();
            self.catalog.states.extend(states);
            self.catalog.models.extend(models);
            self.catalog.faces.extend(faces);
            self.catalog.fluids.extend(fluids);
            self.catalog.glass_references.extend(references);
            for (&id, sprite) in &sprites {
                if !sprite.frames.is_empty() && !self.catalog.sprites.contains_key(&id) {
                    self.animated.push(id);
                }
            }
            self.catalog.sprites.extend(sprites);
            self.catalog
                .prepare_delta(&state_ids, &model_ids, &sprite_ids);
            return Ok(());
        }
        scene.publish_resource_textures(generation, textures)?;
        self.catalog = replacement.unwrap();
        self.animated = self
            .catalog
            .sprites
            .iter()
            .filter_map(|(&id, s)| (!s.frames.is_empty()).then_some(id))
            .collect();
        self.animation_tick = Some(raw.tick);
        self.resource_generation = generation;
        self.resource_version = raw.identity.game_version;
        self.resource_reset_pending = true;
        Ok(())
    }
}
