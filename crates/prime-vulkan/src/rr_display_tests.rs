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
        reconstruction_display: None,
    };
    unsafe {
        let bindings = [4, 10, 11, 13, 18, 19].map(|binding| {
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
                        descriptor_count: 6,
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
        let code = include_bytes!(concat!(env!("OUT_DIR"), "/rr_display.spv"));
        let words: Vec<_> = code
            .as_chunks::<4>()
            .0
            .iter()
            .map(|word| u32::from_le_bytes(*word))
            .collect();
        let shader = context
            .device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
            .unwrap();
        let result = context.device.create_compute_pipelines(
            vk::PipelineCache::null(),
            &[vk::ComputePipelineCreateInfo::default()
                .layout(pipeline.layout)
                .stage(
                    vk::PipelineShaderStageCreateInfo::default()
                        .module(shader)
                        .stage(vk::ShaderStageFlags::COMPUTE)
                        .name(c"main"),
                )],
            None,
        );
        context.device.destroy_shader_module(shader, None);
        pipeline.reconstruction_display = Some(result.unwrap()[0]);
    }
    pipeline
}

fn run(context: &Arc<Context>, pipeline: &Pipeline, image: &Image, control: [u32; 3]) -> Vec<u8> {
    let mut push = Vec::with_capacity(64);
    for value in [2, 2, 5, 3, control[0], control[1], control[2], 0] {
        push.extend(value.to_le_bytes());
    }
    for value in PrimeDrtSettings::default().prepare(1.0).unwrap().values {
        push.extend(value.to_le_bytes());
    }
    push[52..56].copy_from_slice(&4.0f32.to_le_bytes());
    let readback = Buffer::new_readback(context, u64::from(OUTPUT[0] * OUTPUT[1] * 4)).unwrap();
    context
        .submit_named("rr_display_fixture_dispatch", |command| unsafe {
            let before = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &before,
                &[],
                &[],
            );
            context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.reconstruction_display.unwrap(),
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
            context.device.cmd_dispatch(command, 1, 1, 1);
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
                        width: OUTPUT[0],
                        height: OUTPUT[1],
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
    readback.read((OUTPUT[0] * OUTPUT[1] * 4) as usize).unwrap()
}

#[test]
#[ignore = "windowless production RR display readback; exclusive GPU with validation"]
fn gpu_rr_display_fallback_upscale_and_orientation() {
    let context = Context::new().unwrap();
    let images: Vec<_> = [
        (OUTPUT, vk::Format::R8G8B8A8_UNORM),
        (INPUT, vk::Format::R16G16B16A16_SFLOAT),
        (INPUT, vk::Format::R32_SFLOAT),
        (INPUT, vk::Format::R16G16B16A16_SFLOAT),
        (OUTPUT, vk::Format::R16G16B16A16_SFLOAT),
        (INPUT, vk::Format::R8_UNORM),
    ]
    .into_iter()
    .map(|(extent, format)| Image::with_format(&context, extent[0], extent[1], format).unwrap())
    .collect();
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
    let pipeline = pipeline(&context);
    let infos: Vec<_> = images
        .iter()
        .map(|image| {
            [vk::DescriptorImageInfo::default()
                .image_view(image.view)
                .image_layout(vk::ImageLayout::GENERAL)]
        })
        .collect();
    let writes: Vec<_> = [4, 10, 11, 13, 18, 19]
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
        "Unresolved guides must display current raw color even after successful RR"
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
}
