//! Byte protocol adapters retained only for native/oracle fixtures.
use crate::*;

impl TerrainContext {
    pub fn plan(&mut self, pages: &[&[u8]], source_epoch: u64) -> Result<&[u8], String> {
        self.plan_budget(pages, source_epoch, usize::MAX)
    }

    /// Fixture entry: finish source capture now, but compile at most this many cells.
    /// Deferred compilation resumes on subsequent live batches, not while offline is frozen.
    pub fn plan_with_budget(
        &mut self,
        pages: &[&[u8]],
        source_epoch: u64,
        cell_budget: u32,
    ) -> Result<&[u8], String> {
        if !(1..=128).contains(&cell_budget) {
            return Err("terrain build budget must be between 1 and 128 cells".into());
        }
        self.plan_budget(pages, source_epoch, cell_budget as usize)
    }

    fn plan_budget(
        &mut self,
        pages: &[&[u8]],
        source_epoch: u64,
        cell_budget: usize,
    ) -> Result<&[u8], String> {
        let start = Instant::now();
        if self.pending.is_some() || self.awaiting_colors.is_some() {
            return Err("previous source batch still awaiting completion".into());
        }
        let input = FrameInput::read(pages)?;
        self.plan_input(input, source_epoch, cell_budget, start)?;
        let pending = self.pending.as_ref().unwrap();
        self.requests.clear();
        u64_to(&mut self.requests, pending.input.batch);
        u64_to(&mut self.requests, pending.demand.requests.len() as u64);
        u64_to(&mut self.requests, pending.demand.columns.len() as u64);
        u64_to(&mut self.requests, self.chunks.active_len() as u64);
        for &s in &pending.demand.requests {
            for v in [s.0, s.1, s.2] {
                u32_to(&mut self.requests, v as u32);
            }
            u32_to(&mut self.requests, u32::from(self.chunks.is_active(&s)));
        }
        for &(x, z, active) in &pending.demand.columns {
            for v in [x, z, i32::from(active)] {
                u32_to(&mut self.requests, v as u32);
            }
        }
        Ok(&self.requests)
    }

    pub fn accept(&mut self, pages: &[&[u8]], scene: &mut SourceScene) -> Result<(), String> {
        if self.awaiting_colors.is_some() {
            return self.accept_colors(pages, scene);
        }
        self.tint_requests.clear();
        let start = Instant::now();
        let pending = self
            .pending
            .as_ref()
            .ok_or("unsolicited section response")?;
        let mut r = Reader::new(pages)?;
        let (version, epoch, batch) = r.header(2)?;
        if (version, epoch, batch) != (self.version, self.epoch, pending.input.batch)
            || epoch != scene.epoch()
        {
            return Err("section response identity mismatch".into());
        }
        let requested: HashSet<_> = pending.demand.requests.iter().copied().collect();
        let mut received = HashMap::with_capacity(requested.len());
        let mut states = HashMap::new();
        let mut models = HashMap::new();
        let mut faces = HashMap::new();
        let mut fluids = HashMap::new();
        let mut sprites = HashMap::new();
        loop {
            match r.u32()? {
                0 => break,
                1 => {
                    let (id, state) = model::state(&mut r)?;
                    if states.insert(id, state).is_some() {
                        return Err("duplicate state definition".into());
                    }
                }
                2 => {
                    let (id, model) = model::model(&mut r)?;
                    if models.insert(id, model).is_some() {
                        return Err("duplicate model definition".into());
                    }
                }
                3 => {
                    let section = Section(r.i32()?, r.i32()?, r.i32()?);
                    if !requested.contains(&section) || received.contains_key(&section) {
                        return Err("section response was not requested or is duplicated".into());
                    }
                    let data = match r.u32()? {
                        0 => None,
                        1 => Some(SectionData::read(&mut r)?),
                        _ => return Err("invalid section availability".into()),
                    };
                    received.insert(section, data);
                }
                4 => {
                    let (id, face) = shape::Face::read(&mut r)?;
                    if faces.insert(id, face).is_some() {
                        return Err("duplicate face profile".into());
                    }
                }
                5 => {
                    let (id, fluid) = fluid::FluidMaterial::read(&mut r)?;
                    if fluids.insert(id, fluid).is_some() {
                        return Err("duplicate fluid material".into());
                    }
                }
                6 => {
                    let (id, sprite) = sprite::Sprite::read(&mut r)?;
                    if sprites.insert(id, sprite).is_some() {
                        return Err("duplicate sprite definition".into());
                    }
                }
                9 => {
                    let id = r.u32()?;
                    let sprite = sprites
                        .get_mut(&id)
                        .ok_or("LabPBR references undefined batch sprite")?;
                    if sprite.material.is_some() {
                        return Err("duplicate LabPBR definition".into());
                    }
                    sprite.material = Some(labpbr::Material::read(&mut r, sprite)?);
                }
                _ => return Err("unknown section response record".into()),
            }
        }
        r.finish()?;
        if received.len() != requested.len() {
            return Err("incomplete section response".into());
        }
        for model in models.values_mut() {
            if let model::Model::Mesh(quads) = model {
                for quad in quads {
                    if quad.sprite != 0 {
                        let sprite = sprites
                            .get(&quad.sprite)
                            .or_else(|| {
                                (!pending.demand.reset_catalog)
                                    .then(|| self.catalog.sprites.get(&quad.sprite))
                                    .flatten()
                            })
                            .ok_or("undefined source sprite")?;
                        if let Some(uvs) = sprite.local(quad.uvs) {
                            quad.uvs = uvs;
                        } else {
                            // Preserve unknown cross-sprite sampling, not a silently clamped edge.
                            quad.sprite = 0;
                            self.stats.hacks.sprite += 1;
                        }
                    }
                }
            }
        }
        // A resource ID denotes immutable content until the explicit catalog invalidation.
        // Reject in-place changes before committing any source data or consuming Pending.
        fn unchanged<T: PartialEq>(old: &HashMap<u32, T>, new: &HashMap<u32, T>) -> bool {
            new.iter()
                .all(|(id, value)| old.get(id).is_none_or(|previous| previous == value))
        }
        if !pending.demand.reset_catalog
            && (!unchanged(&self.catalog.states, &states)
                || !unchanged(&self.catalog.models, &models)
                || !unchanged(&self.catalog.fluids, &fluids)
                || !unchanged(&self.catalog.sprites, &sprites)
                || faces
                    .iter()
                    .any(|(id, value)| self.catalog.faces.get(id).is_some_and(|old| old != value)))
        {
            return Err("resource definition changed without catalog invalidation".into());
        }
        for state in states.values() {
            if state.faces.iter().any(|id| {
                id.0 > 1
                    && !faces.contains_key(id)
                    && (pending.demand.reset_catalog || !self.catalog.faces.contains_key(id))
            }) {
                return Err("undefined face profile".into());
            }
            if state.fluid.kind != 0
                && !fluids.contains_key(&state.fluid.material)
                && (pending.demand.reset_catalog
                    || !self.catalog.fluids.contains_key(&state.fluid.material))
            {
                return Err("undefined fluid material".into());
            }
        }
        for fluid in fluids.values() {
            if fluid.identities.iter().any(|id| {
                *id != 0
                    && !sprites.contains_key(id)
                    && (pending.demand.reset_catalog || !self.catalog.sprites.contains_key(id))
            }) {
                return Err("undefined fluid sprite".into());
            }
        }
        // No fallible resource interpretation after taking Pending or mutating the catalog.
        let mut references = Vec::new();
        let mut textures = Vec::new();
        for (&id, sprite) in &sprites {
            textures.push((
                sprite::texture(id),
                sprite.image(pending.input.tick, scene)?,
            ));
            references.push((sprite::texture(id), sprite.reference(scene)?));
        }
        if !pending.demand.reset_catalog && self.animation_tick != Some(pending.input.tick) {
            for &id in &self.animated {
                if !sprites.contains_key(&id)
                    && !self.animation_tick.is_some_and(|old| {
                        self.catalog.sprites[&id].same_phase(old, pending.input.tick)
                    })
                {
                    textures.push((
                        sprite::texture(id),
                        self.catalog.sprites[&id].image(pending.input.tick, scene)?,
                    ));
                }
            }
        }
        if let Some(atlas) = labpbr::atlas(scene, &sprites) {
            textures.push((1, atlas));
        }
        scene.validate_textures(&textures)?;
        self.textures = textures;
        let Pending {
            input,
            demand,
            cell_budget,
        } = self.pending.take().unwrap();
        if demand.reset_catalog {
            self.catalog = Catalog::default();
            self.animated.clear();
        }
        self.animation_tick = Some(input.tick);
        let prepare = !models.is_empty() || !states.is_empty();
        let added_fluids = !fluids.is_empty();
        self.catalog.states.extend(states);
        self.catalog.faces.extend(faces);
        self.catalog.fluids.extend(fluids);
        self.catalog.glass_references.extend(references);
        for (&id, sprite) in &sprites {
            if !sprite.frames.is_empty() && !self.catalog.sprites.contains_key(&id) {
                self.animated.push(id);
            }
        }
        self.catalog.sprites.extend(sprites);
        if prepare {
            self.catalog.models.extend(models);
            self.catalog.prepare();
        } else if added_fluids {
            self.catalog.refresh_contact_capability();
        }
        self.start_compile(
            input,
            demand,
            received,
            cell_budget,
            scene,
            start,
            pages.iter().map(|p| p.len()).sum(),
        )?;
        self.encode_color_requests();
        Ok(())
    }

    /// Session-owned read-only view; valid until the next mutable source call.
    pub fn tint_requests(&self) -> &[u8] {
        &self.tint_requests
    }

    fn accept_colors(&mut self, pages: &[&[u8]], scene: &mut SourceScene) -> Result<(), String> {
        let start = Instant::now();
        let waiting = self.awaiting_colors.as_ref().unwrap();
        let (kind, expected) = match &waiting.stage {
            ColorStage::Sources => (3, self.stats.tint_requests),
            ColorStage::Biomes(_, source) => (4, source.requests.len()),
        };
        let mut r = Reader::new(pages)?;
        if r.header(kind)? != (self.version, self.epoch, waiting.batch)
            || self.epoch != scene.epoch()
        {
            return Err("tint response identity mismatch".into());
        }
        let count = usize::try_from(r.u64()?).map_err(|_| "tint count overflow")?;
        if count != expected {
            return Err("tint response count mismatch".into());
        }
        if kind == 3 {
            let radius = r.i32()?;
            if !(0..=7).contains(&radius) {
                return Err("unsupported biome blend radius".into());
            }
            let mut recipes = Vec::with_capacity(count);
            let mut callbacks = 0;
            for _ in 0..count {
                let kind = r.u32()?;
                let value = r.u32()?;
                recipes.push(biome::Recipe::read(kind, value)?);
                callbacks += usize::from(kind == 0);
            }
            let definitions = match r.u32()? {
                0 => None,
                1 if self.chunks.biome_sources().definitions.is_none() => {
                    Some(biome_source::Definitions::read(&mut r)?)
                }
                _ => return Err("unexpected biome definitions".into()),
            };
            r.finish()?;
            if let Some(colors) = self.prepare_color_sources(
                &recipes,
                definitions,
                radius,
                callbacks,
                pages.iter().map(|p| p.len()).sum(),
                start,
            )? {
                self.tint_requests.clear();
                self.complete_colors(&colors, scene)
            } else {
                self.encode_color_requests();
                Ok(())
            }
        } else {
            let ColorStage::Biomes(_, source) = &waiting.stage else {
                unreachable!()
            };
            let response = self.chunks.biome_sources().read_response(source, &mut r)?;
            r.finish()?;
            self.tint_requests.clear();
            self.complete_biome_sources(response, pages.iter().map(|p| p.len()).sum(), start, scene)
        }
    }

    fn encode_color_requests(&mut self) {
        self.tint_requests.clear();
        let Some(waiting) = &self.awaiting_colors else {
            return;
        };
        u64_to(&mut self.tint_requests, waiting.batch);
        let count = match &waiting.stage {
            ColorStage::Sources => waiting.requests.len(),
            ColorStage::Biomes(_, source) => source.requests.len(),
        };
        u64_to(&mut self.tint_requests, count as u64);
        u64_to(&mut self.tint_requests, self.epoch);
        u32_to(&mut self.tint_requests, self.version);
        let kind = match &waiting.stage {
            ColorStage::Sources => {
                if self.chunks.biome_sources().definitions.is_none() {
                    2
                } else {
                    0
                }
            }
            ColorStage::Biomes(..) => 3,
        };
        u32_to(&mut self.tint_requests, kind);
        match &waiting.stage {
            ColorStage::Sources => {
                for request in &waiting.requests {
                    for v in request.position {
                        u32_to(&mut self.tint_requests, v as u32);
                    }
                    u32_to(&mut self.tint_requests, request.state);
                    u32_to(&mut self.tint_requests, request.slot as u32);
                }
                self.stats.tint_bytes += 32;
            }
            ColorStage::Biomes(_, source) => {
                for request in &source.requests {
                    for v in [request.section.0, request.section.1, request.section.2] {
                        u32_to(&mut self.tint_requests, v as u32);
                    }
                    u64_to(&mut self.tint_requests, request.mask);
                }
                self.stats.tint_bytes = self.stats.tint_bytes + 32 - count * 4;
            }
        }
    }
}
