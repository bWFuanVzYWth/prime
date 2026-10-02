use super::fixture::Fixture;
use crate::{Buffer, Context, FRAME_SLOTS, Pipeline, error, surface::node_bytes};
use ash::vk;
use std::{io::Cursor, time::Instant};

pub const BATCH: u32 = 32;

pub struct Gpu {
    pipeline: Pipeline,
    buffers: Vec<Buffer>,
    readback: Buffer,
    query: vk::QueryPool,
    width: u32,
    height: u32,
    pages: u32,
    wall: u32,
}

impl Drop for Gpu {
    fn drop(&mut self) {
        if self.pipeline.context.can_destroy() {
            unsafe {
                self.pipeline
                    .context
                    .device
                    .destroy_query_pool(self.query, None);
            }
        }
    }
}

pub struct BatchTime {
    pub gpu_ns: Vec<u64>,
    /// Synchronous lab submission, including CPU recording, queue submission and wait.
    pub host_ns: u128,
}

impl Gpu {
    pub fn new(fixture: &Fixture, width: u32, height: u32) -> Result<Self, String> {
        if width < 8 || height < 4 || !width.is_multiple_of(8) || !height.is_multiple_of(4) {
            return Err("Extent must be a positive multiple of the 8x4 receiver grid".into());
        }
        let context = Context::new()?;
        context.render_extent(width, height)?;
        if context.timestamp_bits == 0 {
            return Err("GPU timestamps unavailable".into());
        }
        let usage =
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS;
        let mut local = Vec::new();
        let mut aliases = Vec::new();
        for a in &fixture.aliases {
            aliases.extend(a.cut.to_le_bytes());
            aliases.extend(a.other.to_le_bytes());
        }
        let mut pages = Vec::new();
        for p in &fixture.pages {
            for v in [
                (local.len() / 32) as u32,
                p.first as u32,
                p.count as u32,
                (aliases.len() / 8) as u32,
            ] {
                pages.extend(v.to_le_bytes());
            }
            local.extend(node_bytes(&p.nodes));
            for a in &p.aliases {
                aliases.extend(a.cut.to_le_bytes());
                aliases.extend(a.other.to_le_bytes());
            }
        }
        let mut emitters = Vec::new();
        for e in &fixture.emitters {
            for v in [
                e.position[0],
                e.position[1],
                e.position[2],
                e.half_size,
                e.radiance,
                e.power,
                0.0,
                0.0,
            ] {
                emitters.extend(v.to_le_bytes());
            }
        }
        let mut buffers = Vec::new();
        for bytes in [node_bytes(&fixture.world), local, aliases, pages, emitters] {
            buffers.push(Buffer::upload_device(&context, &bytes, usage)?);
        }
        let bytes = u64::from(width) * u64::from(height) * 8;
        buffers.push(Buffer::new(
            &context,
            bytes,
            usage | vk::BufferUsageFlags::TRANSFER_SRC,
            false,
        )?);
        let readback = Buffer::new_readback(&context, bytes)?;
        // Reuse the existing RAII owner; this private entry uses only BDA push constants.
        let mut pipeline = Pipeline {
            context: context.clone(),
            layout: vk::PipelineLayout::null(),
            descriptor_layout: vk::DescriptorSetLayout::null(),
            environment_layout: vk::DescriptorSetLayout::null(),
            pool: vk::DescriptorPool::null(),
            descriptors: [vk::DescriptorSet::null(); FRAME_SLOTS],
            pipelines: [vk::Pipeline::null(); 6],
            single_sample_pipelines: None,
            primary_pipelines: None,
            realtime_post: None,
            reconstruction_display: None,
        };
        unsafe {
            let ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
                .size(80)];
            pipeline.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default().push_constant_ranges(&ranges),
                    None,
                )
                .map_err(|e| error("Create sampling experiment layout", e))?;
            let code = ash::util::read_spv(&mut Cursor::new(include_bytes!(concat!(
                env!("OUT_DIR"),
                "/light_sampling.spv"
            ))))
            .map_err(|e| e.to_string())?;
            let shader = context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&code), None)
                .map_err(|e| error("Create sampling experiment shader", e))?;
            let build = (|| {
                for method in 0..3u32 {
                    for sequence in 0..2u32 {
                        let entries = [
                            vk::SpecializationMapEntry {
                                constant_id: 0,
                                offset: 0,
                                size: 4,
                            },
                            vk::SpecializationMapEntry {
                                constant_id: 1,
                                offset: 4,
                                size: 4,
                            },
                        ];
                        let data: Vec<_> = [method, sequence]
                            .into_iter()
                            .flat_map(u32::to_le_bytes)
                            .collect();
                        let spec = vk::SpecializationInfo::default()
                            .map_entries(&entries)
                            .data(&data);
                        let info = [vk::ComputePipelineCreateInfo::default()
                            .layout(pipeline.layout)
                            .stage(
                                vk::PipelineShaderStageCreateInfo::default()
                                    .stage(vk::ShaderStageFlags::COMPUTE)
                                    .module(shader)
                                    .name(c"main")
                                    .specialization_info(&spec),
                            )];
                        match context.device.create_compute_pipelines(
                            vk::PipelineCache::null(),
                            &info,
                            None,
                        ) {
                            Ok(values) => {
                                pipeline.pipelines[(method * 2 + sequence) as usize] = values[0]
                            }
                            Err((partial, e)) => {
                                for value in partial {
                                    context.device.destroy_pipeline(value, None);
                                }
                                return Err(error("Create sampling experiment pipeline", e));
                            }
                        }
                    }
                }
                Ok(())
            })();
            context.device.destroy_shader_module(shader, None);
            build?;
        }
        let query = unsafe {
            context.device.create_query_pool(
                &vk::QueryPoolCreateInfo::default()
                    .query_type(vk::QueryType::TIMESTAMP)
                    .query_count(BATCH * 2),
                None,
            )
        }
        .map_err(|e| error("Create sampling timestamps", e))?;
        Ok(Self {
            pipeline,
            buffers,
            readback,
            query,
            width,
            height,
            pages: fixture.pages.len() as u32,
            wall: u32::from(fixture.wall),
        })
    }

    pub fn device_name(&self) -> &str {
        &self.pipeline.context.name
    }

    /// No uploads/readback/scene builds inside these timestamp intervals. Each dispatch
    /// is one sample per pixel; methods have the same estimator and accumulation work.
    pub fn run(
        &self,
        method: usize,
        sequence: usize,
        seed: u32,
        first: u32,
        count: u32,
        inspect: bool,
    ) -> Result<BatchTime, String> {
        if method >= 3
            || sequence >= 2
            || count == 0
            || count > BATCH
            || first.checked_add(count).is_none_or(|v| v > 1 << 20)
        {
            return Err("Invalid sampling batch".into());
        }
        let context = &self.pipeline.context;
        let mut push: Vec<u8> = self
            .buffers
            .iter()
            .flat_map(|b| b.address().to_le_bytes())
            .collect();
        for v in [
            self.width,
            self.height,
            first,
            seed,
            self.pages,
            self.wall,
            self.width
                .max(self.height)
                .next_power_of_two()
                .trailing_zeros(),
            u32::from(inspect),
        ] {
            push.extend(v.to_le_bytes());
        }
        let start = Instant::now();
        context.submit_named("light_sampling", |command| unsafe {
            context
                .device
                .cmd_reset_query_pool(command, self.query, 0, count * 2);
            context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                self.pipeline.pipelines[method * 2 + sequence],
            );
            for i in 0..count {
                // This includes the dependency from the previous batch on the same queue.
                context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(
                            vk::AccessFlags::MEMORY_WRITE | vk::AccessFlags::TRANSFER_READ,
                        )
                        .dst_access_mask(
                            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                        )],
                    &[],
                    &[],
                );
                push[56..60].copy_from_slice(&(first + i).to_le_bytes());
                context.device.cmd_push_constants(
                    command,
                    self.pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    &push,
                );
                context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    self.query,
                    i * 2,
                );
                context.device.cmd_dispatch(
                    command,
                    self.width.div_ceil(8),
                    self.height.div_ceil(8),
                    1,
                );
                context.device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    self.query,
                    i * 2 + 1,
                );
            }
        })?;
        let host_ns = start.elapsed().as_nanos();
        let mut stamps = vec![0u64; count as usize * 2];
        unsafe {
            context.device.get_query_pool_results(
                self.query,
                0,
                &mut stamps,
                vk::QueryResultFlags::TYPE_64,
            )
        }
        .map_err(|e| error("Read sampling timestamps", e))?;
        let mask = u64::MAX
            .checked_shr(64 - context.timestamp_bits)
            .unwrap_or(0);
        let gpu_ns = stamps
            .as_chunks::<2>()
            .0
            .iter()
            .map(|v| {
                ((v[1].wrapping_sub(v[0]) & mask) as f64 * f64::from(context.timestamp_period))
                    as u64
            })
            .collect();
        Ok(BatchTime { gpu_ns, host_ns })
    }

    pub fn read(&self, inspect: bool) -> Result<Vec<f32>, String> {
        let context = &self.pipeline.context;
        let bytes = u64::from(self.width) * u64::from(self.height) * if inspect { 8 } else { 4 };
        context.submit_named("light_sampling_readback", |command| unsafe {
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
                &[],
                &[],
            );
            context.device.cmd_copy_buffer(
                command,
                self.buffers[5].buffer,
                self.readback.buffer,
                &[vk::BufferCopy::default().size(bytes)],
            );
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::HOST_READ)],
                &[],
                &[],
            );
        })?;
        Ok(self
            .readback
            .read(bytes as usize)?
            .as_chunks::<4>()
            .0
            .iter()
            .map(|v| f32::from_le_bytes(*v))
            .collect())
    }
}
