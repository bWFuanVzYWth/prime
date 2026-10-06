//! Windowless execution of the production RR display shader; no Streamline runtime or Present.
use super::*;

const INPUT: [u32; 2] = [2, 2];
const OUTPUT: [u32; 2] = [5, 3];

fn half(value: f32) -> u16 {
    // Fixture values are nonnegative, normal binary fractions or zero, exactly representable.
    if value == 0.0 {
        0
    } else {
        let bits = value.to_bits();
        ((((bits >> 23) & 255) - 112) << 10 | ((bits >> 13) & 1023)) as u16
    }
}

fn upload(context: &Arc<Context>, image: &Image, extent: [u32; 2], data: &[u8]) {
    let source = Buffer::upload(context, data, vk::BufferUsageFlags::TRANSFER_SRC).unwrap();
    context
        .submit_named("rr_display_fixture_upload", |command| unsafe {
            let before = [vk::ImageMemoryBarrier::default()
                .image(image.image)
                .old_layout(vk::ImageLayout::GENERAL)
                .new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .subresource_range(target::color_range())];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &before,
            );
            context.device.cmd_copy_buffer_to_image(
                command,
                source.buffer,
                image.image,
                vk::ImageLayout::GENERAL,
                &[vk::BufferImageCopy::default()
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width: extent[0],
                        height: extent[1],
                        depth: 1,
                    })],
            );
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &after,
                &[],
                &[],
            );
        })
        .unwrap();
}

fn pipeline(context: &Arc<Context>) -> Pipeline {
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
        realtime_linear_post: None,
        reconstruction_display: None,
        reconstruction_linear: None,
        restir: None,
    };
    unsafe {
        let bindings = [4, 10, 11, 13, 18, 19, 14, 15].map(|binding| {
            vk::DescriptorSetLayoutBinding::default()
                .binding(binding)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
        });
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
                        .size(64)]),
                None,
            )
            .unwrap();
        pipeline.pool = context
            .device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default()
                    .max_sets(1)
                    .pool_sizes(&[vk::DescriptorPoolSize {
                        ty: vk::DescriptorType::STORAGE_IMAGE,
                        descriptor_count: 8,
                    }]),
                None,
            )
            .unwrap();
        pipeline.descriptors[0] = context
            .device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(pipeline.pool)
                    .set_layouts(&layouts),
            )
            .unwrap()[0];
        for (code, linear) in [
            (prime_shaders::rr_display(), false),
            (prime_shaders::rr_linear(), true),
        ] {
            let words = ash::util::read_spv(&mut Cursor::new(code)).unwrap();
            let shader = context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .unwrap();
            let result =
                context.create_compute_pipelines(&[vk::ComputePipelineCreateInfo::default()
                    .layout(pipeline.layout)
                    .stage(
                        vk::PipelineShaderStageCreateInfo::default()
                            .module(shader)
                            .stage(vk::ShaderStageFlags::COMPUTE)
                            .name(c"main"),
                    )]);
            context.device.destroy_shader_module(shader, None);
            if linear {
                pipeline.reconstruction_linear = Some(result.unwrap()[0]);
            } else {
                pipeline.reconstruction_display = Some(result.unwrap()[0]);
            }
        }
    }
    pipeline
}

fn run(context: &Arc<Context>, pipeline: &Pipeline, image: &Image, control: [u32; 3]) -> Vec<u8> {
    run_extent(context, pipeline, image, control, INPUT, OUTPUT)
}

fn fixture_images(context: &Arc<Context>, input: [u32; 2], output: [u32; 2]) -> Vec<Image> {
    [
        (output, vk::Format::R8G8B8A8_UNORM),
        (input, vk::Format::R16G16B16A16_SFLOAT),
        (input, vk::Format::R32_SFLOAT),
        (input, vk::Format::R16G16B16A16_SFLOAT),
        (output, vk::Format::R16G16B16A16_SFLOAT),
        (input, vk::Format::R8_UNORM),
        (input, vk::Format::R16G16B16A16_SFLOAT),
        (input, vk::Format::R16G16B16A16_SFLOAT),
    ]
    .into_iter()
    .map(|(extent, format)| Image::with_format(context, extent[0], extent[1], format).unwrap())
    .collect()
}

fn bind_images(context: &Arc<Context>, pipeline: &Pipeline, images: &[Image], output: &Image) {
    let infos: Vec<_> = images
        .iter()
        .enumerate()
        .map(|(index, image)| {
            [vk::DescriptorImageInfo::default()
                .image_view(if index == 0 { output.view } else { image.view })
                .image_layout(vk::ImageLayout::GENERAL)]
        })
        .collect();
    let writes: Vec<_> = [4, 10, 11, 13, 18, 19, 14, 15]
        .into_iter()
        .zip(&infos)
        .map(|(binding, info)| {
            vk::WriteDescriptorSet::default()
                .dst_set(pipeline.descriptors[0])
                .dst_binding(binding)
                .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .image_info(info)
        })
        .collect();
    unsafe { context.device.update_descriptor_sets(&writes, &[]) };
}

fn run_extent(
    context: &Arc<Context>,
    pipeline: &Pipeline,
    image: &Image,
    control: [u32; 3],
    input: [u32; 2],
    output: [u32; 2],
) -> Vec<u8> {
    run_image(
        context,
        pipeline,
        image,
        control,
        input,
        output,
        false,
        PrimeDrtSettings::default().prepare(1.0).unwrap(),
    )
}

#[allow(clippy::too_many_arguments)]
fn run_image(
    context: &Arc<Context>,
    pipeline: &Pipeline,
    image: &Image,
    control: [u32; 3],
    input: [u32; 2],
    output: [u32; 2],
    linear: bool,
    display: PrimeDrtParameters,
) -> Vec<u8> {
    let mut push = Vec::with_capacity(64);
    for value in [
        input[0], input[1], output[0], output[1], control[0], control[1], control[2], 0,
    ] {
        push.extend(value.to_le_bytes());
    }
    for value in display.values {
        push.extend(value.to_le_bytes());
    }
    push[52..56].copy_from_slice(&4.0f32.to_le_bytes());
    let bytes = output[0] * output[1] * if linear { 16 } else { 4 };
    let readback = Buffer::new_readback(context, u64::from(bytes)).unwrap();
    context
        .submit_named("rr_display_fixture_dispatch", |command| unsafe {
            let before = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &before,
                &[],
                &[],
            );
            context.device.cmd_clear_color_image(
                command,
                image.image,
                vk::ImageLayout::GENERAL,
                &vk::ClearColorValue {
                    float32: [65504.0, 65504.0, 65504.0, 0.0],
                },
                &[target::color_range()],
            );
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)],
                &[],
                &[],
            );
            context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                if linear {
                    pipeline.reconstruction_linear.unwrap()
                } else {
                    pipeline.reconstruction_display.unwrap()
                },
            );
            context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.layout,
                0,
                &[pipeline.descriptors[0]],
                &[],
            );
            context.device.cmd_push_constants(
                command,
                pipeline.layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                &push,
            );
            context
                .device
                .cmd_dispatch(command, output[0].div_ceil(8), output[1].div_ceil(8), 1);
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &after,
                &[],
                &[],
            );
            context.device.cmd_copy_image_to_buffer(
                command,
                image.image,
                vk::ImageLayout::GENERAL,
                readback.buffer,
                &[vk::BufferImageCopy::default()
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width: output[0],
                        height: output[1],
                        depth: 1,
                    })],
            );
            let host = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &host,
                &[],
                &[],
            );
        })
        .unwrap();
    readback.read(bytes as usize).unwrap()
}

#[test]
#[ignore = "windowless production RR display readback; exclusive GPU with validation"]
fn gpu_rr_display_fallback_upscale_and_orientation() {
    let context = Context::new().unwrap();
    let images = fixture_images(&context, INPUT, OUTPUT);
    let levels = [0.0f32, 0.25, 0.5, 1.0];
    let noisy: Vec<_> = levels
        .into_iter()
        .flat_map(|v| [v, v, v, 1.0])
        .flat_map(|v| half(v).to_le_bytes())
        .collect();
    let depth: Vec<_> = [1.0f32, 2.0, 4.0, f32::MAX]
        .into_iter()
        .flat_map(f32::to_le_bytes)
        .collect();
    let normals: Vec<_> = [0.0f32, 0.0, 1.0, 0.5]
        .repeat(4)
        .into_iter()
        .flat_map(|v| half(v).to_le_bytes())
        .collect();
    upload(&context, &images[1], INPUT, &noisy);
    upload(&context, &images[2], INPUT, &depth);
    upload(&context, &images[3], INPUT, &normals);
    upload(&context, &images[4], OUTPUT, &[0; 5 * 3 * 8]);
    upload(&context, &images[5], INPUT, &[0; 4]);
    upload(&context, &images[6], INPUT, &normals);
    upload(&context, &images[7], INPUT, &normals);
    let pipeline = pipeline(&context);
    bind_images(&context, &pipeline, &images, &images[0]);
    let top = run(&context, &pipeline, &images[0], [0, 1, 0]);
    let flipped = run(&context, &pipeline, &images[0], [1, 1, 0]);
    let srgb = |linear: f32| {
        let encoded = if linear <= 0.0031308 {
            12.92 * linear
        } else {
            1.055 * linear.powf(1.0 / 2.4) - 0.055
        };
        (encoded * 255.0 + 0.5) as u8
    };
    for y in 0..OUTPUT[1] as usize {
        for x in 0..OUTPUT[0] as usize {
            let u = ((x as f32 + 0.5) * 2.0 / 5.0 - 0.5).clamp(0.0, 1.0);
            let v = ((y as f32 + 0.5) * 2.0 / 3.0 - 0.5).clamp(0.0, 1.0);
            let linear = (0.25 * u) * (1.0 - v) + (0.5 + 0.5 * u) * v;
            let offset = (y * 5 + x) * 4;
            for channel in 0..3 {
                assert!(top[offset + channel].abs_diff(srgb(linear)) <= 1);
            }
            assert_eq!(
                top[offset + 3],
                255,
                "every odd-extent output pixel is written"
            );
            assert_eq!(
                &top[offset..offset + 4],
                &flipped[((2 - y) * 5 + x) * 4..][..4]
            );
        }
    }
    let fallback = run(&context, &pipeline, &images[0], [0, 0, 0]);
    let success = run(&context, &pipeline, &images[0], [0, 0, 1]);
    assert!(fallback.as_chunks::<4>().0.iter().any(|p| p[0] != 0));
    assert!(
        success
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| *p == [0, 0, 0, 255])
    );
    upload(&context, &images[5], INPUT, &[255; 4]);
    let unresolved = run(&context, &pipeline, &images[0], [0, 0, 1]);
    assert_eq!(
        unresolved, fallback,
        "Malformed guides must use spatial denoising even after successful RR"
    );
    upload(&context, &images[5], INPUT, &[255, 0, 0, 0]);
    let mixed = run(&context, &pipeline, &images[0], [0, 0, 1]);
    for y in 0..OUTPUT[1] {
        for x in 0..OUTPUT[0] {
            let source = [
                (x as f32 + 0.5) * 2.0 / 5.0 - 0.5,
                (y as f32 + 0.5) * 2.0 / 3.0 - 0.5,
            ];
            let includes_unresolved = source.into_iter().all(|v| v.floor().clamp(0.0, 1.0) == 0.0);
            let offset = ((y * OUTPUT[0] + x) * 4) as usize;
            let expected = if includes_unresolved {
                &fallback
            } else {
                &success
            };
            assert_eq!(
                &mixed[offset..offset + 4],
                &expected[offset..offset + 4],
                "Guide fallback must cover the full upsampling footprint at {x},{y}"
            );
        }
    }
    upload(&context, &images[5], INPUT, &[0; 4]);
    let normal = run(&context, &pipeline, &images[0], [0, 3, 1]);
    assert!(
        normal
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| *p == [128, 128, 255, 255])
    );
    let depth = run(&context, &pipeline, &images[0], [0, 2, 1]);
    assert_eq!(&depth[..4], &[64, 64, 64, 255]);
    assert_eq!(&depth[16..20], &[128, 128, 128, 255]);
    assert_eq!(&depth[40..44], &[255, 255, 255, 255]);
    assert_eq!(&depth[56..60], &[0, 0, 0, 255]);
    let reconstructed: Vec<_> = [0.125f32, 0.125, 0.125, 1.0]
        .repeat((OUTPUT[0] * OUTPUT[1]) as usize)
        .into_iter()
        .flat_map(|v| half(v).to_le_bytes())
        .collect();
    upload(&context, &images[4], OUTPUT, &reconstructed);
    upload(&context, &images[5], INPUT, &[0; 4]);
    let dlaa_success = run_extent(&context, &pipeline, &images[0], [0, 0, 1], INPUT, INPUT);
    let dlaa_raw = run_extent(&context, &pipeline, &images[0], [0, 0, 0], INPUT, INPUT);
    upload(&context, &images[5], INPUT, &[0, 0, 0, 255]);
    let dlaa = run_extent(&context, &pipeline, &images[0], [0, 0, 1], INPUT, INPUT);
    assert_eq!(
        &dlaa[..12],
        &dlaa_success[..12],
        "DLAA zero-weight neighbours cannot expand fallback"
    );
    assert_eq!(&dlaa[12..16], &dlaa_raw[12..16]);
    assert_ne!(&dlaa_raw[4..8], &dlaa_success[4..8]); // the adjacent consumer is observable

    // Same-surface colors must be averaged rather than displayed directly. The previous
    // fixture deliberately had depth discontinuities, which the filter must preserve.
    let flat_depth: Vec<_> = [3.0f32; 4].into_iter().flat_map(f32::to_le_bytes).collect();
    upload(&context, &images[2], INPUT, &flat_depth);
    upload(&context, &images[5], INPUT, &[0; 4]);
    let filtered = run(&context, &pipeline, &images[0], [0, 0, 0]);
    assert!(
        filtered[0] > fallback[0],
        "same-surface neighbours reduce dark noise"
    );
    assert!(
        filtered[56] < fallback[56],
        "same-surface neighbours reduce bright noise"
    );
    // Alpha/visibility classes independently stop spatial bleed at a foreground silhouette.
    upload(&context, &images[5], INPUT, &[8, 0, 0, 0]);
    let silhouette = run(&context, &pipeline, &images[0], [0, 0, 0]);
    assert_eq!(
        &silhouette[..4],
        &fallback[..4],
        "foreground silhouette stays sharp"
    );
    upload(&context, &images[5], INPUT, &[0; 4]);
    let mut material_edge = normals.clone();
    let red: Vec<_> = [1.0f32, 0.0, 0.0, 0.5]
        .into_iter()
        .flat_map(|v| half(v).to_le_bytes())
        .collect();
    material_edge[..8].copy_from_slice(&red);
    upload(&context, &images[6], INPUT, &material_edge);
    let material = run(&context, &pipeline, &images[0], [0, 0, 0]);
    assert_eq!(
        &material[..4],
        &fallback[..4],
        "coplanar material edge stays sharp"
    );
}

#[derive(Clone, Copy)]
struct SpatialSample {
    radiance: f64,
    alpha: f64,
    depth: f64,
    normal: [f64; 3],
    roughness: f64,
    diffuse: [f64; 3],
    specular: [f64; 3],
    foreground: bool,
}

fn half_value(value: f64) -> f64 {
    let bits = half(value as f32);
    let exponent = i32::from((bits >> 10) & 31);
    if exponent == 0 {
        f64::from(bits & 1023) * 2.0f64.powi(-24)
    } else {
        (1.0 + f64::from(bits & 1023) / 1024.0) * 2.0f64.powi(exponent - 15)
    }
}

impl SpatialSample {
    fn stored(mut self) -> Self {
        self.radiance = half_value(self.radiance);
        self.alpha = half_value(self.alpha);
        self.normal = self.normal.map(half_value);
        self.roughness = half_value(self.roughness);
        self.diffuse = self.diffuse.map(half_value);
        self.specular = self.specular.map(half_value);
        self.depth = f64::from(self.depth as f32);
        self
    }
}

// Independent FP64 gather/normalization oracle. It evaluates the stated Gaussian
// distances and angular exponent in log space, never calling a shader weight helper.
fn spatial_oracle(samples: &[SpatialSample], input: [u32; 2], source: [f64; 2]) -> [f64; 2] {
    let center: [i32; 2] = std::array::from_fn(|axis| {
        ((source[axis] + 0.5).floor() as i32).clamp(0, input[axis] as i32 - 1)
    });
    let at = |x: i32, y: i32| samples[(y as u32 * input[0] + x as u32) as usize];
    let reference = at(center[0], center[1]);
    let sky = f64::from(f32::MAX);
    let dot = |a: [f64; 3], b: [f64; 3]| a.into_iter().zip(b).map(|(a, b)| a * b).sum::<f64>();
    let square_distance = |a: [f64; 3], b: [f64; 3]| {
        a.into_iter()
            .zip(b)
            .map(|(a, b)| (a - b).powi(2))
            .sum::<f64>()
    };
    let mut sum = [0.0; 2];
    let mut mass = 0.0;
    for y in center[1] - 2..=center[1] + 2 {
        for x in center[0] - 2..=center[0] + 2 {
            if x < 0 || y < 0 || x >= input[0] as i32 || y >= input[1] as i32 {
                continue;
            }
            let sample = at(x, y);
            if reference.foreground != sample.foreground
                || (reference.depth == sky) != (sample.depth == sky)
            {
                continue;
            }
            let mut log_weight =
                -((f64::from(x) - source[0]).powi(2) + (f64::from(y) - source[1]).powi(2)) / 4.0;
            if reference.depth != sky {
                log_weight -=
                    ((sample.depth - reference.depth) / (0.02 * reference.depth).max(0.01)).powi(2);
                let lengths =
                    dot(reference.normal, reference.normal) * dot(sample.normal, sample.normal);
                if lengths > 1e-12 {
                    let cosine =
                        (dot(reference.normal, sample.normal) / lengths.sqrt()).clamp(0.0, 1.0);
                    if cosine == 0.0 {
                        continue;
                    }
                    log_weight += 32.0 * cosine.ln();
                }
            }
            log_weight -= 64.0
                * (square_distance(reference.diffuse, sample.diffuse)
                    + square_distance(reference.specular, sample.specular)
                    + (reference.roughness - sample.roughness).powi(2));
            let weight = log_weight.exp();
            sum[0] += weight * sample.radiance;
            sum[1] += weight * sample.alpha;
            mass += weight;
        }
    }
    assert!(mass > 0.0);
    sum.map(|sum| sum / mass)
}

fn neutral_sdr(value: f64) -> u8 {
    // This fixture chooses unit exposure/curve peak and no hue/saturation changes.
    // For neutral RGB the gamut transforms preserve gray; use the rational DRT
    // curve rather than its GPU reciprocal implementation.
    let mapped = if value <= 0.18 {
        value
    } else {
        0.18 + 0.82 * (value - 0.18) / (value - 0.18 + 0.82)
    };
    let encoded = if mapped < 0.0031308 {
        mapped * 12.92
    } else {
        1.055 * mapped.powf(1.0 / 2.4) - 0.055
    };
    (encoded.clamp(0.0, 1.0) * 255.0 + 0.5).floor() as u8
}

fn upload_spatial_samples(
    context: &Arc<Context>,
    images: &[Image],
    input: [u32; 2],
    samples: &[SpatialSample],
) {
    let rgba_half = |values: Vec<[f64; 4]>| -> Vec<u8> {
        values
            .into_iter()
            .flatten()
            .flat_map(|value| half(value as f32).to_le_bytes())
            .collect()
    };
    upload(
        context,
        &images[1],
        input,
        &rgba_half(
            samples
                .iter()
                .map(|s| [s.radiance, s.radiance, s.radiance, s.alpha])
                .collect(),
        ),
    );
    upload(
        context,
        &images[2],
        input,
        &samples
            .iter()
            .flat_map(|s| (s.depth as f32).to_le_bytes())
            .collect::<Vec<_>>(),
    );
    upload(
        context,
        &images[3],
        input,
        &rgba_half(
            samples
                .iter()
                .map(|s| [s.normal[0], s.normal[1], s.normal[2], s.roughness])
                .collect(),
        ),
    );
    upload(
        context,
        &images[5],
        input,
        &samples
            .iter()
            .map(|s| if s.foreground { 8 } else { 0 })
            .collect::<Vec<_>>(),
    );
    for (image, specular) in [(6, false), (7, true)] {
        upload(
            context,
            &images[image],
            input,
            &rgba_half(
                samples
                    .iter()
                    .map(|s| {
                        let rgb = if specular { s.specular } else { s.diffuse };
                        [rgb[0], rgb[1], rgb[2], 1.0]
                    })
                    .collect(),
            ),
        );
    }
}

#[test]
#[ignore = "windowless production 5x5 RR spatial gather against FP64 oracle; separate guide boundaries and SDR/linear outputs"]
fn gpu_rr_spatial_full_kernel_and_each_guide_boundary_match_fp64() {
    let context = Context::new().unwrap();
    let input = [9, 7];
    let output = [19, 13];
    let images = fixture_images(&context, input, output);
    let linear = Image::with_format(
        &context,
        output[0],
        output[1],
        vk::Format::R32G32B32A32_SFLOAT,
    )
    .unwrap();
    let pipeline = pipeline(&context);
    let parameters = PrimeDrtParameters {
        values: [1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    };
    // An observable reconstructed image also catches accidental use of the SDK path.
    let reconstructed: Vec<_> = [0.75f32, 0.75, 0.75, 1.0]
        .repeat((output[0] * output[1]) as usize)
        .into_iter()
        .flat_map(|value| half(value).to_le_bytes())
        .collect();
    upload(&context, &images[4], output, &reconstructed);
    for case in [
        "outer_ring",
        "constant",
        "checkerboard",
        "depth",
        "normal",
        "normal_angle",
        "diffuse",
        "specular",
        "roughness",
        "foreground",
        "sky",
    ] {
        let samples: Vec<_> = (0..input[0] * input[1])
            .map(|index| {
                let x = index % input[0];
                let y = index / input[0];
                let right = x >= 4;
                let radiance = match case {
                    "outer_ring" => {
                        if [x, y] == [6, 3] {
                            1.0
                        } else {
                            0.0
                        }
                    }
                    "constant" => 0.25,
                    "checkerboard" => {
                        if (x + y) % 2 == 0 {
                            0.0
                        } else {
                            0.5
                        }
                    }
                    _ => {
                        if right {
                            1.0
                        } else {
                            0.125
                        }
                    }
                };
                let mut sample = SpatialSample {
                    radiance,
                    alpha: if y % 2 == 0 { 0.5 } else { 1.0 },
                    depth: 3.0,
                    normal: [0.0, 0.0, 1.0],
                    roughness: 0.5,
                    diffuse: [0.25; 3],
                    specular: [0.125; 3],
                    foreground: true,
                };
                if right {
                    match case {
                        "depth" => sample.depth = 6.0,
                        "normal" => sample.normal = [1.0, 0.0, 0.0],
                        "normal_angle" => sample.normal = [0.5, 0.0, 0.8660254],
                        "diffuse" => sample.diffuse = [0.75, 0.25, 0.25],
                        "specular" => sample.specular = [0.125, 0.625, 0.125],
                        "roughness" => sample.roughness = 1.0,
                        "foreground" => sample.foreground = false,
                        "sky" => {
                            sample.depth = f64::from(f32::MAX);
                            sample.normal = [0.0; 3];
                        }
                        _ => {}
                    }
                }
                sample.stored()
            })
            .collect();
        upload_spatial_samples(&context, &images, input, &samples);
        bind_images(&context, &pipeline, &images, &linear);
        let actual = run_image(
            &context,
            &pipeline,
            &linear,
            [0, 0, 0],
            input,
            output,
            true,
            parameters,
        );
        let actual: Vec<_> = actual
            .as_chunks::<4>()
            .0
            .iter()
            .map(|word| f32::from_le_bytes(*word))
            .collect();
        bind_images(&context, &pipeline, &images, &images[0]);
        let sdr = run_image(
            &context,
            &pipeline,
            &images[0],
            [0, 0, 0],
            input,
            output,
            false,
            parameters,
        );
        let mut mean = 0.0;
        let mut variance = 0.0;
        for y in 0..output[1] {
            for x in 0..output[0] {
                let source = [
                    (f64::from(x) + 0.5) * f64::from(input[0]) / f64::from(output[0]) - 0.5,
                    (f64::from(y) + 0.5) * f64::from(input[1]) / f64::from(output[1]) - 0.5,
                ];
                let expected = spatial_oracle(&samples, input, source);
                let pixel = ((y * output[0] + x) * 4) as usize;
                for channel in 0..4 {
                    let expected = expected[usize::from(channel == 3)];
                    assert!(
                        (f64::from(actual[pixel + channel]) - expected).abs() < 3e-6,
                        "{case} linear {x},{y}/{channel}: {} != {expected}",
                        actual[pixel + channel]
                    );
                }
                for channel in 0..3 {
                    assert!(
                        sdr[pixel + channel].abs_diff(neutral_sdr(expected[0])) <= 1,
                        "{case} SDR {x},{y}/{channel}: {} != {}",
                        sdr[pixel + channel],
                        neutral_sdr(expected[0])
                    );
                }
                assert_eq!(sdr[pixel + 3], 255, "{case} complete output {x},{y}");
                mean += f64::from(actual[pixel]);
                variance += (f64::from(actual[pixel]) - 0.25).powi(2);
            }
        }
        if case == "outer_ring" {
            let center = ((6 * output[0] + 9) * 4) as usize;
            assert!(
                actual[center] > 0.03,
                "The radius-two-only impulse must reach the interior center"
            );
        }
        if case == "checkerboard" {
            let count = f64::from(output[0] * output[1]);
            assert!(
                (mean / count - 0.25).abs() < 0.02,
                "local smoothing preserves the checkerboard mean"
            );
            assert!(
                variance / count < 0.002,
                "smoothing must reduce the 0.0625 input variance"
            );
        }
    }
}
