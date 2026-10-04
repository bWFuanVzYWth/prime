//! Legacy Prime's device-local histogram and temporal exposure. No CPU readback or extra submit.
use crate::{
    post_compute::{PostCompute, barrier},
    resources::{Buffer, Context},
};
use ash::vk;
use std::sync::Arc;

pub(crate) struct Exposure {
    context: Arc<Context>,
    compute: PostCompute,
    histogram: Buffer,
    state: Buffer,
    initialized: bool,
}

impl Exposure {
    pub fn new(context: &Arc<Context>) -> Result<Self, String> {
        let compute = PostCompute::new(
            context,
            &[
                vk::DescriptorType::SAMPLED_IMAGE,
                vk::DescriptorType::STORAGE_BUFFER,
                vk::DescriptorType::STORAGE_BUFFER,
            ],
            &[
                prime_shaders::exposure_histogram(),
                prime_shaders::exposure_update(),
            ],
            32,
        )?;
        let usage = vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST;
        Ok(Self {
            context: context.clone(),
            compute,
            histogram: Buffer::new(context, 257 * 4, usage, false)?,
            state: Buffer::new(
                context,
                16,
                usage
                    | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
                    | vk::BufferUsageFlags::TRANSFER_SRC,
                false,
            )?,
            initialized: false,
        })
    }

    pub fn state_address(&self) -> u64 {
        self.state.address()
    }

    /// Frozen Offline frames retain the same state by omitting this call. Returning to realtime
    /// resets explicitly; changing manual exposure never changes the linear accumulation.
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &mut self,
        command: vk::CommandBuffer,
        slot: usize,
        input_view: vk::ImageView,
        input_address: u64,
        extent: [u32; 2],
        linear709: bool,
        delta: f32,
        reset: bool,
        instant: bool,
        compensation: f32,
    ) -> Result<(), String> {
        if extent.contains(&0)
            || u64::from(extent[0]) * u64::from(extent[1]) > u64::from(u32::MAX)
            || !delta.is_finite()
            || delta < 0.
            || !compensation.is_finite()
            || !(0. ..=1.).contains(&compensation)
        {
            return Err("Invalid exposure extent, frame delta or compensation".into());
        }
        self.compute
            .image(slot, 0, vk::DescriptorType::SAMPLED_IMAGE, input_view);
        self.compute.buffer(slot, 1, &self.histogram);
        self.compute.buffer(slot, 2, &self.state);
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::ALL_COMMANDS,
            vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
            vk::PipelineStageFlags::TRANSFER,
            vk::AccessFlags::TRANSFER_WRITE,
        );
        unsafe {
            self.context.device.cmd_fill_buffer(
                command,
                self.histogram.buffer,
                0,
                self.histogram.size,
                0,
            );
            if !self.initialized {
                self.context.device.cmd_fill_buffer(
                    command,
                    self.state.buffer,
                    0,
                    self.state.size,
                    0,
                );
            }
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
        for (out, value) in push[..16].as_chunks_mut::<4>().0.iter_mut().zip([
            extent[0],
            extent[1],
            u32::from(linear709),
            u32::from(input_address != 0),
        ]) {
            *out = value.to_le_bytes();
        }
        push[16..24].copy_from_slice(&input_address.to_le_bytes());
        self.compute.dispatch(
            command,
            0,
            slot,
            &push,
            [extent[0].div_ceil(64), extent[1].div_ceil(64), 1],
        );
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_WRITE,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
        );
        for (out, value) in push[..16].as_chunks_mut::<4>().0.iter_mut().zip([
            delta.to_bits(),
            u32::from(reset || !self.initialized),
            u32::from(instant),
            compensation.to_bits(),
        ]) {
            *out = value.to_le_bytes();
        }
        self.compute.dispatch(command, 1, slot, &push[..16], [1; 3]);
        barrier(
            &self.context,
            command,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_WRITE,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::AccessFlags::SHADER_READ,
        );
        self.initialized = true;
        Ok(())
    }
}

#[cfg(all(test, feature = "shader-tests"))]
#[path = "mature_display_tests.rs"]
mod gpu_tests;
