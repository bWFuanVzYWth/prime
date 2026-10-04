//! GPU behavior of the actual generation/replay/reconnection modules, with owned scene resources.
use super::*;
use prime_scene::{
    TextureMaterial,
    surface::{Emission, LayerMode, Medium, Optics, SurfaceDetail, SurfaceLayer},
    workers::CpuWorkers,
};
use realtime_tests::{camera, face, scene, texture};

struct TestPipeline {
    context: Arc<Context>,
    handle: vk::Pipeline,
}
impl Drop for TestPipeline {
    fn drop(&mut self) {
        unsafe { self.context.device.destroy_pipeline(self.handle, None) };
    }
}

fn self_shift(renderer: &Renderer, count: u32) -> Vec<[u32; 32]> {
    let code = match renderer.settings.light_sampling {
        LightSampling::Grid => {
            include_bytes!(concat!(env!("OUT_DIR"), "/restir_adapter.spv")).as_slice()
        }
        LightSampling::Tree => {
            include_bytes!(concat!(env!("OUT_DIR"), "/restir_adapter_tree.spv")).as_slice()
        }
        LightSampling::TreeSphere => {
            include_bytes!(concat!(env!("OUT_DIR"), "/restir_adapter_tree_sphere.spv")).as_slice()
        }
    };
    run_probe(renderer, count, code, [count, 0, 0, 0])
}

pub(super) fn run_probe(
    renderer: &Renderer,
    count: u32,
    code: &[u8],
    push: [u32; 4],
) -> Vec<[u32; 32]> {
    let context = &renderer.context;
    let layout = renderer.pipeline.as_ref().unwrap().layout;
    let spirv = ash::util::read_spv(&mut Cursor::new(code)).unwrap();
    let handle = unsafe {
        let module = context
            .device
            .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&spirv), None)
            .unwrap();
        let created = context.device.create_compute_pipelines(
            vk::PipelineCache::null(),
            &[vk::ComputePipelineCreateInfo::default()
                .layout(layout)
                .stage(
                    vk::PipelineShaderStageCreateInfo::default()
                        .stage(vk::ShaderStageFlags::COMPUTE)
                        .module(module)
                        .name(c"main"),
                )],
            None,
        );
        context.device.destroy_shader_module(module, None);
        created.unwrap()[0]
    };
    let test_pipeline = TestPipeline {
        context: context.clone(),
        handle,
    };
    let destination = Buffer::new(
        context,
        u64::from(count) * 128,
        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        true,
    )
    .unwrap();
    let production_uniform = renderer.restir.as_ref().unwrap().uniform_for_test(0);
    let mut constants = production_uniform
        .read(restir::UNIFORM_BYTES as usize)
        .unwrap();
    constants[384..392].copy_from_slice(&destination.address().to_le_bytes());
    let test_uniform =
        Buffer::upload(context, &constants, vk::BufferUsageFlags::UNIFORM_BUFFER).unwrap();
    let set = renderer.pipeline.as_ref().unwrap().descriptors[0];
    let update_uniform = |buffer: &Buffer| unsafe {
        let info = [vk::DescriptorBufferInfo::default()
            .buffer(buffer.buffer)
            .range(restir::UNIFORM_BYTES)];
        context.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(set)
                .dst_binding(23)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .buffer_info(&info)],
            &[],
        );
    };
    update_uniform(&test_uniform);
    let push = push.map(u32::to_le_bytes);
    context
        .submit_named("restir_adapter_self_shift", |command| unsafe {
            context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                test_pipeline.handle,
            );
            context.device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::COMPUTE,
                layout,
                0,
                &[
                    renderer.pipeline.as_ref().unwrap().descriptors[0],
                    renderer.atmosphere.as_ref().unwrap().descriptor(0),
                ],
                &[],
            );
            context.device.cmd_push_constants(
                command,
                layout,
                vk::ShaderStageFlags::COMPUTE,
                0,
                push.as_flattened(),
            );
            context
                .device
                .cmd_dispatch(command, count.div_ceil(64), 1, 1);
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::HOST_READ)],
                &[],
                &[],
            );
        })
        .unwrap();
    update_uniform(production_uniform);
    destination
        .read(count as usize * 128)
        .unwrap()
        .as_chunks::<128>()
        .0
        .iter()
        .map(|row| {
            std::array::from_fn(|i| u32::from_le_bytes(row[i * 4..i * 4 + 4].try_into().unwrap()))
        })
        .collect()
}

pub(super) fn lamp() -> prime_scene::surface::SurfaceFace {
    let mut lamp = face(3.);
    lamp.geometry.positions = [[12., 8., 3.], [12., 10., 3.], [14., 10., 3.], [14., 8., 3.]];
    lamp.emission = Emission {
        radiance: [4., 2., 1.],
        two_sided: true,
        textured: false,
    };
    lamp
}

pub(super) fn fixtures() -> Vec<(&'static str, Scene)> {
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    let mut mapped_rear = rear.clone();
    mapped_rear.geometry.texture_id = 8;
    let mut air = scene(1, vec![face(0.), mapped_rear, lamp()]);
    let mut normal_mapped = texture(1, 1, vec![255; 4]);
    normal_mapped.material = Some(Arc::new(TextureMaterial {
        normal: Some(texture(1, 1, vec![128, 128, 255, 0])),
        ..Default::default()
    }));
    air.textures.insert(8, normal_mapped);
    let mut submerged = face(0.);
    submerged.media = [0, 7];
    submerged.optics = Some(Optics {
        negative: Medium::default(),
        positive: Medium {
            ior: 1.5,
            extinction: [0.2, 0.4, 0.7],
        },
        ior_textures: [None; 2],
        transmit: false,
        thin: false,
    });
    let submerged_scene = scene(2, vec![submerged.clone(), rear.clone(), lamp()]);
    let mut interface = submerged.clone();
    interface.geometry.texture_id = 7;
    for position in &mut interface.geometry.positions {
        position[2] = 2.;
    }
    interface.optics.as_mut().unwrap().transmit = true;
    // Keep both sides inside the published cell and give refraction finite near-axis endpoints.
    let mut lower_lamp = face(1.);
    lower_lamp.emission = lamp().emission;
    let mut refractive = scene(5, vec![interface, rear.clone(), lamp(), lower_lamp]);
    let mut glass = texture(1, 1, vec![255; 4]);
    glass.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![80, 10, 0, 0])),
        ..Default::default()
    }));
    refractive.textures.insert(7, glass);
    submerged.geometry.positions.reverse();
    submerged.media = [7, 0];
    let optics = submerged.optics.as_mut().unwrap();
    std::mem::swap(&mut optics.negative, &mut optics.positive);
    let back = scene(3, vec![submerged, rear.clone(), lamp()]);
    let mut coating = face(0.);
    coating.detail = Some(Arc::new(SurfaceDetail {
        mode: LayerMode::OverlayBoth,
        layer: SurfaceLayer {
            colors: [[1.; 4]; 4],
            uvs: [[0.; 2]; 4],
            texture_id: 7,
            flags: 2,
            repeat: None,
            emission: Emission::default(),
        },
    }));
    let mut coated = scene(4, vec![coating, rear, lamp()]);
    let mut material = texture(1, 1, vec![30, 170, 90, 128]);
    material.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![80, 20, 0, 0])),
        ..Default::default()
    }));
    coated.textures.insert(7, material);
    vec![
        ("air", air),
        ("submerged", submerged_scene),
        ("backside submerged", back),
        ("stochastic coating", coated),
        ("rough refractive interface", refractive),
    ]
}

#[test]
#[ignore = "requires windowless Vulkan; production ReSTIR mixed integral and self-shift behavior"]
fn gpu_restir_generate_replay_self_shift_preserves_mixed_integral_and_media() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        ray_reconstruction: false,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut coverage_bits = [false; 2];
    let mut colored_endpoints = [0u32; 2];
    let mut normal_mapped_forced_nee = 0u32;
    for method in [
        LightSampling::Grid,
        LightSampling::Tree,
        LightSampling::TreeSphere,
    ] {
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        for (label, fixture) in fixtures() {
            renderer.render(&fixture, &camera(), 3, 3, 0).unwrap();
            let rows = self_shift(&renderer, 1024);
            let mut active = 0;
            let mut total = [0.; 3];
            let mut shifted_total = [0.; 3];
            let mut max_relative_error = 0_f32;
            let mut emitter_endpoints = [0u32; 2];
            let mut transmission_endpoints = 0u32;
            let mut colored_medium_paths = 0u32;
            let mut camera_absorption_paths = 0u32;
            for row in rows {
                let value = row.map(f32::from_bits);
                if label == "stochastic coating" {
                    coverage_bits[((row[14] >> 24) & 1) as usize] = true;
                }
                if value[3] == 0. {
                    continue;
                }
                active += 1;
                assert!(value[3].is_finite() && value[3] > 0.);
                if row[12] & 6 != 0 {
                    transmission_endpoints += 1;
                }
                let rc_length = (row[12] >> 8) & 255;
                let path_length = (row[12] >> 16) & 255;
                if value[19] > 1. && value[20..23].iter().all(|coefficient| *coefficient > 0.) {
                    colored_medium_paths += 1;
                }
                if label.contains("submerged") {
                    assert!(
                        (value[19] - 1.5).abs() < 1e-6,
                        "{method:?} {label}: replay lost known incident IOR {row:?}"
                    );
                    for (channel, extinction) in [0.2_f32, 0.4, 0.7].into_iter().enumerate() {
                        assert!(
                            (value[20 + channel] - extinction).abs() < 1e-6,
                            "{method:?} {label}: replay lost known colored medium {row:?}"
                        );
                        if rc_length <= 1 {
                            // The center camera is exactly four units above the receiver.
                            let expected = (-4. * extinction).exp();
                            assert!(
                                (value[16 + channel] - expected).abs() < 2e-5,
                                "{method:?} {label}: camera Beer attenuation not consumed {row:?}"
                            );
                        }
                    }
                    if rc_length <= 1 {
                        camera_absorption_paths += 1;
                    }
                }
                if method == LightSampling::TreeSphere
                    && label == "air"
                    && path_length == 1
                    && rc_length == 2
                    && (row[12] & 1) != 0
                {
                    normal_mapped_forced_nee += 1;
                }
                if rc_length == path_length + 1 && row[15] != u32::MAX {
                    let endpoint_type = (row[12] & 1) as usize;
                    emitter_endpoints[endpoint_type] += 1;
                    if label.contains("submerged") || label == "rough refractive interface" {
                        colored_endpoints[endpoint_type] += 1;
                    }
                }
                if rc_length <= 1 {
                    for channel in 0..4 {
                        assert!(
                            (value[16 + channel] - value[24 + channel]).abs() < 2e-5,
                            "{method:?} {label}: direct/full replay mismatch {row:?}"
                        );
                    }
                }
                for channel in 0..3 {
                    let original = value[channel];
                    let shifted = value[4 + channel] * value[7];
                    assert!(
                        original.is_finite()
                            && original >= 0.
                            && shifted.is_finite()
                            && shifted >= 0.,
                        "{method:?} {label}: nonfinite/negative response {row:?}"
                    );
                    max_relative_error = max_relative_error.max(
                        (original - shifted).abs() / original.abs().max(shifted.abs()).max(1e-8),
                    );
                    total[channel] += f64::from(original * value[3]);
                    shifted_total[channel] += f64::from(shifted * value[3]);
                }
            }
            eprintln!(
                "ReSTIR self-shift {method:?} {label}: active={active}, transmission_endpoints={transmission_endpoints}, colored_medium_paths={colored_medium_paths}, camera_absorption_paths={camera_absorption_paths}, emitter_endpoints_bsdf_nee={emitter_endpoints:?}, max_relative_error={max_relative_error}, original={total:?}, shifted={shifted_total:?}"
            );
            if label.contains("submerged") {
                assert!(colored_medium_paths > 0 && camera_absorption_paths > 0);
            }
            // Absorption and escape after refraction leave fewer positive samples in glass.
            // Require a substantial measured sample population and real transmission events.
            let minimum_active = if label == "rough refractive interface" {
                assert!(
                    transmission_endpoints > 0,
                    "{method:?} {label}: no positive transmission endpoint"
                );
                128
            } else {
                400
            };
            assert!(
                active > minimum_active,
                "{method:?} {label}: insufficient positive samples"
            );
            assert!(
                max_relative_error < 1e-3,
                "{method:?} {label}: an identity shift changes an individual path by >=0.1%"
            );
            for channel in 0..3 {
                let scale = total[channel].max(shifted_total[channel]).max(1e-12);
                assert!(
                    (total[channel] - shifted_total[channel]).abs() / scale < 0.005,
                    "{method:?} {label}: self-shift changes estimator mean by >=0.5%"
                );
            }
        }
    }
    assert!(
        coverage_bits.into_iter().all(|seen| seen),
        "both stochastic coating leaves exercised"
    );
    assert!(
        colored_endpoints.into_iter().all(|count| count > 0),
        "colored-medium finite emitters must exercise both BSDF-hit and NEE branches"
    );
    assert!(
        normal_mapped_forced_nee > 0,
        "SphereTree normal-mapped secondary receiver must exercise forced-emitter NEE replay"
    );
}
