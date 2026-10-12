use super::*;
use crate::float;

// Independent evaluation of the locked four-band solar fit at the selected Y=8 baseline.
// The old frozen sky fixtures used a per-channel 12.5 normalization.
const BASE_SOLAR_RGB: [f32; 3] = [7.999351, 8.000100, 8.001729];

fn f32s(bytes: &[u8]) -> Vec<f32> {
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|s| f32::from_le_bytes(*s))
        .collect()
}
fn fixture(bytes: &[u8], offset: &mut usize) -> f32 {
    let result = f32::from_le_bytes(bytes[*offset..*offset + 4].try_into().unwrap());
    *offset += 4;
    result
}
fn execute(atmosphere: &Atmosphere, mode: u32, count: u32, input: &[u8]) -> Vec<f32> {
    let context = &atmosphere.context;
    let benchmarking = mode == 8 || mode == 9;
    let output_stride = if mode == 12 { 192 } else { 32 };
    let source = Buffer::upload(context, input, vk::BufferUsageFlags::STORAGE_BUFFER).unwrap();
    let output = Buffer::new(
        context,
        u64::from(count) * output_stride,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        !benchmarking,
    )
    .unwrap();
    let consumer_fixture = (mode == 12).then(|| {
        let storage =
            Buffer::upload(context, &[0; 128], vk::BufferUsageFlags::STORAGE_BUFFER).unwrap();
        let prefix = Buffer::upload(
            context,
            [0.0f32, 0.0, 0.0, 400.0]
                .map(f32::to_le_bytes)
                .as_flattened(),
            vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        )
        .unwrap();
        let tail = Buffer::upload(
            context,
            &[0; 16],
            vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        )
        .unwrap();
        let stars = Texture::new(context, [2, 2, 1], vk::Format::R32G32B32A32_SFLOAT).unwrap();
        let star_texels = [0.8f32, 0.4, 0.2, 1.0]
            .repeat(4)
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect::<Vec<_>>();
        stars.upload(context, &star_texels).unwrap();
        (storage, prefix, tail, stars)
    });
    let c = vk::DescriptorType::COMBINED_IMAGE_SAMPLER;
    let i = vk::DescriptorType::SAMPLED_IMAGE;
    let b = vk::DescriptorType::STORAGE_BUFFER;
    let compute = Compute::new(
        context,
        &[
            &[
                c,
                c,
                i,
                i,
                i,
                b,
                b,
                b,
                vk::DescriptorType::STORAGE_IMAGE,
                b,
                b,
                b,
                b,
            ],
            CONSUMER_TYPES,
        ],
        1,
        &[prime_shader_tests::atmosphere_test()],
        32,
    )
    .unwrap();
    for (binding, image) in atmosphere._physical.iter().enumerate() {
        compute.image(0, 0, binding as u32, if binding < 2 { c } else { i }, image);
    }
    compute.buffer(0, 0, 5, &atmosphere._medium);
    compute.buffer(0, 0, 6, &source);
    compute.buffer(0, 0, 7, &output);
    compute.image(0, 0, 8, vk::DescriptorType::STORAGE_IMAGE, &atmosphere._sky);
    for binding in 9..=12 {
        compute.buffer(
            0,
            0,
            binding,
            consumer_fixture.as_ref().map_or(&source, |f| &f.0),
        );
    }
    compute.buffer(1, 0, 0, &atmosphere._frames[0]);
    for (binding, image) in [
        &atmosphere._sky,
        &atmosphere._transmittance,
        &atmosphere._aerial_radiance,
        &atmosphere._aerial_transmittance,
    ]
    .into_iter()
    .enumerate()
    {
        compute.image(1, 0, binding as u32 + 1, c, image);
    }
    // Old fixtures disable stars. Only the actual consumer test needs its tiny uploaded star map.
    compute.image(
        1,
        0,
        5,
        c,
        consumer_fixture.as_ref().map_or(&atmosphere._sky, |f| &f.3),
    );
    let control_info = [vk::DescriptorBufferInfo::default()
        .buffer(atmosphere.lighting_controls[0].buffer)
        .range(32)];
    unsafe {
        context.device.update_descriptor_sets(
            &[vk::WriteDescriptorSet::default()
                .dst_set(compute.sets[1][0])
                .dst_binding(6)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .buffer_info(&control_info)],
            &[],
        );
    }
    let mut push = [mode, count, 0, 0]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();
    push.extend_from_slice(
        &consumer_fixture
            .as_ref()
            .map_or(0, |f| f.1.address())
            .to_le_bytes(),
    );
    push.extend_from_slice(
        &consumer_fixture
            .as_ref()
            .map_or(0, |f| f.2.address())
            .to_le_bytes(),
    );
    context
        .submit_named("atmosphere_contract", |command| unsafe {
            barrier(context, command);
            compute.dispatch(command, 0, 0, &push, [count.div_ceil(64), 1, 1]);
            let memory = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &memory,
                &[],
                &[],
            );
        })
        .unwrap();
    if benchmarking {
        return vec![];
    }
    f32s(
        &output
            .read(count as usize * output_stride as usize)
            .unwrap(),
    )
}
fn camera() -> Camera {
    Camera {
        position: [0.; 3],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 1.0,
    }
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_atmosphere_matches_frozen_spectral_and_sky_reference() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    atmosphere
        .prepare(
            Environment {
                altitude_offset_meters: 300,
                ..Default::default()
            },
            &camera(),
            16. / 9.,
            0,
            None,
        )
        .unwrap();
    let reference = include_bytes!("../../tests/fixtures/atmosphere-transport.bin");
    let count = u32::from_le_bytes(reference[..4].try_into().unwrap());
    let mut input = vec![];
    let mut expected = vec![];
    for chunk in reference[4..].as_chunks::<64>().0.iter() {
        input.extend_from_slice(&chunk[..32]);
        expected.extend(f32s(&chunk[32..]));
    }
    let actual = execute(&atmosphere, 1, count, &input);
    let mut maximum = 0f32;
    for (index, (&value, &target)) in actual.iter().zip(&expected).enumerate() {
        let tolerance = if index % 8 < 4 {
            2e-8f32.max(target * 0.008)
        } else {
            3e-4
        };
        assert!(
            value.is_finite() && (value - target).abs() <= tolerance,
            "spectral {index}: {value} vs {target}"
        );
        if index % 8 < 4 && target > 1e-7 {
            maximum = maximum.max((value / target - 1.).abs());
        }
    }
    eprintln!("Atmosphere maximum spectral relative error: {maximum}");
    let sky = include_bytes!("../../tests/fixtures/atmosphere-sky.bin");
    let projection = include_bytes!("../../tests/fixtures/atmosphere-projection.bin");
    let cases = u32::from_le_bytes(sky[..4].try_into().unwrap());
    let samples = u32::from_le_bytes(sky[4..8].try_into().unwrap()) as usize;
    let mut sky_offset = 8;
    let mut projection_offset = 0;
    let mut worst = 0f32;
    let mut worst_lookup = 0f32;
    let mut covered = 0;
    for case in 0..cases {
        let height = fixture(sky, &mut sky_offset);
        let elevation = fixture(sky, &mut sky_offset);
        let samples_start = sky_offset;
        sky_offset += samples * 20;
        let projection_start = projection_offset;
        projection_offset += 192 * 44;
        // Production retains the old in-atmosphere camera clamp. The spectral test above covers space.
        if height >= 120. {
            continue;
        }
        let radians = elevation.to_radians();
        let environment = Environment {
            world_y: (f64::from(height) - 0.3) / f64::from(0.001f32) - 64.,
            altitude_offset_meters: 300,
            sun_direction: [0., radians.sin(), radians.cos()],
        };
        atmosphere
            .prepare(environment, &camera(), 16. / 9., 0, None)
            .unwrap();
        let pixels = f32s(&atmosphere._sky.read(&context).unwrap());
        let mut offset = samples_start;
        for _ in 0..samples {
            let x = u32::from_le_bytes(sky[offset..offset + 4].try_into().unwrap()) as usize;
            let y = u32::from_le_bytes(sky[offset + 4..offset + 8].try_into().unwrap()) as usize;
            offset += 8;
            for lane in 0..3 {
                let target = fixture(sky, &mut offset) * (BASE_SOLAR_RGB[lane] / 12.5);
                let value = (pixels[(y * 256 + x) * 4 + lane].exp() - 1e-30).max(0.);
                assert!(
                    (value - target).abs() <= 2e-6f32.max(target * 0.012),
                    "sky case {case} ({x},{y}) lane {lane}: {value} vs {target}"
                );
                if target > 1e-5 {
                    worst = worst.max((value / target - 1.).abs());
                }
            }
        }
        let mut input = vec![];
        let mut expected = vec![];
        for sample in projection[projection_start..projection_offset]
            .as_chunks::<44>()
            .0
            .iter()
        {
            input.extend_from_slice(&sample[..32]);
            expected.extend(f32s(&sample[32..]));
        }
        let result = execute(&atmosphere, 0, 192, &input);
        for (ray, (actual, expected)) in result
            .as_chunks::<4>()
            .0
            .iter()
            .take(192)
            .zip(expected.as_chunks::<3>().0.iter())
            .enumerate()
        {
            for lane in 0..3 {
                let value = actual[lane];
                let target = expected[lane] * (BASE_SOLAR_RGB[lane] / 12.5);
                assert!(
                    value.is_finite() && (value - target).abs() <= 2e-6f32.max(target * 0.004),
                    "lookup case {case} ray {ray} lane {lane}: {value} vs {target}"
                );
                if target > 1e-5 {
                    worst_lookup = worst_lookup.max((value / target - 1.).abs());
                }
            }
        }
        covered += 1;
    }
    assert!(covered >= 6);
    eprintln!(
        "Atmosphere {covered} cases: max SkyView error={worst}; max lookup error={worst_lookup}"
    );
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_solar_disk_and_update_dependencies() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    let camera = camera();
    let mut environment = Environment {
        altitude_offset_meters: 300,
        ..Default::default()
    };
    atmosphere
        .prepare(environment, &camera, 16. / 9., 0, None)
        .unwrap();
    let first = (atmosphere.sky_updates, atmosphere.transmittance_updates);
    atmosphere
        .prepare(environment, &camera, 16. / 9., 0, None)
        .unwrap();
    assert_eq!(
        first,
        (atmosphere.sky_updates, atmosphere.transmittance_updates)
    );
    environment.sun_direction = [0.5, 0.8660254, 0.];
    atmosphere
        .prepare(environment, &camera, 16. / 9., 0, None)
        .unwrap();
    assert_eq!(
        first,
        (atmosphere.sky_updates, atmosphere.transmittance_updates)
    );
    environment.sun_direction = [1., 0., 0.];
    atmosphere
        .prepare(environment, &camera, 16. / 9., 0, None)
        .unwrap();
    assert_eq!(
        (first.0 + 1, first.1),
        (atmosphere.sky_updates, atmosphere.transmittance_updates)
    );
    // Solar limb intersects the ground horizon: retain both visible and invisible samples.
    environment.world_y = -364.;
    atmosphere
        .prepare(environment, &camera, 16. / 9., 0, None)
        .unwrap();
    let mut input = vec![];
    for i in 0..4096 {
        for x in [
            (i as f32 + 0.5) / 4096.,
            ((i * 1597) % 4096) as f32 / 4096.,
            0.,
            0.,
        ] {
            float(&mut input, x);
        }
    }
    let samples = execute(&atmosphere, 2, 4096, &input);
    let mut visible = 0;
    for sample in samples.as_chunks::<8>().0.iter() {
        let norm: f32 = sample[..3].iter().map(|x| x * x).sum();
        assert!((norm - 1.).abs() < 4e-7);
        assert!(sample[3] > 14000. && sample[3] < 15000.);
        assert!(sample[7] > 0.5, "sample escaped its own solar cone");
        assert!(
            sample[4..7]
                .iter()
                .all(|x| x.is_finite() && *x >= 0. && *x <= 1.)
        );
        visible += usize::from(sample[4..7].iter().any(|x| *x > 0.));
    }
    assert!(
        (1800..2300).contains(&visible),
        "partial solar limb visible={visible}"
    );
}

#[test]
#[ignore = "windowless atmosphere altitude setting, LUT invalidation and reuse"]
fn gpu_atmosphere_altitude_setting_updates_height_dependent_luts() {
    use prime_scene::{
        scene::Scene,
        settings::{RenderMode, RenderSettings},
    };
    let mut settings = RenderSettings {
        mode: RenderMode::Offline,
        native_noisy_output: true,
        ..Default::default()
    };
    assert_eq!(settings.atmosphere_altitude_offset_meters, 500);
    let mut renderer = crate::Renderer::with_settings_and_workers(
        settings,
        std::sync::Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
    )
    .unwrap();
    assert_eq!(renderer.environment.altitude_offset_meters, 500);
    renderer
        .set_environment(Environment {
            world_y: -64.,
            altitude_offset_meters: 500,
            ..Default::default()
        })
        .unwrap();
    let scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    let camera = camera();
    renderer.render(&scene, &camera, 32, 24, 0).unwrap();
    assert_eq!(renderer.samples, 1);
    let counts = |atmosphere: &Atmosphere| {
        (
            atmosphere.sky_updates,
            atmosphere.transmittance_updates,
            atmosphere.aerial_updates,
            atmosphere.aerial_t_updates,
        )
    };
    let input = [0.0f32, 0., -1., 0., 0., 0., 0., 0.].map(f32::to_le_bytes);
    let mut previous = execute(
        renderer.atmosphere.as_ref().unwrap(),
        0,
        1,
        input.as_flattened(),
    );
    for offset in [10000, 0] {
        let before = counts(renderer.atmosphere.as_ref().unwrap());
        settings.atmosphere_altitude_offset_meters = offset;
        renderer.configure(settings).unwrap();
        assert_eq!(renderer.samples, 0);
        assert_eq!(renderer.environment.world_y, -64.);
        assert_eq!(
            renderer.environment.eye_radius_km(),
            6360. + offset as f32 * 0.001
        );
        renderer.render(&scene, &camera, 32, 24, 1).unwrap();
        let after = counts(renderer.atmosphere.as_ref().unwrap());
        assert_eq!(
            after,
            (before.0 + 1, before.1 + 1, before.2 + 1, before.3 + 1)
        );
        let sky = execute(
            renderer.atmosphere.as_ref().unwrap(),
            0,
            1,
            input.as_flattened(),
        );
        assert!(sky[..3].iter().all(|v| v.is_finite() && *v >= 0.));
        assert!(
            sky[..3]
                .iter()
                .zip(&previous)
                .any(|(a, b)| (a - b).abs() > 1e-4)
        );
        previous = sky;
        renderer.configure(settings).unwrap();
        assert_eq!(renderer.samples, 1);
        renderer.render(&scene, &camera, 32, 24, 2).unwrap();
        assert_eq!(renderer.samples, 2);
        assert_eq!(counts(renderer.atmosphere.as_ref().unwrap()), after);
    }
}

#[test]
#[ignore = "windowless sampled solar radiance, earth occlusion and partial solar limb"]
fn gpu_solar_sampled_radiance_preserves_day_night_and_partial_limb() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    let camera = camera();
    let mut input = Vec::new();
    for i in 0..4096 {
        for value in [
            (i as f32 + 0.5) / 4096.,
            ((i * 1597) % 4096) as f32 / 4096.,
            0.,
            0.,
        ] {
            float(&mut input, value);
        }
    }
    for (name, sun_direction) in [
        ("day", [0., 1., 0.]),
        ("limb", [0., 0., -1.]),
        ("night", [0., -1., 0.]),
    ] {
        // Put the eye on the physical ground horizon so the horizontal disk is partially visible.
        atmosphere
            .prepare(
                Environment {
                    world_y: -364.,
                    altitude_offset_meters: 300,
                    sun_direction,
                },
                &camera,
                16. / 9.,
                0,
                None,
            )
            .unwrap();
        let transmission = execute(&atmosphere, 2, 4096, &input);
        let radiance = execute(&atmosphere, 6, 4096, &input);
        let mut visible = 0;
        for (t, l) in transmission
            .as_chunks::<8>()
            .0
            .iter()
            .zip(radiance.as_chunks::<8>().0)
        {
            assert_eq!(
                &t[..4],
                &l[..4],
                "{name}: the gate must use the same sampled direction/PDF"
            );
            let supported = l[4..7].iter().any(|&v| v > 0.);
            assert_eq!(supported, t[4..7].iter().any(|&v| v > 0.));
            visible += usize::from(supported);
            for channel in 4..7 {
                let radius = 0.00471_f64;
                let projected = std::f64::consts::PI * radius.sin().powi(2);
                let expected =
                    t[channel] * (f64::from(BASE_SOLAR_RGB[channel - 4]) / projected) as f32;
                assert!(
                    l[channel].is_finite()
                        && (l[channel] - expected).abs() <= 1e-6_f32.max(expected * 3e-6),
                    "{name}: solar radiance {l:?}, transmittance {t:?}"
                );
            }
        }
        match name {
            "day" => assert_eq!(visible, 4096),
            "night" => assert_eq!(visible, 0),
            "limb" => assert!(
                (1800..2300).contains(&visible),
                "visible solar limb={visible}"
            ),
            _ => unreachable!(),
        }
    }
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_aerial_integral_filtering_and_cache_dependencies() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    let mut camera = camera();
    let mut env = Environment {
        world_y: 9636.,
        ..Default::default()
    };
    atmosphere.prepare(env, &camera, 16. / 9., 0, None).unwrap();
    let mut input = vec![];
    for i in 0..96u32 {
        for v in [
            ((i * 73) % 128) as f32,
            ((i * 97) % 256) as f32,
            ((i * 47) % 128) as f32,
            0.,
        ] {
            float(&mut input, v);
        }
    }
    let s = execute(&atmosphere, 4, 96, &input);
    for (i, pair) in s.as_chunks::<8>().0.iter().enumerate() {
        for lane in 0..3 {
            assert!(
                pair[lane].is_finite()
                    && (pair[lane] - pair[lane + 4]).abs() <= 3e-6f32.max(pair[lane + 4] * 0.008),
                "aerial S {i}/{lane}: {pair:?}"
            );
        }
    }
    for i in 0..96 {
        input[i * 16 + 4..i * 16 + 8].copy_from_slice(&(((i * 37) % 64) as f32).to_le_bytes());
    }
    let t = execute(&atmosphere, 7, 96, &input);
    for pair in t.as_chunks::<8>().0.iter() {
        for lane in 0..3 {
            assert!(
                (pair[lane] - pair[lane + 4]).abs() < 0.001,
                "aerial T {pair:?}"
            );
        }
    }
    let mut samples = vec![];
    for i in 0..512 {
        for v in [
            ((i * 317) % 512) as f32 / 511.,
            ((i * 157) % 512) as f32 / 511.,
            i as f32 / 511.,
            0.,
        ] {
            float(&mut samples, v);
        }
    }
    for sun in [[0., 0.8660254, 0.5], [0., 0., -1.], [1., 0., 0.]] {
        env.sun_direction = sun;
        atmosphere.prepare(env, &camera, 16. / 9., 0, None).unwrap();
        let values = execute(&atmosphere, 5, 512, &samples);
        for pair in values.as_chunks::<8>().0.iter() {
            for lane in 0..3 {
                assert!(
                    (pair[lane] - pair[lane + 4]).abs() <= 2e-6f32.max(pair[lane + 4] * 0.003),
                    "filtered aerial {pair:?}"
                );
            }
        }
    }
    let count = (atmosphere.aerial_updates, atmosphere.aerial_t_updates);
    for slot in 0..crate::FRAME_SLOTS {
        atmosphere
            .prepare(env, &camera, 16. / 9., slot, None)
            .unwrap();
    }
    assert_eq!(
        count,
        (atmosphere.aerial_updates, atmosphere.aerial_t_updates)
    );
    camera.position[0] += 1.;
    atmosphere.prepare(env, &camera, 16. / 9., 0, None).unwrap();
    assert_eq!(
        (count.0 + 1, count.1),
        (atmosphere.aerial_updates, atmosphere.aerial_t_updates)
    );
    camera.vertical_fov_radians += 0.1;
    atmosphere.prepare(env, &camera, 16. / 9., 0, None).unwrap();
    assert_eq!(
        (count.0 + 2, count.1 + 1),
        (atmosphere.aerial_updates, atmosphere.aerial_t_updates)
    );
    let zero = [0.5f32, 0.5, 0., 0.].map(f32::to_le_bytes);
    let near = execute(&atmosphere, 3, 1, zero.as_flattened());
    assert_eq!(&near[..3], &[0.; 3]);
    assert_eq!(&near[4..7], &[1.; 3]);
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_aerial_geometry_and_material_changes_invalidate_radiance_only() {
    use prime_scene::scene::{DynamicScene, Instance, InstanceScene, Prototype, Scene, Triangle};
    let mut renderer = crate::Renderer::new().unwrap();
    let camera = camera();
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    let env = Environment {
        sun_direction: [0., 1., 0.],
        ..Default::default()
    };
    renderer.set_environment(env).unwrap();
    renderer.render(&scene, &camera, 32, 24, 0).unwrap();
    let sample = [0.5f32, 0.5, 40., 0.].map(f32::to_le_bytes);
    let lit = execute(
        renderer.atmosphere.as_ref().unwrap(),
        3,
        1,
        sample.as_flattened(),
    );
    assert!(lit[..3].iter().all(|v| *v > 0.));
    let count = renderer.atmosphere.as_ref().unwrap().aerial_t_updates;
    scene.dynamic = DynamicScene {
        revision: 1,
        origin: [0.; 3],
        triangles: [
            [[-100., 20., -100.], [100., 20., -100.], [100., 20., 100.]],
            [[-100., 20., -100.], [100., 20., 100.], [-100., 20., 100.]],
        ]
        .map(|positions| Triangle {
            positions,
            colors: [[1.; 4]; 3],
            uvs: [[0.; 2]; 3],
            texture_id: 0,
            flags: 1,
        })
        .to_vec()
        .into(),
    };
    renderer.render(&scene, &camera, 32, 24, 1).unwrap();
    let blocked = execute(
        renderer.atmosphere.as_ref().unwrap(),
        3,
        1,
        sample.as_flattened(),
    );
    assert_eq!(
        count,
        renderer.atmosphere.as_ref().unwrap().aerial_t_updates
    );
    for lane in 0..3 {
        assert!(
            blocked[lane] < lit[lane] * 0.01,
            "roof did not block aerial: {lit:?} / {blocked:?}"
        );
    }
    let roof = scene.dynamic.triangles.as_slice().into();
    scene.dynamic.revision += 1;
    scene.dynamic.triangles = Default::default();
    renderer.render(&scene, &camera, 32, 24, 2).unwrap();
    let restored = execute(
        renderer.atmosphere.as_ref().unwrap(),
        3,
        1,
        sample.as_flattened(),
    );
    for lane in 0..3 {
        assert!((restored[lane] - lit[lane]).abs() < 1e-6);
    }

    // The same cutout roof changes coverage through instance alpha alone.
    // Shading/shadow invalidation must not depend on an acceleration rebuild.
    let mut source = InstanceScene {
        epoch: 1,
        resource_revision: 1,
        instance_revision: 1,
        ..Default::default()
    };
    source.prototypes.insert(
        1,
        Prototype {
            revision: 1,
            triangles: roof,
            bounds: [[-100., 20., -100.], [100., 20., 100.]],
        },
    );
    source.instances.insert(
        1,
        Instance {
            revision: 1,
            prototype_id: 1,
            origin: [0.; 3],
            transform: crate::plan::translation([0.; 3]),
            texture_id: crate::plan::INHERIT,
            flags: crate::plan::INHERIT,
            tint: [255; 4],
            uv_transform: [1., 1., 0., 0.],
        },
    );
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 3)
        .unwrap();
    let generation = renderer.geometry.as_ref().unwrap().top.generation();
    let updates = renderer.atmosphere.as_ref().unwrap().aerial_updates;
    {
        let instance = source.instances.get_mut(&1).unwrap();
        instance.tint[0..3].copy_from_slice(&[64, 128, 192]);
        instance.revision += 1;
        source.instance_revision += 1;
        renderer
            .render_with_instances(&scene, &source, &camera, 32, 24, 3)
            .unwrap();
        assert_eq!(
            renderer.atmosphere.as_ref().unwrap().aerial_updates,
            updates,
            "RGB-only instance tint keeps atmospheric visibility"
        );
        assert_eq!(renderer.samples, 1, "appearance still resets sampling");
    }
    for (index, alpha) in [0, 255, 0].into_iter().enumerate() {
        let instance = source.instances.get_mut(&1).unwrap();
        instance.tint[3] = alpha;
        instance.revision += 1;
        source.instance_revision += 1;
        renderer
            .render_with_instances(&scene, &source, &camera, 32, 24, 4 + index as u32)
            .unwrap();
        let atmosphere = renderer.atmosphere.as_ref().unwrap();
        let actual = execute(atmosphere, 3, 1, sample.as_flattened());
        let expected = if alpha == 0 { &lit } else { &blocked };
        for lane in 0..3 {
            assert!(
                (actual[lane] - expected[lane]).abs() < 1e-6,
                "instance alpha={alpha} retained stale aerial shadows: {actual:?} / {expected:?}"
            );
        }
        assert_eq!(atmosphere.aerial_updates, updates + index as u64 + 1);
        assert_eq!(atmosphere.aerial_t_updates, count);
        assert_eq!(
            renderer.geometry.as_ref().unwrap().top.generation(),
            generation
        );
        assert_eq!(renderer.instance_work().rebuilt_blas, 0);
        assert_eq!(
            renderer.samples, 1,
            "material edits must reset accumulation"
        );
    }
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 7)
        .unwrap();
    assert_eq!(
        renderer.atmosphere.as_ref().unwrap().aerial_updates,
        updates + 3
    );
    assert_eq!(
        renderer.samples, 2,
        "unchanged input must retain accumulation"
    );

    // Unexpanded static producer pages use the same exact RGB/coverage classification.
    source.instances.clear();
    source.instance_revision += 1;
    let roof_cell = prime_scene::spatial::Cell::containing([0.; 3]).unwrap();
    scene.ready_terrain.insert(roof_cell);
    scene.meshes.insert(
        (1, 0),
        prime_scene::SceneMesh {
            revision: 1,
            flags: 1,
            origin: [0.; 3],
            triangles: source.prototypes[&1].triangles.clone().into(),
        },
    );
    scene.revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 8)
        .unwrap();
    let updates = renderer.atmosphere.as_ref().unwrap().aerial_updates;
    let mesh = scene.meshes.get_mut(&(1, 0)).unwrap();
    let mut triangles = mesh.triangles.iter().collect::<Vec<_>>();
    for triangle in &mut triangles {
        for color in &mut triangle.colors {
            color[0] = 0.125;
        }
    }
    mesh.triangles = triangles.into();
    mesh.revision += 1;
    scene.revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 9)
        .unwrap();
    assert_eq!(
        renderer.atmosphere.as_ref().unwrap().aerial_updates,
        updates,
        "static producer RGB-only keeps atmospheric visibility"
    );
    let mesh = scene.meshes.get_mut(&(1, 0)).unwrap();
    let mut triangles = mesh.triangles.iter().collect::<Vec<_>>();
    triangles[0].colors[0][3] = 0.25;
    mesh.triangles = triangles.into();
    mesh.revision += 1;
    scene.revision += 1;
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 10)
        .unwrap();
    assert_eq!(
        renderer.atmosphere.as_ref().unwrap().aerial_updates,
        updates + 1,
        "static alpha changes invalidate atmospheric visibility"
    );
    scene.anchor[0] += 256.;
    renderer
        .render_with_instances(&scene, &source, &camera, 32, 24, 11)
        .unwrap();
    assert_eq!(
        renderer.atmosphere.as_ref().unwrap().aerial_updates,
        updates + 2,
        "anchor rebase invalidates relative-world atmospheric visibility even for unchanged sources"
    );
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_aerial_profile_reuse_matches_full_recompute() {
    use prime_scene::scene::{Scene, Triangle};
    let mut renderer = crate::Renderer::new().unwrap();
    let mut reference = Atmosphere::new(&renderer.context).unwrap();
    let mut camera = camera();
    let mut environment = Environment::default();
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    // Creation, movement, RGB-only edits, coverage changes and removal. The last
    // cases change each non-shadow dependency while retaining the same scene.
    for case in 0..12 {
        if case < 7 {
            let x = if case >= 2 { 4. } else { -8. };
            let alpha = if case == 4 { 0. } else { 1. };
            let red = if case == 3 { 0.25 } else { 1. };
            scene.dynamic.revision += 1;
            scene.dynamic.triangles = if case == 0 || case == 6 {
                Default::default()
            } else {
                [
                    [[x, 10., -24.], [x + 12., 10., -24.], [x + 12., 10., 8.]],
                    [[x, 10., -24.], [x + 12., 10., 8.], [x, 10., 8.]],
                ]
                .map(|positions| Triangle {
                    positions,
                    colors: [[red, 1., 1., alpha]; 3],
                    uvs: [[0.; 2]; 3],
                    texture_id: 0,
                    flags: 1,
                })
                .to_vec()
                .into()
            };
        }
        match case {
            7 => camera.position[0] += 2.,
            8 => camera.vertical_fov_radians += 0.2,
            9 => environment.sun_direction = [0., 0.8, 0.6],
            10 => environment.world_y += 40.,
            _ => {}
        }
        renderer.set_environment(environment).unwrap();
        renderer.render(&scene, &camera, 32, 24, case).unwrap();
        // An independent LUT with all caches invalidated is the reference,
        // including a fresh ray query for every demanded shadow column.
        reference.aerial_key = None;
        reference.shadow_key = None;
        reference
            .prepare(
                environment,
                &camera,
                4. / 3.,
                0,
                Some((
                    renderer.geometry.as_ref().unwrap(),
                    renderer.atmosphere_scene_revision,
                )),
            )
            .unwrap();
        let actual = renderer
            .atmosphere
            .as_ref()
            .unwrap()
            ._aerial_radiance
            .read(&renderer.context)
            .unwrap();
        let expected = reference._aerial_radiance.read(&renderer.context).unwrap();
        assert_eq!(
            actual.iter().zip(&expected).position(|(a, b)| a != b),
            None,
            "cached/full aerial differ in case {case}"
        );
    }
    // Prove the unchanged-profile path really skips all slice writes. A changed
    // publication with the same blockers must preserve this diagnostic sentinel.
    let atmosphere = renderer.atmosphere.as_mut().unwrap();
    atmosphere
        ._aerial_radiance
        .upload(
            &renderer.context,
            &vec![0; atmosphere._aerial_radiance.size()],
        )
        .unwrap();
    atmosphere
        .prepare(
            environment,
            &camera,
            4. / 3.,
            0,
            Some((
                renderer.geometry.as_ref().unwrap(),
                renderer.atmosphere_scene_revision + 1,
            )),
        )
        .unwrap();
    assert!(
        atmosphere
            ._aerial_radiance
            .read(&renderer.context)
            .unwrap()
            .iter()
            .all(|v| *v == 0),
        "unchanged blocker profiles rewrote the aerial LUT"
    );
}

#[test]
#[ignore = "requires Vulkan device; run with PRIME_PROFILE=1 and validation disabled"]
fn atmosphere_cost_matrix() {
    let context = Context::new().unwrap();
    assert!(context.profile_snapshot().is_some(), "set PRIME_PROFILE=1");
    eprintln!(
        "Atmosphere isolated GPU cost; {}; fixed seed; native 1920x1080 query domain; no game/FPS claim",
        context.name
    );
    let mut a = Atmosphere::new(&context).unwrap();
    let mut camera = camera();
    let mut env = Environment::default();
    a.prepare(env, &camera, 16. / 9., 0, None).unwrap();
    for mode in [9, 8] {
        let mut values = vec![];
        for _ in 0..25 {
            let before = context.profile_snapshot().unwrap();
            let _ = execute(&a, mode, 1920 * 1080, &[0; 32]);
            let after = context.profile_snapshot().unwrap();
            values.push((after.gpu_ns - before.gpu_ns) as f64 / 1e6);
        }
        let raw = values.clone();
        values.sort_by(f64::total_cmp);
        eprintln!(
            "sky_query mode={mode} GPU p50={:.4} p95={:.4} ms; samples={raw:?}",
            values[12], values[23]
        );
    }
    for kind in ["unchanged", "sun", "view", "height"] {
        let mut gpu = vec![];
        let mut cpu = vec![];
        for i in 0..25 {
            match kind {
                "sun" => {
                    let angle = (0.2 + i as f32 * 0.0001).to_radians();
                    env.sun_direction = [angle.sin(), angle.cos(), 0.];
                }
                "view" => camera.vertical_fov_radians += 0.0001,
                "height" => env.world_y += 1.,
                _ => {}
            }
            let before = context.profile_snapshot().unwrap();
            let start = std::time::Instant::now();
            a.prepare(env, &camera, 16. / 9., 0, None).unwrap();
            cpu.push(start.elapsed().as_secs_f64() * 1e6);
            gpu.push((context.profile_snapshot().unwrap().gpu_ns - before.gpu_ns) as f64 / 1e6);
        }
        let raw = gpu.clone();
        gpu.sort_by(f64::total_cmp);
        cpu.sort_by(f64::total_cmp);
        eprintln!(
            "update {kind}: GPU p50={:.4} p95={:.4} ms; CPU including sync p95={:.1} us; GPU samples={raw:?}",
            gpu[12], gpu[23], cpu[23]
        );
    }
}

// These coefficients and samples are an independent CPU oracle for the frozen spectral fit.
// Neither the raw tristimulus conversion nor the source normalization reads a GPU result.
fn spectral_rgb_reference(spectrum: [f32; 4]) -> [f64; 3] {
    let columns = [
        [5.8809709548950195, -7.0044331550598145, 90.48178100585938],
        [-14.151336669921875, 54.43069839477539, 8.974457740783691],
        [87.37126159667969, 58.01542282104492, -1.4691294431686401],
        [31.710432052612305, -1.2180235385894775, 0.05091293156147003],
    ];
    std::array::from_fn(|channel| {
        (0..4)
            .map(|band| f64::from(spectrum[band]) * columns[band][channel])
            .sum()
    })
}

fn solar_source_reference() -> ([f64; 3], f64) {
    let raw = spectral_rgb_reference([2.0108538, 1.9255825, 1.856787, 1.5677555]);
    let luminance = raw[0] * 0.262700212 + raw[1] * 0.677998072 + raw[2] * 0.059301716;
    let scale = 8.0 / luminance;
    (raw.map(|value| value * scale), scale)
}

fn assert_radiance_close(actual: f32, expected: f64, context: &str) {
    let tolerance = 2e-6f64.max(expected.abs() * 3e-5);
    assert!(
        actual.is_finite() && (f64::from(actual) - expected).abs() <= tolerance,
        "{context}: {actual} vs {expected} (tolerance {tolerance})"
    );
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_atmosphere_solar_source_matches_raw_spectrum_and_projected_disk_integral() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    atmosphere
        .prepare(
            Environment {
                sun_direction: [0.0, 1.0, 0.0],
                altitude_offset_meters: 300,
                ..Default::default()
            },
            &camera(),
            16.0 / 9.0,
            0,
            None,
        )
        .unwrap();
    let spectra = [
        [1.0, 0.0, 0.0, 0.0],
        [0.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 1.0, 0.0],
        [0.0, 0.0, 0.0, 1.0],
        [2.0108538, 1.9255825, 1.856787, 1.5677555],
        [0.2, 0.5, 0.7, 0.9],
    ];
    let input = spectra
        .iter()
        .flatten()
        .flat_map(|value| f32::to_le_bytes(*value))
        .collect::<Vec<_>>();
    let actual = execute(&atmosphere, 10, spectra.len() as u32, &input);
    let (source_rgb, scale) = solar_source_reference();
    for (index, spectrum) in spectra.into_iter().enumerate() {
        let raw = spectral_rgb_reference(spectrum);
        for channel in 0..3 {
            assert_radiance_close(actual[index * 8 + channel], raw[channel], "raw spectrum");
            assert_radiance_close(
                actual[index * 8 + 4 + channel],
                (raw[channel] * scale).max(0.0),
                "normalized spectrum",
            );
        }
    }
    let samples = 4096u32;
    let input = (0..samples)
        .flat_map(|index| {
            let azimuth = index.reverse_bits() as f64 / 4_294_967_296.0;
            [
                (index as f32 + 0.5) / samples as f32,
                azimuth as f32,
                0.0,
                0.0,
            ]
            .map(f32::to_le_bytes)
            .into_iter()
            .flatten()
        })
        .collect::<Vec<_>>();
    let actual = execute(&atmosphere, 11, samples, &input);
    let mut integral = [0.0f64; 3];
    let projected_angle = std::f64::consts::PI * 0.00471f64.sin().powi(2);
    let solid_angle = 4.0 * std::f64::consts::PI * (0.00471f64 * 0.5).sin().powi(2);
    for row in actual.as_chunks::<8>().0 {
        assert_eq!(
            row[3], 1.0,
            "sampled Sun direction failed the actual disk gate"
        );
        for channel in 0..3 {
            assert!(row[channel].is_finite());
            assert!(row[4 + channel] > 0.0 && row[4 + channel] <= 1.0);
            integral[channel] += f64::from(row[channel]) / f64::from(samples);
        }
        assert_radiance_close(row[7], 1.0 / solid_angle, "Sun sampling PDF");
    }
    for channel in 0..3 {
        let relative_error = (integral[channel] / source_rgb[channel] - 1.0).abs();
        assert!(
            relative_error < 3e-5,
            "disk integral: {integral:?} vs {source_rgb:?}"
        );
    }
    let source_y =
        source_rgb[0] * 0.262700212 + source_rgb[1] * 0.677998072 + source_rgb[2] * 0.059301716;
    assert!((source_y - 8.0).abs() < 1e-12);
    eprintln!(
        "solar disk samples={samples}; projectedAngle={projected_angle}; rawRGB={:?}; sourceRGB={source_rgb:?}; integralRGB={integral:?}; sourceY={source_y}",
        spectral_rgb_reference(spectra[4]),
    );
}

#[test]
#[ignore = "requires Vulkan ray-query device"]
fn gpu_atmosphere_solar_scale_drives_actual_consumers_without_scaling_stars_or_transmittance() {
    let context = Context::new().unwrap();
    let mut atmosphere = Atmosphere::new(&context).unwrap();
    atmosphere
        .prepare(
            Environment {
                sun_direction: [0.6, 0.8, 0.0],
                altitude_offset_meters: 300,
                ..Default::default()
            },
            &camera(),
            16.0 / 9.0,
            0,
            None,
        )
        .unwrap();
    let scales = [0.25f32, 1.0, 2.0];
    let input = [f32::NAN, 1e20]
        .into_iter()
        .flat_map(|poison| {
            scales.into_iter().flat_map(move |scale| {
                [scale, poison, 0.025, 400.0]
                    .map(f32::to_le_bytes)
                    .into_iter()
                    .flatten()
            })
        })
        .collect::<Vec<_>>();
    let actual = execute(&atmosphere, 12, 6, &input);
    let rows = actual.as_chunks::<48>().0;
    let reference = &rows[1]; // Solar scale one, with the former sky lane poisoned by NaN.
    let (source_rgb, _) = solar_source_reference();
    for (index, row) in rows.iter().enumerate() {
        let scale = f64::from(scales[index % 3]);
        assert!(row.iter().all(|value| value.is_finite()));
        for channel in 0..3 {
            for field in [0, 1, 3, 4] {
                assert_radiance_close(
                    row[field * 4 + channel],
                    f64::from(reference[field * 4 + channel]) * scale,
                    "solar-scaled production consumer",
                );
            }
            for field in [5, 6, 8, 9, 10, 11] {
                assert_eq!(
                    row[field * 4 + channel].to_bits(),
                    reference[field * 4 + channel].to_bits(),
                    "solar intensity changed an unscaled source, T, or star field"
                );
            }
            let star = f64::from(row[6 * 4 + channel]);
            let sky = f64::from(row[channel]);
            assert_radiance_close(row[2 * 4 + channel], sky + star, "ReSTIR sky and stars");
            assert_radiance_close(
                row[7 * 4 + channel],
                sky + star,
                "Primary secondary sky and stars",
            );
            assert_radiance_close(
                row[1 * 4 + channel],
                f64::from(row[3 * 4 + channel]) + f64::from(row[8 * 4 + channel]) * scale,
                "Primary Sun disk plus sky",
            );
            assert_radiance_close(
                row[4 * 4 + channel],
                f64::from(row[11 * 4 + channel]) * scale,
                "actual realtime aerial composition",
            );
            assert_radiance_close(
                row[6 * 4 + channel],
                f64::from([0.8f32, 0.4, 0.2][channel]) * 0.025 * f64::from(row[9 * 4 + channel]),
                "independent star map times distant transmittance",
            );
            assert_radiance_close(
                row[10 * 4 + channel],
                source_rgb[channel],
                "source solar RGB",
            );
        }
        if index >= 3 {
            assert_eq!(
                row,
                &rows[index - 3],
                "former sky lane affected actual consumer output"
            );
        }
    }
    for field in [0, 1, 3, 4, 6, 11] {
        assert!(
            reference[field * 4..field * 4 + 3]
                .iter()
                .all(|value| *value > 0.0)
        );
    }
    eprintln!(
        "actual Primary/ReSTIR/realtime aerial scale cases={scales:?}; former sky lane=NaN/1e20; T and stars bit-identical; base sky={:?}; aerialS={:?}; stars={:?}",
        &reference[..3],
        &reference[44..47],
        &reference[24..27],
    );
}
