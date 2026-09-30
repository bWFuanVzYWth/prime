//! Source ownership and scene references are independent retirement prerequisites.
use std::collections::{BTreeMap, BTreeSet};

/// Charge immutable pixel allocations once, including every animation frame and source mip.
/// Descriptor-only changes keep the same backings and need no resident-texture scan.
#[derive(Default)]
pub(crate) struct TextureMemory {
    references: BTreeMap<usize, usize>,
    bytes: usize,
}
impl TextureMemory {
    pub fn capacity<'a>(
        &self,
        replacements: impl Iterator<Item = (Option<&'a crate::Texture>, &'a crate::Texture)>,
    ) -> Result<usize, String> {
        let mut changes = BTreeMap::<usize, (usize, i64)>::new();
        for (old, new) in replacements {
            if old.is_some_and(|old| old.same_backings(new)) {
                continue;
            }
            for (texture, sign) in old.into_iter().map(|t| (t, -1)).chain([(new, 1)]) {
                for pixels in texture.backings() {
                    changes
                        .entry(pixels.as_ptr() as usize)
                        .or_insert((pixels.len(), 0))
                        .1 += sign;
                }
            }
        }
        let mut bytes = self.bytes as i64;
        for (key, (size, delta)) in changes {
            let old = self.references.get(&key).copied().unwrap_or(0) as i64;
            let new = old + delta;
            debug_assert!(new >= 0);
            bytes += (i64::from(new > 0) - i64::from(old > 0)) * size as i64;
        }
        if bytes > crate::protocol::MAX_TEXTURE_BYTES as i64 {
            return Err("texture capacity exceeded".into());
        }
        Ok(bytes as usize)
    }
    pub fn replace(&mut self, old: Option<&crate::Texture>, new: Option<&crate::Texture>) {
        if old.zip(new).is_some_and(|(a, b)| a.same_backings(b)) {
            return;
        }
        if let Some(texture) = old {
            for pixels in texture.backings() {
                let key = pixels.as_ptr() as usize;
                let count = self
                    .references
                    .get_mut(&key)
                    .expect("owned texture backing");
                *count -= 1;
                if *count == 0 {
                    self.references.remove(&key);
                    self.bytes -= pixels.len();
                }
            }
        }
        if let Some(texture) = new {
            for pixels in texture.backings() {
                let count = self.references.entry(pixels.as_ptr() as usize).or_default();
                if *count == 0 {
                    self.bytes += pixels.len();
                }
                *count += 1;
            }
        }
    }
}

#[derive(Default)]
pub(crate) struct TextureLifetime {
    references: BTreeMap<u32, u64>,
    retired: BTreeSet<u32>,
    pub candidates: BTreeSet<u32>,
}

impl TextureLifetime {
    pub fn acquire_ior_textures(&mut self, geometry: &crate::geometry::MeshGeometry) {
        if let crate::geometry::MeshGeometry::Surfaces(mesh) = geometry {
            for texture in mesh.ior_textures() {
                self.acquire(texture);
            }
        }
    }

    pub fn release_ior_textures(&mut self, geometry: &crate::geometry::MeshGeometry) {
        if let crate::geometry::MeshGeometry::Surfaces(mesh) = geometry {
            for texture in mesh.ior_textures() {
                self.release(texture);
            }
        }
    }

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
                self.texture_memory.replace(Some(&old), None);
                self.edits.textures.insert(id);
                self.revision = revision;
            }
        }
        Ok(())
    }
}
