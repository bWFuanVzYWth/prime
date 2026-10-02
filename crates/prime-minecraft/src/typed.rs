//! Typed production source boundaries. The legacy byte readers remain fixture inputs.
use crate::*;
use prime_abi::{
    minecraft::{self as input, range},
    *,
};

#[derive(Default)]
pub(super) struct Output {
    sections: Vec<PrimeMcSectionRequest>,
    columns: Vec<PrimeMcColumnRequest>,
    colors: Vec<PrimeMcColorRequest>,
    biomes: Vec<PrimeMcBiomeRequest>,
}
impl TerrainContext {
    fn check_identity(
        &self,
        id: PrimeMcIdentity,
        batch: u64,
        scene: &SourceScene,
    ) -> Result<(), String> {
        if (id.game_version, id.epoch, id.batch, id.resource_generation)
            != (self.version, self.epoch, batch, self.resource_generation)
            || id.epoch != scene.epoch()
        {
            return Err("typed source identity mismatch".into());
        }
        Ok(())
    }
    pub fn requests_typed(&mut self) -> PrimeMcRequests {
        let out = &mut self.typed_output;
        out.sections.clear();
        out.columns.clear();
        out.colors.clear();
        out.biomes.clear();
        let mut result = PrimeMcRequests {
            identity: PrimeMcIdentity {
                struct_size: std::mem::size_of::<PrimeMcRequests>() as u32,
                abi_version: PRIME_ABI_VERSION,
                source_version: PRIME_MC_SOURCE_VERSION,
                game_version: self.version,
                resource_generation: self.resource_generation,
                epoch: self.epoch,
                batch: self.last_batch,
            },
            ..Default::default()
        };
        if let Some(pending) = &self.pending {
            result.phase = 1;
            result.identity.batch = pending.input.batch;
            result.active_sections = self.chunks.active_len() as u64;
            out.sections.extend(
                pending
                    .demand
                    .requests
                    .iter()
                    .map(|s| PrimeMcSectionRequest {
                        x: s.0,
                        y: s.1,
                        z: s.2,
                        active: u32::from(self.chunks.is_active(s)),
                    }),
            );
            out.columns
                .extend(pending.demand.columns.iter().map(|&(x, z, active)| {
                    PrimeMcColumnRequest {
                        x,
                        z,
                        active: u32::from(active),
                    }
                }));
        } else if let Some(waiting) = &self.awaiting_colors {
            result.identity.batch = waiting.batch;
            match &waiting.stage {
                ColorStage::Sources => {
                    result.phase = if self.chunks.biome_sources().definitions.is_none() {
                        3
                    } else {
                        2
                    };
                    out.colors
                        .extend(waiting.requests.iter().map(|r| PrimeMcColorRequest {
                            x: r.position[0],
                            y: r.position[1],
                            z: r.position[2],
                            state: r.state,
                            slot: r.slot,
                        }));
                }
                ColorStage::Biomes(_, source) => {
                    result.phase = 4;
                    out.biomes
                        .extend(source.requests.iter().map(|r| PrimeMcBiomeRequest {
                            x: r.section.0,
                            y: r.section.1,
                            z: r.section.2,
                            reserved: 0,
                            mask: r.mask,
                        }));
                }
            }
        }
        result.sections = out.sections.as_ptr();
        result.section_count = out.sections.len() as u64;
        result.columns = out.columns.as_ptr();
        result.column_count = out.columns.len() as u64;
        result.colors = out.colors.as_ptr();
        result.color_count = out.colors.len() as u64;
        result.biomes = out.biomes.as_ptr();
        result.biome_count = out.biomes.len() as u64;
        result
    }
    pub fn plan_typed(
        &mut self,
        value: &input::Plan<'_>,
        source_epoch: u64,
        budget: u32,
    ) -> Result<(), String> {
        let start = Instant::now();
        let v = value.raw();
        let id = v.identity;
        if self.pending.is_some() || self.awaiting_colors.is_some() {
            return Err("previous source transaction pending".into());
        }
        if !(1..=128).contains(&budget)
            || id.resource_generation != self.resource_generation
            || id.epoch == 0
            || id.batch == 0
            || id.game_version != self.resource_version
            || v.reserved != 0
            || v.position
                .iter()
                .any(|p| !p.is_finite() || p.abs() > 32_000_000.)
            || !(0..=2_000_000).contains(&v.radius)
            || v.min_y < -524288
            || v.max_y > 524287
            || v.max_y < v.min_y
        {
            return Err("invalid typed source frame".into());
        }
        let mut events = Vec::with_capacity(value.events().len() + 1);
        for e in value.events() {
            if !(1..=7).contains(&e.kind)
                || e.x.abs_diff(0) > 2_000_000
                || e.z.abs_diff(0) > 2_000_000
            {
                return Err("invalid source event".into());
            }
            events.push((e.kind, Section(e.x, e.y, e.z)));
        }
        if self.resource_reset_pending {
            events.push((4, Section(0, 0, 0)));
        }
        let frame = FrameInput {
            version: id.game_version,
            epoch: id.epoch,
            batch: id.batch,
            center: v.position.map(|p| (p / 16.).floor() as i32),
            radius: v.radius,
            min_y: v.min_y,
            max_y: v.max_y,
            source: schedule::Window {
                x0: v.source[0],
                x1: v.source[1],
                z0: v.source[2],
                z1: v.source[3],
            },
            tick: v.tick,
            events,
        };
        self.plan_input(frame, source_epoch, budget as usize, start)?;
        self.resource_reset_pending = false;
        Ok(())
    }
    pub fn sections_typed(
        &mut self,
        values: &input::Sections<'_>,
        scene: &mut SourceScene,
    ) -> Result<(), String> {
        let start = Instant::now();
        let pending = self.pending.as_ref().ok_or("no source request pending")?;
        self.check_identity(values.raw().identity, pending.input.batch, scene)?;
        let requested: HashSet<_> = pending.demand.requests.iter().copied().collect();
        let mut received = HashMap::with_capacity(values.sections().len());
        for v in values.sections() {
            let key = Section(v.x, v.y, v.z);
            if !requested.contains(&key)
                || received.contains_key(&key)
                || v.present > 1
                || v.reserved != 0
            {
                return Err("unrequested/duplicate/invalid section source".into());
            }
            let palette = range(values.palette(), v.palette)?;
            let words = range(values.words(), v.storage)?;
            let data = if v.present == 0 {
                if !palette.is_empty() || !words.is_empty() || v.bits != 0 {
                    return Err("absent section has payload".into());
                }
                None
            } else {
                if v.bits > 32
                    || palette.len() > 4096
                    || (v.bits == 0 && (!words.is_empty() || palette.len() != 1))
                {
                    return Err("invalid section palette layout".into());
                }
                let per_word = if v.bits == 0 { 0 } else { 64 / v.bits as usize };
                if v.bits != 0 && words.len() != 4096usize.div_ceil(per_word) {
                    return Err("invalid section storage length".into());
                }
                let data = SectionData {
                    palette: palette.to_vec(),
                    storage: words.to_vec(),
                    bits: v.bits,
                    per_word,
                };
                if !data.valid_indices() {
                    return Err("section palette index out of range".into());
                }
                Some(data)
            };
            received.insert(key, data);
        }
        if received.len() != requested.len() {
            return Err("incomplete section response".into());
        }
        let tick = pending.input.tick;
        let mut textures = Vec::new();
        if self.animation_tick != Some(tick) {
            for &id in &self.animated {
                let sprite = &self.catalog.sprites[&id];
                if self
                    .animation_tick
                    .is_some_and(|old| sprite.same_phase(old, tick))
                {
                    continue;
                }
                textures.push((sprite::texture(id), sprite.image(tick, scene)?));
            }
        }
        scene.validate_textures(&textures)?;
        self.textures = textures;
        self.animation_tick = Some(tick);
        let Pending {
            input,
            demand,
            cell_budget,
        } = self.pending.take().unwrap();
        self.start_compile(
            input,
            demand,
            received,
            cell_budget,
            scene,
            start,
            std::mem::size_of_val(values.sections())
                + std::mem::size_of_val(values.palette())
                + std::mem::size_of_val(values.words()),
        )
    }
    pub fn colors_typed(
        &mut self,
        values: &input::Colors<'_>,
        scene: &mut SourceScene,
    ) -> Result<(), String> {
        let decode_start = Instant::now();
        let waiting = self
            .awaiting_colors
            .as_ref()
            .ok_or("no color source request")?;
        self.check_identity(values.raw().identity, waiting.batch, scene)?;
        if !matches!(waiting.stage, ColorStage::Sources)
            || values.recipes().len() != waiting.requests.len()
            || values.raw().reserved != 0
            || !(0..=7).contains(&values.raw().radius)
            || values.definitions().len() > 1
        {
            return Err("invalid color response stage/count".into());
        }
        let recipes = values
            .recipes()
            .iter()
            .map(|r| biome::Recipe::read(r.kind, r.value))
            .collect::<Result<Vec<_>, _>>()?;
        let definitions = values
            .definitions()
            .first()
            .map(|d| biome_source::Definitions::from_typed(d, values.colormaps()))
            .transpose()?;
        let bytes = std::mem::size_of_val(values.recipes())
            + std::mem::size_of_val(values.definitions())
            + std::mem::size_of_val(values.colormaps());
        let callbacks = values.recipes().iter().filter(|r| r.kind == 0).count();
        if let Some(colors) = self.prepare_color_sources(
            &recipes,
            definitions,
            values.raw().radius,
            callbacks,
            bytes,
            decode_start,
        )? {
            self.complete_colors(&colors, scene)?;
        }
        Ok(())
    }
    pub fn biomes_typed(
        &mut self,
        values: &input::Biomes<'_>,
        scene: &mut SourceScene,
    ) -> Result<(), String> {
        let decode_start = Instant::now();
        let waiting = self.awaiting_colors.as_ref().ok_or("no biome request")?;
        self.check_identity(values.raw().identity, waiting.batch, scene)?;
        let ColorStage::Biomes(_, source) = &waiting.stage else {
            return Err("unexpected biome response".into());
        };
        let response = self.chunks.biome_sources().typed_response(source, values)?;
        self.complete_biome_sources(
            response,
            std::mem::size_of_val(values.biomes()) + std::mem::size_of_val(values.indices()),
            decode_start,
            scene,
        )
    }
}
