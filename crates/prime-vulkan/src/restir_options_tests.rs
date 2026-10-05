//! Windowless RR output statistics, using the production pipeline without NGX.
use super::*;

struct StatisticsPipeline {
    context: Arc<Context>,
    descriptor_layout: vk::DescriptorSetLayout,
    layout: vk::PipelineLayout,
    pool: vk::DescriptorPool,
    set: vk::DescriptorSet,
    pipeline: vk::Pipeline,
}

impl Drop for StatisticsPipeline {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            unsafe {
                self.context.device.destroy_pipeline(self.pipeline, None);
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

impl StatisticsPipeline {
    fn new(context: &Arc<Context>, uniform: &Buffer) -> Self {
        let mut result = Self {
            context: context.clone(),
            descriptor_layout: vk::DescriptorSetLayout::null(),
            layout: vk::PipelineLayout::null(),
            pool: vk::DescriptorPool::null(),
            set: vk::DescriptorSet::null(),
            pipeline: vk::Pipeline::null(),
        };
        unsafe {
            let bindings = [vk::DescriptorSetLayoutBinding::default()
                .binding(23)
                .descriptor_count(1)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .stage_flags(vk::ShaderStageFlags::COMPUTE)];
            result.descriptor_layout = context
                .device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .unwrap();
            let layouts = [result.descriptor_layout];
            result.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts),
                    None,
                )
                .unwrap();
            let sizes = [vk::DescriptorPoolSize {
                ty: vk::DescriptorType::UNIFORM_BUFFER,
                descriptor_count: 1,
            }];
            result.pool = context
                .device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(1)
                        .pool_sizes(&sizes),
                    None,
                )
                .unwrap();
            result.set = context
                .device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(result.pool)
                        .set_layouts(&layouts),
                )
                .unwrap()[0];
            let info = [vk::DescriptorBufferInfo::default()
                .buffer(uniform.buffer)
                .range(restir::UNIFORM_BYTES)];
            context.device.update_descriptor_sets(
                &[vk::WriteDescriptorSet::default()
                    .dst_set(result.set)
                    .dst_binding(23)
                    .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                    .buffer_info(&info)],
                &[],
            );
            let words = ash::util::read_spv(&mut std::io::Cursor::new(
                prime_shaders::restir_rr_statistics(),
            ))
            .unwrap();
            let module = ShaderModule {
                context,
                handle: context
                    .device
                    .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
                    .unwrap(),
            };
            result.pipeline = context
                .device
                .create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default()
                        .layout(result.layout)
                        .stage(
                            vk::PipelineShaderStageCreateInfo::default()
                                .stage(vk::ShaderStageFlags::COMPUTE)
                                .module(module.handle)
                                .name(c"main"),
                        )],
                    None,
                )
                .unwrap()[0];
        }
        result
    }

    fn execute(&self, extent: [u32; 2]) {
        self.context
            .submit_named("restir_rr_statistics_oracle", |command| unsafe {
                self.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::HOST,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::HOST_WRITE)
                        .dst_access_mask(
                            vk::AccessFlags::UNIFORM_READ
                                | vk::AccessFlags::SHADER_READ
                                | vk::AccessFlags::SHADER_WRITE,
                        )],
                    &[],
                    &[],
                );
                self.context.device.cmd_bind_pipeline(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.pipeline,
                );
                self.context.device.cmd_bind_descriptor_sets(
                    command,
                    vk::PipelineBindPoint::COMPUTE,
                    self.layout,
                    0,
                    &[self.set],
                    &[],
                );
                self.context.device.cmd_dispatch(
                    command,
                    extent[0].div_ceil(8),
                    extent[1].div_ceil(8),
                    1,
                );
                self.context.device.cmd_pipeline_barrier(
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
    }
}

fn word(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn address(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

fn read_word(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn morton([x, y]: [u32; 2], extent: [u32; 2]) -> usize {
    let tile = (y / 16) * extent[0].div_ceil(16) + x / 16;
    let local: u32 = (0..4)
        .map(|bit| (((x >> bit) & 1) << (2 * bit)) | (((y >> bit) & 1) << (2 * bit + 1)))
        .sum();
    (tile * 256 + local) as usize
}

fn donor([x, y]: [u32; 2], extent: [u32; 2]) -> [i32; 2] {
    match (x + 2 * y) % 6 {
        0 => [-1, y as i32],
        1 => [extent[0] as i32, y as i32],
        2 => [0, 0],
        3 => [extent[0] as i32 - 1, extent[1] as i32 - 1],
        4 => [1, extent[1] as i32 / 2],
        _ => [x as i32, y as i32],
    }
}

fn age([x, y]: [u32; 2]) -> u32 {
    (7 * x + 13 * y) % 57
}

fn previous([x, y]: [u32; 2]) -> f32 {
    // Non-affine field catches footprint, normalization and step errors.
    (100 + 3 * x * x + 5 * y + (x * y) % 11) as f32 / 1000.
}

fn energy([x, y]: [u32; 2]) -> f32 {
    if x == 16 && y >= 8 {
        0.
    } else if (x == 0 || x == 8 || x == 16) && (y == 0 || y == 8) {
        8.
    } else if x == 2 && y == 0 {
        f32::NAN
    } else if x == 3 && y == 0 {
        -1.
    } else {
        1.
    }
}

fn decoupled_energy([x, y]: [u32; 2]) -> f32 {
    if (x == 5 && y == 2) || (x == 13 && y == 10) {
        32.
    } else {
        2.
    }
}

#[test]
#[ignore = "windowless Vulkan production RR statistics; synthetic BDA data, no NGX"]
fn gpu_restir_rr_statistics_bootstrap_ema_firefly_and_inactive_pointers() {
    let context = Context::new().unwrap();
    let extent: [u32; 2] = [17, 11]; // Morton boundary plus partial 8x8 groups on both axes.
    let pixels = (extent[0].div_ceil(16) * extent[1].div_ceil(16) * 256) as usize;
    let reservoir = 0;
    let ages = 80 * pixels;
    let reprojection = ages + 4 * pixels;
    let old = reprojection + 8 * pixels;
    let shading = old + 4 * pixels;
    let smooth = shading + 16 * pixels;
    let fireflies = smooth + 4 * pixels;
    let bytes = fireflies + 4 * pixels;
    let storage = Buffer::new(
        &context,
        bytes as u64,
        vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
        true,
    )
    .unwrap();
    let uniform = Buffer::new(
        &context,
        restir::UNIFORM_BYTES,
        vk::BufferUsageFlags::UNIFORM_BUFFER,
        true,
    )
    .unwrap();
    let pipeline = StatisticsPipeline::new(&context, &uniform);
    let mut initial = vec![0xCD; bytes];
    for y in 0..extent[1] {
        for x in 0..extent[0] {
            let pixel = [x, y];
            let i = morton(pixel, extent);
            initial[80 * i..80 * (i + 1)].fill(0);
            word(&mut initial, 80 * i + 4, 1_f32.to_bits());
            for axis in 0..3 {
                word(&mut initial, 80 * i + 8 + 4 * axis, energy(pixel).to_bits());
            }
            word(&mut initial, ages + 4 * i, age(pixel));
            for (axis, value) in donor(pixel, extent).into_iter().enumerate() {
                word(&mut initial, reprojection + 8 * i + 4 * axis, value as u32);
            }
            word(&mut initial, old + 4 * i, previous(pixel).to_bits());
            for axis in 0..4 {
                word(
                    &mut initial,
                    shading + 16 * i + 4 * axis,
                    if axis == 3 {
                        0.
                    } else {
                        decoupled_energy(pixel)
                    }
                    .to_bits(),
                );
            }
        }
    }
    // flags, mode, temporal, factor, EMA, firefly, strength, bank, decoupled color.
    let cases: [(u32, u32, u32, f32, f32, u32, f32, u32, bool); 12] = [
        (1, 2, 1, 0.4, 0.2, 1, 0.7, 0, false), // Bootstrap: previous pointer zero.
        (3, 2, 1, 0.4, 0.2, 1, 0.7, 1, false),
        (3, 2, 1, 0.4, 0.0, 0, 0.7, 0, false),
        (3, 2, 1, 0.4, 1.0, 1, 1.0, 1, false),
        (1, 1, 1, 0.4, 0.2, 1, 0.0, 1, false),
        (1, 1, 1, 0.4, 0.2, 0, 0.7, 0, false), // All pointers zero.
        (0, 2, 1, 0.4, 0.2, 1, 0.7, 0, false),
        (2, 2, 1, 0.4, 0.2, 1, 0.7, 0, false), // Readiness alone cannot enable.
        (1, 0, 1, 0.4, 0.2, 1, 0.7, 0, false),
        (1, 2, 1, 0.0, 0.2, 1, 0.7, 0, false),
        (1, 2, 0, 0.4, 0.2, 1, 0.7, 0, false),
        (3, 2, 1, 0.4, 0.2, 1, 0.7, 1, true), // Only shadingSum is addressable.
    ];
    for (case, (flags, mode, temporal, factor, ema, detect, strength, bank, decoupled)) in
        cases.into_iter().enumerate()
    {
        let active = flags & 1 != 0 && mode != 0 && temporal != 0 && factor > 0.;
        let statistics = active && mode == 2;
        let detection = active && detect != 0;
        let mut constants = vec![0; restir::UNIFORM_BYTES as usize];
        word(&mut constants, 64, extent[0]);
        word(&mut constants, 68, extent[1]);
        word(&mut constants, 260, temporal);
        word(&mut constants, 472, if decoupled { 1 } else { bank });
        word(&mut constants, 488, u32::from(decoupled));
        for (i, value) in [factor, 0.5, ema, strength, 15., 0., 0., 0.]
            .into_iter()
            .enumerate()
        {
            word(&mut constants, 608 + 4 * i, value.to_bits());
        }
        for (i, value) in [flags, mode, 1, detect].into_iter().enumerate() {
            word(&mut constants, 640 + 4 * i, value);
        }
        let mut pointers = Vec::new();
        if detection {
            pointers.push((680, fireflies));
            pointers.push(if decoupled {
                (592, shading)
            } else {
                (if bank == 0 { 272 } else { 280 }, reservoir)
            });
        }
        if statistics {
            pointers.extend([(576, ages), (312, reprojection), (664, smooth)]);
            if flags & 2 != 0 {
                pointers.push((672, old));
            }
        }
        for (position, offset) in pointers {
            address(&mut constants, position, storage.address() + offset as u64);
        }
        storage.write(&initial).unwrap();
        uniform.write(&constants).unwrap();
        pipeline.execute(extent);
        let actual = storage.read(bytes).unwrap();
        assert_eq!(
            &actual[..smooth],
            &initial[..smooth],
            "case {case}: reservoir, ages, reprojection or previous statistics mutated"
        );
        let mut valid = vec![false; pixels];
        for y in 0..extent[1] {
            for x in 0..extent[0] {
                let pixel = [x, y];
                let i = morton(pixel, extent);
                valid[i] = true;
                if statistics {
                    let raw = f64::from(age(pixel).min(31)) / 40.;
                    let [px, py] = donor(pixel, extent);
                    let previous_valid = flags & 2 != 0
                        && px >= 0
                        && py >= 0
                        && px < extent[0] as i32
                        && py < extent[1] as i32;
                    let expected = if previous_valid {
                        let taps: Vec<_> = (-2..=2)
                            .flat_map(|dy| (-2..=2).map(move |dx| [px + 2 * dx, py + 2 * dy]))
                            .filter(|p| {
                                p[0] >= 0
                                    && p[1] >= 0
                                    && p[0] < extent[0] as i32
                                    && p[1] < extent[1] as i32
                            })
                            .map(|p| f64::from(previous(p.map(|v| v as u32))))
                            .collect();
                        f64::from(ema) * raw
                            + (1. - f64::from(ema)) * taps.iter().sum::<f64>() / taps.len() as f64
                    } else {
                        raw
                    };
                    let value = f32::from_bits(read_word(&actual, smooth + 4 * i));
                    assert!(
                        (f64::from(value) - expected).abs() < 2e-7,
                        "case {case}, {pixel:?}: EMA {value}, expected {expected}"
                    );
                } else {
                    assert_eq!(read_word(&actual, smooth + 4 * i), 0xCDCDCDCD);
                }
                if detection {
                    let output_energy = if decoupled { decoupled_energy } else { energy };
                    let gx = x / 8 * 8;
                    let gy = y / 8 * 8;
                    let positive: Vec<_> = (gy..(gy + 8).min(extent[1]))
                        .flat_map(|yy| (gx..(gx + 8).min(extent[0])).map(move |xx| [xx, yy]))
                        .map(output_energy)
                        .filter(|v| v.is_finite() && *v > 0.)
                        .collect();
                    let mean = if positive.is_empty() {
                        0.
                    } else {
                        positive.iter().map(|&v| f64::from(v)).sum::<f64>() / positive.len() as f64
                    };
                    let multiplier = 10. / f64::from(strength).clamp(1e-6, 1.) - 9.;
                    let expected = u32::from(f64::from(output_energy(pixel)) > mean * multiplier);
                    assert_eq!(
                        read_word(&actual, fireflies + 4 * i),
                        expected,
                        "case {case}, {pixel:?}: group firefly mismatch"
                    );
                } else {
                    assert_eq!(read_word(&actual, fireflies + 4 * i), 0xCDCDCDCD);
                }
            }
        }
        for (i, valid) in valid.into_iter().enumerate() {
            if !valid {
                assert_eq!(read_word(&actual, smooth + 4 * i), 0xCDCDCDCD);
                assert_eq!(read_word(&actual, fireflies + 4 * i), 0xCDCDCDCD);
            }
        }
    }
}
