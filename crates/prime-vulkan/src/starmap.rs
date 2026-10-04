//! Fixed NASA 2020 BC6H asset and legacy Prime celestial projection; no runtime transcoder.
use crate::{
    post_compute::{PostCompute, barrier},
    resources::{Buffer, Context, error},
    texture_asset::{TextureAsset, TextureSpec},
};
use ash::vk;
use std::sync::Arc;
pub(crate) const WIDTH: u32 = 16384;
pub(crate) const HEIGHT: u32 = 8192;
pub(crate) const MIPS: u32 = 15;
const SPEC: TextureSpec = TextureSpec {
    format: vk::Format::BC6H_UFLOAT_BLOCK,
    extent: [WIDTH, HEIGHT, 0],
    levels: MIPS,
    primaries: 4, // D65 linear BT.2020; the original payload is already in this working space.
};
fn asset() -> Result<TextureAsset<'static>, String> {
    let asset = TextureAsset::parse(prime_render_data::starmap(), SPEC)?;
    for (key, expected) in [
        (
            "source_sha256",
            "19a1351f00c386a6e5eec4d67af96d5fc71edf6a1189941579b9498b52e7589a",
        ),
        (
            "projection",
            "plate carree ICRF/J2000; RA 0h at center, RA increases left",
        ),
        ("working_color", "D65 linear Rec.2020"),
    ] {
        if asset.metadata_text(key)? != expected {
            return Err(format!("Unsupported starmap metadata {key}"));
        }
    }
    Ok(asset)
}
fn range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .level_count(MIPS)
        .layer_count(1)
}

pub(crate) struct Starmap {
    context: Arc<Context>,
    pub image: vk::Image,
    pub view: vk::ImageView,
    pub sampler: vk::Sampler,
    memory: vk::DeviceMemory,
    asset: Option<TextureAsset<'static>>,
}
impl Starmap {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        if !context.supports_starmap() {
            return Err(
                "Device does not support the fixed 16K, 15-mip linearly sampled BC6H starmap"
                    .into(),
            );
        }
        let mut result = Self {
            context: context.clone(),
            image: vk::Image::null(),
            view: vk::ImageView::null(),
            sampler: vk::Sampler::null(),
            memory: vk::DeviceMemory::null(),
            asset: Some(asset()?),
        };
        unsafe {
            result.image = context
                .device
                .create_image(
                    &vk::ImageCreateInfo::default()
                        .image_type(vk::ImageType::TYPE_2D)
                        .format(vk::Format::BC6H_UFLOAT_BLOCK)
                        .extent(vk::Extent3D {
                            width: WIDTH,
                            height: HEIGHT,
                            depth: 1,
                        })
                        .mip_levels(MIPS)
                        .array_layers(1)
                        .samples(vk::SampleCountFlags::TYPE_1)
                        .tiling(vk::ImageTiling::OPTIMAL)
                        .usage(vk::ImageUsageFlags::SAMPLED | vk::ImageUsageFlags::TRANSFER_DST)
                        .sharing_mode(vk::SharingMode::EXCLUSIVE),
                    None,
                )
                .map_err(|e| error("Create starmap image", e))?;
            let requirements = context.device.get_image_memory_requirements(result.image);
            let memory_type = (0..context.memory.memory_type_count)
                .find(|&i| {
                    requirements.memory_type_bits & (1 << i) != 0
                        && context.memory.memory_types[i as usize]
                            .property_flags
                            .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
                })
                .ok_or("No device-local starmap memory")?;
            result.memory = context.allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(requirements.size)
                    .memory_type_index(memory_type),
            )?;
            context
                .device
                .bind_image_memory(result.image, result.memory, 0)
                .map_err(|e| error("Bind starmap image", e))?;
            result.view = context
                .device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(result.image)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(vk::Format::BC6H_UFLOAT_BLOCK)
                        .subresource_range(range()),
                    None,
                )
                .map_err(|e| error("Create starmap view", e))?;
            result.sampler = context
                .device
                .create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::LINEAR)
                        .min_filter(vk::Filter::LINEAR)
                        .mipmap_mode(vk::SamplerMipmapMode::LINEAR)
                        .address_mode_u(vk::SamplerAddressMode::REPEAT)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .max_lod((MIPS - 1) as f32),
                    None,
                )
                .map_err(|e| error("Create starmap sampler", e))?;
        }
        Ok(result)
    }
    /// Record once. The caller retains returned staging until submit_named returns: owned mode
    /// has completed its fence, while borrowed mode can then retire by the active host serial.
    /// Zstd writes directly into mapped mip staging; all staging coexist until completion.
    /// The base mip is one 128MiB allocation instead of four 32MiB stripe allocations.
    pub fn prepare(&mut self, command: vk::CommandBuffer) -> Result<Vec<Buffer>, String> {
        let Some(asset) = self.asset.as_ref() else {
            return Ok(Vec::new());
        };
        let mut uploads = Vec::with_capacity(MIPS as usize);
        // Validate/decode every mip before recording any initialization command. Failed
        // allocations or corrupt frames remain CPU-owned and are never submitted.
        for level in 0..MIPS as usize {
            let size = asset.level_size(level);
            let staging = Buffer::new(
                &self.context,
                size as u64,
                vk::BufferUsageFlags::TRANSFER_SRC,
                true,
            )?;
            // SAFETY: New staging has no GPU consumer; success initializes the whole range.
            unsafe {
                staging.write_with(0, size, |bytes| asset.decode_level_into(level, bytes))?;
            }
            uploads.push(staging);
        }
        unsafe {
            let transition = [vk::ImageMemoryBarrier::default()
                .image(self.image)
                .old_layout(vk::ImageLayout::UNDEFINED)
                .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .subresource_range(range())];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TOP_OF_PIPE,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &transition,
            );
        }
        for (level, staging) in uploads.iter().enumerate() {
            let [width, height, depth] = asset.level_extent(level);
            let copy = [vk::BufferImageCopy::default()
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .mip_level(level as u32)
                        .layer_count(1),
                )
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth,
                })];
            unsafe {
                self.context.device.cmd_copy_buffer_to_image(
                    command,
                    staging.buffer,
                    self.image,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    &copy,
                );
            }
        }
        unsafe {
            let transition = [vk::ImageMemoryBarrier::default()
                .image(self.image)
                .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                .new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .subresource_range(range())];
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &transition,
            );
        }
        self.asset.take();
        Ok(uploads)
    }
}
impl Drop for Starmap {
    fn drop(&mut self) {
        // The renderer retains the sampler until its final queue consumer completes.
        if self.context.can_destroy() {
            unsafe {
                self.context.device.destroy_sampler(self.sampler, None);
            }
            self.context
                .retire_image(self.image, self.view, self.memory);
        }
    }
}

pub(crate) struct Stars {
    context: Arc<Context>,
    compute: PostCompute,
}
impl Stars {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        Ok(Self {
            context: context.clone(),
            compute: PostCompute::new(
                context,
                &[
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                ],
                &[prime_shaders::stars()],
                112,
            )?,
        })
    }
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        command: vk::CommandBuffer,
        slot: usize,
        output: vk::ImageView,
        status: vk::ImageView,
        transmittance: vk::ImageView,
        starmap: &Starmap,
        parameters: &StarsParameters,
    ) {
        self.compute
            .image(slot, 0, vk::DescriptorType::STORAGE_IMAGE, output);
        self.compute.sampled(slot, 1, starmap.view, starmap.sampler);
        self.compute
            .image(slot, 2, vk::DescriptorType::SAMPLED_IMAGE, transmittance);
        self.compute
            .image(slot, 3, vk::DescriptorType::STORAGE_IMAGE, status);
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::AccessFlags::MEMORY_WRITE,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
        );
        let mut push = [0u8; 112];
        let mut words = [0u32; 28];
        for (out, values) in words[..20].as_chunks_mut::<4>().0.iter_mut().zip([
            parameters.forward,
            parameters.right,
            parameters.up,
            parameters.sun,
            parameters.settings,
        ]) {
            *out = values.map(f32::to_bits);
        }
        words[20..24].copy_from_slice(&parameters.dimensions);
        words[24..26].copy_from_slice(&parameters.jitter.map(f32::to_bits));
        for (out, value) in push.as_chunks_mut::<4>().0.iter_mut().zip(words) {
            *out = value.to_le_bytes();
        }
        self.compute.dispatch(
            command,
            0,
            slot,
            &push,
            [
                parameters.dimensions[0].div_ceil(8),
                parameters.dimensions[1].div_ceil(8),
                1,
            ],
        );
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_WRITE,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_READ,
        );
    }
}
/// Basis is unjittered and canonical top-left. Settings are latitude/season radians,
/// absolute star scale (legacy default 0.025), and 1 for linear709 output.
pub(crate) struct StarsParameters {
    pub forward: [f32; 4],
    pub right: [f32; 4],
    pub up: [f32; 4],
    pub sun: [f32; 4],
    pub settings: [f32; 4],
    pub dimensions: [u32; 4],
    pub jitter: [f32; 2],
}
#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    use std::mem::MaybeUninit;

    #[test]
    fn ktx2_mip_payloads_match_independently_locked_legacy_gpu_bytes() {
        const HASHES: [&str; 15] = [
            "d3ed4c4ebf391aa4150f87718eb9ede61f51bbd70205c23772aa645711ff2212",
            "9fa64aab4a7f095970d42c5f92f39a9e9b3f80089db6b5165a6b51d59f945320",
            "40e78340f7e6005f3e4ac258588318a1abcf0036a21a5148e14620a9b4fd2fc6",
            "b1b365f1c22889b6e4396df2be6a22df0d6f5691e593a05bb0fa3d051f327c8d",
            "faf8db882c181a7b51712b0b17f02664e8cc5ea7bcbd40d17c5e35cf105414b5",
            "eae1c8b4d7c0f10ff5632c923950c3321409979310592c1aa2ac81e88efcc1a9",
            "92bd06a18a25da6bb01b5abbe08139a81f3cb131365d94b53f64cc7ce6964352",
            "e698686dbaeae3d4a5078f2819045a986c597a383d55f29ed83dd80530adf117",
            "1a708ff926d94841894a37782db598fda8e8f596d063f78f98d8a4c72cf5d24b",
            "bef04d77942b24dc61c3de6560725afdf161a51cae52a9c82279da6f7d858a04",
            "2bea2de2206fd028712ebfa2227d3eb1efe4635a28202416458e04722b4dc632",
            "a5b07534ffaadca29774aa94280e76b5e1cdde32da7de757b06b401e2d9dbbf4",
            "c2b46e9e9858bd7f4247656020397215ffd189fd9596174a94f080f90164c578",
            "0b6391d605c76af6e006ab96bf3840eef857b04df57e1480f065a50bb8c7be3a",
            "c3922a143ac968affceeb17e7125387b77c63bafea16723727b93ba62d3c5089",
        ];
        let asset = asset().unwrap();
        let mut total = 0;
        for (level, expected) in HASHES.iter().enumerate() {
            let size = asset.level_size(level);
            let mut output = Vec::<u8>::with_capacity(size);
            let destination: &mut [MaybeUninit<u8>] = &mut output.spare_capacity_mut()[..size];
            asset.decode_level_into(level, destination).unwrap();
            // SAFETY: The actual decoder successfully initialized exactly size bytes.
            unsafe { output.set_len(size) };
            assert_eq!(
                format!("{:x}", Sha256::digest(&output)),
                *expected,
                "mip={level}"
            );
            total += size;
        }
        assert_eq!(total, 178_957_008);
        let bytes = prime_render_data::starmap();
        assert!(TextureAsset::parse(&bytes[..bytes.len() - 1], SPEC).is_err());
    }
}
