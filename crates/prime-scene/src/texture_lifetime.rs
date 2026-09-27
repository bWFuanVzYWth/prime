//! Source ownership and scene references are independent retirement prerequisites.
use std::collections::{BTreeMap, BTreeSet};

#[derive(Default)]
pub(crate) struct TextureLifetime {
    references: BTreeMap<u32, u64>,
    retired: BTreeSet<u32>,
    pub candidates: BTreeSet<u32>,
}

impl TextureLifetime {
    pub fn acquire(&mut self, id: u32) {
        if id == 0 || id == u32::MAX {
            return;
        }
        *self.references.entry(id).or_default() += 1;
        self.candidates.remove(&id);
    }
    pub fn release(&mut self, id: u32) {
        if id == 0 || id == u32::MAX {
            return;
        }
        let count = self
            .references
            .get_mut(&id)
            .expect("registered texture reference");
        *count -= 1;
        if *count == 0 {
            self.references.remove(&id);
            if self.retired.contains(&id) {
                self.candidates.insert(id);
            }
        }
    }
    pub fn retire(&mut self, id: u32) {
        self.retired.insert(id);
        if !self.references.contains_key(&id) {
            self.candidates.insert(id);
        }
    }
    pub fn owned(&mut self, id: u32) {
        self.retired.remove(&id);
        self.candidates.remove(&id);
    }
    pub fn collected(&mut self, id: u32) {
        self.retired.remove(&id);
    }
}

impl crate::SourceScene {
    pub(crate) fn collect_textures(&mut self) -> Result<(), String> {
        if self.texture_lifetime.candidates.is_empty() {
            return Ok(());
        }
        let revision = self
            .revision
            .checked_add(1)
            .ok_or("scene revision exhausted")?;
        while let Some(id) = self.texture_lifetime.candidates.pop_first() {
            self.texture_lifetime.collected(id);
            if let Some(old) = self.textures.remove(&id) {
                self.texture_bytes -= old.pixels.len();
                self.edits.textures.insert(id);
                self.revision = revision;
            }
        }
        Ok(())
    }
}
