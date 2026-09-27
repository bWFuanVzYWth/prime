//! Stable texture identities; newly observed entity textures do not repack terrain pixels.
use super::geometry::transfer_barrier;
use super::resources::{Buffer, Context};
use super::uint;
use crate::plan::Slots;
use ash::vk;
use prime_scene::Texture;
use prime_scene::incremental::{TextureCursor, TextureInput};
use std::{collections::BTreeMap, sync::Arc};

pub(super) struct Textures {
    source: BTreeMap<u32, Texture>,
    cursor: Option<TextureCursor>,
    pub indices: BTreeMap<u32, u32>,
    allocations: BTreeMap<u32, (u32, u32)>,
    pixels: Slots,
    slots: Slots,
    pub metadata: Buffer,
    pub texels: Buffer,
}

impl Textures {
    pub fn new<'a>(
        context: &Arc<Context>,
        source: impl Into<TextureInput<'a>>,
        uploads: &mut crate::arena::Arena,
    ) -> Result<Self, String> {
        let mut metadata = Vec::new();
        for value in [0, 1, 1, 0] {
            uint(&mut metadata, value);
        }
        let mut pixels = Slots::default();
        pixels.allocate(1)?;
        let mut slots = Slots::default();
        slots.allocate(1)?;
        let mut result = Self {
            source: BTreeMap::new(),
            cursor: None,
            indices: BTreeMap::from([(0, 0)]),
            allocations: BTreeMap::from([(0, (0, 1))]),
            pixels,
            slots,
            metadata: Buffer::upload_device(context, &metadata, storage_usage())?,
            texels: Buffer::upload_device(context, &[255; 4], storage_usage())?,
        };
        result.update(context, source, uploads)?;
        Ok(result)
    }

    fn remove(&mut self, id: u32) {
        if self.source.remove(&id).is_some() {
            self.slots.release(self.indices.remove(&id).unwrap(), 1);
            let (offset, capacity) = self.allocations.remove(&id).unwrap();
            self.pixels.release(offset, capacity);
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
        for (id, texture) in updates {
            let Some(texture) = texture else {
                self.remove(*id);
                continue;
            };
            if *id == 0 {
                return Err("Texture zero is reserved for explicit untextured white".into());
            }
            if self.source.get(id).is_some_and(|old| {
                old.width == texture.width
                    && old.height == texture.height
                    && Arc::ptr_eq(&old.pixels, &texture.pixels)
            }) {
                continue;
            }
            let count = texture
                .width
                .checked_mul(texture.height)
                .ok_or("Texture extent overflow")?;
            if count == 0 || u64::from(count) * 4 != texture.pixels.len() as u64 {
                return Err(format!("Texture {id} has invalid RGBA8 dimensions"));
            }
            let index = if let Some(&index) = self.indices.get(id) {
                index
            } else {
                let index = self.slots.allocate(1)?;
                self.indices.insert(*id, index);
                index
            };
            let offset = match self.allocations.get(id) {
                Some(&(offset, capacity)) if count <= capacity => offset,
                _ => {
                    if let Some((offset, capacity)) = self.allocations.remove(id) {
                        self.pixels.release(offset, capacity);
                    }
                    let offset = self.pixels.allocate(count)?;
                    self.allocations.insert(*id, (offset, count));
                    offset
                }
            };
            pixel_copies.push(
                vk::BufferCopy::default()
                    .src_offset(pixels.len() as u64)
                    .dst_offset(u64::from(offset) * 4)
                    .size(texture.pixels.len() as u64),
            );
            pixels.extend_from_slice(&texture.pixels);
            metadata_copies.push(
                vk::BufferCopy::default()
                    .src_offset(metadata.len() as u64)
                    .dst_offset(u64::from(index) * 16)
                    .size(16),
            );
            for value in [offset, texture.width, texture.height, 0] {
                uint(&mut metadata, value);
            }
            self.source.insert(*id, texture.clone());
        }
        if !pixels.is_empty() {
            grow(context, &mut self.texels, u64::from(self.pixels.end) * 4)?;
            grow(context, &mut self.metadata, u64::from(self.slots.end) * 16)?;
            let pixel_upload = uploads.allocate(context, pixels.len() as u64, 4)?;
            pixel_upload.write(&pixels)?;
            let metadata_upload = uploads.allocate(context, metadata.len() as u64, 4)?;
            metadata_upload.write(&metadata)?;
            for copy in &mut pixel_copies {
                copy.src_offset += pixel_upload.offset;
            }
            for copy in &mut metadata_copies {
                copy.src_offset += metadata_upload.offset;
            }
            let result = context.submit_named("texture_delta", |command| unsafe {
                context.device.cmd_copy_buffer(
                    command,
                    pixel_upload.buffer.buffer,
                    self.texels.buffer,
                    &pixel_copies,
                );
                context.device.cmd_copy_buffer(
                    command,
                    metadata_upload.buffer.buffer,
                    self.metadata.buffer,
                    &metadata_copies,
                );
                transfer_barrier(context, command);
            });
            uploads.retire(pixel_upload);
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
