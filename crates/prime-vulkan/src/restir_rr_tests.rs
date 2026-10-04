//! Actual ReSTIR RR generation, guide and resolve images without an SDK substitute.
use super::*;
use prime_scene::{
    TextureMaterial,
    surface::{Emission, Medium, Optics},
    workers::CpuWorkers,
};
use realtime_tests::{camera, face, scene, texture};

const INPUT: [u32; 2] = [17, 9];
const OUTPUT: [u32; 2] = [31, 17];
const CHANNELS: [(u32, vk::Format, usize); 12] = [
    (10, vk::Format::R16G16B16A16_SFLOAT, 8),
    (11, vk::Format::R32_SFLOAT, 4),
    (12, vk::Format::R16G16_SFLOAT, 4),
    (13, vk::Format::R16G16B16A16_SFLOAT, 8),
    (14, vk::Format::R16G16B16A16_SFLOAT, 8),
    (15, vk::Format::R16G16B16A16_SFLOAT, 8),
    (16, vk::Format::R16_SFLOAT, 2),
    (19, vk::Format::R8_UNORM, 1),
    (20, vk::Format::R16G16_SFLOAT, 4),
    (21, vk::Format::R32_SFLOAT, 4),
    (22, vk::Format::R16G16_SFLOAT, 4),
    (24, vk::Format::R32G32B32A32_SFLOAT, 16),
];

fn half(word: u16) -> f32 {
    let sign = if word & 0x8000 == 0 { 1.0 } else { -1.0 };
    let exponent = (word >> 10) & 31;
    let mantissa = word & 1023;
    sign * match exponent {
        0 => f32::from(mantissa) * 2f32.powi(-24),
        31 if mantissa == 0 => f32::INFINITY,
        31 => f32::NAN,
        _ => (1.0 + f32::from(mantissa) / 1024.0) * 2f32.powi(i32::from(exponent) - 15),
    }
}

struct Snapshot {
    channels: Vec<Vec<u8>>,
    displayed: Vec<u8>,
}
impl Snapshot {
    fn values(&self, channel: usize) -> Vec<f32> {
        match CHANNELS[channel].1 {
            vk::Format::R32_SFLOAT | vk::Format::R32G32B32A32_SFLOAT => self.channels[channel]
                .as_chunks::<4>()
                .0
                .iter()
                .map(|word| f32::from_le_bytes(*word))
                .collect(),
            vk::Format::R8_UNORM => self.channels[channel]
                .iter()
                .map(|value| f32::from(*value) / 255.0)
                .collect(),
            _ => self.channels[channel]
                .as_chunks::<2>()
                .0
                .iter()
                .map(|word| half(u16::from_le_bytes(*word)))
                .collect(),
        }
    }
    fn finite(&self) {
        for channel in 0..CHANNELS.len() {
            assert!(
                self.values(channel).into_iter().all(f32::is_finite),
                "finite image binding {}",
                CHANNELS[channel].0
            );
        }
        assert!(
            self.displayed
                .as_chunks::<4>()
                .0
                .iter()
                .all(|p| p[3] == 255)
        );
        assert_eq!(self.displayed.len(), (OUTPUT[0] * OUTPUT[1] * 4) as usize);
    }
    fn complete(&self) {
        self.finite();
        assert!(self.channels[7].iter().all(|status| status & 3 == 0));
        for channel in [3, 4, 5] {
            assert!(self.values(channel).iter().all(|value| value.abs() <= 1.0));
        }
    }
    fn static_motion(&self) {
        for channel in [2, 8, 10] {
            assert!(self.values(channel).iter().all(|value| value.abs() <= 2e-5));
        }
    }
    fn color_matches_raw_resolve(&self) {
        let noisy = self.values(0);
        let raw = self.values(11);
        for (pixel, source) in noisy.as_chunks::<4>().0.iter().zip(raw.as_chunks::<4>().0) {
            let converted = [
                1.660_491 * source[0] - 0.587_641_1 * source[1] - 0.072_849_86 * source[2],
                -0.124_550_48 * source[0] + 1.132_899_9 * source[1] - 0.008_349_422 * source[2],
                -0.018_150_764 * source[0] - 0.100_578_9 * source[1] + 1.118_729_7 * source[2],
            ];
            for (actual, expected) in pixel[..3].iter().zip(converted) {
                let expected = expected.clamp(0.0, 65504.0);
                assert!(
                    (*actual - expected).abs() <= 0.001 * expected.abs() + 0.000_002,
                    "linear working-to-709 before RR: {actual} != {expected}"
                );
            }
        }
    }
}

struct Fixture {
    renderer: Renderer,
    rr: Option<Pipeline>,
    images: Vec<Image>,
    reconstructed: Image,
    target: Image,
    constants: Buffer,
}

struct RawResolve {
    context: Arc<Context>,
    handle: vk::Pipeline,
}
impl Drop for RawResolve {
    fn drop(&mut self) {
        unsafe { self.context.device.destroy_pipeline(self.handle, None) };
    }
}
impl RawResolve {
    fn new(context: &Arc<Context>, layout: vk::PipelineLayout, variant: usize) -> Self {
        let code = prime_shaders::restir_resolve();
        let words = ash::util::read_spv(&mut Cursor::new(code)).unwrap();
        let features = [
            [0, 0, 0],
            [1, 0, 0],
            [2, 0, 0],
            [2, 1, 0],
            [2, 0, 1],
            [2, 1, 1],
        ][variant];
        let data = [features[0], features[1], features[2], 0u32, 0].map(u32::to_le_bytes);
        let entries = [0, 1, 2, 3, 4].map(|id| vk::SpecializationMapEntry {
            constant_id: id,
            offset: id * 4,
            size: 4,
        });
        let specialization = vk::SpecializationInfo::default()
            .map_entries(&entries)
            .data(data.as_flattened());
        let handle = unsafe {
            let module = context
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                .unwrap();
            let result = context.device.create_compute_pipelines(
                vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default()
                    .layout(layout)
                    .stage(
                        vk::PipelineShaderStageCreateInfo::default()
                            .stage(vk::ShaderStageFlags::COMPUTE)
                            .module(module)
                            .name(c"main")
                            .specialization_info(&specialization),
                    )],
                None,
            );
            context.device.destroy_shader_module(module, None);
            result.unwrap()[0]
        };
        Self {
            context: context.clone(),
            handle,
        }
    }
}
impl Fixture {
    fn new() -> Self {
        let settings = RenderSettings {
            integrator: Integrator::RestirPt,
            mode: RenderMode::Realtime,
            bounces: 6,
            sun: 1.0 / 256.0,
            sky: 1.0 / 256.0,
            stars: 0.0,
            ray_reconstruction: false,
            opacity_micromap: false,
            ..Default::default()
        };
        let mut renderer = Renderer::with_settings_and_workers(
            settings,
            Arc::new(CpuWorkers::configured().unwrap()),
        )
        .unwrap();
        renderer.set_diagnostics(false).unwrap();
        let context = &renderer.context;
        let rr = Pipeline::new(
            context,
            Integrator::RestirPt,
            RenderMode::Realtime,
            true,
            true,
            settings.light_sampling,
            &renderer.energy_lut,
        )
        .unwrap();
        assert!(rr.primary_pipelines.is_none());
        assert!(rr.realtime_post.is_none());
        Self {
            images: CHANNELS
                .iter()
                .map(|(_, format, _)| {
                    Image::with_format(context, INPUT[0], INPUT[1], *format).unwrap()
                })
                .collect(),
            reconstructed: Image::with_format(
                context,
                OUTPUT[0],
                OUTPUT[1],
                vk::Format::R16G16B16A16_SFLOAT,
            )
            .unwrap(),
            target: Image::new(context, OUTPUT[0], OUTPUT[1]).unwrap(),
            constants: Buffer::new(context, 144, vk::BufferUsageFlags::UNIFORM_BUFFER, true)
                .unwrap(),
            rr: Some(rr),
            renderer,
        }
    }

    fn run(
        &mut self,
        scene: &Scene,
        instances: &InstanceScene,
        jitter: [f32; 2],
        sequence: u32,
        bounces: u32,
    ) -> Snapshot {
        let camera = camera();
        self.renderer
            .configure(RenderSettings {
                bounces,
                ..self.renderer.settings
            })
            .unwrap();
        // This synchronous native preparation owns real AS/material/atmosphere resources.
        self.renderer
            .render_with_instances(scene, instances, &camera, INPUT[0], INPUT[1], 0)
            .unwrap();
        let context = self.renderer.context.clone();
        let raw = self.renderer.pipeline.take().unwrap();
        let rr = self.rr.take().unwrap();
        let set = rr.descriptors[0];
        let copies: Vec<_> = [0, 2, 3, 7, 8, 9, 23]
            .into_iter()
            .map(|binding| {
                vk::CopyDescriptorSet::default()
                    .src_set(raw.descriptors[0])
                    .src_binding(binding)
                    .dst_set(set)
                    .dst_binding(binding)
                    .descriptor_count(1)
            })
            .collect();
        unsafe { context.device.update_descriptor_sets(&[], &copies) };
        let update_image = |binding, image: &Image| unsafe {
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                    .image_info(&[vk::DescriptorImageInfo::default()
                        .image_view(image.view)
                        .image_layout(vk::ImageLayout::GENERAL)])],
                &[],
            );
        };
        for ((binding, _, _), image) in CHANNELS.iter().zip(&self.images) {
            update_image(*binding, image);
        }
        update_image(4, &self.target);
        update_image(18, &self.reconstructed);
        let aspect = OUTPUT[0] as f32 / OUTPUT[1] as f32;
        self.constants
            .write(&reconstruction_history::camera_constants(
                camera, camera, aspect, jitter, true,
            ))
            .unwrap();
        unsafe {
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(17)
                    .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                    .buffer_info(&[vk::DescriptorBufferInfo::default()
                        .buffer(self.constants.buffer)
                        .range(144)])],
                &[],
            );
        }
        let uniform = self.renderer.restir.as_ref().unwrap().uniform_for_test(0);
        let mut bytes = uniform.read(restir::UNIFORM_BYTES as usize).unwrap();
        bytes[28..32].copy_from_slice(&aspect.to_le_bytes());
        bytes[84..88].copy_from_slice(&sequence.to_le_bytes());
        bytes[268..272].copy_from_slice(&1u32.to_le_bytes());
        for (word, value) in bytes[400..416]
            .as_chunks_mut::<4>()
            .0
            .iter_mut()
            .zip(jitter.into_iter().chain(jitter))
        {
            *word = value.to_le_bytes();
        }
        uniform.write(&bytes).unwrap();
        // Run the production host dispatch with production RR specialization and descriptors.
        self.renderer.pipeline = Some(rr);
        let variant = self.renderer.geometry.as_ref().unwrap().shader_variant();
        let raw_resolve = RawResolve::new(
            &context,
            self.renderer.pipeline.as_ref().unwrap().layout,
            variant,
        );
        let mut offsets = Vec::new();
        let mut byte_count = 0usize;
        for (_, _, stride) in CHANNELS {
            byte_count = byte_count.next_multiple_of(stride.max(4));
            offsets.push(byte_count);
            byte_count += (INPUT[0] * INPUT[1]) as usize * stride;
        }
        byte_count = byte_count.next_multiple_of(4);
        let display_offset = byte_count;
        byte_count += (OUTPUT[0] * OUTPUT[1] * 4) as usize;
        let readback = Buffer::new_readback(&context, byte_count as u64).unwrap();
        context
            .submit_named("restir_rr_production_images", |command| unsafe {
                let barrier = |source, destination, source_access, destination_access| {
                    context.device.cmd_pipeline_barrier(
                        command,
                        source,
                        destination,
                        vk::DependencyFlags::empty(),
                        &[vk::MemoryBarrier::default()
                            .src_access_mask(source_access)
                            .dst_access_mask(destination_access)],
                        &[],
                        &[],
                    );
                };
                barrier(
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
                    vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                );
                self.renderer.dispatch_restir_for_test(command);
                let pipeline = self.renderer.pipeline.as_ref().unwrap();
                // Raw FP32 resolve on identical selected reservoirs is the color reference.
                context.device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    raw_resolve.handle,
                );
                context
                    .device
                    .cmd_dispatch(command, INPUT[0].div_ceil(8), INPUT[1].div_ceil(8), 1);
                barrier(
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::AccessFlags::SHADER_WRITE,
                    vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                );
                context.device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    pipeline.reconstruction_display.unwrap(),
                );
                let mut push = Vec::with_capacity(64);
                for value in [INPUT[0], INPUT[1], OUTPUT[0], OUTPUT[1], 0, 0, 0, 0] {
                    push.extend(value.to_le_bytes());
                }
                for value in self.renderer.display.values {
                    push.extend(value.to_le_bytes());
                }
                context.device.cmd_push_constants(
                    command,
                    pipeline.layout,
                    vk::ShaderStageFlags::COMPUTE,
                    0,
                    &push,
                );
                context.device.cmd_dispatch(
                    command,
                    OUTPUT[0].div_ceil(8),
                    OUTPUT[1].div_ceil(8),
                    1,
                );
                barrier(
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::AccessFlags::SHADER_WRITE,
                    vk::AccessFlags::TRANSFER_READ,
                );
                let copy_image = |image: &Image, extent: [u32; 2], offset| {
                    context.device.cmd_copy_image_to_buffer(
                        command,
                        image.image,
                        vk::ImageLayout::GENERAL,
                        readback.buffer,
                        &[vk::BufferImageCopy::default()
                            .buffer_offset(offset as u64)
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
                };
                for (image, offset) in self.images.iter().zip(&offsets) {
                    copy_image(image, INPUT, *offset);
                }
                copy_image(&self.target, OUTPUT, display_offset);
                barrier(
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::HOST,
                    vk::AccessFlags::TRANSFER_WRITE,
                    vk::AccessFlags::HOST_READ,
                );
            })
            .unwrap();
        self.rr = self.renderer.pipeline.take();
        self.renderer.pipeline = Some(raw);
        let bytes = readback.read(byte_count).unwrap();
        Snapshot {
            channels: offsets
                .into_iter()
                .zip(CHANNELS)
                .map(|(offset, (_, _, stride))| {
                    bytes[offset..offset + (INPUT[0] * INPUT[1]) as usize * stride].to_vec()
                })
                .collect(),
            displayed: bytes[display_offset..].to_vec(),
        }
    }
}

fn glass(revision: u64) -> Scene {
    let mut interface = face(2.0);
    interface.media = [7, 0];
    interface.optics = Some(Optics {
        negative: Medium {
            ior: 1.5,
            extinction: [0.0; 3],
        },
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin: true,
    });
    let mut rear = face(6.0);
    rear.geometry.positions.reverse();
    scene(revision, vec![interface, face(0.0), rear])
}

fn mirror() -> Scene {
    let mut mirror = face(2.0);
    mirror.geometry.texture_id = 7;
    let mut rear = face(6.0);
    rear.geometry.positions.reverse();
    let mut result = scene(4, vec![mirror, rear]);
    let mut material = texture(1, 1, vec![255; 4]);
    material.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![255, 231, 0, 255])),
        ..Default::default()
    }));
    result.textures.insert(7, material);
    result
}

fn glass_mips(mips: bool) -> Scene {
    let mut interface = face(2.0);
    interface.media = [7, 0];
    interface.optics = Some(Optics {
        negative: Medium {
            ior: 1.5,
            extinction: [0.0; 3],
        },
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin: true,
    });
    let mut terminal = face(0.0);
    terminal.geometry.texture_id = 9;
    let mut rear = face(6.0);
    rear.geometry.positions.reverse();
    let mut result = scene(if mips { 6 } else { 5 }, vec![interface, terminal, rear]);
    let mut color = texture(64, 64, [200, 40, 20, 255].repeat(64 * 64));
    color.region = Some([0, 0, 64, 64]);
    if mips {
        color.sampling = Some(Arc::new(prime_scene::scene::TextureSampling {
            levels: [32, 16, 8, 4, 2, 1]
                .into_iter()
                .map(|width| prime_scene::scene::TextureLevel {
                    width,
                    height: width,
                    pixels: [20, 200, 40, 255].repeat((width * width) as usize).into(),
                    region: [0, 0, width, width],
                    next: [0, 0],
                })
                .collect(),
            next: [0, 0],
            blend: 0.0,
            coverage_frames: Arc::from([]),
        }));
    }
    result.textures.insert(9, color);
    result
}

fn unknown_motion(scene: &Scene) -> InstanceScene {
    let mut result = InstanceScene {
        epoch: scene.epoch,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    let triangles: Vec<_> = [
        [[0.0, 0.0, 3.0], [16.0, 0.0, 3.0], [16.0, 16.0, 3.0]],
        [[0.0, 0.0, 3.0], [16.0, 16.0, 3.0], [0.0, 16.0, 3.0]],
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
    result.prototypes.insert(
        1,
        prime_scene::Prototype {
            revision: 1,
            triangles: triangles.into(),
            bounds: [[0.0, 0.0, 3.0], [16.0, 16.0, 3.0]],
        },
    );
    result.instances.insert(
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
    result
}

#[test]
#[ignore = "windowless production ReSTIR RR images, PSR guides, jitter and native color composition"]
fn gpu_restir_rr_production_guides_and_resolve_images() {
    let mut fixture = Fixture::new();
    let instances = InstanceScene::default();
    let mut emissive = face(0.0);
    emissive.emission = Emission {
        radiance: [0.8, 0.3, 0.1],
        two_sided: true,
        textured: false,
    };
    for (label, scene) in [
        ("escape", scene(1, vec![])),
        ("opaque", scene(2, vec![emissive])),
        ("glass", glass(3)),
        ("mirror", mirror()),
    ] {
        let baseline = fixture.run(&scene, &instances, [0.125, -0.25], 17, 6);
        let mut statuses = std::collections::BTreeMap::new();
        for status in &baseline.channels[7] {
            *statuses.entry(*status).or_insert(0usize) += 1;
        }
        let depths = baseline.values(1);
        eprintln!(
            "ReSTIR RR {label} guides: status_counts={statuses:?}, linear_depth=[{}, {}]",
            depths.iter().copied().fold(f32::INFINITY, f32::min),
            depths.iter().copied().fold(f32::NEG_INFINITY, f32::max)
        );
        baseline.complete();
        baseline.static_motion();
        baseline.color_matches_raw_resolve();
        let foreground = label != "escape";
        assert!(
            baseline.channels[7]
                .iter()
                .all(|status| (status & 8 != 0) == foreground)
        );
        if label == "glass" {
            assert!(baseline.channels[7].iter().all(|status| status & 4 != 0));
            assert!(
                baseline
                    .values(1)
                    .iter()
                    .all(|depth| (*depth - 4.0).abs() < 0.002)
            );
            // Guide main/companion selection must not track random lighting reflection choices.
            for sequence in [19, 31, 103] {
                let changed = fixture.run(&scene, &instances, [0.125, -0.25], sequence, 6);
                for channel in [1, 2, 3, 4, 5, 7, 8, 9, 10] {
                    assert_eq!(
                        changed.channels[channel], baseline.channels[channel],
                        "{label} guide independence, binding {}",
                        CHANNELS[channel].0
                    );
                }
            }
        }
        let jittered = fixture.run(&scene, &instances, [-0.375, 0.333], 71, 6);
        jittered.complete();
        jittered.static_motion();
        jittered.color_matches_raw_resolve();
        eprintln!(
            "ReSTIR RR {label}: complete {} pixels, jitter motion finite/zero, composed color matches FP32 raw",
            INPUT[0] * INPUT[1]
        );
    }
    let mip0 = fixture.run(&glass_mips(false), &instances, [0.125, -0.25], 9, 6);
    let lower_mips = fixture.run(&glass_mips(true), &instances, [0.125, -0.25], 9, 6);
    for channel in [1, 2, 3, 4, 5, 7, 8, 9, 10] {
        assert_eq!(
            lower_mips.channels[channel], mip0.channels[channel],
            "ReSTIR LOD0 guide suffix, binding {}",
            CHANNELS[channel].0
        );
    }
    assert!(
        mip0.values(4)
            .as_chunks::<4>()
            .0
            .iter()
            .all(|p| p[0] > 2.0 * p[1])
    );
    let unresolved = fixture.run(&glass(7), &instances, [0.125, -0.25], 11, 1);
    unresolved.finite();
    assert!(unresolved.channels[7].iter().all(|status| status & 1 != 0));
    for channel in [2, 8] {
        assert!(
            unresolved
                .values(channel)
                .iter()
                .all(|motion| *motion == 0.0)
        );
    }
    let dynamic_scene = scene(9, vec![]);
    let dynamic = fixture.run(
        &dynamic_scene,
        &unknown_motion(&dynamic_scene),
        [0.125, -0.25],
        43,
        6,
    );
    dynamic.finite();
    assert!(
        dynamic.channels[7].iter().all(|status| *status == 11),
        "unknown accepted pose must remain a per-pixel pending guide"
    );
    for channel in [2, 8, 10] {
        assert!(dynamic.values(channel).iter().all(|value| *value == 0.0));
    }
}
