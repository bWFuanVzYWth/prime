//! Streamline owns its feature; Rust owns all tagged images and their host completion lifetime.
use super::reconstruction_history as history;
use super::{Buffer, Context, FRAME_SLOTS, Image, Pipeline, PrimeDrtParameters};
use ash::vk;
use prime_scene::scene::Camera;
use prime_scene::settings::{DiagnosticView, ReconstructionQuality};
use std::sync::Arc;

#[path = "streamline.rs"]
mod streamline;
pub(crate) use streamline::present;

const FORMATS: [vk::Format; 8] = [
    vk::Format::R16G16B16A16_SFLOAT, // noisy linear BT.709
    vk::Format::R32_SFLOAT,          // positive view Z
    vk::Format::R16G16_SFLOAT,       // unjittered previous - current UV
    vk::Format::R16G16B16A16_SFLOAT, // world normal, roughness
    vk::Format::R16G16B16A16_SFLOAT, // diffuse albedo
    vk::Format::R16G16B16A16_SFLOAT, // specular albedo
    vk::Format::R16_SFLOAT,          // specular hit distance
    vk::Format::R16G16B16A16_SFLOAT, // reconstructed output linear BT.709
];

struct Images {
    input: [u32; 2],
    output: [u32; 2],
    images: [Image; 8],
}

#[derive(Clone, Copy)]
struct Previous {
    camera: Camera,
    anchor: [f64; 3],
    sequence: u32,
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
}

impl Reconstruction {
    pub fn new(context: &Arc<Context>, failure: &mut Option<String>) -> Option<Self> {
        *failure = None;
        if !context.streamline_capable {
            return None;
        }
        let create = || -> Result<Self, String> {
            let runtime = streamline::Runtime::new(context)?;
            let constants = (0..FRAME_SLOTS)
                .map(|_| Buffer::new(context, 80, vk::BufferUsageFlags::UNIFORM_BUFFER, true))
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
    pub fn reset(&mut self) {
        self.previous = None;
    }
    pub fn input_extent(&self) -> [u32; 2] {
        self.images.as_ref().unwrap().input
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
    ) -> Result<(), String> {
        if self.epoch != Some(epoch) {
            self.reset();
            self.epoch = Some(epoch);
        }
        if self
            .images
            .as_ref()
            .is_none_or(|images| images.output != output)
        {
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
                images,
            });
            self.descriptor_dirty.fill(true);
            self.reset();
            eprintln!(
                "[Prime PT] DLSS RR preset F {:?}: {}x{} -> {}x{}",
                quality, input[0], input[1], output[0], output[1]
            );
        }
        let input = self.input_extent();
        let aspect = output[0] as f32 / output[1] as f32;
        let jitter = history::jitter(sequence, input[0], output[0]);
        let previous = self
            .previous
            .map(|p| (history::rebase(p.camera, p.anchor, anchor), p.sequence));
        let valid = previous
            .is_some_and(|(p, s)| sequence == s.wrapping_add(1) && !history::camera_cut(camera, p));
        let previous_camera = previous.filter(|_| valid).map_or(camera, |(p, _)| p);
        let values = [
            previous_camera.position[0],
            previous_camera.position[1],
            previous_camera.position[2],
            (previous_camera.vertical_fov_radians * 0.5).tan(),
            previous_camera.forward[0],
            previous_camera.forward[1],
            previous_camera.forward[2],
            aspect,
            previous_camera.right[0],
            previous_camera.right[1],
            previous_camera.right[2],
            0.0,
            previous_camera.up[0],
            previous_camera.up[1],
            previous_camera.up[2],
            0.0,
            jitter[0],
            jitter[1],
            f32::from(valid),
            0.0,
        ];
        let bytes = values.map(f32::to_le_bytes);
        self.constants[slot].write(bytes.as_flattened())?;
        self.current = Some(streamline::Frame::new(
            camera,
            previous_camera,
            aspect,
            jitter,
            !valid,
            sequence,
        ));
        self.pending = Some(Previous {
            camera,
            anchor,
            sequence,
        });
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
            .range(80)];
        let mut writes = [vk::WriteDescriptorSet::default(); 9];
        for ((binding, info), write) in [10, 11, 12, 13, 14, 15, 16, 18]
            .into_iter()
            .zip(&infos)
            .zip(&mut writes[..8])
        {
            *write = vk::WriteDescriptorSet::default()
                .dst_set(descriptor)
                .dst_binding(binding)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .image_info(info);
        }
        writes[8] = vk::WriteDescriptorSet::default()
            .dst_set(descriptor)
            .dst_binding(17)
            .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
            .buffer_info(&constants);
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
    ) -> Result<(), String> {
        self.barrier(command, true);
        let images = self.images.as_ref().unwrap();
        let mut success = false;
        if view == DiagnosticView::Output && !self.failed() {
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
                    self.previous = self.pending;
                }
                Err(failure) => {
                    self.error = Some(failure.message.clone());
                    self.previous = None;
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
            self.previous = None;
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
                pipeline.reconstruction_display.unwrap(),
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
