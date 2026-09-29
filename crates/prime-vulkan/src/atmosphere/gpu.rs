//! Small, explicitly owned compute resources for atmosphere producers.
use crate::resources::{Buffer, Context, error};
use crate::target::Image;
use ash::vk;
use std::{io::Cursor, sync::Arc};

pub(super) struct Texture {
    pub image: Image,
    pub extent: [u32; 3],
    pub format: vk::Format,
}
impl Texture {
    pub fn new(
        context: &Arc<Context>,
        extent: [u32; 3],
        format: vk::Format,
    ) -> Result<Self, String> {
        Ok(Self {
            image: Image::with_extent(context, extent, format)?,
            extent,
            format,
        })
    }
    pub fn size(&self) -> usize {
        self.extent
            .into_iter()
            .map(|n| n as usize)
            .product::<usize>()
            * match self.format {
                vk::Format::R16G16B16A16_SFLOAT => 8,
                vk::Format::R32G32B32A32_SFLOAT => 16,
                _ => unreachable!(),
            }
    }
    fn copy(&self) -> vk::BufferImageCopy {
        vk::BufferImageCopy::default()
            .image_subresource(
                vk::ImageSubresourceLayers::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .layer_count(1),
            )
            .image_extent(vk::Extent3D {
                width: self.extent[0],
                height: self.extent[1],
                depth: self.extent[2],
            })
    }
    pub fn upload(&self, context: &Arc<Context>, data: &[u8]) -> Result<(), String> {
        if data.len() != self.size() {
            return Err("Atmosphere texture payload size mismatch".into());
        }
        let source = Buffer::upload(context, data, vk::BufferUsageFlags::TRANSFER_SRC)?;
        context.submit_named("atmosphere_upload", |command| unsafe {
            barrier(context, command);
            context.device.cmd_copy_buffer_to_image(
                command,
                source.buffer,
                self.image.image,
                vk::ImageLayout::GENERAL,
                &[self.copy()],
            );
            barrier(context, command);
        })
    }
    #[cfg(feature = "atmosphere-bake")]
    pub fn clear(&self, context: &Context, command: vk::CommandBuffer) {
        unsafe {
            context.device.cmd_clear_color_image(
                command,
                self.image.image,
                vk::ImageLayout::GENERAL,
                &vk::ClearColorValue { float32: [0.; 4] },
                &[crate::target::color_range()],
            );
        }
    }
    #[cfg(any(feature = "atmosphere-bake", test))]
    pub fn read(&self, context: &Arc<Context>) -> Result<Vec<u8>, String> {
        if context.is_borrowed() {
            return Err("Atmosphere asset readback requires an independent device".into());
        }
        let destination = Buffer::new_readback(context, self.size() as u64)?;
        context.submit_named("atmosphere_asset_readback", |command| unsafe {
            barrier(context, command);
            context.device.cmd_copy_image_to_buffer(
                command,
                self.image.image,
                vk::ImageLayout::GENERAL,
                destination.buffer,
                &[self.copy()],
            );
            let memory = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &memory,
                &[],
                &[],
            );
        })?;
        destination.read(self.size())
    }
}

pub(super) fn barrier(context: &Context, command: vk::CommandBuffer) {
    unsafe {
        let memory = [vk::MemoryBarrier::default()
            .src_access_mask(
                vk::AccessFlags::SHADER_READ
                    | vk::AccessFlags::SHADER_WRITE
                    | vk::AccessFlags::TRANSFER_WRITE
                    | vk::AccessFlags::TRANSFER_READ,
            )
            .dst_access_mask(
                vk::AccessFlags::SHADER_READ
                    | vk::AccessFlags::SHADER_WRITE
                    | vk::AccessFlags::TRANSFER_WRITE
                    | vk::AccessFlags::TRANSFER_READ,
            )];
        let stages = vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER;
        context.device.cmd_pipeline_barrier(
            command,
            stages,
            stages,
            vk::DependencyFlags::empty(),
            &memory,
            &[],
            &[],
        );
    }
}

pub(super) struct Compute {
    context: Arc<Context>,
    pub layout: vk::PipelineLayout,
    pub layouts: Vec<vk::DescriptorSetLayout>,
    pool: vk::DescriptorPool,
    pub sets: Vec<Vec<vk::DescriptorSet>>,
    pipelines: Vec<vk::Pipeline>,
    pub sampler: vk::Sampler,
}
impl Compute {
    pub fn new(
        context: &Arc<Context>,
        groups: &[&[vk::DescriptorType]],
        slots: usize,
        shaders: &[&[u8]],
        push_bytes: u32,
    ) -> Result<Self, String> {
        let mut result = Self {
            context: context.clone(),
            layout: vk::PipelineLayout::null(),
            layouts: vec![],
            pool: vk::DescriptorPool::null(),
            sets: vec![],
            pipelines: vec![],
            sampler: vk::Sampler::null(),
        };
        unsafe {
            result.sampler = context
                .device
                .create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::LINEAR)
                        .min_filter(vk::Filter::LINEAR)
                        .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .max_lod(0.0),
                    None,
                )
                .map_err(|e| error("Create atmosphere sampler", e))?;
            let mut counts = std::collections::BTreeMap::new();
            for &group in groups {
                result.layouts.push(descriptor_layout(context, group)?);
                for &ty in group {
                    *counts.entry(ty).or_insert(0) += slots as u32;
                }
            }
            let sizes: Vec<_> = counts
                .into_iter()
                .map(|(ty, descriptor_count)| vk::DescriptorPoolSize {
                    ty,
                    descriptor_count,
                })
                .collect();
            result.pool = context
                .device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets((slots * groups.len()) as u32)
                        .pool_sizes(&sizes),
                    None,
                )
                .map_err(|e| error("Create atmosphere descriptor pool", e))?;
            for &layout in &result.layouts {
                let layouts = vec![layout; slots];
                result.sets.push(
                    context
                        .device
                        .allocate_descriptor_sets(
                            &vk::DescriptorSetAllocateInfo::default()
                                .descriptor_pool(result.pool)
                                .set_layouts(&layouts),
                        )
                        .map_err(|e| error("Allocate atmosphere descriptors", e))?,
                );
            }
            let push = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
                .size(push_bytes)];
            result.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&result.layouts)
                        .push_constant_ranges(&push),
                    None,
                )
                .map_err(|e| error("Create atmosphere pipeline layout", e))?;
            for &bytes in shaders {
                let spirv =
                    ash::util::read_spv(&mut Cursor::new(bytes)).map_err(|e| e.to_string())?;
                let module = context
                    .device
                    .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&spirv), None)
                    .map_err(|e| error("Create atmosphere shader", e))?;
                let built = context.device.create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default()
                        .layout(result.layout)
                        .stage(
                            vk::PipelineShaderStageCreateInfo::default()
                                .stage(vk::ShaderStageFlags::COMPUTE)
                                .module(module)
                                .name(c"main"),
                        )],
                    None,
                );
                context.device.destroy_shader_module(module, None);
                match built {
                    Ok(pipelines) => result.pipelines.extend(pipelines),
                    Err((partial, e)) => {
                        for pipeline in partial {
                            context.device.destroy_pipeline(pipeline, None);
                        }
                        return Err(error("Create atmosphere compute pipeline", e));
                    }
                }
            }
        }
        Ok(result)
    }
    pub fn image(
        &self,
        group: usize,
        slot: usize,
        binding: u32,
        ty: vk::DescriptorType,
        texture: &Texture,
    ) {
        let info = [vk::DescriptorImageInfo::default()
            .sampler(self.sampler)
            .image_view(texture.image.view)
            .image_layout(vk::ImageLayout::GENERAL)];
        unsafe {
            self.context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(self.sets[group][slot])
                    .dst_binding(binding)
                    .descriptor_type(ty)
                    .image_info(&info)],
                &[],
            );
        }
    }
    pub fn buffer(&self, group: usize, slot: usize, binding: u32, buffer: &Buffer) {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.buffer)
            .range(buffer.size)];
        unsafe {
            self.context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(self.sets[group][slot])
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(&info)],
                &[],
            );
        }
    }
    pub fn dispatch(
        &self,
        command: vk::CommandBuffer,
        pipeline: usize,
        slot: usize,
        push: &[u8],
        count: [u32; 3],
    ) {
        let mut sets = [vk::DescriptorSet::null(); 4];
        for (out, group) in sets.iter_mut().zip(&self.sets) {
            *out = group[slot];
        }
        unsafe {
            self.context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                self.pipelines[pipeline],
            );
            self.context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                self.layout,
                0,
                &sets[..self.sets.len()],
                &[],
            );
            self.context.device.cmd_push_constants(
                command,
                self.layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                push,
            );
            self.context
                .device
                .cmd_dispatch(command, count[0], count[1], count[2]);
        }
    }
    pub fn acceleration(
        &self,
        group: usize,
        slot: usize,
        binding: u32,
        handle: vk::AccelerationStructureKHR,
    ) {
        let handles = [handle];
        let mut acceleration = vk::WriteDescriptorSetAccelerationStructureKHR::default()
            .acceleration_structures(&handles);
        let write = vk::WriteDescriptorSet::default()
            .dst_set(self.sets[group][slot])
            .dst_binding(binding)
            .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
            .descriptor_count(1)
            .push_next(&mut acceleration);
        unsafe {
            self.context.device.update_descriptor_sets(&[write], &[]);
        }
    }
}

pub(super) fn descriptor_layout(
    context: &Context,
    types: &[vk::DescriptorType],
) -> Result<vk::DescriptorSetLayout, String> {
    let bindings: Vec<_> = types
        .iter()
        .enumerate()
        .map(|(binding, &ty)| {
            vk::DescriptorSetLayoutBinding::default()
                .binding(binding as u32)
                .descriptor_type(ty)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
        })
        .collect();
    unsafe {
        context.device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )
    }
    .map_err(|e| error("Create atmosphere descriptor layout", e))
}
impl Drop for Compute {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            unsafe {
                for &pipeline in &self.pipelines {
                    self.context.device.destroy_pipeline(pipeline, None);
                }
                self.context.device.destroy_descriptor_pool(self.pool, None);
                self.context
                    .device
                    .destroy_pipeline_layout(self.layout, None);
                for &layout in &self.layouts {
                    self.context
                        .device
                        .destroy_descriptor_set_layout(layout, None);
                }
                self.context.device.destroy_sampler(self.sampler, None);
            }
        }
    }
}
