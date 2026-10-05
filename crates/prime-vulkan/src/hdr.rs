//! Legacy Prime HDR calibration and linear scRGB presentation. scRGB 1.0 is exactly 80 nit.
use crate::{
    post_compute::{PostCompute, barrier},
    resources::Context,
};
use ash::vk;
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HdrCalibration {
    pub reference_white_nits: f32,
    pub peak_nits: f32,
    pub headroom: f32,
    pub sc_rgb_scale: f32,
}
impl HdrCalibration {
    pub fn new(
        peak_nits: f32,
        system_white_nits: f32,
        configured_white_nits: u32,
    ) -> Result<Self, String> {
        if !peak_nits.is_finite()
            || peak_nits <= 0.
            || !system_white_nits.is_finite()
            || system_white_nits <= 0.
            || configured_white_nits > 10000
        {
            return Err("Invalid HDR peak or reference white calibration".into());
        }
        let reference_white_nits = if configured_white_nits == 0 {
            system_white_nits
        } else {
            configured_white_nits as f32
        }
        .min(peak_nits);
        Ok(Self {
            reference_white_nits,
            peak_nits,
            headroom: (peak_nits / reference_white_nits).clamp(1., 10000.),
            sc_rgb_scale: reference_white_nits / 80.,
        })
    }
}

pub(crate) struct HdrPresent {
    context: Arc<Context>,
    compute: PostCompute,
}
impl HdrPresent {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        Ok(Self {
            context: context.clone(),
            compute: PostCompute::new(
                context,
                &[
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                ],
                &[prime_shaders::hdr_present()],
                32,
            )?,
        })
    }
    // Output is the final canonical swapchain; bottom_up describes only the UI source.
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        command: vk::CommandBuffer,
        slot: usize,
        views: [vk::ImageView; 6],
        extent: [u32; 2],
        composite: bool,
        bottom_up: bool,
        scale: f32,
        fg_enabled: bool,
    ) -> Result<(), String> {
        if extent.contains(&0) || !scale.is_finite() || scale <= 0. || fg_enabled && !composite {
            return Err("Invalid HDR presentation extent or scRGB scale".into());
        }
        for (binding, &view) in views.iter().enumerate() {
            self.compute.image(
                slot,
                binding as u32,
                if binding >= 3 {
                    vk::DescriptorType::STORAGE_IMAGE
                } else {
                    vk::DescriptorType::SAMPLED_IMAGE
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
        let mut push = [0u8; 32];
        for (out, value) in push.as_chunks_mut::<4>().0.iter_mut().zip([
            extent[0],
            extent[1],
            u32::from(composite),
            u32::from(bottom_up),
            scale.to_bits(),
            u32::from(fg_enabled),
            0,
            0,
        ]) {
            *out = value.to_le_bytes();
        }
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

/// SDR frame generation preserves display-encoded RGB; only the native UI orientation changes.
pub(crate) struct FrameGenerationPresent {
    context: Arc<Context>,
    compute: PostCompute,
}
impl FrameGenerationPresent {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        Ok(Self {
            context: context.clone(),
            compute: PostCompute::new(
                context,
                &[
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::SAMPLED_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                    vk::DescriptorType::STORAGE_IMAGE,
                ],
                &[prime_shaders::frame_generation_present()],
                16,
            )?,
        })
    }
    pub fn record(
        &self,
        command: vk::CommandBuffer,
        slot: usize,
        views: [vk::ImageView; 4],
        extent: [u32; 2],
        bottom_up: bool,
    ) -> Result<(), String> {
        if extent.contains(&0) {
            return Err("Invalid frame generation extent".into());
        }
        for (binding, &view) in views.iter().enumerate() {
            self.compute.image(
                slot,
                binding as u32,
                if binding < 2 {
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
        let mut push = [0u8; 16];
        for (out, value) in push.as_chunks_mut::<4>().0.iter_mut().zip([
            extent[0],
            extent[1],
            u32::from(bottom_up),
            0,
        ]) {
            *out = value.to_le_bytes();
        }
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calibration_keeps_nits_sc_rgb_units_and_cross_display_config() {
        let automatic = HdrCalibration::new(1000., 200., 0).unwrap();
        assert_eq!(
            automatic,
            HdrCalibration {
                reference_white_nits: 200.,
                peak_nits: 1000.,
                headroom: 5.,
                sc_rgb_scale: 2.5
            }
        );
        let manual = HdrCalibration::new(600., 200., 1000).unwrap();
        assert_eq!(manual.reference_white_nits, 600.);
        assert_eq!(manual.headroom, 1.);
        assert_eq!(manual.sc_rgb_scale, 7.5);
        for (peak, white, configured) in [
            (f32::NAN, 80., 0),
            (1000., 0., 0),
            (1000., f32::INFINITY, 0),
            (1000., 80., 10001),
        ] {
            assert!(HdrCalibration::new(peak, white, configured).is_err());
        }
    }
}
