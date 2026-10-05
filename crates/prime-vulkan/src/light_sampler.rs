//! One tree sampler owner. Updates only occur during light publications.
use crate::{arena::Arena, light_tree::LightTree, resources::Context, surface::LightPage};
use std::{collections::BTreeMap, sync::Arc};

pub(crate) struct LightSampler {
    tree: LightTree,
    /// Read directly by steady-state scene specialization.
    pub has_lights: bool,
}

impl LightSampler {
    pub fn new(context: &Context) -> Self {
        Self {
            tree: LightTree::new(context),
            has_lights: false,
        }
    }

    pub fn update(
        &mut self,
        context: &Arc<Context>,
        anchor: [f64; 3],
        sources: &BTreeMap<u64, ([f64; 3], &LightPage)>,
        uploads: &mut Arena,
    ) -> Result<(), String> {
        // Geometry owns the upload Arena; table Buffer drops use Context completion retirement.
        self.tree.update(context, anchor, sources, uploads)?;
        self.has_lights = self.tree.has_lights();
        Ok(())
    }

    pub fn first_emitter(&self, key: u64) -> u32 {
        self.tree.first_emitter(key)
    }

    pub fn world_count(&self) -> u32 {
        self.tree.world_count()
    }

    pub fn history_pages(&self) -> impl ExactSizeIterator<Item = Option<(u64, u32)>> + '_ {
        (0..self.tree.history_page_count()).map(|index| self.tree.history_page(index))
    }

    pub fn header_address(&self) -> u64 {
        self.tree.header_address()
    }
}
