//! Versioned byte protocol. All reads are little endian and alignment independent.
use crate::{
    instances::InstanceContext,
    scene::{
        Camera, Instance, Mesh, MeshVersion, Prototype, SectionSequence, SourceScene, Texture,
        Triangle,
    },
};

#[cfg(test)]
#[path = "protocol_capacity_tests.rs"]
mod capacity_tests;

pub const ABI_VERSION: u32 = 7;
pub const MAGIC: u32 = 0x5450_5250;
pub const MAX_PACKET_BYTES: usize = 256 * 1024 * 1024;
pub(crate) const MAX_TEXTURE_BYTES: usize = 512 * 1024 * 1024;

pub(crate) fn validate_triangle_capacity(total: usize) -> Result<(), String> {
    // Scene counts are independent of per-packet counts and GPU page addresses.
    // This is an address-space check, not a promise of physical memory residency.
    total
        .checked_mul(std::mem::size_of::<Triangle>())
        .ok_or("source triangle storage size overflows the host address space")?;
    Ok(())
}

pub(crate) struct Reader<'a> {
    pub(crate) data: &'a [u8],
    pub(crate) offset: usize,
}
impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Result<Self, String> {
        if data.len() > MAX_PACKET_BYTES {
            return Err("packet exceeds 256 MiB limit".into());
        }
        Ok(Self { data, offset: 0 })
    }
    pub(crate) fn take(&mut self, size: usize) -> Result<&'a [u8], String> {
        let end = self
            .offset
            .checked_add(size)
            .ok_or("packet length overflow")?;
        let value = self.data.get(self.offset..end).ok_or("truncated packet")?;
        self.offset = end;
        Ok(value)
    }
    pub(crate) fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub(crate) fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    pub(crate) fn f32(&mut self) -> Result<f32, String> {
        let value = f32::from_bits(self.u32()?);
        if !value.is_finite() {
            return Err("non-finite f32 in packet".into());
        }
        Ok(value)
    }
    pub(crate) fn f64(&mut self) -> Result<f64, String> {
        let value = f64::from_bits(self.u64()?);
        if !value.is_finite() {
            return Err("non-finite f64 in packet".into());
        }
        Ok(value)
    }
    pub(crate) fn vector(&mut self) -> Result<[f32; 3], String> {
        Ok([self.f32()?, self.f32()?, self.f32()?])
    }
    pub(crate) fn origin(&mut self) -> Result<[f64; 3], String> {
        let value = [self.f64()?, self.f64()?, self.f64()?];
        if value.iter().any(|x| x.abs() > 32_000_000.0) {
            return Err("world position out of range".into());
        }
        Ok(value)
    }
    pub(crate) fn zero(&mut self) -> Result<(), String> {
        if self.u32()? != 0 {
            return Err("reserved field must be zero".into());
        }
        Ok(())
    }
    fn header(&mut self) -> Result<(u32, u64), String> {
        if self.u32()? != MAGIC {
            return Err("invalid PRPT packet magic".into());
        }
        if self.u32()? != ABI_VERSION {
            return Err("unsupported packet ABI version".into());
        }
        let op = self.u32()?;
        self.zero()?;
        Ok((op, self.u64()?))
    }
    pub(crate) fn finish(&self) -> Result<(), String> {
        if self.offset != self.data.len() {
            return Err("trailing bytes in packet".into());
        }
        Ok(())
    }
}

impl SourceScene {
    /// Validate fully before publication. Failed or stale packets never change the scene.
    pub fn submit(&mut self, bytes: &[u8]) -> Result<(), String> {
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        #[cfg(test)]
        if op == 12 || op == 13 {
            if epoch == 0 || epoch != self.epoch {
                return Err("stale or uninitialized resource epoch".into());
            }
            return if op == 13 {
                self.routing.resources(input)
            } else {
                self.route_section(input)
            };
        }
        if op == 7 {
            std::mem::swap(
                &mut self.texture_lifetime,
                &mut self.instances.texture_lifetime,
            );
            let result = self.instances.submit(
                bytes,
                &self.textures,
                self.triangle_count + self.dynamic.triangles.len(),
            );
            std::mem::swap(
                &mut self.texture_lifetime,
                &mut self.instances.texture_lifetime,
            );
            return result;
        }
        if op == 8 {
            if epoch == 0 || epoch != self.epoch {
                return Err("stale or uninitialized resource epoch".into());
            }
            return self.replace_section(input);
        }
        if op == 10 || op == 11 {
            if epoch == 0 || epoch != self.epoch {
                return Err("stale or uninitialized resource epoch".into());
            }
            if op == 11 {
                return self.remove_sections(input);
            }
            let completed = SectionSequence(input.u64()?);
            input.finish()?;
            if completed < self.section_completed {
                return Err("section completion watermark regressed".into());
            }
            self.section_completed = completed;
            self.removed.retain(|_, sequence| *sequence > completed);
            return Ok(());
        }
        let revision = if op == 6 {
            self.revision
        } else {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        };
        if op == 1 {
            input.finish()?;
            if epoch <= self.epoch {
                return Err("reset epoch must increase".into());
            }
            *self = SourceScene {
                epoch,
                revision,
                instances: InstanceContext::new(epoch),
                ..Default::default()
            };
            return Ok(());
        }
        if epoch == 0 || epoch != self.epoch {
            return Err("stale or uninitialized resource epoch".into());
        }
        let mut changed = true;
        match op {
            3 => {
                let key = input.u64()?;
                let mesh_revision = SectionSequence(input.u64()?);
                input.finish()?;
                if mesh_revision <= self.section_completed
                    || mesh_revision <= self.removed.get(&key).copied().unwrap_or_default()
                    || self
                        .meshes
                        .range((key, 0)..=(key, u32::MAX))
                        .any(|(_, m)| m.revision.observed_at() >= mesh_revision)
                {
                    return Err("stale section removal".into());
                }
                let keys: Vec<_> = self
                    .meshes
                    .range((key, 0)..=(key, u32::MAX))
                    .map(|(k, _)| *k)
                    .collect();
                if let Some(&origin) = self.sections.origin(key) {
                    self.edits.availability(origin);
                }
                changed = self.sections.remove(key);
                for mesh_key in keys {
                    let mesh = self.meshes.remove(&mesh_key).unwrap();
                    self.texture_lifetime.release(mesh.texture_id);
                    self.texture_lifetime.release_ior_textures(&mesh.triangles);
                    self.triangle_count -= mesh.triangles.len();
                    changed |= !mesh.triangles.is_empty();
                    self.edits.meshes.insert(mesh_key);
                }
                self.removed.insert(key, mesh_revision);
            }
            4 => {
                let id = input.u32()?;
                let width = input.u32()?;
                let height = input.u32()?;
                input.zero()?;
                if id == 0
                    || id == u32::MAX
                    || width == 0
                    || height == 0
                    || width > 16384
                    || height > 16384
                {
                    return Err("invalid texture identity or extent".into());
                }
                let len = width as usize * height as usize * 4;
                let pixels = input.take(len)?;
                input.finish()?;
                let texture = Texture {
                    region: None,
                    sampling: None,
                    material: None,
                    width,
                    height,
                    pixels: pixels.into(),
                };
                self.texture_memory
                    .capacity(std::iter::once((self.textures.get(&id), &texture)))?;
                self.texture_memory
                    .replace(self.textures.get(&id), Some(&texture));
                self.texture_lifetime.owned(id);
                self.textures.insert(id, texture);
                self.edits.textures.insert(id);
            }
            9 => {
                let count = input.u32()? as usize;
                input.zero()?;
                let mut ids = Vec::with_capacity(count.min((input.data.len() - input.offset) / 4));
                for _ in 0..count {
                    let id = input.u32()?;
                    if id <= 1 || id == u32::MAX {
                        return Err("cannot retire a reserved texture identity".into());
                    }
                    ids.push(id);
                }
                input.finish()?;
                for id in ids {
                    if self.textures.contains_key(&id) {
                        self.texture_lifetime.retire(id);
                    }
                }
                changed = false;
            }
            6 => {
                let sequence = input.u64()?;
                let origin = input.origin()?;
                let span_count = input.u32()? as usize;
                input.zero()?;
                if sequence <= self.dynamic.sequence {
                    return Err(
                        "dynamic frame sequence must increase within its resource epoch".into(),
                    );
                }
                if span_count > (input.data.len() - input.offset) / 32 {
                    return Err("truncated dynamic span descriptors".into());
                }
                // Exact bytes, not a hash or a frame-age heuristic. This owned payload was
                // fully validated before publication and its texture references are still held.
                // Source callbacks have already run; unchanged observations only advance ordering.
                let payload = &bytes[32..];
                if self.dynamic.source.get(24..) == Some(&payload[24..]) {
                    self.dynamic.sequence = sequence;
                    if self.dynamic.origin.map(f64::to_bits) != origin.map(f64::to_bits) {
                        // The local geometry is unchanged even when its world origin moves.
                        self.dynamic.revision = sequence;
                        self.dynamic.origin = origin;
                        self.dynamic.source[..24].copy_from_slice(&payload[..24]);
                    }
                    return Ok(());
                }
                // Only reclaim allocations with no published CPU borrower. A failed decode
                // returns its private workspace without changing the visible snapshot.
                let spare = self
                    .dynamic_spares
                    .iter()
                    .position(|s| std::sync::Arc::strong_count(s) == 1);
                let mut storage = spare
                    .map(|i| self.dynamic_spares.swap_remove(i))
                    .unwrap_or_default();
                // Surplus workspaces can leave only after their last published CPU borrower.
                self.dynamic_spares
                    .retain(|spare| std::sync::Arc::strong_count(spare) != 1);
                let triangles = std::sync::Arc::get_mut(&mut storage).unwrap();
                triangles.clear();
                let mut texture_ids = std::collections::BTreeSet::new();
                let decoded = (|| {
                    let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
                    for _ in 0..span_count {
                        let texture_id = input.u32()?;
                        let flags = input.u32()?;
                        let topology = input.u32()? as usize;
                        let count = input.u32()? as usize;
                        let stride = input.u32()? as usize;
                        let position_offset = input.u32()? as usize;
                        let color_offset = input.u32()? as usize;
                        let uv_offset = input.u32()? as usize;
                        validate_flags(flags)?;
                        let layout = VertexLayout {
                            count,
                            stride,
                            position_offset,
                            color_offset,
                            uv_offset,
                            topology,
                        };
                        if topology == 1 {
                            if stride != 52
                                || position_offset != 0
                                || color_offset != 48
                                || uv_offset != 32
                            {
                                return Err("invalid billboard source layout".into());
                            }
                        } else {
                            layout.validate()?;
                        }
                        if count != 0 && texture_id != 0 && !self.textures.contains_key(&texture_id)
                        {
                            return Err(
                                "dynamic frame references a texture that has not been captured"
                                    .into(),
                            );
                        }
                        let raw = input.take(
                            count
                                .checked_mul(stride)
                                .ok_or("vertex byte count overflow")?,
                        )?;
                        if count > 0 {
                            texture_ids.insert(texture_id);
                        }
                        let triangle_count = if topology == 1 {
                            count.checked_mul(2).ok_or("billboard count overflow")?
                        } else {
                            count / topology * (topology - 2)
                        };
                        validate_triangle_capacity(
                            self.triangle_count
                                + triangles.len()
                                + triangle_count
                                + self.instances.triangle_count(),
                        )?;
                        triangles
                            .try_reserve(triangle_count)
                            .map_err(|_| "dynamic frame allocation failed")?;
                        if topology == 1 {
                            self.routing.billboards(
                                raw,
                                texture_id,
                                flags,
                                triangles,
                                &mut bounds,
                            )?;
                        } else {
                            decode_vertices(
                                raw,
                                &layout,
                                texture_id,
                                flags,
                                triangles,
                                &mut bounds,
                            )?;
                        }
                    }
                    input.finish()?;
                    Ok::<_, String>(bounds)
                })();
                let bounds = match decoded {
                    Ok(bounds) => bounds,
                    Err(error) => {
                        self.dynamic_spares.push(storage);
                        return Err(error);
                    }
                };
                if self
                    .dynamic
                    .source
                    .try_reserve(payload.len().saturating_sub(self.dynamic.source.len()))
                    .is_err()
                {
                    self.dynamic_spares.push(storage);
                    return Err("dynamic source cache allocation failed".into());
                }
                for &id in &texture_ids {
                    self.texture_lifetime.acquire(id);
                }
                for &id in &self.dynamic.texture_ids {
                    self.texture_lifetime.release(id);
                }
                self.dynamic.texture_ids = texture_ids;
                self.dynamic_spares
                    .push(std::mem::replace(&mut self.dynamic.triangles, storage));
                self.dynamic.revision = sequence;
                self.dynamic.sequence = sequence;
                self.dynamic.source.clear();
                self.dynamic.source.extend_from_slice(payload);
                self.dynamic.origin = origin;
                self.dynamic.bounds = bounds;
                changed = false;
            }
            _ => return Err("unknown scene operation".into()),
        }
        if changed {
            self.revision = revision;
        }
        Ok(())
    }
}

/// Temporary owned decoding memory; never aliases the caller's FFM packet or published meshes.
/// Every operation consumes or clears its contents before return, retaining only capacity.
#[derive(Default)]
pub(crate) struct SectionScratch {
    vertices: Vec<Triangle>,
    old_layers: Vec<u32>,
    replacements: Vec<(u32, Mesh)>,
}

impl SectionScratch {
    fn clear(&mut self) {
        self.vertices.clear();
        self.old_layers.clear();
        self.replacements.clear();
    }
}

struct SectionPlan {
    key: u64,
    origin: [f64; 3],
    sequence: SectionSequence,
    present: [bool; 256],
    triangle_count: usize,
    revision: u64,
}

impl SourceScene {
    fn remove_sections(&mut self, mut input: Reader<'_>) -> Result<(), String> {
        let count = input.u32()? as usize;
        input.zero()?;
        if count > (input.data.len() - input.offset) / 16 {
            return Err("truncated section removals".into());
        }
        let mut removals = Vec::with_capacity(count);
        for _ in 0..count {
            removals.push((input.u64()?, SectionSequence(input.u64()?)));
        }
        input.finish()?;
        removals.sort_unstable_by_key(|r| r.0);
        if removals.windows(2).any(|w| w[0].0 == w[1].0) {
            return Err("duplicate section removal".into());
        }
        let mut changed = false;
        for &(key, sequence) in &removals {
            if sequence <= self.section_completed
                || sequence <= self.removed.get(&key).copied().unwrap_or_default()
                || self
                    .meshes
                    .range((key, 0)..=(key, u32::MAX))
                    .any(|(_, m)| m.revision.observed_at() >= sequence)
            {
                return Err("stale section removal".into());
            }
            changed |= self.sections.origin(key).is_some()
                || self
                    .meshes
                    .range((key, 0)..=(key, u32::MAX))
                    .any(|(_, m)| !m.triangles.is_empty());
        }
        let revision = if changed {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        } else {
            self.revision
        };
        for (key, sequence) in removals {
            if let Some(&origin) = self.sections.origin(key) {
                self.edits.availability(origin);
            }
            self.sections.remove(key);
            self.section_scratch.old_layers.clear();
            self.section_scratch.old_layers.extend(
                self.meshes
                    .range((key, 0)..=(key, u32::MAX))
                    .map(|(key, _)| key.1),
            );
            for layer in self.section_scratch.old_layers.drain(..) {
                let mesh = self.meshes.remove(&(key, layer)).unwrap();
                self.triangle_count -= mesh.triangles.len();
                self.texture_lifetime.release(mesh.texture_id);
                self.texture_lifetime.release_ior_textures(&mesh.triangles);
                self.edits.meshes.insert((key, layer));
            }
            self.removed.insert(key, sequence);
        }
        self.revision = revision;
        Ok(())
    }

    fn replace_section(&mut self, mut input: Reader<'_>) -> Result<(), String> {
        // The planner may reuse owned scratch, but cannot mutate the published source scene.
        let mut scratch = std::mem::take(&mut self.section_scratch);
        let result = self.plan_section(&mut input, &mut scratch).map(|plan| {
            for layer in &scratch.old_layers {
                if !plan.present[*layer as usize] {
                    if let Some(old) = self.meshes.remove(&(plan.key, *layer)) {
                        self.texture_lifetime.release(old.texture_id);
                        self.texture_lifetime.release_ior_textures(&old.triangles);
                    }
                    self.edits.meshes.insert((plan.key, *layer));
                }
            }
            for (layer, mesh) in scratch.replacements.drain(..) {
                self.texture_lifetime.acquire(mesh.texture_id);
                if let Some(old) = self.meshes.insert((plan.key, layer), mesh) {
                    self.texture_lifetime.release(old.texture_id);
                    self.texture_lifetime.release_ior_textures(&old.triangles);
                }
                self.edits.meshes.insert((plan.key, layer));
            }
            self.triangle_count = plan.triangle_count;
            self.revision = plan.revision;
            self.removed.insert(plan.key, plan.sequence);
            if self.sections.origin(plan.key) != Some(&plan.origin) {
                if let Some(&old) = self.sections.origin(plan.key) {
                    self.edits.availability(old);
                }
                self.edits.availability(plan.origin);
                self.sections.publish(plan.key, plan.origin);
            }
        });
        // Failed plans cannot leave geometry or pending edits for the next packet.
        scratch.clear();
        self.section_scratch = scratch;
        result
    }

    fn plan_section(
        &self,
        input: &mut Reader<'_>,
        scratch: &mut SectionScratch,
    ) -> Result<SectionPlan, String> {
        let key = input.u64()?;
        let sequence = SectionSequence(input.u64()?);
        let origin = input.origin()?;
        let layers = input.u32()? as usize;
        input.zero()?;
        if layers > 256 || layers > (input.data.len() - input.offset) / 40 {
            return Err("invalid or truncated section layer count".into());
        }
        if sequence <= self.section_completed
            || sequence <= self.removed.get(&key).copied().unwrap_or_default()
        {
            return Err("stale complete section sequence".into());
        }
        let mut old_triangles = 0;
        for ((_, layer), mesh) in self.meshes.range((key, 0)..=(key, u32::MAX)) {
            if sequence <= mesh.revision.observed_at() {
                return Err("complete section sequence predates a resident layer".into());
            }
            scratch.old_layers.push(*layer);
            old_triangles += mesh.triangles.len();
        }
        let other_triangles = self.triangle_count - old_triangles;
        let shared_triangles = self.dynamic.triangles.len() + self.instances.triangle_count();
        let mut triangles = 0;
        let mut present = [false; 256];
        let mut seen = [false; 256];
        for _ in 0..layers {
            let layer = input.u32()?;
            let texture_id = input.u32()?;
            let flags = input.u32()?;
            let layout = VertexLayout {
                topology: input.u32()? as usize,
                count: input.u32()? as usize,
                stride: input.u32()? as usize,
                position_offset: input.u32()? as usize,
                color_offset: input.u32()? as usize,
                uv_offset: input.u32()? as usize,
            };
            input.zero()?;
            if layer > 255 || seen[layer as usize] {
                return Err("invalid or duplicate section layer".into());
            }
            seen[layer as usize] = true;
            validate_flags(flags)?;
            layout.validate()?;
            let raw = input.take(
                layout
                    .count
                    .checked_mul(layout.stride)
                    .ok_or("vertex byte count overflow")?,
            )?;
            let count = layout.count / layout.topology * (layout.topology - 2);
            triangles += count;
            validate_triangle_capacity(other_triangles + triangles + shared_triangles)?;
            if count != 0 && texture_id != 0 && !self.textures.contains_key(&texture_id) {
                return Err("section references a texture that has not been captured".into());
            }
            if count == 0 {
                continue;
            }
            present[layer as usize] = true;
            // Compare source fields directly with the validated resident triangles. An exact match
            // proves the same finite/range-checked values without decoding another expanded mesh.
            // No source packet cache, hash collision assumption, or extra retained vertex copy.
            if self.meshes.get(&(key, layer)).is_some_and(|old| {
                old.origin.map(f64::to_bits) == origin.map(f64::to_bits)
                    && old.texture_id == texture_id
                    && old.flags == flags
                    && equal_source_triangles(raw, &layout, &old.triangles)
            }) {
                continue;
            }
            scratch.vertices.clear();
            scratch
                .vertices
                .try_reserve(count)
                .map_err(|_| "section decoding allocation failed")?;
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            decode_vertices(
                raw,
                &layout,
                texture_id,
                flags,
                &mut scratch.vertices,
                &mut bounds,
            )?;
            scratch.replacements.push((
                layer,
                Mesh {
                    revision: MeshVersion::captured(sequence),
                    origin,
                    triangles: scratch.vertices.as_slice().into(),
                    bounds,
                    texture_id,
                    flags,
                },
            ));
        }
        input.finish()?;
        let changed = self.sections.origin(key) != Some(&origin)
            || !scratch.replacements.is_empty()
            || scratch.old_layers.iter().any(|layer| {
                !present[*layer as usize] && !self.meshes[&(key, *layer)].triangles.is_empty()
            });
        let revision = if changed {
            self.revision
                .checked_add(1)
                .ok_or("scene revision exhausted")?
        } else {
            self.revision
        };
        Ok(SectionPlan {
            key,
            origin,
            sequence,
            present,
            triangle_count: other_triangles + triangles,
            revision,
        })
    }
}

/// Bit equality is deliberately conservative (even signed zero differs), never hash-only.
/// Irrelevant padding, normals and source stride are not rendered material/geometry fields.
fn equal_source_triangles(
    raw: &[u8],
    layout: &VertexLayout,
    old: &crate::geometry::MeshGeometry,
) -> bool {
    let per_primitive = layout.topology - 2;
    if old.len() != layout.count / layout.topology * per_primitive {
        return false;
    }
    raw.chunks_exact(layout.stride * layout.topology)
        .enumerate()
        .all(|(index, primitive)| {
            (0..per_primitive).all(|triangle| {
                let old = old.triangle(index * per_primitive + triangle);
                let indices = if triangle == 0 { [0, 1, 2] } else { [2, 3, 0] };
                indices.into_iter().enumerate().all(|(corner, index)| {
                    let source = &primitive[index * layout.stride..][..layout.stride];
                    let position = &source[layout.position_offset..][..12];
                    let uv = &source[layout.uv_offset..][..8];
                    old.positions[corner]
                        .iter()
                        .zip(position.as_chunks::<4>().0)
                        .all(|(old, value)| old.to_bits() == u32::from_le_bytes(*value))
                        && old.uvs[corner]
                            .iter()
                            .zip(uv.as_chunks::<4>().0)
                            .all(|(old, value)| old.to_bits() == u32::from_le_bytes(*value))
                        && old.colors[corner].iter().enumerate().all(|(channel, old)| {
                            old.to_bits()
                                == (f32::from(source[layout.color_offset + channel]) / 255.0)
                                    .to_bits()
                        })
                })
            })
        })
}

/// Owned decoding result, independent of all current scene state.
/// Publication uses InstanceContext's read-only validation followed by one mutation phase.
#[derive(Default)]
pub struct InstanceBatch {
    pub(crate) epoch: u64,
    pub(crate) sequence: u64,
    pub(crate) prototypes: Vec<(u64, Prototype)>,
    pub(crate) prototype_removals: Vec<(u64, u64)>,
    pub(crate) instances: Vec<(u64, Instance)>,
    pub(crate) instance_removals: Vec<(u64, u64)>,
}

impl InstanceBatch {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        let mut batch = Self::default();
        batch.decode(bytes)?;
        Ok(batch)
    }

    /// Scratch owns decoded values only; partial results are never published after an error.
    /// Clearing retains record capacity, while dropping any uncommitted prototype geometry.
    pub(crate) fn clear(&mut self) {
        self.prototypes.clear();
        self.prototype_removals.clear();
        self.instances.clear();
        self.instance_removals.clear();
    }

    pub(crate) fn decode(&mut self, bytes: &[u8]) -> Result<(), String> {
        self.clear();
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        if op != 7 {
            return Err("expected instance batch operation 7".into());
        }
        let sequence = input.u64()?;
        let counts = [input.u32()?, input.u32()?, input.u32()?, input.u32()?];
        let minimum_bytes = counts
            .into_iter()
            .zip([24_u64, 16, 128, 16])
            .map(|(count, stride)| u64::from(count) * stride)
            .sum::<u64>();
        if minimum_bytes == 0 {
            return Err("empty instance batches must not be submitted".into());
        }
        if minimum_bytes > (input.data.len() - input.offset) as u64 {
            return Err("truncated instance batch records".into());
        }
        self.epoch = epoch;
        self.sequence = sequence;
        self.prototypes
            .try_reserve(counts[0] as usize)
            .map_err(|_| "prototype record allocation failed")?;
        self.prototype_removals
            .try_reserve(counts[1] as usize)
            .map_err(|_| "prototype removal allocation failed")?;
        self.instances
            .try_reserve(counts[2] as usize)
            .map_err(|_| "instance record allocation failed")?;
        self.instance_removals
            .try_reserve(counts[3] as usize)
            .map_err(|_| "instance removal allocation failed")?;
        let mut triangle_count = 0;
        for _ in 0..counts[0] {
            let id = input.u64()?;
            let revision = input.u64()?;
            validate_record(id, revision, sequence)?;
            let span_count = input.u32()?;
            input.zero()?;
            if span_count as usize > (input.data.len() - input.offset) / 32 {
                return Err("truncated prototype span descriptors".into());
            }
            let mut triangles = Vec::new();
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for _ in 0..span_count {
                let texture_id = input.u32()?;
                let flags = input.u32()?;
                let layout = VertexLayout {
                    topology: input.u32()? as usize,
                    count: input.u32()? as usize,
                    stride: input.u32()? as usize,
                    position_offset: input.u32()? as usize,
                    color_offset: input.u32()? as usize,
                    uv_offset: input.u32()? as usize,
                };
                validate_flags(flags)?;
                layout.validate()?;
                let raw = input.take(
                    layout
                        .count
                        .checked_mul(layout.stride)
                        .ok_or("vertex byte count overflow")?,
                )?;
                let count = layout.count / layout.topology * (layout.topology - 2);
                triangle_count += count;
                validate_triangle_capacity(triangle_count)?;
                triangles
                    .try_reserve(count)
                    .map_err(|_| "prototype allocation failed")?;
                decode_vertices(raw, &layout, texture_id, flags, &mut triangles, &mut bounds)?;
            }
            if triangles.is_empty() {
                return Err("prototype must contain at least one triangle".into());
            }
            self.prototypes.push((
                id,
                Prototype {
                    revision,
                    triangles: triangles.into(),
                    bounds,
                },
            ));
        }
        for _ in 0..counts[1] {
            let (id, revision) = (input.u64()?, input.u64()?);
            validate_record(id, revision, sequence)?;
            self.prototype_removals.push((id, revision));
        }
        for _ in 0..counts[2] {
            let id = input.u64()?;
            let revision = input.u64()?;
            validate_record(id, revision, sequence)?;
            let prototype_id = input.u64()?;
            let origin = input.origin()?;
            let mut transform = [0.0; 12];
            for value in &mut transform {
                *value = input.f32()?;
            }
            validate_affine(transform)?;
            let texture_id = input.u32()?;
            let flags = input.u32()?;
            if flags != u32::MAX {
                validate_flags(flags)?;
            }
            let tint = input.take(4)?.try_into().unwrap();
            input.zero()?;
            let uv_transform = [input.f32()?, input.f32()?, input.f32()?, input.f32()?];
            self.instances.push((
                id,
                Instance {
                    revision,
                    prototype_id,
                    origin,
                    transform,
                    texture_id,
                    flags,
                    tint,
                    uv_transform,
                },
            ));
        }
        for _ in 0..counts[3] {
            let (id, revision) = (input.u64()?, input.u64()?);
            validate_record(id, revision, sequence)?;
            self.instance_removals.push((id, revision));
        }
        input.finish()?;
        validate_unique(&mut self.prototypes, &mut self.prototype_removals)?;
        validate_unique(&mut self.instances, &mut self.instance_removals)?;
        Ok(())
    }
}

fn validate_record(id: u64, revision: u64, sequence: u64) -> Result<(), String> {
    if id == 0 {
        return Err("instance batch identities must be nonzero".into());
    }
    if revision != sequence {
        return Err("prototype and instance revisions must equal their batch sequence".into());
    }
    Ok(())
}

/// Ordering is not a wire requirement. Canonicalize owned records in place, then reject
/// duplicates both within and across update/removal groups without allocating another set.
fn validate_unique<T>(updates: &mut [(u64, T)], removals: &mut [(u64, u64)]) -> Result<(), String> {
    updates.sort_unstable_by_key(|(id, _)| *id);
    removals.sort_unstable_by_key(|(id, _)| *id);
    if updates.windows(2).any(|pair| pair[0].0 == pair[1].0)
        || removals.windows(2).any(|pair| pair[0].0 == pair[1].0)
    {
        return Err("duplicate identity within instance batch category".into());
    }
    let mut updates = updates.iter().peekable();
    for (removed, _) in removals {
        while updates.peek().is_some_and(|(id, _)| *id < *removed) {
            updates.next();
        }
        if updates.peek().is_some_and(|(id, _)| *id == *removed) {
            return Err("identity is both updated and removed in instance batch".into());
        }
    }
    Ok(())
}

fn validate_affine(transform: [f32; 12]) -> Result<(), String> {
    let m = transform.map(f64::from);
    let cofactors = [
        m[5] * m[10] - m[6] * m[9],
        m[6] * m[8] - m[4] * m[10],
        m[4] * m[9] - m[5] * m[8],
        m[2] * m[9] - m[1] * m[10],
        m[0] * m[10] - m[2] * m[8],
        m[1] * m[8] - m[0] * m[9],
        m[1] * m[6] - m[2] * m[5],
        m[2] * m[4] - m[0] * m[6],
        m[0] * m[5] - m[1] * m[4],
    ];
    let determinant = m[0] * cofactors[0] + m[1] * cofactors[1] + m[2] * cofactors[2];
    if determinant == 0.0
        || cofactors
            .iter()
            .any(|cofactor| !((*cofactor / determinant) as f32).is_finite())
    {
        return Err("instance affine must have a finite representable inverse".into());
    }
    Ok(())
}

fn validate_flags(flags: u32) -> Result<(), String> {
    if flags > 2 {
        return Err("material flags must be opaque (0), cutout (1), or alpha blend (2)".into());
    }
    Ok(())
}

struct VertexLayout {
    count: usize,
    stride: usize,
    position_offset: usize,
    color_offset: usize,
    uv_offset: usize,
    topology: usize,
}

impl VertexLayout {
    fn validate(&self) -> Result<(), String> {
        if self.topology != 3 && self.topology != 4 {
            return Err("topology must be triangles (3) or quads (4)".into());
        }
        if !self.count.is_multiple_of(self.topology) {
            return Err("incomplete primitive".into());
        }
        if !(24..=256).contains(&self.stride)
            || self.position_offset > self.stride - 12
            || self.color_offset > self.stride - 4
            || self.uv_offset > self.stride - 8
        {
            return Err("invalid source vertex layout".into());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Default)]
struct Vertex {
    position: [f32; 3],
    color: [f32; 4],
    uv: [f32; 2],
}

/// Decode each source corner once; triangulation only copies the validated values.
fn decode_vertices(
    raw: &[u8],
    layout: &VertexLayout,
    texture_id: u32,
    flags: u32,
    triangles: &mut Vec<Triangle>,
    bounds: &mut [[f32; 3]; 2],
) -> Result<(), String> {
    for primitive in raw.chunks_exact(layout.stride * layout.topology) {
        let mut vertices = [Vertex::default(); 4];
        for (vertex, source) in vertices
            .iter_mut()
            .zip(primitive.chunks_exact(layout.stride))
        {
            let mut position =
                Reader::new(&source[layout.position_offset..layout.position_offset + 12])?;
            vertex.position = position.vector()?;
            if vertex.position.iter().any(|v| v.abs() > 4096.0) {
                return Err("source mesh must use local positions within 4096 blocks".into());
            }
            for (i, value) in vertex.position.into_iter().enumerate() {
                bounds[0][i] = bounds[0][i].min(value);
                bounds[1][i] = bounds[1][i].max(value);
            }
            vertex.color =
                std::array::from_fn(|i| f32::from(source[layout.color_offset + i]) / 255.0);
            let mut uv = Reader::new(&source[layout.uv_offset..layout.uv_offset + 8])?;
            vertex.uv = [uv.f32()?, uv.f32()?];
        }
        for indices in if layout.topology == 4 {
            &[[0, 1, 2], [2, 3, 0]][..]
        } else {
            &[[0, 1, 2]][..]
        } {
            triangles.push(Triangle {
                positions: indices.map(|i| vertices[i].position),
                colors: indices.map(|i| vertices[i].color),
                uvs: indices.map(|i| vertices[i].uv),
                texture_id,
                flags,
            });
        }
    }
    Ok(())
}

#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub epoch: u64,
    pub world_position: [f64; 3],
    pub camera: Camera,
    pub width: u32,
    pub height: u32,
    pub sample_index: u32,
    pub solar_hour_angle: f32,
}
impl Frame {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        let mut input = Reader::new(bytes)?;
        let (op, epoch) = input.header()?;
        if op != 5 {
            return Err("expected frame operation 5".into());
        }
        let world_position = input.origin()?;
        let forward = input.vector()?;
        let right = input.vector()?;
        let up = input.vector()?;
        for v in [forward, right, up] {
            let squared: f32 = v.into_iter().map(|a| a * a).sum();
            if (squared - 1.0).abs() > 0.01 {
                return Err("camera basis must be normalized".into());
            }
        }
        for (a, b) in [(forward, right), (forward, up), (right, up)] {
            if (0..3).map(|i| a[i] * b[i]).sum::<f32>().abs() > 0.01 {
                return Err("camera basis must be orthogonal".into());
            }
        }
        let vertical_fov_radians = input.f32()?;
        if !(0.01..3.0).contains(&vertical_fov_radians) {
            return Err("invalid vertical field of view".into());
        }
        let width = input.u32()?;
        let height = input.u32()?;
        let sample_index = input.u32()?;
        let solar_hour_angle = input.f32()?;
        input.finish()?;
        crate::extent::RenderExtent::new(width, height)?;
        Ok(Self {
            epoch,
            world_position,
            camera: Camera {
                position: [0.0; 3],
                forward,
                right,
                up,
                vertical_fov_radians,
            },
            width,
            height,
            sample_index,
            solar_hour_angle,
        })
    }
    pub fn anchor(&self) -> [f64; 3] {
        self.world_position.map(|p| (p / 256.0).floor() * 256.0)
    }
    pub fn relative_camera(&self, anchor: [f64; 3]) -> Camera {
        Camera {
            position: std::array::from_fn(|i| (self.world_position[i] - anchor[i]) as f32),
            ..self.camera
        }
    }
    pub fn output_len(&self) -> usize {
        self.width as usize * self.height as usize * 4
    }
}
