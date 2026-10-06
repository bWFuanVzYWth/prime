//! Small compute owner shared by display-only passes. The renderer owns its completion lifetime.
use crate::resources::{Buffer, Context, error};
use ash::vk;
use std::{collections::BTreeMap, io::Cursor, sync::Arc};

pub(crate) struct PostCompute {
    context: Arc<Context>,
    layout: vk::PipelineLayout,
    descriptor_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    sets: Vec<vk::DescriptorSet>,
    pipelines: Vec<vk::Pipeline>,
}

impl PostCompute {
    pub fn new(
        context: &Arc<Context>,
        types: &[vk::DescriptorType],
        shaders: &[&[u8]],
        push_bytes: u32,
    ) -> Result<Self, String> {
        let mut result = Self {
            context: context.clone(),
            layout: vk::PipelineLayout::null(),
            descriptor_layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            sets: vec![],
            pipelines: vec![],
        };
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
            result.descriptor_layout = context
                .device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .map_err(|e| error("Create display descriptor layout", e))?;
            let mut counts = BTreeMap::new();
            for &ty in types {
                *counts.entry(ty).or_insert(0) += crate::FRAME_SLOTS as u32;
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
                        .max_sets(crate::FRAME_SLOTS as u32)
                        .pool_sizes(&sizes),
                    None,
                )
                .map_err(|e| error("Create display descriptor pool", e))?;
            let layouts = [result.descriptor_layout; crate::FRAME_SLOTS];
            result.sets = context
                .device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(result.pool)
                        .set_layouts(&layouts),
                )
                .map_err(|e| error("Allocate display descriptors", e))?;
            let push = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
                .size(push_bytes)];
            result.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&[result.descriptor_layout])
                        .push_constant_ranges(&push),
                    None,
                )
                .map_err(|e| error("Create display pipeline layout", e))?;
            for bytes in shaders {
                let code =
                    ash::util::read_spv(&mut Cursor::new(bytes)).map_err(|e| e.to_string())?;
                let module = context
                    .device
                    .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&code), None)
                    .map_err(|e| error("Create display shader", e))?;
                let built =
                    context.create_compute_pipelines(&[vk::ComputePipelineCreateInfo::default()
                        .layout(result.layout)
                        .stage(
                            vk::PipelineShaderStageCreateInfo::default()
                                .stage(vk::ShaderStageFlags::COMPUTE)
                                .module(module)
                                .name(c"main"),
                        )]);
                context.device.destroy_shader_module(module, None);
                match built {
                    Ok(pipelines) => result.pipelines.extend(pipelines),
                    Err((partial, e)) => {
                        for pipeline in partial {
                            context.device.destroy_pipeline(pipeline, None);
                        }
                        return Err(error("Create display compute pipeline", e));
                    }
                }
            }
        }
        Ok(result)
    }

    // The caller has waited for this frame slot before replacing descriptor identities.
    pub fn image(&self, slot: usize, binding: u32, ty: vk::DescriptorType, view: vk::ImageView) {
        let info = [vk::DescriptorImageInfo::default()
            .image_view(view)
            .image_layout(vk::ImageLayout::GENERAL)];
        unsafe {
            self.context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(self.sets[slot])
                    .dst_binding(binding)
                    .descriptor_type(ty)
                    .image_info(&info)],
                &[],
            );
        }
    }

    pub fn buffer(&self, slot: usize, binding: u32, buffer: &Buffer) {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.buffer)
            .range(buffer.size)];
        unsafe {
            self.context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(self.sets[slot])
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(&info)],
                &[],
            );
        }
    }

    pub fn sampled(&self, slot: usize, binding: u32, view: vk::ImageView, sampler: vk::Sampler) {
        let info = [vk::DescriptorImageInfo::default()
            .sampler(sampler)
            .image_view(view)
            .image_layout(vk::ImageLayout::GENERAL)];
        unsafe {
            self.context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(self.sets[slot])
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(&info)],
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
                &[self.sets[slot]],
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
}

impl Drop for PostCompute {
    fn drop(&mut self) {
        // Kept for the entire renderer lifetime, which ends only after completion/cancellation.
        if self.context.can_destroy() {
            unsafe {
                for &pipeline in &self.pipelines {
                    self.context.device.destroy_pipeline(pipeline, None);
                }
                self.context.device.destroy_descriptor_pool(self.pool, None);
                self.context
                    .device
                    .destroy_pipeline_layout(self.layout, None);
                self.context
                    .device
                    .destroy_descriptor_set_layout(self.descriptor_layout, None);
            }
        }
    }
}

pub(crate) fn barrier(
    context: &Context,
    command: vk::CommandBuffer,
    source_stage: vk::PipelineStageFlags,
    source_access: vk::AccessFlags,
    destination_stage: vk::PipelineStageFlags,
    destination_access: vk::AccessFlags,
) {
    let memory = [vk::MemoryBarrier::default()
        .src_access_mask(source_access)
        .dst_access_mask(destination_access)];
    unsafe {
        context.device.cmd_pipeline_barrier(
            command,
            source_stage,
            destination_stage,
            vk::DependencyFlags::empty(),
            &memory,
            &[],
            &[],
        );
    }
}
