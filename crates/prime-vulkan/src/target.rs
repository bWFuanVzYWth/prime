//! Owned guide/diagnostic images. The production presentation target is borrowed.
use super::resources::{Context, error};
use ash::vk;
use std::sync::Arc;

pub(super) struct Image {
    context: Arc<Context>,
    pub image: vk::Image,
    pub view: vk::ImageView,
    memory: vk::DeviceMemory,
}

impl Image {
    pub fn new(context: &Arc<Context>, width: u32, height: u32) -> Result<Self, String> {
        Self::with_format(context, width, height, vk::Format::R8G8B8A8_UNORM)
    }

    pub fn with_format(
        context: &Arc<Context>,
        width: u32,
        height: u32,
        format: vk::Format,
    ) -> Result<Self, String> {
        let mut result = Self {
            context: context.clone(),
            image: vk::Image::null(),
            view: vk::ImageView::null(),
            memory: vk::DeviceMemory::null(),
        };
        unsafe {
            result.image = context
                .device
                .create_image(
                    &vk::ImageCreateInfo::default()
                        .image_type(vk::ImageType::TYPE_2D)
                        .format(format)
                        .extent(vk::Extent3D {
                            width,
                            height,
                            depth: 1,
                        })
                        .mip_levels(1)
                        .array_layers(1)
                        .samples(vk::SampleCountFlags::TYPE_1)
                        .tiling(vk::ImageTiling::OPTIMAL)
                        .usage(
                            vk::ImageUsageFlags::STORAGE
                                | vk::ImageUsageFlags::SAMPLED
                                | vk::ImageUsageFlags::TRANSFER_SRC,
                        )
                        .sharing_mode(vk::SharingMode::EXCLUSIVE),
                    None,
                )
                .map_err(|e| error("Create diagnostic output image", e))?;
            let requirements = context.device.get_image_memory_requirements(result.image);
            let index = (0..context.memory.memory_type_count)
                .find(|i| {
                    requirements.memory_type_bits & (1 << i) != 0
                        && context.memory.memory_types[*i as usize]
                            .property_flags
                            .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
                })
                .ok_or("No device-local image memory")?;
            result.memory = context
                .device
                .allocate_memory(
                    &vk::MemoryAllocateInfo::default()
                        .allocation_size(requirements.size)
                        .memory_type_index(index),
                    None,
                )
                .map_err(|e| error("Allocate diagnostic image", e))?;
            context
                .device
                .bind_image_memory(result.image, result.memory, 0)
                .map_err(|e| error("Bind diagnostic image", e))?;
            result.view = context
                .device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(result.image)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(format)
                        .subresource_range(color_range()),
                    None,
                )
                .map_err(|e| error("Create diagnostic image view", e))?;
            context.submit_named("initialize_diagnostic_target", |command| {
                let barrier = [vk::ImageMemoryBarrier::default()
                    .image(result.image)
                    .old_layout(vk::ImageLayout::UNDEFINED)
                    .new_layout(vk::ImageLayout::GENERAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .subresource_range(color_range())];
                context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TOP_OF_PIPE,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &barrier,
                );
            })?;
        }
        Ok(result)
    }
}

pub(super) fn color_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .level_count(1)
        .layer_count(1)
}

impl Drop for Image {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            self.context
                .retire_image(self.image, self.view, self.memory);
        }
    }
}
