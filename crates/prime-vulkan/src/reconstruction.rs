//! Streamline owns its feature; Rust owns all tagged images and their host completion lifetime.
use super::reconstruction_history as history;
use super::{Buffer, Context, FRAME_SLOTS, Image, Pipeline, PrimeDrtParameters};
use crate::temporal_reset::{self, Backend, GlobalReset, StorageCold};
use ash::vk;
use prime_scene::scene::Camera;
use prime_scene::settings::{DiagnosticView, ReconstructionQuality};
use std::sync::Arc;

#[path = "streamline.rs"]
mod streamline;
pub(crate) use streamline::bootstrap;
pub(crate) use streamline::frame;
pub(crate) use streamline::present;

const FORMATS: [vk::Format; 9] = [
    vk::Format::R16G16B16A16_SFLOAT, // noisy linear BT.709
    vk::Format::R32_SFLOAT,          // positive view Z
    vk::Format::R16G16_SFLOAT,       // unjittered previous - current input pixels
    vk::Format::R16G16B16A16_SFLOAT, // world normal, roughness
    vk::Format::R16G16B16A16_SFLOAT, // diffuse albedo
    vk::Format::R16G16B16A16_SFLOAT, // specular albedo
    vk::Format::R16_SFLOAT,          // internal sampled reflection distance, not tagged to SDK
    vk::Format::R16G16B16A16_SFLOAT, // reconstructed output linear BT.709
    vk::Format::R16G16_SFLOAT,       // unjittered specular previous - current input pixels
];

struct Images {
    input: [u32; 2],
    output: [u32; 2],
    quality: ReconstructionQuality,
    images: [Image; 9],
    // Engine completion state only; never tagged as an SDK input or BiasCurrentColorHint.
    unresolved: Image,
    visible: Option<[Image; 2]>,
}

#[derive(Clone, Copy)]
struct Previous {
    camera: Camera,
    anchor: [f64; 3],
}

pub(super) struct Reconstruction {
    context: Arc<Context>,
    runtime: Option<streamline::Runtime>,
    images: Option<Images>,
    constants: [Buffer; FRAME_SLOTS],
    previous: Option<Previous>,
    current: Option<streamline::Frame>,
    pending: Option<Previous>,
    last_serial: u64,
    error: Option<String>,
    descriptor_dirty: [bool; FRAME_SLOTS],
    epoch: Option<u64>,
    ignore_global_history_resets: bool,
}

impl Reconstruction {
    pub fn new(context: &Arc<Context>, failure: &mut Option<String>) -> Option<Self> {
        *failure = None;
        if !context.streamline_capable {
            *failure =
                Some("DLSS RR device capability unavailable; using native raw output".into());
            return None;
        }
        let create = || -> Result<Self, String> {
            if !context.supports_storage_sampling(vk::Format::R8_UNORM) {
                return Err(
                    "DLSS RR guide completion mask requires sampled/storage R8_UNORM images".into(),
                );
            }
            let runtime = streamline::Runtime::new(context)?;
            let constants = (0..FRAME_SLOTS)
                .map(|_| Buffer::new(context, 144, vk::BufferUsageFlags::UNIFORM_BUFFER, true))
                .collect::<Result<Vec<_>, _>>()?
                .try_into()
                .map_err(|_| "Invalid RR constants count")?;
            Ok(Self {
                context: context.clone(),
                runtime: Some(runtime),
                images: None,
                constants,
                previous: None,
                current: None,
                pending: None,
                last_serial: 0,
                error: None,
                descriptor_dirty: [true; FRAME_SLOTS],
                epoch: None,
                ignore_global_history_resets: false,
            })
        };
        match create() {
            Ok(result) => {
                eprintln!("[Prime PT] Streamline DLSS Ray Reconstruction available; preset F");
                Some(result)
            }
            Err(message) => {
                eprintln!("[Prime PT] DLSS RR unavailable; using raw output: {message}");
                *failure = Some(message);
                None
            }
        }
    }

    pub fn failed(&self) -> bool {
        self.error.is_some()
    }
    pub fn last_error(&self) -> Option<&str> {
        self.error.as_deref()
    }
    pub fn diagnostics(&self) -> (bool, [u32; 2], [u32; 2]) {
        self.images
            .as_ref()
            .map_or((false, [0; 2], [0; 2]), |images| {
                (self.previous.is_some(), images.input, images.output)
            })
    }
    pub fn set_history_reset_policy(&mut self, ignore: bool) {
        self.ignore_global_history_resets = ignore;
    }
    pub fn reset(&mut self, reason: GlobalReset) {
        temporal_reset::record_global(
            Backend::Rr,
            reason,
            self.previous.is_some(),
            self.ignore_global_history_resets,
        );
        if !self.ignore_global_history_resets {
            self.previous = None;
            self.pending = None;
        }
    }
    pub fn storage_cold(&mut self, reason: StorageCold) {
        temporal_reset::record_storage(Backend::Rr, reason, self.previous.is_some());
        self.previous = None;
        self.pending = None;
    }
    pub fn invalidate_descriptors(&mut self) {
        self.descriptor_dirty.fill(true);
    }
    /// The host proves actual ordered submission, independently of CPU recording.
    pub fn commit(&mut self) {
        self.previous = self.pending.take();
    }
    pub fn input_extent(&self) -> [u32; 2] {
        self.images.as_ref().unwrap().input
    }
    pub fn jitter(&self, sequence: u32) -> [f32; 2] {
        let images = self.images.as_ref().unwrap();
        history::jitter(sequence, images.input[0], images.output[0])
    }
    pub fn status_view(&self) -> vk::ImageView {
        self.images.as_ref().unwrap().unresolved.view
    }

    pub fn frame_generation_supported(&self) -> bool {
        !self.failed()
            && self
                .runtime
                .as_ref()
                .is_some_and(|runtime| runtime.frame_generation_supported())
    }

    /// SDK FG may read outside the world's submission. Its public fence proves that consumer.
    pub(super) fn suspend_frame_generation(&mut self) -> Result<(), String> {
        let result = self
            .runtime
            .as_mut()
            .map_or(Ok(()), |runtime| runtime.suspend_frame_generation());
        if result.is_err() {
            self.context
                .uncertain_submission
                .store(true, std::sync::atomic::Ordering::Relaxed);
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    pub fn prepare_frame_generation(
        &mut self,
        command: vk::CommandBuffer,
        hudless: &Image,
        ui_alpha: &Image,
        back_buffers: u32,
        back_buffer_format: vk::Format,
        serial: u64,
    ) -> Result<bool, String> {
        if !self.frame_generation_supported() {
            return Ok(false);
        }
        let Some(images) = self.images.as_ref() else {
            return Ok(false);
        };
        let Some(visible) = images.visible.as_ref() else {
            return Ok(false);
        };
        let Some(frame) = self.current.as_ref() else {
            return Ok(false);
        };
        self.last_serial = serial;
        self.runtime.as_mut().unwrap().prepare_frame_generation(
            command,
            frame,
            visible,
            hudless,
            ui_alpha,
            images.input,
            images.output,
            back_buffers,
            back_buffer_format,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn prepare(
        &mut self,
        slot: usize,
        camera: Camera,
        anchor: [f64; 3],
        epoch: u64,
        output: [u32; 2],
        sequence: u32,
        quality: ReconstructionQuality,
        completed: u64,
        frame_generation: bool,
    ) -> Result<(), String> {
        if self.epoch != Some(epoch) {
            if self.epoch.is_some() {
                self.reset(GlobalReset::WorldEpochChanged);
            }
            self.epoch = Some(epoch);
        }
        if self
            .images
            .as_ref()
            .is_none_or(|images| images.output != output || images.quality != quality)
        {
            let replacing_images = self.images.is_some();
            // FG's consumer is independent of the host-world timeline.
            self.suspend_frame_generation()?;
            // Reconfiguration can release SDK-private history, so first prove its last use complete.
            if self.last_serial > completed {
                self.context.wait_host_serial(self.last_serial)?;
            }
            let input = self.runtime.as_mut().unwrap().configure(output, quality)?;
            self.context.render_extent(input[0], input[1])?;
            let images = FORMATS
                .into_iter()
                .enumerate()
                .map(|(i, format)| {
                    let extent = if i == 7 { output } else { input };
                    Image::with_format(&self.context, extent[0], extent[1], format)
                })
                .collect::<Result<Vec<_>, _>>()?
                .try_into()
                .map_err(|_| "Invalid RR image count")?;
            self.images = Some(Images {
                input,
                output,
                quality,
                images,
                unresolved: Image::with_format(
                    &self.context,
                    input[0],
                    input[1],
                    vk::Format::R8_UNORM,
                )?,
                visible: None,
            });
            self.descriptor_dirty.fill(true);
            if replacing_images {
                self.reset(GlobalReset::RrFeatureReconfigured);
            }
            self.storage_cold(StorageCold::RrFeatureReconfigured);
            eprintln!(
                "[Prime PT] DLSS RR preset F {:?}: {}x{} -> {}x{}",
                quality, input[0], input[1], output[0], output[1]
            );
        }
        let frame_generation = frame_generation && self.frame_generation_supported();
        if self.images.as_ref().unwrap().visible.is_some() != frame_generation {
            self.suspend_frame_generation()?;
            if self.last_serial > completed {
                self.context.wait_host_serial(self.last_serial)?;
            }
            let images = self.images.as_mut().unwrap();
            images.visible = if frame_generation {
                Some([
                    Image::with_format(
                        &self.context,
                        images.input[0],
                        images.input[1],
                        vk::Format::R32_SFLOAT,
                    )?,
                    Image::with_format(
                        &self.context,
                        images.input[0],
                        images.input[1],
                        vk::Format::R16G16_SFLOAT,
                    )?,
                ])
            } else {
                None
            };
            self.descriptor_dirty.fill(true);
        }
        let input = self.input_extent();
        let aspect = output[0] as f32 / output[1] as f32;
        let jitter = history::jitter(sequence, input[0], output[0]);
        let previous = self
            .previous
            .map(|p| history::rebase(p.camera, p.anchor, anchor));
        // Camera/FOV changes are evaluated by reprojection and guide completion per pixel.
        // Sequence is a sampling identity; zero or a gap does not prove a camera cut.
        let valid = previous.is_some();
        let mut event = prime_diagnostics::scope("rr.history.frame");
        event.count("temporal", u64::from(valid));
        event.count("sequence", u64::from(sequence));
        let previous_camera = previous.unwrap_or(camera);
        self.constants[slot].write(&history::camera_constants(
            camera,
            previous_camera,
            aspect,
            jitter,
            valid,
        ))?;
        self.current = Some(streamline::Frame::new(
            camera,
            previous_camera,
            aspect,
            jitter,
            !valid,
            sequence,
        ));
        self.pending = Some(Previous { camera, anchor });
        Ok(())
    }

    pub fn descriptors(&mut self, descriptor: vk::DescriptorSet, slot: usize) {
        if !self.descriptor_dirty[slot] {
            return;
        }
        let images = &self.images.as_ref().unwrap().images;
        let infos = images.each_ref().map(|image| {
            [vk::DescriptorImageInfo::default()
                .image_view(image.view)
                .image_layout(vk::ImageLayout::GENERAL)]
        });
        let constants = [vk::DescriptorBufferInfo::default()
            .buffer(self.constants[slot].buffer)
            .range(144)];
        let unresolved = [vk::DescriptorImageInfo::default()
            .image_view(self.images.as_ref().unwrap().unresolved.view)
            .image_layout(vk::ImageLayout::GENERAL)];
        let visible = self.images.as_ref().unwrap().visible.as_ref();
        let visible_infos = [vk::DescriptorImageInfo::default()
            .image_view(visible.map_or(images[1].view, |images| images[0].view))
            .image_layout(vk::ImageLayout::GENERAL)];
        let motion_infos = [vk::DescriptorImageInfo::default()
            .image_view(visible.map_or(images[2].view, |images| images[1].view))
            .image_layout(vk::ImageLayout::GENERAL)];
        let mut writes = [vk::WriteDescriptorSet::default(); 13];
        for ((binding, info), write) in [10, 11, 12, 13, 14, 15, 16, 18, 20]
            .into_iter()
            .zip(&infos)
            .zip(&mut writes[..9])
        {
            *write = vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(binding)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .image_info(info);
        }
        writes[9] = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(17)
            .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
            .buffer_info(&constants);
        writes[10] = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(19)
            .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
            .image_info(&unresolved);
        writes[11] = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(21)
            .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
            .image_info(&visible_infos);
        writes[12] = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(22)
            .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
            .image_info(&motion_infos);
        unsafe {
            self.context.device.update_descriptor_sets(&writes, &[]);
        }
        self.descriptor_dirty[slot] = false;
    }

    fn barrier(&self, command: vk::CommandBuffer, before_sdk: bool) {
        // Streamline restores all resources to their tagged GENERAL state. This dependency also
        // includes partially recorded SDK work when evaluation reports failure.
        unsafe {
            self.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                if before_sdk {
                    vk::PipelineStageFlags::ALL_COMMANDS
                } else {
                    vk::PipelineStageFlags::COMPUTE_SHADER
                },
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::MEMORY_WRITE | vk::AccessFlags::MEMORY_READ)
                    .dst_access_mask(if before_sdk {
                        vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE
                    } else {
                        vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE
                    })],
                &[],
                &[],
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn evaluate_and_display(
        &mut self,
        command: vk::CommandBuffer,
        pipeline: &Pipeline,
        slot: usize,
        serial: u64,
        display: PrimeDrtParameters,
        view: DiagnosticView,
        depth_range: f32,
        bottom_up: bool,
        linear_output: bool,
    ) -> Result<(), String> {
        self.barrier(command, true);
        let images = self.images.as_ref().unwrap();
        let mut success = false;
        // Keep the model and accepted camera history advancing together while guides
        // are displayed. A diagnostic view only changes the final display selection.
        if !self.failed() {
            self.last_serial = serial;
            let result = self.runtime.as_mut().unwrap().evaluate(
                command,
                self.current.as_ref().unwrap(),
                &images.images,
                FORMATS,
                images.input,
                images.output,
            );
            match result {
                Ok(()) => {
                    success = true;
                    // Promotion occurs only at the actual host submission acceptance hook.
                }
                Err(failure) => {
                    self.error = Some(failure.message.clone());
                    self.reset(GlobalReset::RrEvaluationFailed);
                    self.storage_cold(StorageCold::RrEvaluationFailed);
                    if failure.unsafe_recording {
                        return Err(failure.message);
                    }
                    eprintln!(
                        "[Prime PT] DLSS RR evaluation failed; raw output restored: {}",
                        failure.message
                    );
                }
            }
        } else {
            // A permanently failed model did not consume this camera. Raw fallback
            // must not promote it as accepted SDK history.
            self.pending = None;
        }
        self.barrier(command, false);
        let images = self.images.as_ref().unwrap();
        let mut push = [0u8; 64];
        let integers = [
            images.input[0],
            images.input[1],
            images.output[0],
            images.output[1],
            u32::from(bottom_up),
            view as u32,
            u32::from(success),
            0,
        ];
        for (dst, v) in push[..32].as_chunks_mut::<4>().0.iter_mut().zip(integers) {
            *dst = v.to_le_bytes();
        }
        for (dst, v) in push[32..]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(display.values)
        {
            *dst = v.to_le_bytes();
        }
        push[52..56].copy_from_slice(&depth_range.to_le_bytes());
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                if linear_output {
                    pipeline.reconstruction_linear.unwrap()
                } else {
                    pipeline.reconstruction_display.unwrap()
                },
            );
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[pipeline.descriptors[slot]],
                &[],
            );
            self.context.device.cmd_push_constants(
                command,
                pipeline.layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                &push,
            );
            self.context.device.cmd_dispatch(
                command,
                images.output[0].div_ceil(8),
                images.output[1].div_ceil(8),
                1,
            );
        }
        Ok(())
    }
}

impl Drop for Reconstruction {
    fn drop(&mut self) {
        if self.context.can_destroy()
            && let Err(message) = self.suspend_frame_generation()
        {
            eprintln!("[Prime PT] Streamline FG resources quarantined: {message}");
        }
        if self.context.can_destroy()
            && self.last_serial != 0
            && let Err(message) = self.context.wait_host_serial(self.last_serial)
        {
            eprintln!("[Prime PT] Streamline resources quarantined: {message}");
        }
        if !self.context.can_destroy() {
            // Without completion proof neither SDK objects nor its DLL may be released.
            if let Some(runtime) = self.runtime.take() {
                std::mem::forget(runtime);
            }
        }
    }
}
