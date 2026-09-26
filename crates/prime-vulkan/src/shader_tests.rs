//! Windowless behavioral tests executing the actual production Slang modules.
use super::*;
use prime_scene::{Instance, Prototype, Triangle};

mod reference;
use reference::{cross, dot, inverse, normalized, sub, transform};

const FOUNDATIONS: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/foundations.spv"));
const DISPLAY: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/display.spv"));
const INTERSECTION: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/intersection.spv"));

// Test-only submission/readback. Production shader libraries declare no bindings.
fn run(
    context: &Arc<Context>,
    code: &[u8],
    input: &[u32],
    output_words: usize,
    mode_count: [u32; 2],
    geometry: Option<&Geometry>,
) -> Vec<u32> {
    let bytes: Vec<_> = input.iter().flat_map(|v| v.to_le_bytes()).collect();
    let source = Buffer::upload(context, &bytes, vk::BufferUsageFlags::STORAGE_BUFFER).unwrap();
    let destination = Buffer::new(
        context,
        (output_words * 4) as u64,
        vk::BufferUsageFlags::STORAGE_BUFFER,
        true,
    )
    .unwrap();
    // Reuse the production RAII owner, with a private fixture layout and one set.
    let mut pipeline = Pipeline {
        context: context.clone(),
        layout: vk::PipelineLayout::null(),
        descriptor_layout: vk::DescriptorSetLayout::null(),
        pool: vk::DescriptorPool::null(),
        descriptors: [vk::DescriptorSet::null(); FRAME_SLOTS],
        pipeline: vk::Pipeline::null(),
    };
    unsafe {
        let types: Vec<_> = (0..if geometry.is_some() { 7 } else { 2 })
            .map(|i| {
                if i == 2 {
                    vk::DescriptorType::ACCELERATION_STRUCTURE_KHR
                } else {
                    vk::DescriptorType::STORAGE_BUFFER
                }
            })
            .collect();
        let bindings: Vec<_> = types
            .iter()
            .enumerate()
            .map(|(i, &ty)| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(i as u32)
                    .descriptor_count(1)
                    .descriptor_type(ty)
                    .stage_flags(vk::ShaderStageFlags::COMPUTE)
            })
            .collect();
        pipeline.descriptor_layout = context
            .device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .unwrap();
        let layouts = [pipeline.descriptor_layout];
        let ranges = [vk::PushConstantRange::default()
            .size(16)
            .stage_flags(vk::ShaderStageFlags::COMPUTE)];
        pipeline.layout = context
            .device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&layouts)
                    .push_constant_ranges(&ranges),
                None,
            )
            .unwrap();
        let mut sizes = vec![vk::DescriptorPoolSize {
            ty: vk::DescriptorType::STORAGE_BUFFER,
            descriptor_count: 6,
        }];
        if geometry.is_some() {
            sizes.push(vk::DescriptorPoolSize {
                ty: vk::DescriptorType::ACCELERATION_STRUCTURE_KHR,
                descriptor_count: 1,
            });
        }
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
        let mut buffers = vec![(0, &source), (1, &destination)];
        if let Some(g) = geometry {
            buffers.extend([
                (3, &g.textures.metadata),
                (4, &g.textures.texels),
                (5, &g.objects.metadata),
                (6, &g.static_bases),
            ]);
        }
        for (binding, buffer) in buffers {
            let info = [vk::DescriptorBufferInfo::default()
                .buffer(buffer.buffer)
                .range(buffer.size)];
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(binding)
                    .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                    .buffer_info(&info)],
                &[],
            );
        }
        if let Some(g) = geometry {
            let handles = [g.top.handle()];
            let mut as_info = vk::WriteDescriptorSetAccelerationStructureKHR::default()
                .acceleration_structures(&handles);
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(set)
                    .dst_binding(2)
                    .descriptor_count(1)
                    .descriptor_type(vk::DescriptorType::ACCELERATION_STRUCTURE_KHR)
                    .push_next(&mut as_info)],
                &[],
            );
        }
        let words = ash::util::read_spv(&mut Cursor::new(code)).unwrap();
        let module = context
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
                        .module(module)
                        .name(c"main"),
                )],
            None,
        );
        context.device.destroy_shader_module(module, None);
        pipeline.pipeline = built.unwrap()[0];
    }
    let push: Vec<_> = [mode_count[0], mode_count[1], 0, 0]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect();
    context
        .submit_named("shader_contract", |command| unsafe {
            context.device.cmd_bind_pipeline(
                command,
                vk::PipelineBindPoint::COMPUTE,
                pipeline.pipeline,
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
                .cmd_dispatch(command, mode_count[1].div_ceil(64), 1, 1);
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
    destination
        .read(output_words * 4)
        .unwrap()
        .as_chunks::<4>()
        .0
        .iter()
        .map(|v| u32::from_le_bytes(*v))
        .collect()
}

#[test]
#[ignore = "requires Vulkan ray query; executes production Slang on GPU"]
fn gpu_z_sobol_matches_u64_oracle_and_dyadic_nets() {
    let context = Context::new().unwrap();
    let mut input = Vec::new();
    for r in 0..=16 {
        for s in 0..=20 {
            for trial in 0..8 {
                let h = reference::hash(57 + trial + 131 * r + 7919 * s);
                input.extend([
                    h & ((1 << r) - 1),
                    h.rotate_left(13) & ((1 << r) - 1),
                    h.rotate_left(21) & ((1 << s) - 1),
                    r,
                    s,
                    reference::hash(h),
                    trial * 12345,
                    0,
                ]);
            }
            input.extend([
                (1 << r) - 1,
                (1 << r) - 1,
                (1 << s) - 1,
                r,
                s,
                u32::MAX,
                u32::MAX,
                0,
            ]);
        }
    }
    let actual = run(
        &context,
        FOUNDATIONS,
        &input,
        input.len(),
        [0, (input.len() / 8) as u32],
        None,
    );
    for (case, got) in input
        .as_chunks::<8>()
        .0
        .iter()
        .zip(actual.as_chunks::<8>().0)
    {
        let expected = reference::sobol(case);
        assert_eq!(
            &got[..3],
            &[expected[0], expected[1], expected[0]],
            "case {case:?}"
        );
        for channel in 0..3 {
            let expected = (got[channel] >> 8) as f32 / 16777216.0;
            assert_eq!(f32::from_bits(got[channel + 4]), expected);
            assert!((0.0..1.0).contains(&expected));
        }
    }
    // Every elementary rectangle of volume 1/N contains one sample, for a full
    // pixel sample set and a power-of-two square pixel neighborhood. Early sample
    // prefixes are intentionally not asserted to have this complete-set property.
    for side in [1, 2, 4] {
        for s in 0..=6 {
            let mut cases = Vec::new();
            for y in 0..side {
                for x in 0..side {
                    for sample in 0..1 << s {
                        cases.extend([x + 4, y + 8, sample, 4, s, 0x5a17_4b39, 7, 0]);
                    }
                }
            }
            let bits = run(
                &context,
                FOUNDATIONS,
                &cases,
                cases.len(),
                [0, (cases.len() / 8) as u32],
                None,
            );
            let n = bits.len() / 8;
            for x_bits in 0..=n.ilog2() {
                let y_bits = n.ilog2() - x_bits;
                let mut bins = vec![0; n];
                for sample in bits.as_chunks::<8>().0 {
                    let x = (u64::from(sample[0]) >> (32 - x_bits)) as usize;
                    let y = (u64::from(sample[1]) >> (32 - y_bits)) as usize;
                    bins[(x << y_bits) | y] += 1;
                }
                assert!(
                    bins.iter().all(|&v| v == 1),
                    "side={side} S={s} partition={x_bits}/{y_bits}"
                );
            }
        }
    }
}

#[test]
#[ignore = "requires Vulkan ray query; executes production Slang on GPU"]
fn gpu_color_transfer_and_working_space_match_f64_reference() {
    let context = Context::new().unwrap();
    let mut colors = vec![
        [1.0, 0.0, 0.0],
        [0.0, 1.0, 0.0],
        [0.0, 0.0, 1.0],
        [0.18, 0.4, 0.9],
    ];
    for value in [
        0.0, 0.001, 0.0031307, 0.0031308, 0.0031309, 0.040449, 0.04045, 0.040451, 0.5, 1.0, 4.0,
        16.0,
    ] {
        colors.push([value; 3]);
    }
    let input: Vec<_> = colors
        .iter()
        .flat_map(|c| [c[0], c[1], c[2], 0.0].map(f32::to_bits))
        .collect();
    let result = run(
        &context,
        FOUNDATIONS,
        &input,
        colors.len() * 16,
        [1, colors.len() as u32],
        None,
    );
    let matrix = [
        [0.627403896, 0.329283038, 0.043313066],
        [0.069097289, 0.919540395, 0.011362316],
        [0.016391439, 0.088013308, 0.895595253],
    ];
    for (color, words) in colors.iter().zip(result.as_chunks::<16>().0) {
        let got = words.map(f32::from_bits).map(f64::from);
        let linear = color.map(|v| reference::decode(f64::from(v)));
        for i in 0..3 {
            close(got[i], dot(matrix[i], linear), 3e-6, "working primary");
            close(got[i + 4], f64::from(color[i]), 3e-6, "source round trip");
            close(got[i + 8], linear[i], 3e-6, "linear color round trip");
            close(
                got[i + 12],
                reference::encode(f64::from(color[i])),
                3e-6,
                "sRGB toe",
            );
        }
        close(
            got[3],
            dot([0.212639006, 0.715168679, 0.072192315], linear),
            3e-6,
            "luminance",
        );
    }
    let negative = [-1.0f32, -0.0001, 0.0, 0.0].map(f32::to_bits);
    let result = run(&context, FOUNDATIONS, &negative, 16, [1, 1], None);
    assert_eq!(
        &result[12..15],
        &[0; 3],
        "negative display channels clip to zero"
    );
}

fn close(actual: f64, expected: f64, tolerance: f64, label: &str) {
    assert!(
        (actual - expected).abs() <= tolerance * expected.abs().max(1.0),
        "{label}: {actual} != {expected}"
    );
}

#[test]
#[ignore = "requires Vulkan; compares primeDRT against frozen legacy Slang"]
fn gpu_prime_drt_matches_legacy_across_controls_and_headroom() {
    let context = Context::new().unwrap();
    let mut colors = vec![
        [0.0; 3],
        [-1.0, 0.1, 0.3],
        [1e30, 1e-10, 1e10],
        [f32::NAN, 1.0, 0.0],
        [f32::INFINITY; 3],
    ];
    for value in [
        1e-9,
        0.0031308,
        0.18 - 1e-6,
        0.18,
        0.18 + 1e-6,
        1.0,
        16.0,
        46.08,
        1e10,
    ] {
        for primary in [
            [1.0; 3],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
            [1.0, 1.0, 0.0],
            [1.0, 0.0, 1.0],
            [0.0, 1.0, 1.0],
            [1.0, 1.00001, 1.0],
            [1.0, 0.01, 0.9],
        ] {
            colors.push(primary.map(|v| v * value));
        }
    }
    let mut input = Vec::new();
    for headroom in [1.0, 2.0, 16.0, 10000.0] {
        for exposure_multiplier in [1.0 / 256.0, 1.0, 256.0] {
            for hue_compensation in [0.0, 0.75, 1.0] {
                for saturation_compensation in [0.0, 0.08, 0.5] {
                    let p = PrimeDrtSettings {
                        exposure_multiplier,
                        hue_compensation,
                        saturation_compensation,
                    }
                    .prepare(headroom)
                    .unwrap();
                    for &color in &colors {
                        input.extend([color[0], color[1], color[2], 0.0].map(f32::to_bits));
                        input.extend(p.values.map(f32::to_bits));
                        input.extend([headroom, 0.0, 0.0, 0.0].map(f32::to_bits));
                    }
                }
            }
        }
    }
    let count = input.len() / 16;
    let result = run(
        &context,
        DISPLAY,
        &input,
        count * 8,
        [0, count as u32],
        None,
    );
    for (case, words) in input
        .as_chunks::<16>()
        .0
        .iter()
        .zip(result.as_chunks::<8>().0)
    {
        let values = words.map(f32::from_bits);
        for channel in 0..3 {
            assert!(
                values[channel].is_finite(),
                "primeDRT finite output for {:?}",
                case.map(f32::from_bits)
            );
            close(
                f64::from(values[channel]),
                f64::from(values[channel + 4]),
                8e-6,
                "legacy primeDRT equivalence",
            );
            assert!(values[channel] >= 0.0 && values[channel] <= f32::from_bits(case[6]));
        }
    }
}

#[test]
#[ignore = "requires Vulkan ray query; executes production Slang on GPU"]
fn gpu_ray_error_bounds_cover_reconstruction_and_both_spawn_sides() {
    let context = Context::new().unwrap();
    let mut input = Vec::new();
    let mut expected = Vec::new();
    for size in [0.0001, 1.0, 128.0] {
        for translation in [0.0, 2048.0, 65536.0] {
            for matrix in [
                [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
                [[-1.5, 0.2, 0.1], [0.0, 0.7, 0.3], [0.2, 0.0, 1.25]],
            ] {
                let o2w: [[f32; 4]; 3] = std::array::from_fn(|i| {
                    [matrix[i][0], matrix[i][1], matrix[i][2], translation]
                });
                let w2o = inverse(o2w);
                let triangle = [[0.1, 0.2, -0.3], [1.1, 0.4, -0.1], [-0.2, 1.2, 0.3]]
                    .map(|v| v.map(|x| x * size));
                let bary = [0.17f32, 0.63];
                let local = std::array::from_fn(|i| {
                    f64::from(triangle[0][i])
                        + f64::from(bary[0])
                            * (f64::from(triangle[1][i]) - f64::from(triangle[0][i]))
                        + f64::from(bary[1])
                            * (f64::from(triangle[2][i]) - f64::from(triangle[0][i]))
                });
                let point = transform(o2w, local);
                let edge1 = sub(triangle[1].map(f64::from), triangle[0].map(f64::from));
                let edge2 = sub(triangle[2].map(f64::from), triangle[0].map(f64::from));
                let linear = o2w.map(|mut row| {
                    row[3] = 0.0;
                    row
                });
                let mut normal =
                    normalized(cross(transform(linear, edge1), transform(linear, edge2)));
                let local_normal = cross(edge1, edge2);
                let inverse_transpose_normal =
                    std::array::from_fn(|i| dot(w2o.map(|row| f64::from(row[i])), local_normal));
                if dot(normal, inverse_transpose_normal) < 0.0 {
                    normal = normal.map(|v| -v);
                }
                expected.push((point, normal));
                for v in triangle {
                    input.extend([v[0], v[1], v[2], 0.0].map(f32::to_bits));
                }
                input.extend([bary[0], bary[1], 0.0, 0.0].map(f32::to_bits));
                for row in o2w.into_iter().chain(w2o) {
                    input.extend(row.map(f32::to_bits));
                }
            }
        }
    }
    let result = run(
        &context,
        FOUNDATIONS,
        &input,
        expected.len() * 16,
        [2, expected.len() as u32],
        None,
    );
    for ((point, reference_normal), words) in expected.into_iter().zip(result.as_chunks::<16>().0) {
        let values = words.map(f32::from_bits).map(f64::from);
        assert!(values.iter().all(|v| v.is_finite()));
        let p: [f64; 3] = values[..3].try_into().unwrap();
        let n: [f64; 3] = values[4..7].try_into().unwrap();
        close(dot(n, n), 1.0, 1e-6, "unit normal");
        for i in 0..3 {
            close(
                n[i],
                reference_normal[i],
                1e-6,
                "transformed geometric normal",
            );
        }
        assert!(values[3] > 0.0);
        assert!(
            dot(sub(p, point), reference_normal).abs() <= values[3],
            "reconstruction exceeds error bound"
        );
        let front = values[8..11].try_into().unwrap();
        let back = values[12..15].try_into().unwrap();
        assert!(
            dot(sub(front, point), reference_normal) > 0.0,
            "front inside error plane"
        );
        assert!(
            dot(sub(back, point), reference_normal) < 0.0,
            "back inside error plane"
        );
    }
}

#[test]
#[ignore = "requires Vulkan hardware ray query; tests actual secondary intersections"]
fn gpu_spawn_avoids_self_hits_without_skipping_nearby_occluders() {
    let context = Context::new().unwrap();
    for scale in [0.001, 1.0, 100.0] {
        for translated in [false, true] {
            // Mirror, nonuniform scale and shear; camera rays are defined in object space.
            let mut transform = [-1.5, 0.2, 0.1, 0.0, 0.0, 0.7, 0.3, 0.0, 0.2, 0.0, 1.25, 0.0];
            if translated {
                transform[3] = 4096.0;
                transform[7] = -2048.0;
                transform[11] = 512.0;
            }
            let matrix: [[f32; 4]; 3] =
                std::array::from_fn(|i| transform[4 * i..4 * i + 4].try_into().unwrap());
            let triangles = vec![Triangle {
                positions: [
                    [-scale, -scale, 0.0],
                    [scale, -scale, 0.0],
                    [0.0, scale, 0.0],
                ],
                colors: [[1.0; 4]; 3],
                uvs: [[0.0; 2]; 3],
                texture_id: 0,
                flags: 0,
            }];
            let mut source = InstanceScene {
                resource_revision: 1,
                instance_revision: 1,
                ..Default::default()
            };
            source.prototypes.insert(
                1,
                Prototype {
                    revision: 1,
                    triangles: triangles.clone().into(),
                    bounds: [[-scale; 3], [scale; 3]],
                },
            );
            source.instances.insert(
                1,
                Instance {
                    revision: 1,
                    prototype_id: 1,
                    origin: [0.0; 3],
                    transform,
                    texture_id: u32::MAX,
                    flags: u32::MAX,
                    tint: [255; 4],
                    uv_transform: [1.0, 1.0, 0.0, 0.0],
                },
            );
            let scene = Scene::default();
            let mut geometry = Geometry::new(&context, &scene).unwrap();
            geometry
                .prepare_dynamic(
                    &context,
                    &scene,
                    &source,
                    0,
                    &mut cpu_profile::FrameCpu::default(),
                )
                .unwrap();
            let linear: [[f32; 4]; 3] = matrix.map(|mut row| {
                row[3] = 0.0;
                row
            });
            let point = reference::transform(matrix, [0.0; 3]);
            let incoming = normalized(reference::transform(linear, [0.0, 0.0, -1.0]));
            let origin = std::array::from_fn(|i| point[i] - incoming[i] * f64::from(scale) * 2.0);
            let mut rays = Vec::new();
            for local_direction in [
                [0.0, 0.0, 1.0],
                [0.0, 0.0, -1.0],
                [1.0, 0.0, 0.00001],
                [1.0, 0.0, -0.00001],
            ] {
                let outgoing = normalized(reference::transform(linear, local_direction));
                for v in [origin, incoming, outgoing] {
                    rays.extend([v[0] as f32, v[1] as f32, v[2] as f32, 0.0].map(f32::to_bits));
                }
            }
            let result = run(&context, INTERSECTION, &rays, 32, [0, 4], Some(&geometry));
            for case in result.as_chunks::<8>().0 {
                assert_eq!(
                    &case[..2],
                    &[1, 0],
                    "scale={scale}, translated={translated}"
                );
            }
            if !translated {
                // At the origin the legitimate gap is far smaller than the old 1e-4 minimum bias.
                let gap = scale * 0.00001;
                let mut blocker = triangles[0];
                for vertex in &mut blocker.positions {
                    vertex[2] = gap;
                }
                let prototype = source.prototypes.get_mut(&1).unwrap();
                prototype.triangles = vec![triangles[0], blocker].into();
                prototype.revision += 1;
                source.resource_revision += 1;
                geometry
                    .prepare_dynamic(
                        &context,
                        &scene,
                        &source,
                        0,
                        &mut cpu_profile::FrameCpu::default(),
                    )
                    .unwrap();
                // Approach the original from below, then transmit toward the close blocker.
                let below = std::array::from_fn(|i| point[i] + incoming[i] * f64::from(scale));
                let up = incoming.map(|v| -v);
                let rays: Vec<_> = [below, up, up]
                    .into_iter()
                    .flat_map(|v: [f64; 3]| {
                        [v[0] as f32, v[1] as f32, v[2] as f32, 0.0].map(f32::to_bits)
                    })
                    .collect();
                let result = run(&context, INTERSECTION, &rays, 8, [0, 1], Some(&geometry));
                assert_eq!(
                    &result[..2],
                    &[1, 1],
                    "near blocker skipped at scale={scale}"
                );
            }
        }
    }
}
