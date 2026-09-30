//! Closed geometry produced by native source adapters. No host callbacks or MC layouts.
pub use crate::geometry::CompiledQuad;
use crate::geometry::{MeshGeometry, QuadFragments};
use crate::{
    SourceScene, Triangle,
    incremental::ContextId,
    protocol::validate_triangle_capacity,
    scene::{Mesh, MeshVersion, SectionSequence},
};
use std::sync::Arc;

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
    surfaces: [Layer; 3],
}
impl CompiledSection {
    pub fn triangle_count(&self) -> usize {
        self.layers
            .iter()
            .chain(&self.surfaces)
            .map(Layer::triangle_count)
            .sum()
    }
}

#[derive(Default, Debug)]
pub struct Publication {
    pub replaced_layers: usize,
    pub retained_layers: usize,
    retired: Vec<Option<MeshGeometry>>,
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
            .map(MeshGeometry::byte_len)
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
    fn empty_surfaces(&self, key: u64) -> [Layer; 3] {
        std::array::from_fn(|i| {
            if self.meshes.contains_key(&(key, 3 + i as u32)) {
                Layer::Remove
            } else {
                Layer::Retain(0)
            }
        })
    }

    /// Attach already resolved uncommon sheets to the same immutable publication proof.
    /// Ordinary fragments keep their compact allocation and are never expanded to this IR.
    pub fn prepare_surface_layers(
        &self,
        compiled: &mut CompiledSection,
        mut surfaces: [Vec<crate::surface::SurfaceFace>; 3],
    ) {
        compiled.surfaces = std::array::from_fn(|i| {
            let faces = std::mem::take(&mut surfaces[i]);
            let old = self.meshes.get(&(compiled.key, 3 + i as u32));
            if faces.is_empty() {
                return if old.is_some() {
                    Layer::Remove
                } else {
                    Layer::Retain(0)
                };
            }
            if let Some(old) = old
                && old.origin == compiled.origin
                && let MeshGeometry::Surfaces(mesh) = &old.triangles
                && mesh.quads == faces
            {
                return Layer::Retain(faces.len() * 2);
            }
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for p in faces.iter().flat_map(|q| q.geometry.positions) {
                for a in 0..3 {
                    bounds[0][a] = bounds[0][a].min(p[a]);
                    bounds[1][a] = bounds[1][a].max(p[a]);
                }
            }
            Layer::Replace(Mesh {
                revision: MeshVersion::captured(SectionSequence(0)),
                origin: compiled.origin,
                bounds,
                triangles: MeshGeometry::Surfaces(Arc::new(crate::surface::SurfaceMesh {
                    revision: 0,
                    quads: faces,
                    lights: Default::default(),
                    stats: Default::default(),
                })),
                texture_id: 1,
                flags: i as u32,
            })
        });
    }
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

    /// Retain uniformly colored quads through publication, translation and GPU packing.
    /// Source fragments and tint results remain borrowed for this synchronous call only.
    pub fn prepare_compiled_quads(
        &self,
        key: u64,
        origin: [f64; 3],
        parts: &[&[Vec<CompiledQuad>; 3]],
    ) -> CompiledSection {
        let layers = std::array::from_fn(|layer| {
            let count: usize = parts.iter().map(|p| p[layer].len()).sum();
            let previous = self.meshes.get(&(key, layer as u32));
            if count == 0 {
                return if previous.is_some() {
                    Layer::Remove
                } else {
                    Layer::Retain(0)
                };
            }
            let input = || parts.iter().flat_map(|p| p[layer].iter());
            if let Some(old) = previous
                && old.origin.map(f64::to_bits) == origin.map(f64::to_bits)
                && old.texture_id == 1
                && old.flags == layer as u32
                && old.triangles.len() == count * 2
            {
                let equal = match &old.triangles {
                    MeshGeometry::Surfaces(_) => false,
                    MeshGeometry::Quads(quads) => quads.iter().zip(input()).all(|(a, b)| {
                        a.texture_id == b.texture_id
                            && a.flags == b.flags
                            && a.positions.map(|p| p.map(f32::to_bits))
                                == b.positions.map(|p| p.map(f32::to_bits))
                            && a.uvs.map(|p| p.map(f32::to_bits))
                                == b.uvs.map(|p| p.map(f32::to_bits))
                            && a.color.map(f32::to_bits) == b.color.map(f32::to_bits)
                    }),
                    MeshGeometry::QuadFragments(parts) => {
                        parts.iter().zip(input()).all(|(a, b)| a.same_bits(b))
                    }
                    MeshGeometry::Triangles(triangles) => triangles
                        .iter()
                        .zip(input().flat_map(CompiledQuad::triangles))
                        .all(|(a, b)| same_triangle(a, &b)),
                };
                if equal {
                    return Layer::Retain(count * 2);
                }
            }
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            let mut input = input();
            let quads: Arc<[CompiledQuad]> = (0..count)
                .map(|_| {
                    let quad = *input.next().unwrap();
                    for p in quad.positions {
                        for (axis, value) in p.into_iter().enumerate() {
                            bounds[0][axis] = bounds[0][axis].min(value);
                            bounds[1][axis] = bounds[1][axis].max(value);
                        }
                    }
                    quad
                })
                .collect();
            Layer::Replace(Mesh {
                revision: MeshVersion::captured(SectionSequence(0)),
                origin,
                triangles: MeshGeometry::Quads(quads),
                bounds,
                texture_id: 1,
                flags: layer as u32,
            })
        });
        CompiledSection {
            base: self.compiled_base(),
            key,
            origin,
            layers,
            surfaces: self.empty_surfaces(key),
        }
    }

    /// Transfer producer buffers without flattening or copying their vertices. Equal fragments
    /// share the old allocation and bounds; a whole equal layer retains its source identity.
    pub fn prepare_compiled_quad_fragments(
        &self,
        key: u64,
        origin: [f64; 3],
        parts: impl IntoIterator<Item = [Vec<CompiledQuad>; 3]>,
    ) -> CompiledSection {
        let mut parts: Vec<_> = parts.into_iter().collect();
        let layers = std::array::from_fn(|layer| {
            let count: usize = parts.iter().map(|p| p[layer].len()).sum();
            let previous = self.meshes.get(&(key, layer as u32));
            if count == 0 {
                return if previous.is_some() {
                    Layer::Remove
                } else {
                    Layer::Retain(0)
                };
            }
            let compatible = previous.filter(|old| {
                old.origin.map(f64::to_bits) == origin.map(f64::to_bits)
                    && old.texture_id == 1
                    && old.flags == layer as u32
            });
            let old_parts = compatible.and_then(|old| match &old.triangles {
                MeshGeometry::QuadFragments(parts) => Some(&**parts),
                _ => None,
            });
            let same_partition = old_parts.is_some_and(|old| {
                old.blocks.len() == parts.len()
                    && old
                        .blocks
                        .iter()
                        .zip(&parts)
                        .all(|(a, b)| a.values.len() == b[layer].len())
            });
            if !same_partition
                && let Some(old) = compatible
                && !matches!(old.triangles, MeshGeometry::Surfaces(_))
                && old.triangles.len() == count * 2
                && old
                    .triangles
                    .iter()
                    .zip(
                        parts
                            .iter()
                            .flat_map(|p| &p[layer])
                            .flat_map(CompiledQuad::triangles),
                    )
                    .all(|(a, b)| same_triangle(&a, &b))
            {
                return Layer::Retain(count * 2);
            }
            let fragments = QuadFragments::reuse(
                parts.iter_mut().map(|p| std::mem::take(&mut p[layer])),
                old_parts,
            );
            if let Some(old) = old_parts
                && old.blocks.len() == fragments.blocks.len()
                && old
                    .blocks
                    .iter()
                    .zip(&fragments.blocks)
                    .all(|(a, b)| Arc::ptr_eq(a, b))
            {
                return Layer::Retain(count * 2);
            }
            Layer::Replace(Mesh {
                revision: MeshVersion::captured(SectionSequence(0)),
                origin,
                bounds: fragments.bounds(),
                triangles: MeshGeometry::QuadFragments(Arc::new(fragments)),
                texture_id: 1,
                flags: layer as u32,
            })
        });
        CompiledSection {
            base: self.compiled_base(),
            key,
            origin,
            layers,
            surfaces: self.empty_surfaces(key),
        }
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
                && !matches!(old.triangles, MeshGeometry::Surfaces(_))
                && old.triangles.len() == count
                && old
                    .triangles
                    .iter()
                    .zip(inputs[layer].clone())
                    .all(|(a, b)| same_triangle(&a, &b))
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
                triangles: triangles.into(),
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
            surfaces: self.empty_surfaces(key),
        }
    }

    /// A source resource generation becomes visible together with its matching surfaces.
    /// Texture validation reserves enough revision space; geometry publication cannot change
    /// texture capacity, so installing the validated batch afterward is infallible.
    pub fn publish_compiled_with_textures(
        &mut self,
        epoch: u64,
        sequence: u64,
        sections: Vec<CompiledSection>,
        removed: &[u64],
        textures: Vec<(u32, crate::Texture)>,
    ) -> Result<Publication, String> {
        self.validate_textures(&textures)?;
        self.revision
            .checked_add(2)
            .ok_or("scene revision exhausted")?;
        let publication = self.publish_compiled(epoch, sequence, sections, removed)?;
        self.set_textures(textures)
            .expect("validated texture batch under exclusive scene ownership");
        Ok(publication)
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
                if *layer > 5 || mesh.revision.observed_at().0 >= sequence {
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
                s.layers.iter().chain(&s.surfaces).any(Layer::changed)
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
            for layer in 0..6 {
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
            for (layer, plan) in section
                .layers
                .into_iter()
                .chain(section.surfaces)
                .enumerate()
            {
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
