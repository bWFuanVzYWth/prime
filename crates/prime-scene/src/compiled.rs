//! Closed geometry produced by native source adapters. No host callbacks or MC layouts.
use crate::{
    SourceScene, Triangle,
    incremental::ContextId,
    protocol::validate_triangle_capacity,
    scene::{Mesh, MeshVersion, SectionSequence},
};
use std::sync::Arc;

/// Closed quad with one source color. Producers retain four corners until immutable publication;
/// expansion preserves the established 0-1-2 / 2-3-0 winding, including nonplanar quads.
#[derive(Clone, Copy)]
pub struct CompiledQuad {
    pub positions: [[f32; 3]; 4],
    pub uvs: [[f32; 2]; 4],
    pub color: [f32; 4],
    pub texture_id: u32,
    pub flags: u32,
}
impl CompiledQuad {
    pub fn triangles(&self) -> [Triangle; 2] {
        [[0, 1, 2], [2, 3, 0]].map(|corners| Triangle {
            positions: corners.map(|i| self.positions[i]),
            colors: [self.color; 3],
            uvs: corners.map(|i| self.uvs[i]),
            texture_id: self.texture_id,
            flags: self.flags,
        })
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct Base {
    owner: ContextId,
    epoch: u64,
    revision: u64,
    completed: SectionSequence,
}

enum Layer {
    Retain(usize),
    Remove,
    Replace(Mesh),
}
impl Layer {
    fn triangle_count(&self) -> usize {
        match self {
            Self::Retain(count) => *count,
            Self::Remove => 0,
            Self::Replace(mesh) => mesh.triangles.len(),
        }
    }
    fn changed(&self) -> bool {
        !matches!(self, Self::Retain(_))
    }
}

/// A content proof against one immutable source snapshot. Only this module can construct it.
/// It cannot be replayed after publication, reset, or against another scene owner.
pub struct CompiledSection {
    base: Base,
    key: u64,
    origin: [f64; 3],
    layers: [Layer; 3],
}
impl CompiledSection {
    pub fn triangle_count(&self) -> usize {
        self.layers.iter().map(Layer::triangle_count).sum()
    }
}

#[derive(Default, Debug)]
pub struct Publication {
    pub replaced_layers: usize,
    pub retained_layers: usize,
    retired: Vec<Option<Arc<[Triangle]>>>,
}
impl Publication {
    /// Release this owner's references after publication, with a synchronous join. Other
    /// scene/upload consumers keep their Arcs; this is not a GPU completion declaration.
    pub fn release_retired(
        &mut self,
        workers: Option<&crate::workers::CpuWorkers>,
    ) -> Result<(), String> {
        // Small batches cost less to drop inline than to wake a pool. The crossover is
        // measured by the section suite; this only chooses a CPU executor, never a lifetime.
        const PARALLEL_RETIRE_BYTES: usize = 8 * 1024 * 1024;
        let bytes: usize = self
            .retired
            .iter()
            .flatten()
            .map(|t| t.len() * size_of::<Triangle>())
            .sum();
        if let Some(workers) = workers
            && bytes >= PARALLEL_RETIRE_BYTES
        {
            workers.chunks_mut(&mut self.retired, 1, |_, retired| {
                for triangles in retired {
                    *triangles = None;
                }
                Ok(())
            })?;
        }
        self.retired.clear();
        Ok(())
    }
}

fn same_triangle(a: &Triangle, b: &Triangle) -> bool {
    a.texture_id == b.texture_id
        && a.flags == b.flags
        && a.positions.map(|p| p.map(f32::to_bits)) == b.positions.map(|p| p.map(f32::to_bits))
        && a.colors.map(|p| p.map(f32::to_bits)) == b.colors.map(|p| p.map(f32::to_bits))
        && a.uvs.map(|p| p.map(f32::to_bits)) == b.uvs.map(|p| p.map(f32::to_bits))
}

impl SourceScene {
    fn compiled_base(&self) -> Base {
        Base {
            owner: self.id,
            epoch: self.epoch,
            revision: self.revision,
            completed: self.section_completed,
        }
    }

    /// Pure preparation, safe on disjoint synchronous workers. The producer owns geometry
    /// validation. Equality, bounds, allocation and redundant output disposal happen here;
    /// publication does not walk triangle arrays. These are the sole producer's three layers.
    pub fn prepare_compiled(
        &self,
        key: u64,
        origin: [f64; 3],
        layers: [Vec<Triangle>; 3],
    ) -> CompiledSection {
        self.prepare_compiled_parts(key, origin, &[&layers])
    }

    /// Preserve fragment order without a merged Vec or a second full geometry copy.
    /// Parts are borrowed only during preparation; the returned proof owns its immutable output.
    pub fn prepare_compiled_parts(
        &self,
        key: u64,
        origin: [f64; 3],
        parts: &[&[Vec<Triangle>; 3]],
    ) -> CompiledSection {
        self.prepare_compiled_layers(
            key,
            origin,
            std::array::from_fn(|layer| {
                let count: usize = parts.iter().map(|p| p[layer].len()).sum();
                let mut triangles = parts.iter().flat_map(move |p| p[layer].iter().copied());
                (0..count).map(move |_| triangles.next().unwrap())
            }),
        )
    }

    /// Expand compact, uniformly colored quads only while comparing or filling the final Arc.
    /// Source fragments and tint results remain borrowed for this synchronous call only.
    pub fn prepare_compiled_quads(
        &self,
        key: u64,
        origin: [f64; 3],
        parts: &[&[Vec<CompiledQuad>; 3]],
    ) -> CompiledSection {
        self.prepare_compiled_layers(
            key,
            origin,
            std::array::from_fn(|layer| {
                let count: usize = parts.iter().map(|p| p[layer].len() * 2).sum();
                let mut triangles = parts
                    .iter()
                    .flat_map(move |p| p[layer].iter().flat_map(CompiledQuad::triangles));
                (0..count).map(move |_| triangles.next().unwrap())
            }),
        )
    }

    fn prepare_compiled_layers<I: ExactSizeIterator<Item = Triangle> + Clone>(
        &self,
        key: u64,
        origin: [f64; 3],
        inputs: [I; 3],
    ) -> CompiledSection {
        let layers = std::array::from_fn(|layer| {
            let index = layer as u32;
            let count = inputs[layer].len();
            let previous = self.meshes.get(&(key, index));
            if count == 0 {
                return if previous.is_some() {
                    Layer::Remove
                } else {
                    Layer::Retain(0)
                };
            }
            if let Some(old) = previous
                && old.origin.map(f64::to_bits) == origin.map(f64::to_bits)
                && old.texture_id == 1
                && old.flags == index
                && old.triangles.len() == count
                && old
                    .triangles
                    .iter()
                    .zip(inputs[layer].clone())
                    .all(|(a, b)| same_triangle(a, &b))
            {
                return Layer::Retain(count);
            }
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            // Range + map is trusted-length: std initializes one Arc allocation directly.
            // Bounds are reduced while copying, rather than scanning the merged output again.
            #[expect(
                clippy::manual_inspect,
                reason = "Keep the measured owned-value adapter for fused bounds and Arc construction"
            )]
            let triangles: Arc<[Triangle]> = inputs[layer]
                .clone()
                .map(|triangle| {
                    for p in triangle.positions {
                        for (axis, value) in p.into_iter().enumerate() {
                            bounds[0][axis] = bounds[0][axis].min(value);
                            bounds[1][axis] = bounds[1][axis].max(value);
                        }
                    }
                    triangle
                })
                .collect();
            Layer::Replace(Mesh {
                revision: MeshVersion::captured(SectionSequence(0)),
                origin,
                triangles,
                bounds,
                texture_id: 1,
                flags: index,
            })
        });
        CompiledSection {
            base: self.compiled_base(),
            key,
            origin,
            layers,
        }
    }

    /// Publish after all preparers join. All fallible checks precede mutation. Equal
    /// geometry retains Arc identity and mesh version but still advances source completion.
    pub fn publish_compiled(
        &mut self,
        epoch: u64,
        sequence: u64,
        sections: Vec<CompiledSection>,
        removed: &[u64],
    ) -> Result<Publication, String> {
        if epoch != self.epoch || sequence <= self.section_completed.0 {
            return Err("stale compiled section batch".into());
        }
        let base = self.compiled_base();
        if sections.iter().any(|s| s.base != base) {
            return Err("compiled section proof is stale or belongs to another owner".into());
        }
        let mut count = self.triangle_count;
        let mut identities = std::collections::BTreeSet::new();
        for key in removed
            .iter()
            .copied()
            .chain(sections.iter().map(|s| s.key))
        {
            if !identities.insert(key) {
                return Err("duplicate compiled section identity".into());
            }
            for ((_, layer), mesh) in self.meshes.range((key, 0)..=(key, u32::MAX)) {
                if *layer > 2 || mesh.revision.observed_at().0 >= sequence {
                    return Err("compiled sections cannot mix terrain producers or precede resident geometry".into());
                }
                count -= mesh.triangles.len();
            }
        }
        for section in &sections {
            count = count
                .checked_add(section.triangle_count())
                .ok_or("compiled geometry size overflow")?;
        }
        let total = count
            .checked_add(self.dynamic.triangles.len())
            .and_then(|n| n.checked_add(self.instances.triangle_count()))
            .ok_or("compiled scene size overflow")?;
        validate_triangle_capacity(total)?;
        if count != 0 && !self.textures.contains_key(&1) {
            return Err("compiled terrain needs the block atlas".into());
        }
        let changed = removed
            .iter()
            .any(|key| self.sections.origin(*key).is_some())
            || sections.iter().any(|s| {
                s.layers.iter().any(Layer::changed)
                    || self.sections.origin(s.key) != Some(&s.origin)
            });
        let revision = if changed {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        } else {
            self.revision
        };
        let mut result = Publication::default();
        for &key in removed {
            if let Some(&origin) = self.sections.origin(key) {
                self.edits.availability(origin);
            }
            self.sections.remove(key);
            for layer in 0..3 {
                if let Some(old) = self.meshes.remove(&(key, layer)) {
                    self.texture_lifetime.release(old.texture_id);
                    result.retired.push(Some(old.triangles));
                    self.edits.meshes.insert((key, layer));
                    result.replaced_layers += 1;
                }
            }
        }
        for section in sections {
            if self.sections.origin(section.key) != Some(&section.origin) {
                if let Some(&old) = self.sections.origin(section.key) {
                    self.edits.availability(old);
                }
                self.edits.availability(section.origin);
                self.sections.publish(section.key, section.origin);
            }
            for (layer, plan) in section.layers.into_iter().enumerate() {
                let key = (section.key, layer as u32);
                if let Layer::Retain(count) = plan {
                    result.retained_layers += usize::from(count != 0);
                    continue;
                }
                if let Some(old) = self.meshes.remove(&key) {
                    self.texture_lifetime.release(old.texture_id);
                    result.retired.push(Some(old.triangles));
                }
                if let Layer::Replace(mut mesh) = plan {
                    mesh.revision = MeshVersion::captured(SectionSequence(sequence));
                    self.texture_lifetime.acquire(mesh.texture_id);
                    self.meshes.insert(key, mesh);
                }
                result.replaced_layers += 1;
                self.edits.meshes.insert(key);
            }
        }
        self.revision = revision;
        self.triangle_count = count;
        self.section_completed = SectionSequence(sequence);
        self.removed.retain(|_, observed| observed.0 > sequence);
        Ok(result)
    }
}

#[cfg(test)]
mod tests;
