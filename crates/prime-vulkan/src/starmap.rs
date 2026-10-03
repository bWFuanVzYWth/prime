//! Fixed NASA 2020 BC6H asset and legacy Prime celestial projection; no runtime transcoder.
use crate::{
    post_compute::{PostCompute, barrier},
    resources::{Buffer, Context, error},
};
use ash::vk;
use flate2::read::GzDecoder;
use std::{io::Read, sync::Arc};
pub(crate) const WIDTH: u32 = 16384;
pub(crate) const HEIGHT: u32 = 8192;
pub(crate) const MIPS: u32 = 15;
const PARTS: &[(u32, u32, u32, u32, &[u8])] = &[
    (
        0,
        0,
        16384,
        2048,
        include_bytes!("../assets/starmap/starmap_2020_16k_0.bc6h.gz"),
    ),
    (
        2048,
        0,
        16384,
        2048,
        include_bytes!("../assets/starmap/starmap_2020_16k_1.bc6h.gz"),
    ),
    (
        4096,
        0,
        16384,
        2048,
        include_bytes!("../assets/starmap/starmap_2020_16k_2.bc6h.gz"),
    ),
    (
        6144,
        0,
        16384,
        2048,
        include_bytes!("../assets/starmap/starmap_2020_16k_3.bc6h.gz"),
    ),
    (
        0,
        1,
        8192,
        4096,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip1.bc6h.gz"),
    ),
    (
        0,
        2,
        4096,
        2048,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip2.bc6h.gz"),
    ),
    (
        0,
        3,
        2048,
        1024,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip3.bc6h.gz"),
    ),
    (
        0,
        4,
        1024,
        512,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip4.bc6h.gz"),
    ),
    (
        0,
        5,
        512,
        256,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip5.bc6h.gz"),
    ),
    (
        0,
        6,
        256,
        128,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip6.bc6h.gz"),
    ),
    (
        0,
        7,
        128,
        64,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip7.bc6h.gz"),
    ),
    (
        0,
        8,
        64,
        32,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip8.bc6h.gz"),
    ),
    (
        0,
        9,
        32,
        16,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip9.bc6h.gz"),
    ),
    (
        0,
        10,
        16,
        8,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip10.bc6h.gz"),
    ),
    (
        0,
        11,
        8,
        4,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip11.bc6h.gz"),
    ),
    (
        0,
        12,
        4,
        2,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip12.bc6h.gz"),
    ),
    (
        0,
        13,
        2,
        1,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip13.bc6h.gz"),
    ),
    (
        0,
        14,
        1,
        1,
        include_bytes!("../assets/starmap/starmap_2020_16k_mip14.bc6h.gz"),
    ),
];
fn decode(bytes: &[u8], expected: usize) -> Result<Vec<u8>, String> {
    let mut decoder = GzDecoder::new(bytes);
    let mut output = vec![0; expected];
    decoder
        .read_exact(&mut output)
        .map_err(|e| format!("Decode starmap asset: {e}"))?;
    if decoder
        .read(&mut [0; 1])
        .map_err(|e| format!("Starmap gzip CRC: {e}"))?
        != 0
    {
        return Err("Starmap asset exceeds its mip extent".into());
    }
    Ok(output)
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
    initialized: bool,
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
            initialized: false,
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
    /// One decompressed stripe is temporary on CPU; all staging coexist until completion.
    pub fn prepare(&mut self, command: vk::CommandBuffer) -> Result<Vec<Buffer>, String> {
        if self.initialized {
            return Ok(Vec::new());
        }
        let mut uploads = Vec::with_capacity(PARTS.len());
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
        for &(y, level, width, height, bytes) in PARTS {
            let expected = width.div_ceil(4) as usize * height.div_ceil(4) as usize * 16;
            let decoded = decode(bytes, expected)?;
            let staging =
                Buffer::upload(&self.context, &decoded, vk::BufferUsageFlags::TRANSFER_SRC)?;
            let copy = [vk::BufferImageCopy::default()
                .image_subresource(
                    vk::ImageSubresourceLayers::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .mip_level(level)
                        .layer_count(1),
                )
                .image_offset(vk::Offset3D {
                    x: 0,
                    y: y as i32,
                    z: 0,
                })
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
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
            uploads.push(staging);
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
        self.initialized = true;
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
                &[include_bytes!(concat!(env!("OUT_DIR"), "/stars.spv"))],
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
    #[test]
    fn compressed_mip_chain_is_complete_and_gzip_crc_valid() {
        let mut size = 0;
        for &(_, _, w, h, bytes) in PARTS {
            let n = w.div_ceil(4) as usize * h.div_ceil(4) as usize * 16;
            assert_eq!(decode(bytes, n).unwrap().len(), n);
            size += n;
        }
        assert_eq!(size, 178957008);
        let (_, _, w, h, bytes) = PARTS[17];
        assert!(decode(bytes, (w * h * 16 - 1) as usize).is_err());
        let mut corrupt = bytes.to_vec();
        *corrupt.last_mut().unwrap() ^= 1;
        assert!(decode(&corrupt, 16).is_err());
    }
}
