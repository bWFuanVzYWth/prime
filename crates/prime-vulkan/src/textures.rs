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
    source: BTreeMap<u32, Texture>,
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
    ) -> Result<(), String> {
        let (cursor, updates) = source.into().updates(self.cursor);
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
        let mut pixels = Vec::new();
        let mut metadata = Vec::new();
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
            if *id == 0 {
                return Err("Texture zero is reserved for explicit untextured white".into());
            }
            if self.source.get(id).is_some_and(|old| old.same(texture)) {
                continue;
            }
            texture.validate()?;
            let index = if let Some(&index) = self.indices.get(id) {
                index
            } else {
                let index = self.slots.allocate(1)?;
                self.indices.insert(*id, index);
                index
            };
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
                            .src_offset(pixels.len() as u64)
                            .dst_offset(u64::from(offset) * 4)
                            .size(image.len() as u64),
                    );
                    let lookup = texture
                        .material
                        .as_ref()
                        .filter(|m| m.atlas_lookup)
                        .and_then(|m| m.coverage.as_ref())
                        .is_some_and(|p| Arc::ptr_eq(&p.pixels, image));
                    if lookup {
                        for pixel in image.as_chunks::<4>().0.iter() {
                            let id = u32::from_le_bytes(*pixel);
                            let descriptor = if id == 0 {
                                0
                            } else {
                                *self
                                    .indices
                                    .get(&id)
                                    .ok_or("atlas material sprite missing")?
                            };
                            pixels.extend_from_slice(&descriptor.to_le_bytes());
                        }
                    } else {
                        pixels.extend_from_slice(image);
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
            for (plane_index, plane, index, first) in std::iter::once((0, texture, index, first))
                .chain(
                    planes.into_iter().enumerate().filter_map(|(i, p)| {
                        p.map(|p| (i + 1, p, descriptors[i], descriptors[i] + 1))
                    }),
                )
            {
                let count = plane.sampling.as_ref().map_or(0, |s| s.levels.len() as u32);
                let region = plane.region.unwrap_or([0, 0, plane.width, plane.height]);
                let next = plane
                    .sampling
                    .as_ref()
                    .map_or([region[0], region[1]], |s| s.next);
                let blend = plane.sampling.as_ref().map_or(0., |s| s.blend).to_bits();
                let views = std::iter::once((index, &plane.pixels, plane.width, region, next))
                    .chain(plane.sampling.iter().flat_map(|s| {
                        s.levels
                            .iter()
                            .enumerate()
                            .map(|(i, m)| (first + i as u32, &m.pixels, m.width, m.region, m.next))
                    }));
                for (slot, image, stride, [x, y, w, h], next) in views {
                    let base = self.allocations[&(image.as_ptr() as usize)].offset;
                    metadata_copies.push(
                        vk::BufferCopy::default()
                            .src_offset(metadata.len() as u64)
                            .dst_offset(u64::from(slot) * 64)
                            .size(64),
                    );
                    let flags = if plane.region.is_some() { 1 << 31 } else { 0 };
                    for value in [
                        base + y * stride + x,
                        w,
                        h,
                        stride | flags,
                        base + next[1] * stride + next[0],
                        first,
                        count,
                        blend,
                        if plane_index == 0 { descriptors[0] } else { 0 },
                        if plane_index == 0 { descriptors[1] } else { 0 },
                        if plane_index == 0 { material_flags } else { 0 },
                        if plane_index == 0 { descriptors[2] } else { 0 },
                        bounds[0],
                        bounds[1],
                        bounds[2],
                        bounds[3],
                    ] {
                        uint(&mut metadata, value);
                    }
                }
            }
            self.sprites -=
                usize::from(self.source.get(id).is_some_and(|old| old.region.is_some()));
            self.sprites += usize::from(texture.region.is_some());
            self.source.insert(*id, texture.clone());
        }
        if !metadata.is_empty() {
            grow(context, &mut self.texels, u64::from(self.pixels.end) * 4)?;
            grow(context, &mut self.metadata, u64::from(self.slots.end) * 64)?;
            let pixel_upload = if pixels.is_empty() {
                None
            } else {
                let upload = uploads.allocate(context, pixels.len() as u64, 4)?;
                upload.write(&pixels)?;
                Some(upload)
            };
            let metadata_upload = uploads.allocate(context, metadata.len() as u64, 4)?;
            metadata_upload.write(&metadata)?;
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
        Ok(())
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
