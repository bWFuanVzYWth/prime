//! Stable texture identities; newly observed entity textures do not repack terrain pixels.
use super::geometry::transfer_barrier;
use super::resources::{Buffer, Context};
use super::uint;
use crate::plan::Slots;
use ash::vk;
use prime_scene::Texture;
use prime_scene::incremental::{TextureCursor, TextureInput};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};

pub(super) struct Textures {
    pub(crate) source: BTreeMap<u32, Texture>,
    pub(crate) coverage_changed: BTreeSet<u32>,
    pub(crate) occlusion_changed: bool,
    cursor: Option<TextureCursor>,
    pub sprites: usize,
    pub indices: BTreeMap<u32, u32>,
    allocations: BTreeMap<usize, Backing>,
    pixels: Slots,
    mip_slots: BTreeMap<u32, (u32, u32)>,
    slots: Slots,
    pub metadata: Buffer,
    pub texels: Buffer,
}
struct Backing {
    offset: u32,
    count: u32,
    references: usize,
}
// Retain borrowed source views, not a serialized 64-byte record per mip/plane.
struct MetadataWrite<'a> {
    texture: &'a Texture,
    index: u32,
    first: u32,
    descriptors: [u32; 3],
    material_flags: u32,
    bounds: [u32; 4],
}
impl MetadataWrite<'_> {
    fn records<'a>(
        &'a self,
        allocations: &'a BTreeMap<usize, Backing>,
    ) -> impl Iterator<Item = (u32, [u32; 16])> + 'a {
        let material = self.texture.material.as_deref();
        let planes = [
            material.and_then(|m| m.normal.as_ref()),
            material.and_then(|m| m.specular.as_ref()),
            material.and_then(|m| m.coverage.as_ref()),
        ];
        std::iter::once((0, self.texture, self.index, self.first))
            .chain(planes.into_iter().enumerate().filter_map(|(i, plane)| {
                plane.map(|plane| (i + 1, plane, self.descriptors[i], self.descriptors[i] + 1))
            }))
            .flat_map(move |(plane_index, plane, index, first)| {
                let count = plane.sampling.as_ref().map_or(0, |s| s.levels.len() as u32);
                let region = plane.region.unwrap_or([0, 0, plane.width, plane.height]);
                let next = plane
                    .sampling
                    .as_ref()
                    .map_or([region[0], region[1]], |s| s.next);
                let blend = plane.sampling.as_ref().map_or(0., |s| s.blend).to_bits();
                std::iter::once((index, &plane.pixels, plane.width, region, next))
                    .chain(plane.sampling.iter().flat_map(move |s| {
                        s.levels.iter().enumerate().map(move |(i, m)| {
                            (first + i as u32, &m.pixels, m.width, m.region, m.next)
                        })
                    }))
                    .map(move |(slot, image, stride, [x, y, w, h], next)| {
                        let base = allocations[&(image.as_ptr() as usize)].offset;
                        let flags = if plane.region.is_some() { 1 << 31 } else { 0 };
                        (
                            slot,
                            [
                                base + y * stride + x,
                                w,
                                h,
                                stride | flags,
                                base + next[1] * stride + next[0],
                                first,
                                count,
                                blend,
                                if plane_index == 0 {
                                    self.descriptors[0]
                                } else {
                                    0
                                },
                                if plane_index == 0 {
                                    self.descriptors[1]
                                } else {
                                    0
                                },
                                if plane_index == 0 {
                                    self.material_flags
                                } else {
                                    0
                                },
                                if plane_index == 0 {
                                    self.descriptors[2]
                                } else {
                                    0
                                },
                                self.bounds[0],
                                self.bounds[1],
                                self.bounds[2],
                                self.bounds[3],
                            ],
                        )
                    })
            })
    }
}
// Only mip0 coverage dependencies. RGB/PBR/mip-only changes do not invalidate OMM.
fn coverage_same(a: &Texture, b: &Texture) -> bool {
    if a.width != b.width || a.height != b.height || !Arc::ptr_eq(&a.pixels, &b.pixels) {
        return false;
    }
    if let (Some(a_sampling), Some(b_sampling)) = (&a.sampling, &b.sampling)
        && !a_sampling.coverage_frames.is_empty()
        && !b_sampling.coverage_frames.is_empty()
    {
        // The boundary validates current/next membership. The OMM proof covers every
        // frame and interpolation, so descriptor-only animation never rebuilds a BLAS.
        return a.region.map(|r| [r[2], r[3]]) == b.region.map(|r| [r[2], r[3]])
            && (Arc::ptr_eq(&a_sampling.coverage_frames, &b_sampling.coverage_frames)
                || a_sampling.coverage_frames == b_sampling.coverage_frames);
    }
    a.region == b.region
        && match (&a.sampling, &b.sampling) {
            (None, None) => true,
            (Some(a), Some(b)) => {
                a.next == b.next && a.blend == b.blend && a.coverage_frames == b.coverage_frames
            }
            _ => false,
        }
}

fn images(texture: &Texture) -> impl Iterator<Item = &Arc<[u8]>> {
    texture.backings()
}
fn keys(texture: &Texture) -> BTreeSet<usize> {
    images(texture).map(|p| p.as_ptr() as usize).collect()
}

impl Textures {
    pub fn new<'a>(
        context: &Arc<Context>,
        source: impl Into<TextureInput<'a>>,
        uploads: &mut crate::arena::Arena,
    ) -> Result<Self, String> {
        let mut metadata = Vec::new();
        for value in [0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0] {
            uint(&mut metadata, value);
        }
        let mut pixels = Slots::default();
        pixels.allocate(1)?;
        let mut slots = Slots::default();
        slots.allocate(1)?;
        let mut result = Self {
            source: BTreeMap::new(),
            coverage_changed: BTreeSet::new(),
            occlusion_changed: false,
            cursor: None,
            sprites: 0,
            indices: BTreeMap::from([(0, 0)]),
            allocations: BTreeMap::new(),
            pixels,
            mip_slots: BTreeMap::new(),
            slots,
            metadata: Buffer::upload_device(context, &metadata, storage_usage())?,
            texels: Buffer::upload_device(context, &[255; 4], storage_usage())?,
        };
        result.update(context, source, uploads)?;
        Ok(result)
    }

    fn remove(&mut self, id: u32) {
        if let Some(old) = self.source.remove(&id) {
            self.coverage_changed.insert(id);
            self.sprites -= usize::from(old.region.is_some());
            self.slots.release(self.indices.remove(&id).unwrap(), 1);
            if let Some((start, count)) = self.mip_slots.remove(&id) {
                self.slots.release(start, count);
            }
            for key in keys(&old) {
                self.release(key);
            }
        }
    }
    fn release(&mut self, key: usize) {
        let backing = self.allocations.get_mut(&key).unwrap();
        backing.references -= 1;
        if backing.references == 0 {
            let backing = self.allocations.remove(&key).unwrap();
            self.pixels.release(backing.offset, backing.count);
        }
    }

    pub fn index(&self, id: u32) -> Result<u32, String> {
        if id != 0 && !self.source.contains_key(&id) {
            return Err(format!("Texture {id} has not been captured"));
        }
        self.indices
            .get(&id)
            .copied()
            .ok_or_else(|| "Unknown texture".into())
    }
    #[cfg(test)]
    pub fn retained_slots(&self) -> (u32, u32) {
        (self.slots.end, self.pixels.end)
    }

    pub fn update<'a>(
        &mut self,
        context: &Arc<Context>,
        source: impl Into<TextureInput<'a>>,
        uploads: &mut crate::arena::Arena,
    ) -> Result<bool, String> {
        let old_count = self.source.len();
        let (cursor, updates) = source.into().updates(self.cursor);
        // Validate fallible source/atlas membership before changing any ownership.
        let removed: BTreeSet<_> = if updates.is_snapshot() {
            self.source
                .keys()
                .filter(|id| !updates.retains(**id))
                .copied()
                .collect()
        } else {
            updates
                .clone()
                .filter_map(|(id, t)| t.is_none().then_some(*id))
                .collect()
        };
        let published: BTreeSet<_> = updates
            .clone()
            .filter_map(|(id, t)| t.is_some().then_some(*id))
            .collect();
        for (id, texture) in updates.clone() {
            let Some(texture) = texture else {
                continue;
            };
            if *id == 0 {
                return Err("Texture zero is reserved for explicit untextured white".into());
            }
            if self.source.get(id).is_some_and(|old| old.same(texture)) {
                continue;
            }
            texture.validate()?;
            if let Some(lookup) = texture
                .material
                .as_ref()
                .filter(|m| m.atlas_lookup)
                .and_then(|m| m.coverage.as_ref())
            {
                for pixel in lookup.pixels.as_chunks::<4>().0 {
                    let id = u32::from_le_bytes(*pixel);
                    if id != 0
                        && !published.contains(&id)
                        && (!self.indices.contains_key(&id) || removed.contains(&id))
                    {
                        return Err("atlas material sprite missing".into());
                    }
                }
            }
        }
        self.coverage_changed.clear();
        self.occlusion_changed = false;
        // Do not scan resident identities for a certified incremental input.
        if updates.is_snapshot() {
            let removed: Vec<_> = self
                .source
                .keys()
                .filter(|id| !updates.retains(**id))
                .copied()
                .collect();
            for id in removed {
                self.remove(id);
            }
        }
        let mut pixel_data = Vec::new();
        let mut pixel_bytes = 0u64;
        let mut metadata = Vec::new();
        let mut metadata_records = 0usize;
        let mut pixel_copies = Vec::new();
        let mut metadata_copies = Vec::new();
        // Retire removed identities before preallocating replacements, so their descriptors
        // remain reusable even when atlas lookups require all new identities up front.
        for (id, texture) in updates.clone() {
            if texture.is_none() {
                self.remove(*id);
            }
        }
        // Atlas lookups may refer to sprites later in the same update. Allocate their stable
        // descriptors before translating any lookup pixels.
        for (id, texture) in updates.clone() {
            if texture.is_some() && *id != 0 && !self.indices.contains_key(id) {
                self.indices.insert(*id, self.slots.allocate(1)?);
            }
        }
        for (id, texture) in updates {
            let Some(texture) = texture else {
                continue;
            };
            if self.source.get(id).is_some_and(|old| old.same(texture)) {
                continue;
            }
            if self
                .source
                .get(id)
                .is_none_or(|old| !coverage_same(old, texture))
            {
                self.coverage_changed.insert(*id);
            }
            let index = if let Some(&index) = self.indices.get(id) {
                index
            } else {
                let index = self.slots.allocate(1)?;
                self.indices.insert(*id, index);
                index
            };
            // Animation/view-only changes still publish descriptors, but retain the same
            // backing ownership. Avoid building two temporary sets for that common case.
            if self
                .source
                .get(id)
                .is_none_or(|old| !old.same_backings(texture))
            {
                let previous = self.source.get(id).map_or_else(BTreeSet::new, keys);
                let current = keys(texture);
                for &key in previous.difference(&current) {
                    self.release(key);
                }
                for &key in current.difference(&previous) {
                    if let Some(backing) = self.allocations.get_mut(&key) {
                        backing.references += 1;
                    } else {
                        let image = images(texture)
                            .find(|p| p.as_ptr() as usize == key)
                            .unwrap();
                        let count = (image.len() / 4) as u32;
                        let offset = self.pixels.allocate(count)?;
                        self.allocations.insert(
                            key,
                            Backing {
                                offset,
                                count,
                                references: 1,
                            },
                        );
                        pixel_copies.push(
                            vk::BufferCopy::default()
                                .src_offset(pixel_bytes)
                                .dst_offset(u64::from(offset) * 4)
                                .size(image.len() as u64),
                        );
                        let lookup = texture
                            .material
                            .as_ref()
                            .filter(|m| m.atlas_lookup)
                            .and_then(|m| m.coverage.as_ref())
                            .is_some_and(|p| Arc::ptr_eq(&p.pixels, image));
                        pixel_bytes += image.len() as u64;
                        pixel_data.push((image, lookup));
                    }
                }
            }
            let color_count = texture
                .sampling
                .as_ref()
                .map_or(0, |s| s.levels.len() as u32);
            let material = texture.material.as_deref();
            let planes = [
                material.and_then(|m| m.normal.as_ref()),
                material.and_then(|m| m.specular.as_ref()),
                material.and_then(|m| m.coverage.as_ref()),
            ];
            let count = color_count
                + planes
                    .into_iter()
                    .flatten()
                    .map(|p| 1 + p.sampling.as_ref().map_or(0, |s| s.levels.len() as u32))
                    .sum::<u32>();
            if self.mip_slots.get(id).is_some_and(|&(_, n)| n != count) {
                let (start, n) = self.mip_slots.remove(id).unwrap();
                self.slots.release(start, n);
            }
            let first = if count == 0 {
                0
            } else if let Some(&(start, _)) = self.mip_slots.get(id) {
                start
            } else {
                let start = self.slots.allocate(count)?;
                self.mip_slots.insert(*id, (start, count));
                start
            };
            let mut after = first + color_count;
            let descriptors = planes.map(|p| {
                p.map_or(0, |p| {
                    let index = after;
                    after += 1 + p.sampling.as_ref().map_or(0, |s| s.levels.len() as u32);
                    index
                })
            });
            let material_flags = u32::from(planes[0].is_some())
                | u32::from(planes[1].is_some()) << 1
                | u32::from(material.is_some_and(|m| m.authored_emission)) << 2
                | u32::from(material.is_some_and(|m| m.atlas_lookup)) << 3;
            let bounds = material
                .and_then(|m| m.bounds)
                .unwrap_or([0.; 4])
                .map(f32::to_bits);
            let descriptor = MetadataWrite {
                texture,
                index,
                first,
                descriptors,
                material_flags,
                bounds,
            };
            for (slot, _) in descriptor.records(&self.allocations) {
                metadata_copies.push(
                    vk::BufferCopy::default()
                        .src_offset((metadata_records * 64) as u64)
                        .dst_offset(u64::from(slot) * 64)
                        .size(64),
                );
                metadata_records += 1;
            }
            metadata.push(descriptor);
            self.sprites -=
                usize::from(self.source.get(id).is_some_and(|old| old.region.is_some()));
            self.sprites += usize::from(texture.region.is_some());
            self.source.insert(*id, texture.clone());
        }
        if !metadata.is_empty() {
            grow(context, &mut self.texels, u64::from(self.pixels.end) * 4)?;
            grow(context, &mut self.metadata, u64::from(self.slots.end) * 64)?;
            let pixel_upload = if pixel_data.is_empty() {
                None
            } else {
                let mut upload = uploads.allocate(context, pixel_bytes, 4)?;
                upload.write_with(|output| {
                    let mut remaining = output;
                    for (image, lookup) in pixel_data {
                        let (out, tail) = remaining.split_at_mut(image.len());
                        if lookup {
                            for (out, pixel) in out
                                .as_chunks_mut::<4>()
                                .0
                                .iter_mut()
                                .zip(image.as_chunks::<4>().0)
                            {
                                let id = u32::from_le_bytes(*pixel);
                                let descriptor = if id == 0 { 0 } else { self.indices[&id] };
                                for (out, byte) in out.iter_mut().zip(descriptor.to_le_bytes()) {
                                    out.write(byte);
                                }
                            }
                        } else {
                            for (out, &byte) in out.iter_mut().zip(image.iter()) {
                                out.write(byte);
                            }
                        }
                        remaining = tail;
                    }
                    assert!(remaining.is_empty());
                    Ok(())
                })?;
                Some(upload)
            };
            let mut metadata_upload =
                uploads.allocate(context, (metadata_records * 64) as u64, 4)?;
            metadata_upload.write_with(|output| {
                for (out, (_, words)) in output
                    .as_chunks_mut::<64>()
                    .0
                    .iter_mut()
                    .zip(metadata.iter().flat_map(|m| m.records(&self.allocations)))
                {
                    for (out, word) in out.as_chunks_mut::<4>().0.iter_mut().zip(words) {
                        for (out, byte) in out.iter_mut().zip(word.to_le_bytes()) {
                            out.write(byte);
                        }
                    }
                }
                Ok(())
            })?;
            for copy in &mut pixel_copies {
                copy.src_offset += pixel_upload.as_ref().unwrap().offset;
            }
            for copy in &mut metadata_copies {
                copy.src_offset += metadata_upload.offset;
            }
            let result = context.submit_named("texture_delta", |command| unsafe {
                if let Some(upload) = &pixel_upload {
                    context.device.cmd_copy_buffer(
                        command,
                        upload.buffer.buffer,
                        self.texels.buffer,
                        &pixel_copies,
                    );
                }
                context.device.cmd_copy_buffer(
                    command,
                    metadata_upload.buffer.buffer,
                    self.metadata.buffer,
                    &metadata_copies,
                );
                transfer_barrier(context, command);
            });
            if let Some(upload) = pixel_upload {
                uploads.retire(upload);
            }
            uploads.retire(metadata_upload);
            result?;
        }
        self.cursor = cursor;
        self.occlusion_changed = !metadata.is_empty()
            || self.source.len() != old_count
            || !self.coverage_changed.is_empty();
        Ok(!metadata.is_empty()
            || self.source.len() != old_count
            || !self.coverage_changed.is_empty())
    }
}

fn storage_usage() -> vk::BufferUsageFlags {
    vk::BufferUsageFlags::STORAGE_BUFFER
        | vk::BufferUsageFlags::TRANSFER_SRC
        | vk::BufferUsageFlags::TRANSFER_DST
}

fn grow(context: &Arc<Context>, buffer: &mut Buffer, required: u64) -> Result<(), String> {
    if required <= buffer.size {
        return Ok(());
    }
    let capacity = required
        .checked_next_power_of_two()
        .ok_or("Texture capacity overflow")?;
    let replacement = Buffer::new(context, capacity, storage_usage(), false)?;
    context.submit_named("grow_texture_arena", |command| unsafe {
        context.device.cmd_copy_buffer(
            command,
            buffer.buffer,
            replacement.buffer,
            &[vk::BufferCopy::default().size(buffer.size)],
        );
        transfer_barrier(context, command);
    })?;
    *buffer = replacement;
    Ok(())
}
