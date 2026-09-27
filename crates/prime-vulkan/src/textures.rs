//! Stable texture identities; newly observed entity textures do not repack terrain pixels.
use super::geometry::transfer_barrier;
use super::resources::{Buffer, Context};
use super::uint;
use ash::vk;
use prime_scene::Texture;
use prime_scene::incremental::{TextureCursor, TextureInput};
use std::{collections::BTreeMap, sync::Arc};

pub(super) struct Textures {
    source: BTreeMap<u32, Texture>,
    cursor: Option<TextureCursor>,
    pub indices: BTreeMap<u32, u32>,
    allocations: BTreeMap<u32, (u32, u32)>,
    pixel_end: u32,
    pub metadata: Buffer,
    pub texels: Buffer,
}

impl Textures {
    pub fn new<'a>(
        context: &Arc<Context>,
        source: impl Into<TextureInput<'a>>,
    ) -> Result<Self, String> {
        let mut metadata = Vec::new();
        for value in [0, 1, 1, 0] {
            uint(&mut metadata, value);
        }
        let mut result = Self {
            source: BTreeMap::new(),
            cursor: None,
            indices: BTreeMap::from([(0, 0)]),
            allocations: BTreeMap::from([(0, (0, 1))]),
            pixel_end: 1,
            metadata: Buffer::upload_device(context, &metadata, storage_usage())?,
            texels: Buffer::upload_device(context, &[255; 4], storage_usage())?,
        };
        result.update(context, source)?;
        Ok(result)
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

    pub fn update<'a>(
        &mut self,
        context: &Arc<Context>,
        source: impl Into<TextureInput<'a>>,
    ) -> Result<(), String> {
        let (cursor, updates) = source.into().updates(self.cursor);
        // Do not scan resident identities for a certified incremental input.
        if updates.is_snapshot() {
            self.source.retain(|id, _| updates.retains(*id));
        }
        let mut pixels = Vec::new();
        let mut metadata = Vec::new();
        let mut pixel_copies = Vec::new();
        let mut metadata_copies = Vec::new();
        for (id, texture) in updates {
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
            let next_index = u32::try_from(self.indices.len()).map_err(|_| "Too many textures")?;
            let index = *self.indices.entry(*id).or_insert(next_index);
            let offset = match self.allocations.get(id) {
                Some(&(offset, capacity)) if count <= capacity => offset,
                _ => {
                    let offset = self.pixel_end;
                    self.pixel_end = offset.checked_add(count).ok_or("Texture arena overflow")?;
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
            grow(context, &mut self.texels, u64::from(self.pixel_end) * 4)?;
            grow(context, &mut self.metadata, self.indices.len() as u64 * 16)?;
            let pixel_upload =
                Buffer::upload(context, &pixels, vk::BufferUsageFlags::TRANSFER_SRC)?;
            let metadata_upload =
                Buffer::upload(context, &metadata, vk::BufferUsageFlags::TRANSFER_SRC)?;
            context.submit_named("texture_delta", |command| unsafe {
                context.device.cmd_copy_buffer(
                    command,
                    pixel_upload.buffer,
                    self.texels.buffer,
                    &pixel_copies,
                );
                context.device.cmd_copy_buffer(
                    command,
                    metadata_upload.buffer,
                    self.metadata.buffer,
                    &metadata_copies,
                );
                transfer_barrier(context, command);
            })?;
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
