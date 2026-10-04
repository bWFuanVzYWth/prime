//! One sampler owner at a time. Dispatch only occurs during light publications.
use crate::{
    arena::Arena, light_grid::LightGrid, light_tree::LightTree, resources::Context,
    surface::LightPage,
};
use prime_scene::settings::LightSampling;
use std::{collections::BTreeMap, sync::Arc};

enum Tables {
    Grid(LightGrid),
    Tree(LightTree),
}

pub(crate) struct LightSampler {
    tables: Tables,
    method: LightSampling,
    /// Read directly by steady-state scene specialization; no sampler dispatch.
    pub has_lights: bool,
}

impl LightSampler {
    pub fn new(context: &Context, method: LightSampling) -> Self {
        Self {
            tables: match method {
                LightSampling::Grid => Tables::Grid(LightGrid::new(context)),
                LightSampling::Tree | LightSampling::TreeSphere => {
                    Tables::Tree(LightTree::new(context))
                }
            },
            method,
            has_lights: false,
        }
    }

    pub fn update(
        &mut self,
        context: &Arc<Context>,
        anchor: [f64; 3],
        sources: &BTreeMap<u64, ([f64; 3], &LightPage)>,
        uploads: &mut Arena,
        completed: u64,
    ) -> Result<(), String> {
        // Deferred reclamation retains completed leases until another publication;
        // it never reuses an allocation without its actual completion proof.
        let serial = context.retirement_serial();
        match &mut self.tables {
            Tables::Grid(grid) => {
                grid.begin_frame(completed, serial);
                grid.update(context, anchor, sources, uploads)?;
                self.has_lights = grid.has_lights();
            }
            Tables::Tree(tree) => {
                tree.begin_frame(completed, serial);
                tree.update(context, anchor, sources, uploads, self.method)?;
                self.has_lights = tree.has_lights();
            }
        }
        Ok(())
    }

    pub fn first_emitter(&self, key: u64) -> u32 {
        match &self.tables {
            Tables::Grid(grid) => grid.first_emitter(key),
            Tables::Tree(tree) => tree.first_emitter(key),
        }
    }

    pub fn world_count(&self) -> u32 {
        match &self.tables {
            Tables::Grid(grid) => grid.world_count(),
            Tables::Tree(tree) => tree.world_count(),
        }
    }

    pub fn history_pages(&self) -> impl ExactSizeIterator<Item = Option<(u64, u32)>> + '_ {
        let count = match &self.tables {
            Tables::Grid(grid) => grid.history_page_count(),
            Tables::Tree(tree) => tree.history_page_count(),
        };
        (0..count).map(|index| match &self.tables {
            Tables::Grid(grid) => grid.history_page(index),
            Tables::Tree(tree) => tree.history_page(index),
        })
    }

    pub fn header_address(&self) -> u64 {
        match &self.tables {
            Tables::Grid(grid) => grid.header_address(),
            Tables::Tree(tree) => tree.header_address(),
        }
    }
}
