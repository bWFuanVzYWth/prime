//! Final display consumes scene-linear input after reconstruction/stars/exposure.
use crate::{
    display::PrimeDrtParameters,
    post_compute::{PostCompute, barrier},
    resources::Context,
};
use ash::vk;
use std::sync::Arc;
pub(crate) struct LinearDisplay {
    context: Arc<Context>,
    compute: PostCompute,
}
impl LinearDisplay {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        Ok(Self {
            context: context.clone(),
            compute: PostCompute::new(
                context,
                &[
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                ],
                &[include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/display_from_linear.spv"
                ))],
                96,
            )?,
        })
    }
    /// Views are linear input, borrowed RGBA8 host target, FP16 encoded HDR world,
    /// and immutable RGBA8 SDR baseline. HDR output can be a 1x1 dummy with HDR off;
    /// baseline can be a dummy only when both HDR and frame generation are off.
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        command: vk::CommandBuffer,
        slot: usize,
        views: [vk::ImageView; 4],
        input_address: u64,
        extent: [u32; 2],
        linear709: bool,
        bottom_up: bool,
        hdr_enabled: bool,
        exposure_address: u64,
        sdr: PrimeDrtParameters,
        hdr: PrimeDrtParameters,
        fg_enabled: bool,
    ) -> Result<(), String> {
        if extent.contains(&0) {
            return Err("Invalid linear display extent".into());
        }
        for (binding, &view) in views.iter().enumerate() {
            self.compute.image(
                slot,
                binding as u32,
                if binding == 0 {
                    vk::DescriptorType::SAMPLED_IMAGE
                } else {
                    vk::DescriptorType::STORAGE_IMAGE
                },
                view,
            );
        }
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::AccessFlags::MEMORY_WRITE,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
        );
        let mut push = [0u8; 96];
        for (out, value) in push[..64]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(sdr.values.into_iter().chain(hdr.values))
        {
            *out = value.to_bits().to_le_bytes();
        }
        let flags = u32::from(linear709)
            | u32::from(bottom_up) << 1
            | u32::from(hdr_enabled) << 2
            | u32::from(fg_enabled) << 3
            | u32::from(exposure_address != 0) << 4
            | u32::from(input_address != 0) << 5;
        for (out, value) in push[64..80]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip([extent[0], extent[1], flags, 0])
        {
            *out = value.to_le_bytes();
        }
        push[80..88].copy_from_slice(&exposure_address.to_le_bytes());
        push[88..96].copy_from_slice(&input_address.to_le_bytes());
        self.compute.dispatch(
            command,
            0,
            slot,
            &push,
            [extent[0].div_ceil(8), extent[1].div_ceil(8), 1],
        );
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_WRITE,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::AccessFlags::MEMORY_READ,
        );
        Ok(())
    }
}
