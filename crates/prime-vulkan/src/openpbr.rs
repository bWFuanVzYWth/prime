//! Immutable legacy author energy table for exact OpenPBR supported topologies.
use crate::resources::{Buffer, Context, error};
use crate::target::{Image, color_range};
use crate::texture_asset::{TextureAsset, TextureSpec};
use ash::vk;
use std::sync::Arc;

pub(super) const ENERGY_EXTENT: [u32; 3] = [44, 32, 159];
const ENERGY_SPEC: TextureSpec = TextureSpec {
    format: vk::Format::R16G16B16A16_SFLOAT,
    extent: ENERGY_EXTENT,
    levels: 1,
    primaries: 0, // Directional energy coefficients, not RGB color.
};

fn energy_asset() -> Result<TextureAsset<'static>, String> {
    let asset = TextureAsset::parse(prime_render_data::openpbr_energy(), ENERGY_SPEC)?;
    if asset.metadata_text("source")? != "RoboCute author-bsdf-hotfix-2026-07-24"
        || asset.metadata_text("source_sha256")?
            != "605c9160fb9348a1d033321c40cf9930226ce74c03f2624033f5b73aacfa67df"
    {
        return Err("OpenPBR energy asset does not match the locked author source".into());
    }
    Ok(asset)
}

pub(super) struct EnergyLut {
    context: Arc<Context>,
    image: Image,
    sampler: vk::Sampler,
    // CPU ownership until the first actual resource preparation command. After the copy is
    // recorded, staging retires using the existing Context host serial proof.
    pending: Option<Buffer>,
}

impl EnergyLut {
    #[cfg(test)]
    pub fn is_ready(&self) -> bool {
        self.pending.is_none()
    }

    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        let asset = energy_asset()?;
        let image = Image::sampled_3d_uninitialized(
            context,
            ENERGY_EXTENT,
            vk::Format::R16G16B16A16_SFLOAT,
        )?;
        let staging = Buffer::new(
            context,
            asset.level_size(0) as u64,
            vk::BufferUsageFlags::TRANSFER_SRC,
            true,
        )?;
        // SAFETY: Newly allocated staging is exclusively CPU-owned and has no GPU consumer.
        // The asset decoder initializes the entire mapped range before any copy is recorded.
        unsafe {
            staging.write_with(0, asset.level_size(0), |bytes| {
                asset.decode_level_into(0, bytes)
            })?;
        }
        let pending = Some(staging);
        let sampler = unsafe {
            context
                .device
                .create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::LINEAR)
                        .min_filter(vk::Filter::LINEAR)
                        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .min_lod(0.0)
                        .max_lod(0.0),
                    None,
                )
                .map_err(|e| error("Create OpenPBR energy sampler", e))?
        };
        Ok(Self {
            context: context.clone(),
            image,
            sampler,
            pending,
        })
    }

    pub fn descriptor(&self) -> vk::DescriptorImageInfo {
        vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(self.image.view)
            .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
    }

    pub fn prepare(&mut self) -> Result<(), String> {
        let Some(staging) = self.pending.as_ref() else {
            return Ok(());
        };
        self.context
            .submit_named("upload_openpbr_energy", |command| unsafe {
                let initial = [vk::ImageMemoryBarrier::default()
                    .image(self.image.image)
                    .old_layout(vk::ImageLayout::UNDEFINED)
                    .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .subresource_range(color_range())];
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TOP_OF_PIPE,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &initial,
                );
                self.context.device.cmd_copy_buffer_to_image(
                    command,
                    staging.buffer,
                    self.image.image,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    &[vk::BufferImageCopy::default()
                        .image_subresource(
                            vk::ImageSubresourceLayers::default()
                                .aspect_mask(vk::ImageAspectFlags::COLOR)
                                .layer_count(1),
                        )
                        .image_extent(vk::Extent3D {
                            width: ENERGY_EXTENT[0],
                            height: ENERGY_EXTENT[1],
                            depth: ENERGY_EXTENT[2],
                        })],
                );
                let ready = [vk::ImageMemoryBarrier::default()
                    .image(self.image.image)
                    .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ)
                    .subresource_range(color_range())];
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &ready,
                );
            })?;
        self.pending.take();
        Ok(())
    }
}

impl Drop for EnergyLut {
    fn drop(&mut self) {
        // Renderer owns this independently of its pipelines and drains before
        // destruction. An uncertain submission retains every native object.
        if self.context.can_destroy() {
            unsafe {
                self.context.device.destroy_sampler(self.sampler, None);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn locked_energy_table_has_exact_half4_extent() {
        let asset = energy_asset().unwrap();
        assert_eq!(asset.level_size(0), 1_790_976);
        assert_eq!(ENERGY_EXTENT, [44, 32, 159]);
        assert_eq!(asset.level_extent(0), ENERGY_EXTENT);
        let expected =
            include_bytes!("../assets/openpbr/author-bsdf-hotfix-2026-07-24/trans_ggx.bytes");
        let mut decoded = vec![std::mem::MaybeUninit::uninit(); asset.level_size(0)];
        asset.decode_level_into(0, &mut decoded).unwrap();
        for (actual, expected) in decoded.iter().zip(expected) {
            // SAFETY: Successful exact-length decoding initialized every byte above.
            assert_eq!(unsafe { actual.assume_init() }, *expected);
        }
    }
}
