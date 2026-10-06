//! Small HDR fallback owner for vanilla/UI; no scene, PT pipelines or immutable PT assets.
use crate::{
    FRAME_SLOTS,
    hdr::{HdrCalibration, HdrPresent},
    resources::Context,
    target::{Image, color_range},
};
use ash::vk::{self, Handle};
use std::sync::Arc;
struct RecordingScope(Arc<Context>);
impl Drop for RecordingScope {
    fn drop(&mut self) {
        self.0.end_host_record();
    }
}

pub struct HdrSurface {
    context: Arc<Context>,
    present: HdrPresent,
    dummy: [Image; 3],
    initialized: bool,
    serials: [u64; FRAME_SLOTS],
    failed: bool,
}

impl HdrSurface {
    /// # Safety
    /// The borrowed host handles and timeline remain live until shutdown, and all work uses
    /// the attached graphics queue in submission order with real completion values.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn new(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
    ) -> Result<Self, String> {
        let context =
            unsafe { Context::borrowed(instance, physical, device, queue, family, timeline)? };
        let dummy = [
            vk::Format::R16G16B16A16_SFLOAT,
            vk::Format::R8G8B8A8_UNORM,
            vk::Format::R8_UNORM,
        ]
        .into_iter()
        .map(|format| Image::storage_uninitialized(&context, format))
        .collect::<Result<Vec<_>, _>>()?
        .try_into()
        .map_err(|_| "Invalid HDR dummy image count")?;
        let present = HdrPresent::new(&context)?;
        context.save_pipeline_cache();
        Ok(Self {
            present,
            dummy,
            context,
            initialized: false,
            serials: [0; FRAME_SLOTS],
            failed: false,
        })
    }

    /// # Safety
    /// Borrowed views are sampled RGBA8 UI and storage RGBA16_SFLOAT output in GENERAL,
    /// on the attached device/queue, retained by the host through serial completion.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn record(
        &mut self,
        command: u64,
        ui: u64,
        output: u64,
        serial: u64,
        extent: [u32; 2],
        peak: f32,
        white: f32,
    ) -> Result<(), String> {
        if self.failed
            || [command, ui, output, serial].contains(&0)
            || self.serials.contains(&serial)
        {
            return Err("Invalid or duplicate HDR surface recording".into());
        }
        let calibration = HdrCalibration::new(peak, white, 0)?;
        self.context.render_extent(extent[0], extent[1])?;
        self.failed = true;
        let command = vk::CommandBuffer::from_raw(command);
        let completed = self.context.begin_host_record(command, serial)?;
        let _scope = RecordingScope(self.context.clone());
        let result = (|| {
            let slot = match self.serials.iter().position(|value| *value <= completed) {
                Some(slot) => slot,
                None => {
                    let completed = self
                        .context
                        .wait_host_serial(*self.serials.iter().min().unwrap())?;
                    self.serials
                        .iter()
                        .position(|value| *value <= completed)
                        .ok_or("HDR surface slot did not complete")?
                }
            };
            if !self.initialized {
                let barriers = self.dummy.each_ref().map(|image| {
                    vk::ImageMemoryBarrier::default()
                        .image(image.image)
                        .old_layout(vk::ImageLayout::UNDEFINED)
                        .new_layout(vk::ImageLayout::GENERAL)
                        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .dst_access_mask(
                            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                        )
                        .subresource_range(color_range())
                });
                unsafe {
                    self.context.device.cmd_pipeline_barrier(
                        command,
                        vk::PipelineStageFlags::TOP_OF_PIPE,
                        vk::PipelineStageFlags::COMPUTE_SHADER,
                        vk::DependencyFlags::empty(),
                        &[],
                        &[],
                        &barriers,
                    );
                }
                self.initialized = true;
            }
            self.present.record(
                command,
                slot,
                [
                    self.dummy[0].view,
                    self.dummy[1].view,
                    vk::ImageView::from_raw(ui),
                    vk::ImageView::from_raw(output),
                    self.dummy[0].view,
                    self.dummy[2].view,
                ],
                extent,
                false,
                false,
                calibration.sc_rgb_scale,
                false,
            )?;
            self.serials[slot] = serial;
            Ok(())
        })();
        if result.is_ok() {
            self.failed = false;
        }
        result
    }

    /// Host must first submit all recorded work. Completion is checked before destruction.
    pub fn shutdown(&mut self) -> Result<(), String> {
        self.context.finish_host()?;
        self.failed = true;
        Ok(())
    }
}

impl Drop for HdrSurface {
    fn drop(&mut self) {
        if self.context.can_destroy()
            && let Err(message) = self.context.wait_host_idle()
        {
            eprintln!("[Prime PT] HDR surface resources quarantined: {message}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Buffer, post_compute::barrier};

    #[test]
    #[ignore = "requires a Vulkan GPU with synchronization validation; real borrowed HDR owner"]
    fn gpu_hdr_surface_records_once_reuses_completed_slots_and_retires_without_pt_assets() {
        let host = Context::new().unwrap();
        let mut ty =
            vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
        let timeline = unsafe {
            host.device
                .create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty), None)
        }
        .unwrap();
        struct Timeline(Arc<Context>, vk::Semaphore);
        impl Drop for Timeline {
            fn drop(&mut self) {
                unsafe {
                    self.0.device.destroy_semaphore(self.1, None);
                }
            }
        }
        let _timeline = Timeline(host.clone(), timeline);
        let extent = [17, 9];
        let ui = Image::new(&host, extent[0], extent[1]).unwrap();
        let output =
            Image::with_format(&host, extent[0], extent[1], vk::Format::R16G16B16A16_SFLOAT)
                .unwrap();
        let readback = Buffer::new_readback(&host, u64::from(extent[0] * extent[1]) * 8).unwrap();
        let mut surface = unsafe {
            HdrSurface::new(
                host.instance_handle(),
                host.physical.as_raw(),
                host.device.handle().as_raw(),
                host.queue.as_raw(),
                host.queue_family,
                timeline.as_raw(),
            )
        }
        .unwrap();
        let context = surface.context.clone();
        assert_eq!(
            context
                .live_allocations
                .load(std::sync::atomic::Ordering::Relaxed),
            3
        );
        let region = vk::BufferImageCopy::default()
            .image_subresource(
                vk::ImageSubresourceLayers::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .layer_count(1),
            )
            .image_extent(vk::Extent3D {
                width: extent[0],
                height: extent[1],
                depth: 1,
            });
        for (serial, white, expected) in [
            (1, 80., 0x3c00u16),
            (2, 200., 0x4100),
            (3, 320., 0x4400),
            (4, 80., 0x3c00),
        ] {
            if serial == 4 {
                // All three real submissions completed before publishing the proof. The ring
                // must retain their descriptor identities until this exact timeline value.
                assert_eq!(surface.serials, [1, 2, 3]);
                unsafe {
                    host.device.signal_semaphore(
                        &vk::SemaphoreSignalInfo::default()
                            .semaphore(timeline)
                            .value(3),
                    )
                }
                .unwrap();
            }
            host.submit_named("borrowed_hdr_surface", |command| {
                barrier(
                    &host,
                    command,
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::TRANSFER_WRITE,
                );
                unsafe {
                    host.device.cmd_clear_color_image(
                        command,
                        ui.image,
                        vk::ImageLayout::GENERAL,
                        &vk::ClearColorValue {
                            float32: [1., 0., 1., 0.25],
                        },
                        &[color_range()],
                    );
                }
                unsafe {
                    surface.record(
                        command.as_raw(),
                        ui.view.as_raw(),
                        output.view.as_raw(),
                        serial,
                        extent,
                        1000.,
                        white,
                    )
                }
                .unwrap();
                assert!(
                    unsafe {
                        surface.record(
                            command.as_raw(),
                            ui.view.as_raw(),
                            output.view.as_raw(),
                            serial,
                            extent,
                            1000.,
                            white,
                        )
                    }
                    .is_err()
                );
                assert!(
                    !surface.failed,
                    "Rejected duplicate must not discard the valid recording"
                );
                barrier(
                    &host,
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::AccessFlags::SHADER_WRITE,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::TRANSFER_READ,
                );
                unsafe {
                    host.device.cmd_copy_image_to_buffer(
                        command,
                        output.image,
                        vk::ImageLayout::GENERAL,
                        readback.buffer,
                        &[region],
                    );
                }
                barrier(
                    &host,
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::TRANSFER_WRITE,
                    vk::PipelineStageFlags::HOST,
                    vk::AccessFlags::HOST_READ,
                );
            })
            .unwrap();
            let actual = readback.read((extent[0] * extent[1] * 8) as usize).unwrap();
            for pixel in actual.as_chunks::<8>().0 {
                let channels: [u16; 4] = std::array::from_fn(|index| {
                    u16::from_le_bytes([pixel[index * 2], pixel[index * 2 + 1]])
                });
                assert_eq!(
                    channels,
                    [expected, 0, expected, 0x3c00],
                    "scRGB reference white and alpha differ"
                );
            }
        }
        unsafe {
            host.device.signal_semaphore(
                &vk::SemaphoreSignalInfo::default()
                    .semaphore(timeline)
                    .value(4),
            )
        }
        .unwrap();
        surface.shutdown().unwrap();
        drop(surface);
        assert_eq!(
            context
                .live_allocations
                .load(std::sync::atomic::Ordering::Relaxed),
            0
        );
    }
}
