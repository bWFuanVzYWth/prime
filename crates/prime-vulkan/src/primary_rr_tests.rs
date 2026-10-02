//! Actual production RR guide images, including FP16 storage and all-pixel initialization.
//! No NGX/Streamline runtime is needed: the only substituted shader service is sky radiance.
use super::*;
use prime_scene::{
    TextureMaterial,
    settings::DiagnosticView,
    surface::{Medium, Optics, SurfaceFace},
    workers::CpuWorkers,
};

const CHANNELS: [(u32, vk::Format, usize); 8] = [
    (11, vk::Format::R32_SFLOAT, 4),
    (12, vk::Format::R16G16_SFLOAT, 4),
    (13, vk::Format::R16G16B16A16_SFLOAT, 8),
    (14, vk::Format::R16G16B16A16_SFLOAT, 8),
    (15, vk::Format::R16G16B16A16_SFLOAT, 8),
    (16, vk::Format::R16_SFLOAT, 2),
    (19, vk::Format::R8_UNORM, 1),
    (20, vk::Format::R16G16_SFLOAT, 4),
];

struct Fixture {
    context: Arc<Context>,
    pipeline: Pipeline,
    _geometry: Geometry,
    images: Vec<Image>,
    constants: Buffer,
    reports: Buffer,
    scratch: realtime::Scratch,
    readback: Buffer,
    offsets: Vec<usize>,
    byte_count: usize,
    extent: [u32; 2],
    history_valid: bool,
}

#[derive(Debug)]
struct Snapshot(Vec<Vec<u8>>, Vec<[u32; 4]>);

fn half(value: u16) -> f32 {
    let sign = if value & 0x8000 == 0 { 1.0 } else { -1.0 };
    let exponent = (value >> 10) & 31;
    let mantissa = value & 1023;
    sign * match exponent {
        0 => f32::from(mantissa) * 2.0f32.powi(-24),
        31 if mantissa == 0 => f32::INFINITY,
        31 => f32::NAN,
        _ => (1.0 + f32::from(mantissa) / 1024.0) * 2.0f32.powi(i32::from(exponent) - 15),
    }
}

impl Snapshot {
    fn channel(&self, index: usize) -> Vec<f32> {
        match CHANNELS[index].1 {
            vk::Format::R32_SFLOAT => self.0[index]
                .as_chunks::<4>()
                .0
                .iter()
                .map(|v| f32::from_le_bytes(*v))
                .collect(),
            vk::Format::R8_UNORM => self.0[index]
                .iter()
                .map(|v| f32::from(*v) / 255.0)
                .collect(),
            _ => self.0[index]
                .as_chunks::<2>()
                .0
                .iter()
                .map(|v| half(u16::from_le_bytes(*v)))
                .collect(),
        }
    }

    fn resolved(&self) {
        assert!(self.0[6].iter().all(|v| v & 3 == 0), "complete guide mask");
        for index in [1, 7] {
            assert!(
                self.channel(index)
                    .iter()
                    .all(|v| v.is_finite() && v.abs() < 64.0),
                "finite pixel motion: {index}"
            );
        }
        for index in [2, 3, 4] {
            assert!(
                self.channel(index)
                    .iter()
                    .all(|v| v.is_finite() && v.abs() <= 1.0),
                "finite material guide: {index}"
            );
        }
        assert!(
            self.channel(0)
                .iter()
                .all(|v| v.is_finite() && *v > 0.0 && *v < 64.0)
        );
        assert!(
            self.channel(5)
                .iter()
                .all(|v| v.is_finite() && *v >= 0.0 && *v < 64.0)
        );
    }

    fn static_motion(&self) {
        for index in [1, 7] {
            for v in self.channel(index) {
                assert!(v.abs() <= 2e-5, "static motion channel {index}: {v}");
            }
        }
    }

    fn planar_motion(
        &self,
        camera: Camera,
        previous: Camera,
        extent: [u32; 2],
        depths: [f32; 2],
        distances: [f32; 2],
    ) {
        let dot = |a: [f32; 3], b: [f32; 3]| a.into_iter().zip(b).map(|(x, y)| x * y).sum::<f32>();
        let tangent = (camera.vertical_fov_radians * 0.5).tan();
        let previous_tangent = (previous.vertical_fov_radians * 0.5).tan();
        let aspect = extent[0] as f32 / extent[1] as f32;
        for ((channel, depth), distance) in [1, 7].into_iter().zip(depths).zip(distances) {
            let values = self.channel(channel);
            for (index, actual) in values.as_chunks::<2>().0.iter().enumerate() {
                let uv = [
                    (index as u32 % extent[0]) as f32 + 0.5,
                    (index as u32 / extent[0]) as f32 + 0.5,
                ];
                let uv = [uv[0] / extent[0] as f32, uv[1] / extent[1] as f32];
                // Closed-form camera ray / fixed unfolded target-plane intersection.
                let ray: [f32; 3] = std::array::from_fn(|i| {
                    camera.forward[i] + camera.right[i] * (uv[0] * 2.0 - 1.0) * tangent * aspect
                        - camera.up[i] * (uv[1] * 2.0 - 1.0) * tangent
                });
                let depth = depth + distance / dot(ray, ray).sqrt();
                let relative = std::array::from_fn(|i| {
                    camera.position[i] - previous.position[i]
                        + depth
                            * (camera.forward[i]
                                + camera.right[i] * (uv[0] * 2.0 - 1.0) * tangent * aspect
                                - camera.up[i] * (uv[1] * 2.0 - 1.0) * tangent)
                });
                let z = dot(relative, previous.forward);
                let projected = [
                    dot(relative, previous.right) / (z * previous_tangent * aspect),
                    -dot(relative, previous.up) / (z * previous_tangent),
                ];
                for component in 0..2 {
                    let expected = (0.5 * (projected[component] + 1.0) - uv[component])
                        * extent[component] as f32;
                    assert!(
                        (actual[component] - expected).abs() < 0.002,
                        "binding {}, pixel {index}, component {component}: {} != {expected}",
                        CHANNELS[channel].0,
                        actual[component]
                    );
                }
            }
        }
    }
}

impl Fixture {
    fn new(context: &Arc<Context>, scene: &Scene, extent: [u32; 2]) -> Self {
        Self::with_instances(
            context,
            scene,
            extent,
            &InstanceScene {
                epoch: scene.epoch,
                ..Default::default()
            },
        )
    }

    fn with_instances(
        context: &Arc<Context>,
        scene: &Scene,
        extent: [u32; 2],
        instances: &InstanceScene,
    ) -> Self {
        let mut geometry =
            Geometry::new(context, scene.into(), Arc::new(CpuWorkers::new(1).unwrap())).unwrap();
        geometry
            .prepare_dynamic(
                context,
                scene,
                instances,
                0,
                &mut cpu_profile::FrameCpu::default(),
            )
            .unwrap();
        let images: Vec<_> = CHANNELS
            .iter()
            .map(|(_, format, _)| {
                Image::with_format(context, extent[0], extent[1], *format).unwrap()
            })
            .collect();
        let constants =
            Buffer::new(context, 144, vk::BufferUsageFlags::UNIFORM_BUFFER, true).unwrap();
        let scratch = realtime::Scratch::new(context, extent, true).unwrap();
        let pixels = (extent[0] * extent[1]) as usize;
        let reports = Buffer::new(
            context,
            (pixels * 16) as u64,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            true,
        )
        .unwrap();
        let mut byte_count = 0usize;
        let offsets = CHANNELS
            .iter()
            .map(|(_, _, size)| {
                byte_count = byte_count.next_multiple_of(4);
                let offset = byte_count;
                byte_count += pixels * size;
                offset
            })
            .collect();
        let readback = Buffer::new_readback(context, byte_count as u64).unwrap();
        let mut pipeline = Pipeline {
            energy_lut: Some(openpbr::EnergyLut::new(context).unwrap()),
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
        pipeline.energy_lut.as_mut().unwrap().prepare().unwrap();
        unsafe {
            let mut bindings: Vec<_> = [
                (0, vk::DescriptorType::ACCELERATION_STRUCTURE_KHR),
                (1, vk::DescriptorType::STORAGE_BUFFER),
                (2, vk::DescriptorType::STORAGE_BUFFER),
                (3, vk::DescriptorType::STORAGE_BUFFER),
                (7, vk::DescriptorType::STORAGE_BUFFER),
                (8, vk::DescriptorType::STORAGE_BUFFER),
                (9, vk::DescriptorType::COMBINED_IMAGE_SAMPLER),
                (17, vk::DescriptorType::UNIFORM_BUFFER),
            ]
            .into_iter()
            .map(|(binding, ty)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(binding)
                    .descriptor_type(ty)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            })
            .collect();
            bindings.extend(CHANNELS.iter().map(|(binding, _, _)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(*binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                    .descriptor_count(1)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            }));
            pipeline.descriptor_layout = context
                .device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .unwrap();
            let layouts = [pipeline.descriptor_layout];
            pipeline.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&[vk::PushConstantRange::default()
                            .stage_flags(vk::ShaderStageFlags::COMPUTE)
                            .size(128)]),
                    None,
                )
                .unwrap();
            let sizes = [
                (vk::DescriptorType::ACCELERATION_STRUCTURE_KHR, 1),
                (vk::DescriptorType::STORAGE_BUFFER, 5),
                (vk::DescriptorType::COMBINED_IMAGE_SAMPLER, 1),
                (vk::DescriptorType::UNIFORM_BUFFER, 1),
                (vk::DescriptorType::STORAGE_IMAGE, CHANNELS.len() as u32),
            ]
            .map(|(ty, descriptor_count)| vk::DescriptorPoolSize {
                ty,
                descriptor_count,
            });
            pipeline.pool = context
                .device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(1)
                        .pool_sizes(&sizes),
                    None,
                )
                .unwrap();
            let set = context
                .device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(pipeline.pool)
                        .set_layouts(&layouts),
                )
                .unwrap()[0];
            pipeline.descriptors[0] = set;
            for (binding, buffer, ty) in [
                (1, &reports, vk::DescriptorType::STORAGE_BUFFER),
                (
                    2,
                    &geometry.textures.metadata,
                    vk::DescriptorType::STORAGE_BUFFER,
                ),
                (
                    3,
                    &geometry.textures.texels,
                    vk::DescriptorType::STORAGE_BUFFER,
                ),
                (
                    7,
                    &geometry.objects.metadata,
                    vk::DescriptorType::STORAGE_BUFFER,
                ),
                (
                    8,
                    &geometry.static_bases,
                    vk::DescriptorType::STORAGE_BUFFER,
                ),
                (17, &constants, vk::DescriptorType::UNIFORM_BUFFER),
            ] {
                context.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(set)
                        .dst_binding(binding)
                        .descriptor_type(ty)
                        .buffer_info(&[vk::DescriptorBufferInfo::default()
                            .buffer(buffer.buffer)
                            .range(buffer.size)])],
                    &[],
                );
            }
            let handles = [geometry.top.handle()];
            let mut acceleration = vk::WriteDescriptorSetAccelerationStructureKHR::default()
                .acceleration_structures(&handles);
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(0)
                    .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                    .descriptor_count(1)
                    .push_next(&mut acceleration)],
                &[],
            );
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(9)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                    .image_info(&[pipeline.energy_lut.as_ref().unwrap().descriptor()])],
                &[],
            );
            for ((binding, _, _), image) in CHANNELS.iter().zip(&images) {
                context.device.update_descriptor_sets(
                    &[vk::WriteDescriptorSet::default()
                        .dst_set(set)
                        .dst_binding(*binding)
                        .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                        .image_info(&[vk::DescriptorImageInfo::default()
                            .image_view(image.view)
                            .image_layout(vk::ImageLayout::GENERAL)])],
                    &[],
                );
            }
            let words = ash::util::read_spv(&mut Cursor::new(include_bytes!(concat!(
                env!("OUT_DIR"),
                "/primary_rr.spv"
            ))))
            .unwrap();
            let shader = context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .unwrap();
            let built = context.device.create_compute_pipelines(
                vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default()
                    .layout(pipeline.layout)
                    .stage(
                        vk::PipelineShaderStageCreateInfo::default()
                            .stage(vk::ShaderStageFlags::COMPUTE)
                            .module(shader)
                            .name(c"main"),
                    )],
                None,
            );
            context.device.destroy_shader_module(shader, None);
            pipeline.pipelines[0] = built.unwrap()[0];
        }
        Self {
            context: context.clone(),
            pipeline,
            _geometry: geometry,
            images,
            constants,
            reports,
            scratch,
            readback,
            offsets,
            byte_count,
            extent,
            history_valid: true,
        }
    }

    fn run(
        &self,
        camera: Camera,
        previous: Camera,
        jitter: [f32; 2],
        seed: u32,
        budget: u32,
    ) -> Snapshot {
        self.run_with_distance(camera, previous, jitter, seed, budget, None)
    }

    fn replace_shader(&mut self, code: &[u8]) {
        let words = ash::util::read_spv(&mut Cursor::new(code)).expect("valid benchmark SPIR-V");
        unsafe {
            let shader = self
                .context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .unwrap();
            let result = self.context.device.create_compute_pipelines(
                vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default()
                    .layout(self.pipeline.layout)
                    .stage(
                        vk::PipelineShaderStageCreateInfo::default()
                            .stage(vk::ShaderStageFlags::COMPUTE)
                            .module(shader)
                            .name(c"main"),
                    )],
                None,
            );
            self.context.device.destroy_shader_module(shader, None);
            let pipeline = result.unwrap()[0];
            self.context
                .device
                .destroy_pipeline(self.pipeline.pipelines[0], None);
            self.pipeline.pipelines[0] = pipeline;
        }
    }

    fn time_dispatch(&self, queries: vk::QueryPool, push: &[u8]) -> u64 {
        self.context
            .submit_named("rr_k1_timestamp", |command| unsafe {
                let device = &self.context.device;
                device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(
                            vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
                        )
                        .dst_access_mask(
                            vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                        )],
                    &[],
                    &[],
                );
                device.cmd_reset_query_pool(command, queries, 0, 2);
                device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.pipeline.pipelines[0],
                );
                device.cmd_bind_descriptor_sets(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.pipeline.layout,
                    0,
                    &[self.pipeline.descriptors[0]],
                    &[],
                );
                device.cmd_push_constants(
                    command,
                    self.pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    push,
                );
                device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::TOP_OF_PIPE,
                    queries,
                    0,
                );
                device.cmd_dispatch(
                    command,
                    self.extent[0].div_ceil(8),
                    self.extent[1].div_ceil(8),
                    1,
                );
                device.cmd_write_timestamp(
                    command,
                    vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                    queries,
                    1,
                );
            })
            .unwrap();
        let mut stamps = [0u64; 2];
        unsafe {
            self.context
                .device
                .get_query_pool_results(queries, 0, &mut stamps, vk::QueryResultFlags::TYPE_64)
                .unwrap();
        }
        let bits = self.context.timestamp_bits;
        let mask = if bits == 64 {
            u64::MAX
        } else {
            (1u64 << bits) - 1
        };
        ((stamps[1].wrapping_sub(stamps[0]) & mask) as f64
            * f64::from(self.context.timestamp_period)) as u64
    }

    #[allow(clippy::too_many_arguments)]
    fn run_with_distance(
        &self,
        camera: Camera,
        previous: Camera,
        jitter: [f32; 2],
        seed: u32,
        budget: u32,
        distance: Option<f32>,
    ) -> Snapshot {
        let aspect = self.extent[0] as f32 / self.extent[1] as f32;
        self.constants
            .write(&reconstruction_history::camera_constants(
                camera,
                previous,
                aspect,
                jitter,
                self.history_valid,
            ))
            .unwrap();
        let mut push = realtime::PushInputs {
            camera,
            input: self.extent,
            output: self.extent,
            sequence: seed,
            sobol_r: 8,
            settings: RenderSettings {
                seed: seed.wrapping_mul(0x9e3779b9),
                bounces: budget,
                view: DiagnosticView::Output,
                ..Default::default()
            },
            display: PrimeDrtSettings::default().prepare(1.0).unwrap(),
            addresses: self.scratch.addresses(),
            jitter: Some(jitter),
            bottom_up: false,
        }
        .primary();
        if let Some(distance) = distance {
            push[92..96].copy_from_slice(&distance.to_le_bytes());
        }
        self.context
            .submit_named("primary_rr_guides_fixture", |command| unsafe {
                let device = &self.context.device;
                let barrier = |source, target, reads, writes| {
                    device.cmd_pipeline_barrier(
                        command,
                        source,
                        target,
                        vk::DependencyFlags::empty(),
                        &[vk::MemoryBarrier::default()
                            .src_access_mask(reads)
                            .dst_access_mask(writes)],
                        &[],
                        &[],
                    );
                };
                barrier(
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
                    vk::AccessFlags::TRANSFER_WRITE,
                );
                // Each run gets a different poison, so stale/unwritten channels cannot compare equal.
                let poison = vk::ClearColorValue {
                    float32: [1000.0 + seed as f32; 4],
                };
                for image in &self.images {
                    device.cmd_clear_color_image(
                        command,
                        image.image,
                        vk::ImageLayout::GENERAL,
                        &poison,
                        &[target::color_range()],
                    );
                }
                barrier(
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::AccessFlags::TRANSFER_WRITE,
                    vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                );
                device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.pipeline.pipelines[0],
                );
                device.cmd_bind_descriptor_sets(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.pipeline.layout,
                    0,
                    &[self.pipeline.descriptors[0]],
                    &[],
                );
                device.cmd_push_constants(
                    command,
                    self.pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    &push,
                );
                device.cmd_dispatch(
                    command,
                    self.extent[0].div_ceil(8),
                    self.extent[1].div_ceil(8),
                    1,
                );
                barrier(
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::SHADER_WRITE,
                    vk::AccessFlags::TRANSFER_READ,
                );
                for (image, offset) in self.images.iter().zip(&self.offsets) {
                    device.cmd_copy_image_to_buffer(
                        command,
                        image.image,
                        vk::ImageLayout::GENERAL,
                        self.readback.buffer,
                        &[vk::BufferImageCopy::default()
                            .buffer_offset(*offset as u64)
                            .image_subresource(
                                vk::ImageSubresourceLayers::default()
                                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                                    .layer_count(1),
                            )
                            .image_extent(vk::Extent3D {
                                width: self.extent[0],
                                height: self.extent[1],
                                depth: 1,
                            })],
                    );
                }
                barrier(
                    vk::PipelineStageFlags::TRANSFER | vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::HOST,
                    vk::AccessFlags::TRANSFER_WRITE | vk::AccessFlags::SHADER_WRITE,
                    vk::AccessFlags::HOST_READ,
                );
            })
            .unwrap();
        let bytes = self.readback.read(self.byte_count).unwrap();
        let pixels = (self.extent[0] * self.extent[1]) as usize;
        Snapshot(
            CHANNELS
                .iter()
                .zip(&self.offsets)
                .map(|((_, _, size), offset)| bytes[*offset..*offset + pixels * size].to_vec())
                .collect(),
            self.reports
                .read(pixels * 16)
                .unwrap()
                .as_chunks::<16>()
                .0
                .iter()
                .map(|bytes| {
                    std::array::from_fn(|i| {
                        u32::from_le_bytes(bytes[i * 4..i * 4 + 4].try_into().unwrap())
                    })
                })
                .collect(),
        )
    }
}

fn camera() -> Camera {
    Camera {
        position: [8.0, 8.0, 0.0],
        forward: [0.0, 0.0, 1.0],
        right: [1.0, 0.0, 0.0],
        up: [0.0, 1.0, 0.0],
        vertical_fov_radians: 0.35,
    }
}

fn plane(z: f32, outward_positive: bool) -> SurfaceFace {
    let mut face = realtime_tests::face(z);
    if !outward_positive {
        face.geometry.positions.reverse();
    }
    face
}

fn boundary(z: f32, outward_positive: bool, medium: Medium, thin: bool) -> SurfaceFace {
    let mut face = plane(z, outward_positive);
    face.media = [7, 0];
    face.optics = Some(Optics {
        negative: medium,
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin,
    });
    face
}

fn water(extinction: f32) -> Scene {
    let medium = Medium {
        ior: 1.333,
        extinction: [extinction; 3],
    };
    realtime_tests::scene(
        1,
        vec![
            boundary(1.0, false, medium, false),
            boundary(2.0, true, medium, false),
            plane(3.0, false),
            plane(-2.0, true),
        ],
    )
}

fn mirror_chain() -> Scene {
    let mut mirror = plane(2.0, false);
    mirror.geometry.texture_id = 7;
    let glass = Medium {
        ior: 1.5,
        extinction: [0.0; 3],
    };
    let mut scene = realtime_tests::scene(
        2,
        vec![boundary(1.0, false, glass, true), mirror, plane(-2.0, true)],
    );
    let mut texture = realtime_tests::texture(1, 1, vec![255; 4]);
    texture.material = Some(Arc::new(TextureMaterial {
        specular: Some(realtime_tests::texture(1, 1, vec![255, 231, 0, 255])),
        ..Default::default()
    }));
    scene.textures.insert(7, texture);
    scene
}

fn roulette_layers() -> Scene {
    let medium = Medium {
        ior: 1.5,
        extinction: [50.0; 3],
    };
    let mut faces: Vec<_> = (1..=3)
        .map(|z| boundary(z as f32, false, medium, true))
        .collect();
    faces.extend([plane(4.0, false), plane(-2.0, true)]);
    realtime_tests::scene(4, faces)
}

fn slow_reflection() -> Scene {
    let glass = Medium {
        ior: 1.5,
        extinction: [0.0; 3],
    };
    let mut mirror = plane(-1.0, true);
    mirror.geometry.texture_id = 7;
    let mut scene = realtime_tests::scene(
        5,
        vec![boundary(1.0, false, glass, true), plane(2.0, false), mirror],
    );
    let mut texture = realtime_tests::texture(1, 1, vec![255; 4]);
    texture.material = Some(Arc::new(TextureMaterial {
        specular: Some(realtime_tests::texture(1, 1, vec![255, 231, 0, 255])),
        ..Default::default()
    }));
    scene.textures.insert(7, texture);
    scene
}

#[test]
#[ignore = "windowless production K1 RR images; seed/roulette independence, odd edges and query budget"]
fn gpu_primary_rr_images_are_stable_across_lighting_seeds_and_roulette() {
    let context = Context::new().unwrap();
    for (name, scene, minimum_budget) in [
        ("water", water(0.0), 3),
        ("absorbing_water", water(1e6), 3),
        ("glass_mirror_chain", mirror_chain(), 4),
        ("roulette_glass_layers", roulette_layers(), 4),
    ] {
        let fixture = Fixture::new(&context, &scene, [17, 9]);
        let expected = fixture.run(camera(), camera(), [0.125, -0.25], 0, 12);
        expected.resolved();
        expected.static_motion();
        assert!(
            expected.0[6].iter().all(|v| v & 4 != 0),
            "optical interface has explicit reflection motion"
        );
        assert!(
            expected.channel(5).iter().all(|v| *v == 0.0),
            "explicit reflection is represented by motion; its unused hit-distance stays zero"
        );
        let mut primary_transmission = 0;
        let mut primary_reflection = 0;
        let mut lighting_active = 0;
        let mut lighting_inactive = 0;
        for seed in 1..64 {
            let actual = fixture.run(camera(), camera(), [0.125, -0.25], seed, 12);
            for report in &actual.1 {
                if f32::from_bits(report[0]) < 0.5 {
                    primary_transmission += 1;
                } else {
                    primary_reflection += 1;
                }
                if report[1] & (1 << 8) != 0 {
                    lighting_active += 1;
                } else {
                    lighting_inactive += 1;
                }
            }
            for (index, (binding, _, _)) in CHANNELS.iter().enumerate() {
                assert_eq!(
                    actual.0[index], expected.0[index],
                    "{name}, seed={seed}, binding={}",
                    binding
                );
            }
        }
        assert!(
            primary_transmission > 128 && primary_reflection > 128,
            "both selected lighting branches exercise shared and cold guide replay"
        );
        if name == "absorbing_water" || name == "roulette_glass_layers" {
            assert!(
                lighting_active > 128 && lighting_inactive > 128,
                "guide outputs survive real lighting termination: {name}"
            );
        }
        let exact = fixture.run(camera(), camera(), [0.125, -0.25], 69, minimum_budget);
        for (index, (binding, _, _)) in CHANNELS.iter().enumerate() {
            assert_eq!(
                exact.0[index], expected.0[index],
                "exact query budget, {name}, binding {}",
                binding
            );
        }
        let exhausted = fixture.run(camera(), camera(), [0.0; 2], 70, minimum_budget - 1);
        assert!(
            exhausted.0[6].iter().all(|v| v & 3 != 0),
            "unresolved delta boundary must be marked"
        );
        for index in [1, 7] {
            assert!(
                exhausted
                    .channel(index)
                    .iter()
                    .all(|v| v.is_finite() && v.abs() < 2.0),
                "invalid sentinel cannot reach NGX"
            );
        }
    }
}

#[test]
#[ignore = "windowless production RR image motion; camera translation, rotation and Halton independence"]
fn gpu_primary_rr_motion_handles_jitter_and_camera_movement() {
    let context = Context::new().unwrap();
    let scene = water(0.0);
    let fixture = Fixture::new(&context, &scene, [17, 9]);
    let current = camera();
    for sequence in 0..64 {
        let jitter = reconstruction_history::jitter(sequence, 17, 34);
        let actual = fixture.run(current, current, jitter, sequence, 12);
        actual.resolved();
        actual.static_motion();
    }
    let mut previous = current;
    previous.position[0] -= 0.1;
    let moved = fixture.run(current, previous, [0.0; 2], 80, 12);
    moved.resolved();
    moved.planar_motion(current, previous, [17, 9], [3.0, 4.0], [0.0; 2]);
    for index in [1, 7] {
        assert!(
            moved
                .channel(index)
                .as_chunks::<2>()
                .0
                .iter()
                .all(|mv| mv[0] > 0.0 && mv[1].abs() < 2e-5),
            "camera translation must produce finite rightward previous UV: binding {}",
            CHANNELS[index].0
        );
    }
    let angle = 0.03f32;
    previous = current;
    previous.forward = [angle.sin(), 0.0, angle.cos()];
    previous.right = [angle.cos(), 0.0, -angle.sin()];
    let rotated = fixture.run(current, previous, [0.0; 2], 81, 12);
    rotated.resolved();
    rotated.planar_motion(current, previous, [17, 9], [3.0, 4.0], [0.0; 2]);
    for index in [1, 7] {
        assert!(
            rotated
                .channel(index)
                .as_chunks::<2>()
                .0
                .iter()
                .all(|mv| mv[0] < 0.0),
            "previous camera rotation must move UV left: binding {}",
            CHANNELS[index].0
        );
    }
}

#[test]
#[ignore = "windowless production reflection post helper; explicit synthetic K2 distance input"]
fn gpu_primary_rr_post_completes_rough_pixel_reflection_motion() {
    let context = Context::new().unwrap();
    let scene = realtime_tests::scene(3, vec![plane(3.0, false)]);
    let fixture = Fixture::new(&context, &scene, [17, 9]);
    let current = camera();
    let mut previous = current;
    previous.position[0] -= 0.1;
    // The geometry/primary guide writer is real. Only the subsequent K2 hit distance is supplied
    // synthetically so this test isolates the exact helper shared with the production post pass.
    for distance in [0.0, 2.0] {
        let actual = fixture.run_with_distance(current, previous, [0.0; 2], 90, 1, Some(distance));
        actual.resolved();
        assert!(
            actual.0[6].iter().all(|status| *status == 0),
            "ordinary surface does not claim a K1 explicit reflection"
        );
        actual.planar_motion(current, previous, [17, 9], [3.0; 2], [0.0, distance]);
    }
    let invalid = fixture.run_with_distance(current, previous, [0.0; 2], 91, 1, Some(-1.0));
    assert!(
        invalid.0[6].iter().all(|status| *status == 2),
        "invalid distance only invalidates reflection completion"
    );
    assert!(
        invalid.channel(7).iter().all(|motion| *motion == 0.0),
        "finite fallback rather than unsupported invalid-MV marker"
    );
}

#[test]
#[ignore = "windowless production RR mask: symmetric partial completion, TIR, reset and unknown dynamic motion"]
fn gpu_primary_rr_partial_completion_tir_reset_and_dynamic_contracts() {
    let context = Context::new().unwrap();
    for (name, scene, expected_status) in [
        ("transmission_pending_reflection_done", water(0.0), 5u8),
        (
            "transmission_done_reflection_pending",
            slow_reflection(),
            6u8,
        ),
    ] {
        let fixture = Fixture::new(&context, &scene, [17, 9]);
        let baseline = fixture.run(camera(), camera(), [0.125, -0.25], 0, 2);
        let mut selections = [0usize; 2];
        for seed in 0..32 {
            let actual = fixture.run(camera(), camera(), [0.125, -0.25], seed, 2);
            assert!(
                actual.0[6].iter().all(|status| *status == expected_status),
                "{name}: {:?}",
                actual.0[6]
            );
            for report in &actual.1 {
                selections[usize::from(f32::from_bits(report[0]) >= 0.5)] += 1;
            }
            for (index, (binding, _, _)) in CHANNELS.iter().enumerate() {
                assert_eq!(
                    actual.0[index], baseline.0[index],
                    "partial completion must not depend on selected lighting branch: {name}, binding {binding}"
                );
            }
            for index in [1, 7] {
                assert!(
                    actual
                        .channel(index)
                        .iter()
                        .all(|motion| motion.is_finite() && motion.abs() <= 2e-5)
                );
            }
        }
        assert!(selections.into_iter().all(|count| count > 128));
    }

    // Back face of a thick high-IOR volume, beyond its critical angle. The only geometric
    // branch is reflection; no complementary reflection guide should be manufactured.
    let medium = Medium {
        ior: 1.5,
        extinction: [0.0; 3],
    };
    let scene = realtime_tests::scene(
        6,
        vec![boundary(1.0, true, medium, false), plane(-2.0, true)],
    );
    let fixture = Fixture::new(&context, &scene, [3, 3]);
    let angle = 1.0f32;
    let tir_camera = Camera {
        forward: [angle.sin(), 0.0, angle.cos()],
        right: [angle.cos(), 0.0, -angle.sin()],
        vertical_fov_radians: 0.05,
        ..camera()
    };
    let expected = fixture.run(tir_camera, tir_camera, [0.0; 2], 0, 2);
    expected.resolved();
    expected.static_motion();
    assert!(
        expected.0[6].iter().all(|status| *status == 0),
        "TIR completes its one primary guide without the explicit-companion bit"
    );
    for seed in 1..32 {
        let actual = fixture.run(tir_camera, tir_camera, [0.0; 2], seed, 2);
        for (index, (binding, _, _)) in CHANNELS.iter().enumerate() {
            assert_eq!(
                actual.0[index], expected.0[index],
                "TIR channel {binding}, seed {seed}"
            );
        }
    }

    let mut reset = Fixture::new(&context, &water(0.0), [17, 9]);
    reset.history_valid = false;
    let mut displaced = camera();
    displaced.position[0] -= 5.0;
    let actual = reset.run(camera(), displaced, [0.3, -0.4], 100, 3);
    actual.resolved();
    assert!(actual.0[6].iter().all(|status| *status == 4));
    for index in [1, 7] {
        assert!(
            actual.channel(index).iter().all(|motion| *motion == 0.0),
            "viewport reset must not consume previous-camera motion"
        );
    }

    // One actual dynamic instance without previous transforms: unknown correspondence is a
    // finite placeholder with pending state, never the unsupported -65504 SDK image marker.
    let scene = realtime_tests::scene(7, vec![]);
    let mut instances = InstanceScene {
        epoch: scene.epoch,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    let triangles: Vec<_> = [
        [[0.0, 0.0, 3.0], [0.0, 16.0, 3.0], [16.0, 16.0, 3.0]],
        [[0.0, 0.0, 3.0], [16.0, 16.0, 3.0], [16.0, 0.0, 3.0]],
    ]
    .into_iter()
    .map(|positions| prime_scene::Triangle {
        positions,
        colors: [[1.0; 4]; 3],
        uvs: [[0.5; 2]; 3],
        texture_id: 0,
        flags: 0,
    })
    .collect();
    instances.prototypes.insert(
        1,
        prime_scene::Prototype {
            revision: 1,
            triangles: triangles.into(),
            bounds: [[0.0, 0.0, 3.0], [16.0, 16.0, 3.0]],
        },
    );
    instances.instances.insert(
        1,
        prime_scene::Instance {
            revision: 1,
            prototype_id: 1,
            origin: [0.0; 3],
            transform: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0],
            texture_id: u32::MAX,
            flags: u32::MAX,
            tint: [255; 4],
            uv_transform: [1.0, 1.0, 0.0, 0.0],
        },
    );
    let fixture = Fixture::with_instances(&context, &scene, [17, 9], &instances);
    let actual = fixture.run(camera(), camera(), [0.0; 2], 101, 1);
    assert!(
        actual.0[6].iter().all(|status| *status == 3),
        "unknown primary motion also prevents reflection proxy completion"
    );
    for index in [1, 7] {
        assert!(actual.channel(index).iter().all(|motion| *motion == 0.0));
    }
}

#[test]
#[ignore = "explicit old/new K1-only SPIR-V; native 1080p isolated GPU dispatch cost, validation off"]
fn gpu_rr_k1_timestamp_ab_ba() {
    use std::{fs::File, io::Write, path::PathBuf, time::Instant};
    let load = |name: &str| {
        let path = PathBuf::from(
            std::env::var_os(name)
                .unwrap_or_else(|| panic!("set {name} to the matching K1-only fixture SPIR-V")),
        )
        .canonicalize()
        .unwrap();
        let bytes = std::fs::read(&path).unwrap();
        assert!(
            bytes.len() >= 20 && bytes[..4] == [3, 2, 35, 7],
            "invalid SPIR-V: {}",
            path.display()
        );
        (path, bytes)
    };
    let before = load("PRIME_RR_K1_BEFORE_SPV");
    let after = load("PRIME_RR_K1_AFTER_SPV");
    assert_ne!(
        before.1, after.1,
        "benchmark requires distinct before/after artifacts"
    );
    assert!(
        std::env::var_os("PRIME_VK_VALIDATION").is_none_or(|value| value == "0"),
        "disable validation for timing"
    );
    let output = std::env::var_os("PRIME_RR_K1_COST_CSV").map_or_else(
        || PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/rr-guide-k1-cost.csv"),
        PathBuf::from,
    );
    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent).unwrap();
    }
    let mut csv = File::create(&output).unwrap();
    let mut metadata = File::create(output.with_extension("txt")).unwrap();
    let context = Context::new().unwrap();
    assert!(
        context.timestamp_bits != 0,
        "GPU timestamps are required; absence is not a pass"
    );
    writeln!(metadata, "{}", context.benchmark_device_details()).unwrap();
    writeln!(metadata, "scope=K1 fixture only; constant environment; matching report writes; no K2/post/NGX/display; not a whole-frame RR result").unwrap();
    writeln!(metadata, "extent=1920x1080 seed=0x13572468 sequence=0 jitter=0,0 budget=12 warmup=12 steady=48 orders=AB,BA retained_outliers=true").unwrap();
    for (label, (path, bytes)) in [("before", &before), ("after", &after)] {
        let hash = bytes.iter().fold(0xcbf29ce484222325u64, |hash, byte| {
            (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
        });
        writeln!(
            metadata,
            "{label}={} bytes={} fnv1a64={hash:016x}",
            path.display(),
            bytes.len()
        )
        .unwrap();
    }
    struct Queries(Arc<Context>, vk::QueryPool);
    impl Drop for Queries {
        fn drop(&mut self) {
            unsafe {
                self.0.device.destroy_query_pool(self.1, None);
            }
        }
    }
    let queries = Queries(context.clone(), unsafe {
        context
            .device
            .create_query_pool(
                &vk::QueryPoolCreateInfo::default()
                    .query_type(vk::QueryType::TIMESTAMP)
                    .query_count(2),
                None,
            )
            .unwrap()
    });
    writeln!(csv, "scene,order,arm,sample,warmup,width,height,seed,sequence,budget,gpu_dispatch_ns,cpu_record_submit_wait_wall_ns").unwrap();
    for (name, scene) in [
        ("opaque", realtime_tests::scene(8, vec![plane(3.0, false)])),
        ("water", water(0.0)),
        ("glass_mirror_chain", mirror_chain()),
    ] {
        let mut fixture = Fixture::new(&context, &scene, [1920, 1080]);
        let current = camera();
        fixture
            .constants
            .write(&reconstruction_history::camera_constants(
                current,
                current,
                1920.0 / 1080.0,
                [0.0; 2],
                true,
            ))
            .unwrap();
        let push = realtime::PushInputs {
            camera: current,
            input: [1920, 1080],
            output: [1920, 1080],
            sequence: 0,
            sobol_r: 11,
            settings: RenderSettings {
                seed: 0x13572468,
                bounces: 12,
                ..Default::default()
            },
            display: PrimeDrtSettings::default().prepare(1.0).unwrap(),
            addresses: fixture.scratch.addresses(),
            jitter: Some([0.0; 2]),
            bottom_up: false,
        }
        .primary();
        for (order, arms) in [("AB", [0, 1]), ("BA", [1, 0])] {
            for arm in arms {
                let (label, code) = if arm == 0 {
                    ("before", &before.1)
                } else {
                    ("after", &after.1)
                };
                fixture.replace_shader(code);
                for sample in 0..60 {
                    let started = Instant::now();
                    let gpu = fixture.time_dispatch(queries.1, &push);
                    let wall = started.elapsed().as_nanos();
                    writeln!(
                        csv,
                        "{name},{order},{label},{sample},{},1920,1080,{},0,12,{gpu},{wall}",
                        sample < 12,
                        0x13572468u32
                    )
                    .unwrap();
                }
                csv.flush().unwrap();
            }
        }
    }
    eprintln!("K1-only GPU cost samples: {}", output.display());
}
