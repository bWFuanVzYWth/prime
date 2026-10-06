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
    let code = prime_shader_tests::restir_adapter_tree();
    run_probe(renderer, count, code, [count, 0, 0, 0])
}

#[test]
#[ignore = "requires windowless Vulkan; actual new-budget source and old-budget inverse support"]
fn gpu_restir_inverse_budget_support_rejects_only_unsupported_actual_sources() {
    let config = RenderSettings {
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        ..restir_tests::settings(RenderMode::Offline)
    };
    let mut renderer = restir_tests::renderer(config);
    let fixture = normal_measure_scene(1, None, false, true);
    renderer.render(&fixture, &camera(), 3, 3, 0).unwrap();
    let code = prime_shader_tests::restir_adapter_tree();
    let source = run_probe(&renderer, 4096, code, [4096, 0, 6, 5]);
    let artifact = std::env::var_os("PRIME_RESTIR_PROBE_ARTIFACT").map(std::path::PathBuf::from);
    let mut unsupported_direct = 0;
    for budget in [1u32, 2, 6, 12] {
        let rows = run_probe(&renderer, 4096, code, [4096, 0, budget, 5]);
        if let Some(path) = &artifact {
            std::fs::create_dir_all(path).unwrap();
            let bytes: Vec<_> = rows
                .iter()
                .flatten()
                .flat_map(|word| word.to_le_bytes())
                .collect();
            std::fs::write(path.join(format!("budget-{budget}.bin")), bytes).unwrap();
        }
        let mut active = 0;
        let mut unsupported = 0;
        let mut supported_positive = 0;
        for (row, original) in rows.iter().zip(&source) {
            assert_eq!(
                &row[..4],
                &original[..4],
                "source changed with target budget"
            );
            assert_eq!(&row[12..16], &original[12..16]);
            let values = row.map(f32::from_bits);
            if values[3] <= 0. || row[..3].iter().all(|word| *word == 0) {
                continue;
            }
            active += 1;
            let path_length = (row[12] >> 16) & 255;
            let nee = row[12] & 1 != 0;
            let fits = path_length + u32::from(!nee) < budget;
            if !fits {
                unsupported += 1;
                assert_eq!(&row[4..8], &[0; 4], "unsupported inverse: {budget} {row:?}");
                if (row[12] >> 8) & 255 == 1 {
                    unsupported_direct += 1;
                }
            } else if values[7] > 0. && values[4..7].iter().any(|value| *value > 0.) {
                supported_positive += 1;
            }
            assert!(values[4..8].iter().all(|value| value.is_finite()));
            if budget == 6 {
                assert_eq!(&row[4..8], &original[4..8], "unchanged supported inverse");
            }
        }
        eprintln!(
            "budget={budget} active={active} unsupported={unsupported} supported_positive={supported_positive}"
        );
        assert!(active > 32 && supported_positive > 8);
        if budget == 1 {
            assert!(unsupported > 8);
        }
    }
    assert!(
        unsupported_direct > 8,
        "must reach the actual RC1 inverse bypass"
    );
}

fn medium_crossing_scene(explicit_source_water: bool) -> Scene {
    let water = Medium {
        ior: 1.5,
        extinction: [0.03, 0.07, 0.11],
    };
    let mut left = face(0.);
    left.geometry.positions[1][0] = 8.;
    left.geometry.positions[2][0] = 8.;
    left.optics = Some(Optics {
        negative: if explicit_source_water {
            water
        } else {
            Medium::default()
        },
        positive: water,
        ior_textures: [None; 2],
        transmit: false,
        thin: false,
    });
    if explicit_source_water {
        // An authored in-water opaque primary, independent of hardware-facing side.
        // The default author configuration remains a distinct changed-medium witness.
        left.media = [7, 7];
    }
    let mut right = face(0.);
    right.geometry.positions[0][0] = 8.;
    right.geometry.positions[3][0] = 8.;
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    let mut boundary = face(0.);
    boundary.geometry.positions = [[8., 0., -1.], [8., 16., -1.], [8., 16., 8.], [8., 0., 8.]];
    boundary.geometry.texture_id = 7;
    boundary.optics = Some(Optics {
        negative: water,
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin: false,
    });
    let mut left_lamp = lamp();
    for position in &mut left_lamp.geometry.positions {
        position[0] -= 10.;
    }
    let mut fixture = scene(
        1 + u64::from(explicit_source_water),
        vec![left, right, rear, boundary, lamp(), left_lamp],
    );
    let mut glass = texture(1, 1, vec![255; 4]);
    glass.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![255, 10, 0, 0])),
        ..Default::default()
    }));
    fixture.textures.insert(7, glass);
    fixture
}

#[test]
#[ignore = "requires windowless Vulkan; actual static medium crossing and source-suffix cache support"]
fn gpu_restir_static_connection_terminal_medium_and_suffix_cache_match_fresh() {
    let mut config = restir_tests::settings(RenderMode::Realtime);
    config.sun = 1. / 256.;
    config.sky = 1. / 256.;
    config.restir_spatial_only = true;
    config.restir.spatial_reuse = false;
    config.restir.duplicate_map = false;
    config.restir.decoupled_shading = false;
    let mut renderer = restir_tests::renderer(config);
    let artifact = std::env::var_os("PRIME_RESTIR_PROBE_ARTIFACT").map(std::path::PathBuf::from);
    let width = 31;
    let height = 17;
    let count = width * height;
    let code = prime_shader_tests::restir_medium_support_tree();
    let mut ambiguity_coverage = 0;
    let mut actual_nonvacuum_sources = 0;
    let mut source_cache_invariants = 0;
    let mut changed_medium_caches = 0;
    for explicit_source_water in [false, true] {
        let fixture = medium_crossing_scene(explicit_source_water);
        let label = if explicit_source_water {
            "water"
        } else {
            "default"
        };
        for (sequence, source_x, delta) in [(0, 2., 12_f32), (1, 14., -12_f32)] {
            let mut source_camera = camera();
            source_camera.position[0] = source_x;
            source_camera.vertical_fov_radians = 0.16;
            renderer
                .render(&fixture, &source_camera, width, height, sequence)
                .unwrap();
            let automatic = run_probe(&renderer, count, code, [count, 0, delta.to_bits(), 0]);
            let fresh = run_probe(&renderer, count, code, [count, 0, delta.to_bits(), 1]);
            if let Some(path) = &artifact {
                std::fs::create_dir_all(path).unwrap();
                for (consumer, rows) in [("automatic", &automatic), ("fresh", &fresh)] {
                    let bytes: Vec<_> = rows
                        .iter()
                        .flatten()
                        .flat_map(|word| word.to_le_bytes())
                        .collect();
                    std::fs::write(
                        path.join(format!("medium-{label}-{source_x}-{consumer}.bin")),
                        bytes,
                    )
                    .unwrap();
                }
            }
            let mut observed = 0;
            let mut crossing_positive = 0;
            let mut ambiguous_positive = 0;
            let mut nonvacuum_sources = 0;
            let mut cache_invariants = 0;
            let mut medium_cache_changes = 0;
            let mut maximum_error = 0_f64;
            for (row, oracle) in automatic.iter().zip(&fresh) {
                assert_eq!(
                    &row[..4],
                    &oracle[..4],
                    "the same actual source must reach both consumers"
                );
                if row[0] & 4 == 0 {
                    continue;
                }
                observed += 1;
                let value = row.map(f32::from_bits);
                let reference = oracle.map(f32::from_bits);
                assert!(
                    value[4..16]
                        .iter()
                        .all(|value| value.is_finite() && *value >= 0.)
                );
                assert!(row[0] & (1 << 8 | 1 << 9) != 0, "declared starting medium");
                let starts_water = row[0] & (1 << 8) != 0;
                let crossing = row[0] & 1 != 0;
                let target_water = value[20] < 8.;
                let terminal_water = if crossing { target_water } else { starts_water };
                let expected_medium = if terminal_water {
                    [1.5, 0.03, 0.07, 0.11]
                } else {
                    [1., 0., 0., 0.]
                };
                if value[8..11].iter().all(|value| *value > 0.) {
                    for (actual, expected) in [value[11], value[12], value[13], value[14]]
                        .into_iter()
                        .zip(expected_medium)
                    {
                        assert!((actual - expected).abs() < 1e-6, "terminal {row:?}");
                    }
                    let p: [f64; 3] = std::array::from_fn(|i| f64::from(value[16 + i]));
                    let q: [f64; 3] = std::array::from_fn(|i| f64::from(value[20 + i]));
                    let sign = (q[2] - p[2]).signum();
                    // All observed opaque endpoints are horizontal. Reconstruct both safe
                    // endpoints independently in f64 before the authored x=8 volume split.
                    let dz = q[2] - p[2] - sign * f64::from(value[24] + value[25]);
                    let length =
                        ((q[0] - p[0]).powi(2) + (q[1] - p[1]).powi(2) + dz.powi(2)).sqrt();
                    let fraction = if !crossing {
                        if starts_water { 1. } else { 0. }
                    } else {
                        // A crossed authored boundary determines both segment media.
                        // Its from/to pair overrides the caller's initial fallback;
                        // only a segment with no boundary uses replay.medium throughout.
                        let t = (8. - p[0]) / (q[0] - p[0]);
                        if p[0] < 8. { t } else { 1. - t }
                    };
                    let water_length = length * fraction;
                    for (a, b, difference) in [(0, 1, 0.03_f64 - 0.07), (1, 2, 0.07_f64 - 0.11)] {
                        let actual = f64::from(value[8 + a]) / f64::from(value[8 + b]);
                        let expected = (-difference * water_length).exp();
                        assert!((actual / expected - 1.).abs() < 3e-4, "Beer ratio {row:?}");
                    }
                }
                if row[0] & 1 != 0 && value[7] > 0. {
                    crossing_positive += 1;
                }
                if row[0] & 2 != 0 && value[7] > 0. {
                    ambiguous_positive += 1;
                }
                assert_eq!(value[7] > 0., reference[7] > 0., "support must match fresh");
                if value[7] > 0. {
                    assert_eq!(row[0] & (1 << 7) != 0, row[1] & (1 << 31) != 0);
                    nonvacuum_sources += usize::from(row[1] & (1 << 31) != 0);
                    let source_observed = row[0] & (1 << 3) != 0;
                    let same_side = row[0] & (1 << 5) != 0;
                    let same_wi = oracle[0] & (1 << 6) != 0;
                    let same_medium = [value[3], value[19], value[29], value[30]]
                        .into_iter()
                        .zip(value[11..15].iter().copied())
                        .all(|(a, b)| a == b);
                    assert_eq!(same_medium, row[0] & (1 << 4) != 0);
                    // Source-cache equality requires measured incoming medium, opaque
                    // facing and fixed suffix Wi. Physical region alone is insufficient.
                    let invariant = source_observed && same_side && same_wi && same_medium;
                    let changed_medium = source_observed && same_side && same_wi && !same_medium;
                    cache_invariants += usize::from(invariant);
                    let mut cache_changed = false;
                    for (old, refreshed) in [value[15], value[23], value[31]]
                        .into_iter()
                        // Compare with the actual forced refresh, including the
                        // vacuum-to-vacuum cases that automatically reuse old RGB.
                        .zip([reference[26], reference[27], reference[28]])
                    {
                        let error =
                            (old - refreshed).abs() / old.abs().max(refreshed.abs()).max(1e-8);
                        cache_changed |= error >= 1e-3;
                        if invariant {
                            assert!(error < 1e-3, "same-medium source incident cache: {row:?}");
                        }
                    }
                    medium_cache_changes += usize::from(changed_medium && cache_changed);
                }
                for (a, b) in value[4..8].iter().zip(&reference[4..8]) {
                    let error =
                        f64::from((a - b).abs()) / f64::from(a.abs().max(b.abs()).max(1e-8));
                    maximum_error = maximum_error.max(error);
                    assert!(
                        error < 1e-3,
                        "automatic cache must match fresh: {row:?} {oracle:?}"
                    );
                }
            }
            ambiguity_coverage += ambiguous_positive;
            actual_nonvacuum_sources += nonvacuum_sources;
            source_cache_invariants += cache_invariants;
            changed_medium_caches += medium_cache_changes;
            eprintln!(
                "medium author={label} source_x={source_x} observed={observed} crossing_positive={crossing_positive} ambiguous_positive={ambiguous_positive} actual_nonvacuum_sources={nonvacuum_sources} source_cache_invariants={cache_invariants} changed_medium_caches={medium_cache_changes} max_relative={maximum_error}"
            );
            assert!(
                observed > 16 && crossing_positive > 8,
                "actual static-crossing coverage"
            );
        }
    }
    assert!(
        ambiguity_coverage > 8,
        "must refresh actual ambiguous connections"
    );
    assert!(actual_nonvacuum_sources > 8, "actual source bit31 coverage");
    assert!(
        source_cache_invariants > 8,
        "complete measured source-cache premises"
    );
    assert!(
        changed_medium_caches > 8,
        "actual changed-medium cache coverage"
    );
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
            material_thin: false,
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
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut coverage_bits = [false; 2];
    let mut colored_endpoints = [0u32; 2];
    {
        let method = LightSampling::Tree;
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
}

fn normal_measure_scene(revision: u64, normal: Option<[u8; 4]>, glossy: bool, near: bool) -> Scene {
    let mut front = face(0.);
    front.geometry.texture_id = 8;
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    rear.geometry.texture_id = 8;
    let mut emitter = lamp();
    if near {
        // At the center primary hit, BSDF connections to this light have distance ~.05,
        // grazing geometric cosines and a much larger tilted shading cosine. The primary
        // ray misses the light, and source/destination use exactly the same scene/camera.
        emitter.geometry.positions = [
            [8.03, 7.98, 0.015],
            [8.03, 8.02, 0.015],
            [8.10, 8.02, 0.015],
            [8.10, 7.98, 0.015],
        ];
    }
    let mut fixture = scene(revision, vec![front, rear, emitter]);
    let mut material = texture(1, 1, vec![255; 4]);
    material.material = Some(Arc::new(TextureMaterial {
        normal: normal.map(|value| texture(1, 1, value.to_vec())),
        specular: glossy.then(|| texture(1, 1, vec![175, 50, 0, 0])),
        ..Default::default()
    }));
    fixture.textures.insert(8, material);
    fixture
}

#[test]
#[ignore = "requires windowless Vulkan; tilted normal and geometric self-shift measure oracle"]
fn gpu_restir_self_shift_preserves_strong_normal_and_grazing_measures() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut grazing = camera();
    grazing.forward = [0.8, 0., -0.6];
    grazing.right = [0.6, 0., 0.8];
    let cases = [
        ("flat", None, false, false, camera(), 1024),
        (
            "tilt-x",
            Some([215, 128, 255, 0]),
            false,
            false,
            camera(),
            1024,
        ),
        (
            "tilt-y-glossy",
            Some([128, 215, 255, 0]),
            true,
            false,
            camera(),
            1024,
        ),
        ("grazing-flat", None, false, false, grazing, 1024),
        (
            "grazing-tilt-x",
            Some([215, 128, 255, 0]),
            false,
            false,
            grazing,
            1024,
        ),
        ("near-flat", None, false, true, camera(), 4096),
        (
            "near-tilt-x",
            Some([215, 128, 255, 0]),
            false,
            true,
            camera(),
            4096,
        ),
    ];
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = prime_shader_tests::restir_adapter_tree();
        for (index, (label, normal, glossy, near, camera, count)) in cases.iter().enumerate() {
            let fixture = normal_measure_scene(1000 + index as u64, *normal, *glossy, *near);
            renderer.render(&fixture, camera, 3, 3, 0).unwrap();
            let rows = run_probe(&renderer, *count, code, [*count, 0, 0, 1]);
            if let Some(output) = &output {
                let bytes: Vec<_> = rows
                    .iter()
                    .flatten()
                    .flat_map(|word| word.to_le_bytes())
                    .collect();
                std::fs::write(output.join(format!("{method:?}-{label}.bin")), bytes).unwrap();
            }
            let mut active = 0;
            let mut finite = 0;
            let mut query_arrivals = 0;
            let mut terminal_nee = 0;
            let mut mapped = 0;
            let mut rejected = 0;
            let mut normal_support_rejected = 0;
            let mut wrong_measure = 0;
            let mut changed_integral = 0;
            let mut max_j_error = 0_f64;
            let mut max_integral_error = 0_f64;
            let mut max_source_g_error = 0_f64;
            let mut examples = Vec::new();
            for (sample, row) in rows.iter().enumerate() {
                let value = row.map(|word| f64::from(f32::from_bits(word)));
                if value[3] == 0. {
                    continue;
                }
                active += 1;
                let rc_length = (row[12] >> 8) & 255;
                let path_length = (row[12] >> 16) & 255;
                let emitter = rc_length == path_length + 1;
                let nee = row[12] & 1 != 0;
                let mut displacement = [value[20], value[21], value[22]];
                let physical_distance2 = displacement.into_iter().map(|v| v * v).sum::<f64>();
                let has_measure = physical_distance2 > 0. && value[16] > 0.;
                let mut normal_support = false;
                let mut g = 0.;
                let mut inverse_geom = 0.;
                let mut inverse_shading = 0.;
                if has_measure {
                    finite += 1;
                    // Independent area-to-solid-angle Jacobian for these horizontal quads:
                    // terminal NEE uses the physical chart; incoming BSDF connections use
                    // the safe-origin chart. Only diagnostic raw values feed this host oracle.
                    assert!((value[23].abs() - 1.).abs() < 1e-5);
                    assert!((value[27].abs() - 1.).abs() < 1e-5);
                    assert!(value[31].is_finite() && value[31] >= 0.);
                    if emitter && nee {
                        terminal_nee += 1;
                    } else {
                        query_arrivals += 1;
                        let sign = (value[23] * displacement[2]).signum();
                        displacement[2] -= sign * value[23] * value[31];
                    }
                    let distance2 = displacement.into_iter().map(|v| v * v).sum::<f64>();
                    let distance = distance2.sqrt();
                    g = (value[27] * displacement[2]).abs() / (distance2 * distance);
                    let geom_cos = (value[23] * displacement[2]).abs() / distance;
                    let shading_cos = (0..3)
                        .map(|i| value[24 + i] * displacement[i])
                        .sum::<f64>()
                        .abs()
                        / distance;
                    inverse_geom = distance2 / geom_cos;
                    inverse_shading = distance2 / shading_cos;
                    // View correction reduces the authored slope at grazing incidence.
                    // Still require a real difference from the geometric normal.
                    if value[26].abs() < 0.99 {
                        mapped += 1;
                    }
                    max_source_g_error = max_source_g_error.max((g / value[16] - 1.).abs());
                    let inverse_pdf = value[19]; // Includes 1/(2*pi) for BSDF-hit emitters.
                    let first_rough = emitter && !nee || value[18] <= 1. / (value[30] * value[30]);
                    normal_support = !(emitter && nee)
                        && first_rough
                        && value[18] > 0.
                        && inverse_pdf > 0.
                        && 1. / (value[18] * g) >= value[17]
                        && inverse_geom / inverse_pdf >= value[17]
                        && inverse_shading / inverse_pdf < value[17];
                    if value[7] > 0. {
                        let expected_j = g / value[16];
                        if (value[7] - expected_j).abs() / expected_j > 2e-5
                            || (value[29] - g).abs() / g > 2e-5
                        {
                            wrong_measure += 1;
                        }
                    }
                }
                let invalid = !(value[7] > 0.) || !value[7].is_finite();
                if invalid {
                    rejected += 1;
                }
                if invalid && normal_support {
                    normal_support_rejected += 1;
                }
                let j_error = (value[7] - 1.).abs();
                max_j_error = max_j_error.max(j_error);
                let mut integral_error = 0_f64;
                for channel in 0..3 {
                    let original = value[channel];
                    let shifted = value[4 + channel] * value[7];
                    if !original.is_finite()
                        || !shifted.is_finite()
                        || original < 0.
                        || shifted < 0.
                    {
                        integral_error = f64::INFINITY;
                    } else {
                        integral_error = integral_error.max(
                            (original - shifted).abs()
                                / original.abs().max(shifted.abs()).max(1e-8),
                        );
                    }
                }
                max_integral_error = max_integral_error.max(integral_error);
                if integral_error >= 1e-3 {
                    changed_integral += 1;
                }
                if (invalid || integral_error >= 1e-3 || j_error >= 1e-3) && examples.len() < 4 {
                    examples.push(format!("sample={sample} seed={} rc={rc_length} path={path_length} nee={nee} physicalG={g} inverseGeom={inverse_geom} inverseShading={inverse_shading} normalSupport={normal_support} raw={row:?}", row[13]));
                }
            }
            eprintln!(
                "ReSTIR normal-measure {method:?} {label}: active={active} finite={finite} query_arrivals={query_arrivals} terminal_nee={terminal_nee} mapped={mapped} rejected={rejected} normal_support_rejected={normal_support_rejected} wrong_measure={wrong_measure} changed_integral={changed_integral} maxJError={max_j_error} maxIntegralError={max_integral_error} maxSourceGError={max_source_g_error}"
            );
            for example in examples {
                eprintln!("{method:?} {label}: {example}");
            }
            if active < 128
                || finite < 64
                || query_arrivals == 0
                || terminal_nee == 0
                || normal.is_some() && mapped < 16
                || rejected != 0
                || wrong_measure != 0
                || changed_integral != 0
                || max_j_error >= 1e-3
            {
                failures.push(format!("{method:?} {label}: insufficient coverage or identity/support/measure violation; see complete records and diagnostics"));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
#[ignore = "requires windowless Vulkan; separates near-emitter sampling and physical measures"]
fn gpu_restir_near_emitter_residual_matches_ray_origin_and_bsdf_measures() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        renderer
            .render(
                &normal_measure_scene(1005, None, false, true),
                &camera(),
                3,
                3,
                0,
            )
            .unwrap();
        let code = prime_shader_tests::restir_adapter_tree();
        // The actual failing near-flat path, retained as a deterministic counterexample.
        // sampleOffset is consumed once by restirScene's copied current frame.
        let [measure]: [[u32; 32]; 1] = run_probe(&renderer, 1, code, [1, 3498, 0, 1])
            .try_into()
            .unwrap();
        let [scatter]: [[u32; 32]; 1] = run_probe(&renderer, 1, code, [1, 3498, 0, 2])
            .try_into()
            .unwrap();
        if let Some(output) = &output {
            let bytes: Vec<_> = [measure, scatter]
                .into_iter()
                .flatten()
                .flat_map(u32::to_le_bytes)
                .collect();
            std::fs::write(output.join(format!("{method:?}-origin-measure.bin")), bytes).unwrap();
        }
        assert_eq!(&measure[..8], &scatter[..8]);
        assert_eq!(&measure[12..16], &scatter[12..16]);
        assert_eq!(measure[13], 2_224_462_880, "counterexample seed changed");
        assert_eq!((measure[12] >> 8) & 255, 1);
        assert_eq!((measure[12] >> 16) & 255, 0);
        assert_eq!(measure[12] & 1, 0, "requires direct BSDF-hit emitter");
        let m = measure.map(|v| f64::from(f32::from_bits(v)));
        let s = scatter.map(|v| f64::from(f32::from_bits(v)));
        assert!(m[3] > 0. && m[7] > 0.);
        let close = |actual: f64, expected: f64| {
            assert!(actual.is_finite() && expected.is_finite() && expected > 0.);
            assert!(
                (actual - expected).abs() / expected < 2e-5,
                "{method:?}: actual={actual} expected={expected}"
            );
        };
        // Independently derive the endpoint area/solid-angle measure from displacement.
        let d2 = m[20..23].iter().map(|v| v * v).sum::<f64>();
        let physical_g = (m[27] * m[22]).abs() / (d2 * d2.sqrt());
        let mut query_displacement = [m[20], m[21], m[22]];
        query_displacement[2] -= (m[23] * m[22]).signum() * m[23] * m[31];
        let query_d2 = query_displacement.into_iter().map(|v| v * v).sum::<f64>();
        let query_g = (m[27] * query_displacement[2]).abs() / (query_d2 * query_d2.sqrt());
        close(s[10], m[16]); // Exact source scatter + safe origin reproduce original G.
        close(s[11], physical_g);
        close(m[7], query_g / m[16]);
        close(m[7], 1.);
        close(m[18], s[19]); // Shift's incoming query PDF now matches the source sample.
        close(s[8], s[19]); // Evaluation at the sampled direction matches its source PDF.
        let power_mis = |pdf: f64, light_pdf: f64| pdf * pdf / (pdf * pdf + light_pdf * light_pdf);
        let mis_ratio = power_mis(s[27], s[9]) / power_mis(s[19], s[23]);
        let geometry_ratio = physical_g / m[16];
        let mut response_ratios = [0.; 3];
        let mut measured_ratios = [0.; 3];
        let mut predicted_ratios = [0.; 3];
        for channel in 0..3 {
            close(s[28 + channel], s[20 + channel]);
            response_ratios[channel] = s[24 + channel] / s[20 + channel];
            measured_ratios[channel] = m[4 + channel] * m[7] / m[channel];
            predicted_ratios[channel] = response_ratios[channel] * mis_ratio * geometry_ratio;
            close(measured_ratios[channel], 1.);
        }
        // Keep the original counterfactual physical-measure discrepancy observable,
        // while the actual source-consistent shift must preserve the individual path.
        assert!(
            predicted_ratios
                .iter()
                .any(|ratio| (ratio - 1.).abs() > 1e-3)
        );
        eprintln!(
            "ReSTIR near origin {method:?}: seed={} sampled_direction={:?} physical_displacement={:?} surface_offset={} source_pdf={} physical_pdf={} source_light_pdf={} physical_light_pdf={} physical_geometry_ratio={geometry_ratio} physical_mis_ratio={mis_ratio} physical_response_ratios={response_ratios:?} actual_shift_ratios={measured_ratios:?} counterfactual_physical_ratios={predicted_ratios:?}",
            measure[13],
            &s[16..19],
            &m[20..23],
            s[31],
            s[19],
            s[27],
            s[23],
            s[9]
        );
        if method == LightSampling::Tree {
            let side_rows = run_probe(&renderer, 3, code, [3, 0, 0, 3]);
            if let Some(output) = &output {
                let bytes: Vec<_> = side_rows
                    .iter()
                    .flatten()
                    .flat_map(|word| word.to_le_bytes())
                    .collect();
                std::fs::write(output.join("spawn-chart-sides.bin"), bytes).unwrap();
            }
            for (index, row) in side_rows.iter().enumerate() {
                let value = row.map(f32::from_bits);
                assert_eq!(
                    &row[16..18],
                    &[1, 0],
                    "physical/query spawn sides must differ"
                );
                assert_eq!(value[2], value[3]);
                assert_eq!(value[6], -value[3]);
                assert!(value[15] < 0.);
                if index == 1 {
                    assert_eq!(&row[18..20], &[0, 1]);
                } else {
                    assert_eq!(value[11], 0.);
                    assert_eq!(
                        &row[18..20],
                        &[0, 0],
                        "equal transmission events do not prove the same safe origin at tangency"
                    );
                }
                eprintln!(
                    "ReSTIR spawn chart sample={index} begin={:?} late={:?} side_and_transmission={:?}",
                    &value[..3],
                    &value[4..7],
                    &row[16..20]
                );
            }
        }
    }
}

#[test]
#[ignore = "requires windowless Vulkan; view-dependent final-emitter competing PDF"]
fn gpu_restir_rc_view_change_preserves_final_emitter_competing_pdf() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    rear.geometry.texture_id = 8;
    let mut other_lamp = lamp();
    other_lamp.geometry.positions = [[6., 10., 3.], [6., 12., 3.], [8., 12., 3.], [8., 10., 3.]];
    // Two non-collinear leaves are necessary: one emitter's selection PMF is always1.
    let mut fixture = scene(2001, vec![face(0.), rear, lamp(), other_lamp]);
    let mut material = texture(1, 1, vec![255; 4]);
    material.material = Some(Arc::new(TextureMaterial {
        normal: Some(texture(1, 1, vec![215, 128, 255, 0])),
        ..Default::default()
    }));
    fixture.textures.insert(8, material);
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    let relative_error = |a: f64, b: f64| (a - b).abs() / a.abs().max(b.abs()).max(1e-8);
    let mis = |bsdf: f64, light: f64| {
        let scale = bsdf.max(light);
        let a = bsdf / scale;
        let b = light / scale;
        a * a / (a * a + b * b)
    };
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        renderer.render(&fixture, &camera(), 3, 3, 0).unwrap();
        let code = prime_shader_tests::restir_rc_view_tree();
        let mut valid = 0;
        let mut normal_changed = 0;
        let mut pdf_changed = 0;
        let mut changed_integral = 0;
        let mut rejected = 0;
        let mut max_error = 0_f64;
        let mut examples = Vec::new();
        for direction in 0..2 {
            // One shift in each invocation keeps the two megakernels out of the same
            // optimizer graph. Source records must match before comparing the targets.
            let cached_rows = run_probe(&renderer, 4096, code, [4096, 0, direction, 0]);
            let fresh_rows = run_probe(&renderer, 4096, code, [4096, 0, direction, 1]);
            if let Some(output) = &output {
                for (label, rows) in [("cached", &cached_rows), ("fresh", &fresh_rows)] {
                    let bytes: Vec<_> = rows
                        .iter()
                        .flatten()
                        .flat_map(|word| word.to_le_bytes())
                        .collect();
                    std::fs::write(
                        output.join(format!("{method:?}-rc-view-{direction}-{label}.bin")),
                        bytes,
                    )
                    .unwrap();
                }
            }
            for (sample, (row, fresh_row)) in cached_rows.iter().zip(&fresh_rows).enumerate() {
                assert_eq!(&row[..4], &fresh_row[..4], "source identity changed");
                if row[2] == 0 {
                    continue;
                }
                for index in (8..13).chain(14..19).chain(20..27).chain([31]) {
                    assert_eq!(
                        row[index], fresh_row[index],
                        "source/probe input changed at word {index}: {row:?} {fresh_row:?}"
                    );
                }
                let value = row.map(|word| f64::from(f32::from_bits(word)));
                let fresh_value = fresh_row.map(|word| f64::from(f32::from_bits(word)));
                let cached_valid = value[7] > 0.;
                let fresh_valid = fresh_value[7] > 0.;
                if !cached_valid || !fresh_valid {
                    rejected += 1;
                    if cached_valid != fresh_valid {
                        failures.push(format!(
                            "{method:?} direction={direction} sample={sample} seed={}: cached/fresh support differs {row:?} {fresh_row:?}",
                            row[1]
                        ));
                    }
                    continue;
                }
                valid += 1;
                assert_eq!((row[0] >> 8) & 255, 1);
                assert_eq!((row[0] >> 16) & 255, 1);
                assert_eq!(row[0] & 1, 0);
                assert!(value[4..].iter().all(|x| x.is_finite()));
                assert!(fresh_value[4..].iter().all(|x| x.is_finite()));
                assert!(value[12] > 0. && fresh_value[13] > 0. && value[15] > 0. && value[16] > 0.);
                assert!(
                    relative_error(value[12], value[14]) < 2e-5,
                    "source inverse PDF {row:?}"
                );
                assert!(
                    relative_error(fresh_value[13], value[15]) < 2e-5,
                    "current inverse PDF {fresh_row:?}"
                );
                assert!(
                    relative_error(value[7], fresh_value[7]) < 2e-5,
                    "incoming query J changed {row:?} {fresh_row:?}"
                );
                assert!(
                    value[27] < 1e-3 && fresh_value[27] < 1e-3,
                    "static final-emitter incident radiance changed {row:?} {fresh_row:?}"
                );
                let expected_cached_mis = mis(value[16], value[12]);
                let expected_fresh_mis = mis(value[16], value[15]);
                let expected_ratio = expected_fresh_mis / expected_cached_mis;
                assert!(relative_error(value[31], expected_ratio) < 2e-5);
                normal_changed += u32::from(value[17] > 1e-3);
                pdf_changed += u32::from(relative_error(value[12], value[15]) > 1e-3);
                let mut error = 0_f64;
                for channel in 0..3 {
                    let cached = value[4 + channel] * value[7];
                    let fresh = fresh_value[4 + channel] * fresh_value[7];
                    assert!(
                        relative_error(value[28 + channel], fresh_value[28 + channel]) < 1e-3,
                        "static final-emitter incident changed {row:?} {fresh_row:?}"
                    );
                    error = error.max(relative_error(cached, fresh));
                    if relative_error(cached, fresh) >= 1e-3 && cached > 0. {
                        // Diagnose a failure through an independently computed MIS factor;
                        // the permanent property below requires the actual integrals to agree.
                        assert!(
                            relative_error(fresh / cached, expected_ratio) < 2e-5,
                            "view mismatch is not explained by the cached competing PDF {row:?} {fresh_row:?}"
                        );
                    }
                }
                max_error = max_error.max(error);
                if error >= 1e-3 {
                    changed_integral += 1;
                    if examples.len() < 4 {
                        examples.push(format!(
                            "direction={direction} sample={sample} seed={} error={error} pdf={:?} normal_source={:?} normal_current={:?} expectedMisRatio={expected_ratio} cached={row:?} fresh={fresh_row:?}",
                            row[1], &value[12..16], &value[20..23], &value[24..27]
                        ));
                    }
                }
            }
        }
        eprintln!(
            "ReSTIR RC-view {method:?}: valid={valid} rejected={rejected} normal_changed={normal_changed} pdf_changed={pdf_changed} changed_integral={changed_integral} maxIntegralError={max_error}"
        );
        for example in examples {
            eprintln!("{method:?} {example}");
        }
        if valid < 8 || normal_changed == 0 || pdf_changed != 0 || changed_integral != 0 {
            failures.push(format!(
                "{method:?}: valid={valid} normal_changed={normal_changed} pdf_changed={pdf_changed} changed_integral={changed_integral} maxIntegralError={max_error}"
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
#[ignore = "requires windowless Vulkan; authored thin-SSS source/continuation contract"]
fn gpu_restir_authored_thin_sss_self_shift_preserves_source_measure() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        // One continuation isolates primary SSS -> emitter, with no later RIS candidates.
        bounces: 2,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    let relative = |a: f64, b: f64| (a - b).abs() / a.abs().max(b.abs()).max(1e-8);
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = prime_shader_tests::restir_adapter_tree();
        for (mapped, label) in [(false, "plain"), (true, "mapped")] {
            let mut primary = face(2.);
            // This fixture explicitly supplies the CPU topology proof for its source sheet.
            // Coverage is independent and cannot authorize subsurface transmission.
            primary.material_thin = true;
            primary.geometry.flags = 1;
            primary.geometry.texture_id = 8;
            let mut emitter = face(1.);
            emitter.emission = lamp().emission;
            let mut fixture = scene(3001 + u64::from(mapped), vec![primary, emitter]);
            let mut material = texture(1, 1, vec![255; 4]);
            material.material = Some(Arc::new(TextureMaterial {
                // Roughness1, non-conductor F0, pure SSS, no authored emission.
                specular: Some(texture(1, 1, vec![0, 10, 190, 255])),
                normal: mapped.then(|| texture(1, 1, vec![128, 128, 255, 0])),
                ..Default::default()
            }));
            fixture.textures.insert(8, material);
            renderer.render(&fixture, &camera(), 3, 3, 0).unwrap();
            let rows = run_probe(&renderer, 4096, code, [4096, 0, 0, 2]);
            if let Some(output) = &output {
                let bytes: Vec<_> = rows
                    .iter()
                    .flatten()
                    .flat_map(|word| word.to_le_bytes())
                    .collect();
                std::fs::write(output.join(format!("{method:?}-sss-{label}.bin")), bytes).unwrap();
            }
            let mut source_count = 0;
            let mut direct_eval_zero = 0;
            let mut shift_rejected = 0;
            let mut changed_integral = 0;
            let mut max_error = 0_f64;
            let mut max_mis_attribution_error = 0_f64;
            let mut examples = Vec::new();
            for (sample, row) in rows.iter().enumerate() {
                let flags = row[12];
                let value = row.map(|word| f64::from(f32::from_bits(word)));
                if value[3] <= 0.
                    || flags & 1 != 0
                    || flags & 2 == 0
                    || (flags >> 8) & 255 != 1
                    || (flags >> 16) & 255 != 0
                    || flags >> 24 != 1
                {
                    continue;
                }
                source_count += 1;
                assert!(
                    value[..12]
                        .iter()
                        .chain(&value[16..])
                        .all(|x| x.is_finite())
                );
                assert!(value[..3].iter().any(|x| *x > 0.));
                assert!(value[19] > 0. && value[20..23].iter().any(|x| *x > 0.));
                assert!(value[10] > 0. && value[9] > 0.);
                // Opaque transmission has no direct-light competitor in production.
                assert_eq!(
                    value[23], 0.,
                    "source competing PDF {method:?} {label} {row:?}"
                );
                assert!(value[18] < 0. && value[31] >= 0. && value[31] < 0.01);

                // Independent one-leaf area->angle PDF: area256, local domain weight0.5.
                // Recover the physical displacement from the actual sampled query ray:
                // previous.z=2, endpoint.z=1, begin.z=2-offset (transmission side).
                let t = (-1. + value[31]) / value[18];
                let displacement = [t * value[16], t * value[17], -1.];
                let distance_squared: f64 = displacement.iter().map(|x| x * x).sum();
                let expected_light_pdf = 0.5 * distance_squared.powf(1.5) / 256.;
                assert!(
                    relative(value[9], expected_light_pdf) < 2e-5,
                    "independent emitter PDF {method:?} {label} {row:?} expected={expected_light_pdf}"
                );
                direct_eval_zero +=
                    u32::from(value[8] == 0. && value[28..31].iter().all(|x| *x == 0.));
                shift_rejected += u32::from(value[7] <= 0.);
                let error = (0..3)
                    .map(|channel| relative(value[channel], value[4 + channel] * value[7]))
                    .fold(0_f64, f64::max);
                max_error = max_error.max(error);
                if error >= 1e-3 {
                    changed_integral += 1;
                    // Attribute supported failures to the counterfactual power-MIS factor.
                    // Correct source MIS is1. After correction this diagnostic is inactive.
                    if value[7] > 0. {
                        let scale = value[19].max(value[9]);
                        let a = value[19] / scale;
                        let b = value[9] / scale;
                        let counterfactual_mis = a * a / (a * a + b * b);
                        for channel in 0..3 {
                            if value[channel] > 0. {
                                let ratio = value[4 + channel] * value[7] / value[channel];
                                max_mis_attribution_error = max_mis_attribution_error
                                    .max(relative(ratio, counterfactual_mis));
                            }
                        }
                    }
                    if examples.len() < 4 {
                        examples.push(format!(
                            "{method:?} {label} sample={sample} seed={} flags={flags:x} integralError={error} raw={row:?}",
                            row[13]
                        ));
                    }
                } else {
                    assert!(relative(value[7], 1.) < 2e-5, "identity query J {row:?}");
                }
            }
            eprintln!(
                "ReSTIR SSS {method:?} {label}: sources={source_count} direct_eval_zero={direct_eval_zero} shift_rejected={shift_rejected} changed_integral={changed_integral} maxIntegralError={max_error} maxMisAttributionError={max_mis_attribution_error}"
            );
            for example in examples {
                eprintln!("{example}");
            }
            if source_count < 8 || shift_rejected != 0 || changed_integral != 0 {
                failures.push(format!(
                    "{method:?} {label}: sources={source_count} rejected={shift_rejected} changedIntegral={changed_integral} maxIntegralError={max_error} maxMisAttributionError={max_mis_attribution_error}"
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

fn rear_sun_scene(revision: u64, mapped: bool) -> Scene {
    let mut primary = face(0.);
    // Cross(+X,+Z)=-Y, facing the camera below; Sun is on the physical back side.
    primary.material_thin = true;
    primary.geometry.positions = [[0., 2., 0.], [16., 2., 0.], [16., 2., 16.], [0., 2., 16.]];
    primary.geometry.flags = 1;
    primary.geometry.texture_id = 8;
    let mut fixture = scene(revision, vec![primary]);
    let mut material = texture(1, 1, vec![255; 4]);
    material.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![0, 10, 190, 255])),
        normal: mapped.then(|| texture(1, 1, vec![128, 128, 255, 0])),
        ..Default::default()
    }));
    fixture.textures.insert(8, material);
    fixture
}

fn rear_sun_camera() -> Camera {
    Camera {
        position: [8., 0., 8.],
        forward: [0., 1., 0.],
        right: [1., 0., 0.],
        up: [0., 0., 1.],
        vertical_fov_radians: 1.,
    }
}

#[test]
#[ignore = "requires windowless Vulkan; authored thin-SSS rear-Sun direct-light source support"]
fn gpu_restir_authored_thin_sss_rear_sun_has_no_direct_nee_competitor() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 2,
        sun: 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut environment = renderer.environment;
    environment.sun_direction = [0., 1., 0.];
    renderer.set_environment(environment).unwrap();
    let camera = rear_sun_camera();
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = prime_shader_tests::restir_adapter_tree();
        for (mapped, label) in [(false, "plain"), (true, "mapped")] {
            let fixture = rear_sun_scene(4001 + u64::from(mapped), mapped);
            renderer.render(&fixture, &camera, 3, 3, 0).unwrap();
            assert_eq!(renderer.environment.sun_direction, [0., 1., 0.]);
            // reserved0=1 only retains Sun/sky intensity. No extra shader body or calls.
            let rows = run_probe(&renderer, 4096, code, [4096, 0, 1, 0]);
            if let Some(output) = &output {
                let bytes: Vec<_> = rows
                    .iter()
                    .flatten()
                    .flat_map(|word| word.to_le_bytes())
                    .collect();
                std::fs::write(
                    output.join(format!("{method:?}-rear-sun-{label}.bin")),
                    bytes,
                )
                .unwrap();
            }
            let mut active_sources = 0;
            let mut sun_nee = 0;
            let mut sun_bsdf = 0;
            let mut sky_sources = 0;
            let mut source_sum = [0_f64; 3];
            let mut sun_nee_sum = [0_f64; 3];
            let mut examples = Vec::new();
            for (sample, row) in rows.iter().enumerate() {
                let value = row.map(|word| f64::from(f32::from_bits(word)));
                if value[3] <= 0. {
                    continue;
                }
                let flags = row[12];
                assert!(
                    value[..12]
                        .iter()
                        .chain(&value[16..])
                        .all(|x| x.is_finite())
                );
                assert!(value[..3].iter().any(|x| *x > 0.));
                assert_ne!(
                    row[14],
                    u32::MAX,
                    "primary source must hit the actual SSS plane"
                );
                assert_eq!(
                    (flags >> 16) & 255,
                    0,
                    "only one surface/continuation exists"
                );
                assert_ne!(flags >> 24, 1, "fixture has no finite local emitters");
                active_sources += 1;
                for channel in 0..3 {
                    source_sum[channel] += value[channel] * value[3];
                }
                if flags >> 24 == 0 {
                    sky_sources += 1;
                }
                if flags >> 24 != 2 {
                    continue;
                }
                // The actual noon Sun cone is entirely +Y. Its geometric direction is
                // therefore backside at this -Y plane; the source event must agree.
                assert_ne!(
                    flags & 2,
                    0,
                    "rear Sun must be a physical transmission event"
                );
                if flags & 1 == 0 {
                    sun_bsdf += 1;
                    continue;
                }
                assert_eq!((flags >> 8) & 255, 1, "terminal Sun NEE source class");
                sun_nee += 1;
                for channel in 0..3 {
                    sun_nee_sum[channel] += value[channel] * value[3];
                }
                if examples.len() < 4 {
                    examples.push(format!(
                        "sample={sample} seed={} flags={flags:x} sourceRGB={:?} weight={} raw={row:?}",
                        row[13], &value[..3], value[3]
                    ));
                }
            }
            eprintln!(
                "ReSTIR rear Sun {method:?} {label}: active_sources={active_sources} sun_nee={sun_nee} sun_bsdf={sun_bsdf} sky_sources={sky_sources} sourceRGBSum={source_sum:?} sunNeeRGBSum={sun_nee_sum:?}"
            );
            for example in examples {
                eprintln!("{method:?} {label} {example}");
            }
            // Ordinary PT's opaque direct-light contract has no backside Sun NEE.
            // BSDF transmission radiance is still allowed, so do not assert total Sun0.
            if active_sources < 8 || sun_nee != 0 {
                failures.push(format!(
                    "{method:?} {label}: active_sources={active_sources} forbiddenRearSunNEE={sun_nee}"
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
#[ignore = "requires windowless Vulkan; existing terminal rear-Sun NEE is rejected at its shift consumer"]
fn gpu_restir_stale_rear_sun_nee_is_rejected_by_current_consumer() {
    const COUNT: u32 = 256;
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 2,
        sun: 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    let mut environment = renderer.environment;
    environment.sun_direction = [0., 1., 0.];
    renderer.set_environment(environment).unwrap();
    let camera = rear_sun_camera();
    let output = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT").map(std::path::PathBuf::from);
    if let Some(output) = &output {
        std::fs::create_dir_all(output).unwrap();
    }
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = prime_shader_tests::restir_adapter_tree();
        for (mapped, label) in [(false, "plain"), (true, "mapped")] {
            let fixture = rear_sun_scene(5001 + u64::from(mapped), mapped);
            renderer.render(&fixture, &camera, 3, 3, 0).unwrap();
            // The same single generate/replay/shift body replaces a local clone before
            // consumption. This is a synthetic invalid cache, not lossless old-raw replay.
            let rows = run_probe(&renderer, COUNT, code, [COUNT, 0, 1, 4]);
            if let Some(output) = &output {
                let bytes: Vec<_> = rows
                    .iter()
                    .flatten()
                    .flat_map(|word| word.to_le_bytes())
                    .collect();
                std::fs::write(
                    output.join(format!("{method:?}-stale-rear-sun-{label}.bin")),
                    bytes,
                )
                .unwrap();
            }
            let mut generated_sources = 0;
            let mut rejected_clones = 0;
            for (sample, row) in rows.iter().enumerate() {
                let generated: [f32; 4] = std::array::from_fn(|i| f32::from_bits(row[28 + i]));
                assert!(generated.iter().all(|value| value.is_finite()));
                if generated[3] <= 0. {
                    continue;
                }
                assert!(generated[..3].iter().any(|value| *value > 0.));
                generated_sources += 1;
                assert_ne!(row[14], u32::MAX, "the actual source must hit its plane");
                assert_eq!(
                    row[15],
                    u32::MAX,
                    "synthetic terminal Sun is not a finite hit"
                );
                assert_eq!(row[12], (2 << 24) | (1 << 8) | 3);
                assert_eq!(
                    row[..4],
                    [1., 2., 3., 1.].map(f32::to_bits),
                    "{method:?}/{label}: positive synthetic incident cache was not consumed"
                );
                assert_eq!(f32::from_bits(row[11]), 1.);
                let replay: [f32; 3] = std::array::from_fn(|i| f32::from_bits(row[16 + i]));
                assert!(
                    replay.iter().all(|value| value.is_finite() && *value > 0.),
                    "the consumer's early zero-replay gate must not hide support rejection"
                );
                let shifted: [f32; 4] = std::array::from_fn(|i| f32::from_bits(row[4 + i]));
                assert_eq!(
                    shifted, [0.; 4],
                    "{method:?}/{label} sample{sample} seed{}: stale rear Sun NEE leaked through shift",
                    row[13]
                );
                rejected_clones += 1;
            }
            assert!(
                generated_sources >= 8,
                "{method:?}/{label}: insufficient real source coverage"
            );
            assert_eq!(rejected_clones, generated_sources);
            eprintln!(
                "ReSTIR stale rear Sun {method:?} {label}: actual_generated_sources={generated_sources} synthetic_terminal_nee_rejected={rejected_clones}; no extra generate/shift calls or reset"
            );
        }
    }
}
