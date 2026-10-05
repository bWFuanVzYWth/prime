//! Real production option dispatches. These tests do not create shader variants or call the SDK.
use super::*;
use realtime_tests::camera;
use restir_tests::{
    complete_rgba, multiple_light_scene, renderer, settings, temporal_snapshot_words,
};
use std::collections::{HashMap, HashSet};

const WIDTH: u32 = 31;
const HEIGHT: u32 = 17;

#[test]
#[ignore = "requires windowless Vulkan; H0 and Offline must omit unused duplicate producers"]
fn gpu_restir_zero_history_and_offline_duplicate_consumers() {
    let fixture = multiple_light_scene(1);
    let mut zero = config_settings();
    zero.restir.history_length = 0;
    let mut spatial = zero;
    spatial.restir.history_length = 20;
    spatial.restir_spatial_only = true;
    let mut zero = renderer(zero);
    let mut spatial = renderer(spatial);
    for sequence in 0..3 {
        for current in [&mut zero, &mut spatial] {
            current
                .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
                .unwrap();
        }
        let state = zero.restir.as_ref().unwrap();
        assert!(!state.temporal_this_frame && !state.duplicate_this_frame);
        assert!(state.accepted_history().0); // H0 does not request a reset.
        assert!(state.auxiliary_for_test().sample_ids.is_none());
        assert!(spatial.restir.as_ref().unwrap().duplicate_this_frame);
        assert_eq!(
            temporal_snapshot_words(&zero, WIDTH, HEIGHT),
            temporal_snapshot_words(&spatial, WIDTH, HEIGHT)
        );
    }
    let mut dormant = config_settings();
    dormant.restir.history_length = 0;
    dormant.restir.distance_threshold *= 2.;
    zero.configure(dormant).unwrap();
    zero.render(&fixture, &camera(), WIDTH, HEIGHT, 3).unwrap();
    assert_eq!(
        word(&uniform(&zero), 524) & 8,
        0,
        "H0 threshold edits have no old-chart consumer"
    );
    assert_eq!(address(&uniform(&zero), 688), 0);
    let resumed = config_settings();
    zero.configure(resumed).unwrap();
    for sequence in 4..6 {
        zero.render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
            .unwrap();
        let state = zero.restir.as_ref().unwrap();
        assert!(state.temporal_this_frame && state.duplicate_this_frame);
        assert!(state.accepted_history().0);
        assert!(state.auxiliary_for_test().duplicate_counts.is_some());
        let (records, linear) = temporal_snapshot_words(&zero, WIDTH, HEIGHT);
        finite_output(&records, &linear);
        let ready = word(&uniform(&zero), 524) >> 2 & 1;
        assert_eq!(
            ready,
            u32::from(sequence > 4),
            "a newly allocated map needs its first accepted producer"
        );
    }
    let mut offline = config_settings();
    offline.mode = RenderMode::Offline;
    let mut disabled = offline;
    disabled.restir.duplicate_map = false;
    let mut enabled = renderer(offline);
    let mut disabled = renderer(disabled);
    for sequence in 0..2 {
        let a = enabled
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
            .unwrap();
        let b = disabled
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
            .unwrap();
        assert_eq!(a, b);
        assert_eq!(
            restir_tests::linear_history(&enabled, WIDTH, HEIGHT),
            restir_tests::linear_history(&disabled, WIDTH, HEIGHT)
        );
        let state = enabled.restir.as_ref().unwrap();
        assert!(!state.duplicate_this_frame && !state.accepted_history().0);
        assert!(state.auxiliary_for_test().sample_ids.is_none());
        assert_eq!(
            enabled
                .pipeline
                .as_ref()
                .unwrap()
                .restir
                .as_ref()
                .unwrap()
                .duplicate_map,
            vk::Pipeline::null()
        );
    }
    offline.restir.debug_view = 1;
    enabled.configure(offline).unwrap();
    enabled
        .render(&fixture, &camera(), WIDTH, HEIGHT, 2)
        .unwrap();
    let state = enabled.restir.as_ref().unwrap();
    assert!(state.duplicate_this_frame);
    assert!(state.auxiliary_for_test().duplicate_counts.is_some());
    assert!(!state.accepted_history().0);
}

fn config_settings() -> RenderSettings {
    // Route real FP32 radiance to binding24 for inspection. Display exposure is
    // independent and is never used in the estimator comparisons below.
    RenderSettings {
        auto_exposure_compensation: 0.1,
        ..settings(RenderMode::Realtime)
    }
}

fn morton(x: u32, y: u32, bits: u32) -> u32 {
    (0..bits).fold(0, |value, bit| {
        value | ((x >> bit & 1) << (bit * 2)) | ((y >> bit & 1) << (bit * 2 + 1))
    })
}

fn offset(x: u32, y: u32) -> usize {
    (((y / 16) * WIDTH.div_ceil(16) + x / 16) * 256 + morton(x, y, 4)) as usize
}

fn word(bytes: &[u8], position: usize) -> u32 {
    u32::from_le_bytes(bytes[position..position + 4].try_into().unwrap())
}

fn address(bytes: &[u8], position: usize) -> u64 {
    u64::from_le_bytes(bytes[position..position + 8].try_into().unwrap())
}

fn uniform(renderer: &Renderer) -> Vec<u8> {
    renderer
        .restir
        .as_ref()
        .unwrap()
        .uniform_for_test(0)
        .read(restir::UNIFORM_BYTES as usize)
        .unwrap()
}

fn read_buffer(renderer: &Renderer, source: &Buffer) -> Vec<u8> {
    let readback = Buffer::new_readback(&renderer.context, source.size).unwrap();
    renderer
        .context
        .submit_named("restir_config_readback", |command| unsafe {
            let before = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
            renderer.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &before,
                &[],
                &[],
            );
            renderer.context.device.cmd_copy_buffer(
                command,
                source.buffer,
                readback.buffer,
                &[vk::BufferCopy::default().size(source.size)],
            );
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(
                    vk::AccessFlags::HOST_READ
                        | vk::AccessFlags::SHADER_READ
                        | vk::AccessFlags::SHADER_WRITE,
                )];
            renderer.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &after,
                &[],
                &[],
            );
        })
        .unwrap();
    readback.read(source.size as usize).unwrap()
}

fn finite_output(records: &[[u32; 20]], linear: &[f32]) {
    assert_eq!(records.len(), (WIDTH * HEIGHT) as usize);
    assert_eq!(linear.len(), (WIDTH * HEIGHT * 4) as usize);
    for record in records {
        // Scalar layout: M/weight/integrand0..4, lightPdf8, barycentrics11..12,
        // Jacobian13, incident radiance14..16, connection direction17..19.
        // Flags5, initial seed6, bounce seed7 and hit identity9..10 are uints.
        for index in [0, 1, 2, 3, 4, 8, 11, 12, 13, 14, 15, 16, 17, 18, 19] {
            assert!(
                f32::from_bits(record[index]).is_finite(),
                "word{index}: {record:?}"
            );
        }
        assert!(f32::from_bits(record[0]) >= 1.0);
        assert!(f32::from_bits(record[1]) >= 0.0);
        assert!(f32::from_bits(record[13]) >= 0.0);
    }
    assert!(
        linear
            .iter()
            .all(|value| value.is_finite() && *value >= 0.0)
    );
    assert!(
        linear
            .as_chunks::<4>()
            .0
            .iter()
            .all(|pixel| pixel[3] == 1.0)
    );
    assert!(
        linear
            .as_chunks::<4>()
            .0
            .iter()
            .any(|pixel| pixel[..3].iter().any(|value| *value > 0.0))
    );
}

fn tea(mut x: u32, mut y: u32) -> u32 {
    let mut sum = 0u32;
    for _ in 0..16 {
        sum = sum.wrapping_add(0x9e3779b9);
        x = x.wrapping_add(
            (y.wrapping_shl(4).wrapping_add(0xa341316c))
                ^ y.wrapping_add(sum)
                ^ (y.wrapping_shr(5).wrapping_add(0xc8013ea4)),
        );
        y = y.wrapping_add(
            (x.wrapping_shl(4).wrapping_add(0xad90777d))
                ^ x.wrapping_add(sum)
                ^ (x.wrapping_shr(5).wrapping_add(0x7e95761e)),
        );
    }
    x
}

fn fresh_seeds(constants: &[u8]) -> HashSet<u32> {
    let initial = word(constants, 476);
    let stream_count = initial + word(constants, 472) + 2;
    let sequence = word(constants, 84).wrapping_add(word(constants, 92));
    (0..HEIGHT)
        .flat_map(|y| {
            (0..WIDTH).flat_map(move |x| {
                (0..initial).map(move |sample| {
                    tea(
                        morton(x, y, 16),
                        sequence.wrapping_mul(stream_count).wrapping_add(sample),
                    )
                })
            })
        })
        .collect()
}

#[test]
#[ignore = "requires windowless Vulkan; real multiple-fresh RIS and spatial bank routing"]
fn gpu_restir_fresh_ris_and_spatial_rounds_use_real_configured_banks() {
    let fixture = multiple_light_scene(1);
    let mut config = config_settings();
    config.restir_spatial_only = true;
    let mut renderer = renderer(config);
    let mut storage = None;
    for (sequence, (initial, rounds)) in [(1, 0), (2, 0), (4, 0), (1, 2), (1, 3)]
        .into_iter()
        .enumerate()
    {
        config.restir.initial_samples = initial;
        config.restir.spatial_reuse = rounds != 0;
        config.restir.spatial_iterations = rounds;
        renderer.configure(config).unwrap();
        let output = renderer
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence as u32)
            .unwrap();
        complete_rgba(&output, WIDTH, HEIGHT);
        let state = renderer.restir.as_ref().unwrap();
        assert!(!state.temporal_this_frame);
        let constants = uniform(&renderer);
        assert_eq!(word(&constants, 476), initial);
        assert_eq!(word(&constants, 472), rounds);
        let (buffer, final_offset, _) = state.history_for_test();
        if let Some(original) = storage {
            assert_eq!(original, buffer.buffer);
        }
        storage = Some(buffer.buffer);
        let selected = if rounds & 1 == 0 { 272 } else { 280 };
        assert_eq!(
            buffer.address() + final_offset,
            address(&constants, selected)
        );
        let (records, linear) = temporal_snapshot_words(&renderer, WIDTH, HEIGHT);
        finite_output(&records, &linear);
        let maximum_m = 4u32.pow(rounds) as f32;
        assert!(
            records
                .iter()
                .all(|record| f32::from_bits(record[0]) <= maximum_m)
        );
        if rounds == 0 {
            assert!(
                records
                    .iter()
                    .all(|record| f32::from_bits(record[0]) == 1.0)
            );
        } else {
            assert!(records.iter().any(|record| f32::from_bits(record[0]) > 1.0));
        }
        let seeds = fresh_seeds(&constants);
        assert!(
            records
                .iter()
                .filter(|record| f32::from_bits(record[1]) > 0.0)
                .all(|record| seeds.contains(&record[6]))
        );
        eprintln!(
            "fresh={initial} rounds={rounds} final_bank_addr={} positive={} maxM={}",
            address(&constants, selected),
            records
                .iter()
                .filter(|record| f32::from_bits(record[1]) > 0.0)
                .count(),
            records
                .iter()
                .map(|record| f32::from_bits(record[0]))
                .fold(0.0_f32, f32::max)
        );
    }
}

#[test]
#[ignore = "requires windowless Vulkan; neighbor scratch resize must retain accepted history"]
fn gpu_restir_neighbor_resize_preserves_history_and_winner_lineage() {
    let fixture = multiple_light_scene(1);
    let mut config = config_settings();
    let mut renderer = renderer(config);
    for sequence in 0..3 {
        renderer
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
            .unwrap();
    }
    let storage = renderer
        .restir
        .as_ref()
        .unwrap()
        .history_for_test()
        .0
        .buffer;
    let generate = renderer
        .pipeline
        .as_ref()
        .unwrap()
        .restir
        .as_ref()
        .unwrap()
        .generate;
    for (sequence, count) in [(10, 1), (11, 5), (12, 3), (13, 1)] {
        let previous = temporal_snapshot_words(&renderer, WIDTH, HEIGHT).0;
        let prior_seeds: HashSet<_> = previous
            .iter()
            .filter(|record| f32::from_bits(record[1]) > 0.0)
            .map(|record| record[6])
            .collect();
        config.restir.spatial_neighbors = count;
        renderer.configure(config).unwrap();
        renderer
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
            .unwrap();
        let state = renderer.restir.as_ref().unwrap();
        assert!(state.temporal_this_frame && state.accepted_history().0);
        assert_eq!(state.history_for_test().0.buffer, storage);
        assert_eq!(
            renderer
                .pipeline
                .as_ref()
                .unwrap()
                .restir
                .as_ref()
                .unwrap()
                .generate,
            generate
        );
        let constants = uniform(&renderer);
        assert_eq!(word(&constants, 468), count);
        assert_eq!(word(&constants, 516), count.max(2));
        let (records, linear) = temporal_snapshot_words(&renderer, WIDTH, HEIGHT);
        finite_output(&records, &linear);
        assert!(
            records
                .iter()
                .any(|record| f32::from_bits(record[0]) > (count + 1) as f32)
        );
        let inherited = records
            .iter()
            .filter(|record| f32::from_bits(record[1]) > 0.0 && prior_seeds.contains(&record[6]))
            .count();
        assert!(
            inherited > 0,
            "count={count} retained no positive temporal source family"
        );
        eprintln!("neighbors={count} inherited_positive={inherited}");
    }
}

#[test]
#[ignore = "requires windowless Vulkan; source profiles preserve old random draw charts across edits"]
fn gpu_restir_threshold_edits_preserve_source_profiles_without_temporal_reset() {
    let fixture = multiple_light_scene(1);
    let mut config = config_settings();
    let mut renderer = renderer(config);
    let mut seed_profiles = HashMap::new();
    let mut inherited_profiles = 0;
    for (sequence, (distance_mean, distance_sigma, roughness_mean, roughness_sigma)) in [
        (0.02, 0.2, 0.2, 0.0),
        (0.02, 0.2, 0.2, 0.0),
        (0.02, 0.0, 0.2, 0.0),
        (0.02, 0.2, 0.2, 0.4),
        (0.02, 0.0, 0.2, 0.4),
        (0.02, 0.2, 0.2, 0.0),
        (0.02, 0.0, 0.2, 0.0),
        // Independently change each mean, then both, and restore the original chart.
        (0.0, 0.2, 0.2, 0.0),
        (0.02, 0.2, 0.0, 0.0),
        (0.0, 0.0, 0.0, 0.4),
        (0.02, 0.2, 0.2, 0.0),
    ]
    .into_iter()
    .enumerate()
    {
        config.restir.distance_threshold = distance_mean;
        config.restir.distance_sigma = distance_sigma;
        config.restir.roughness_threshold = roughness_mean;
        config.restir.roughness_sigma = roughness_sigma;
        renderer.configure(config).unwrap();
        renderer
            .render(&fixture, &camera(), WIDTH, HEIGHT, sequence as u32)
            .unwrap();
        let state = renderer.restir.as_ref().unwrap();
        assert_eq!(state.temporal_this_frame, sequence != 0);
        let constants = uniform(&renderer);
        let current_profile = word(&constants, 712);
        for seed in fresh_seeds(&constants) {
            if let Some(old) = seed_profiles.insert(seed, current_profile) {
                assert_eq!(old, current_profile, "authored seed collision");
            }
        }
        let (records, linear) = temporal_snapshot_words(&renderer, WIDTH, HEIGHT);
        finite_output(&records, &linear);
        if sequence < 2 {
            assert!(state.profiles_for_test().planes_for_test().is_none());
            continue;
        }
        let profiles = state.profiles_for_test();
        let planes = profiles.planes_for_test().unwrap();
        let bytes = read_buffer(&renderer, planes);
        let final_pointer = address(
            &constants,
            if word(&constants, 472) & 1 == 0 {
                688
            } else {
                696
            },
        );
        let final_base = usize::try_from(final_pointer - planes.address()).unwrap();
        assert_eq!(word(&constants, 524) & 8, 8);
        assert_eq!(word(&constants, 524) & 16 != 0, sequence > 2);
        let original = [
            (0.02_f32 / 100.0).to_bits(),
            0.2_f32.to_bits(),
            0.2_f32.to_bits(),
            0,
        ];
        assert_eq!(profiles.values_for_test()[0], original);
        let authored_current = [
            (config.restir.distance_threshold / 100.0).to_bits(),
            distance_sigma.to_bits(),
            config.restir.roughness_threshold.to_bits(),
            roughness_sigma.to_bits(),
        ];
        assert_eq!(
            profiles.values_for_test()[current_profile as usize],
            authored_current
        );
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                let record = records[(y * WIDTH + x) as usize];
                if f32::from_bits(record[1]) == 0.0 {
                    continue;
                }
                let selected_profile = word(&bytes, final_base + 4 * offset(x, y));
                assert_eq!(
                    selected_profile, seed_profiles[&record[6]],
                    "frame={sequence} pixel={x},{y}"
                );
                inherited_profiles += usize::from(selected_profile != current_profile);
            }
        }
        eprintln!(
            "threshold frame={sequence} current_profile={current_profile} table={} different_source_total={inherited_profiles}",
            profiles.values_for_test().len()
        );
    }
    assert!(
        inherited_profiles > 0,
        "fixture must preserve an old positive path across a changed source chart"
    );
}

#[test]
#[ignore = "requires windowless Vulkan; real duplicate statistics and decoupled final color"]
fn gpu_restir_duplicate_map_and_decoupled_color_preserve_complete_reservoirs() {
    let fixture = multiple_light_scene(1);
    let mut config = config_settings();
    config.restir_spatial_only = true;
    config.restir.duplicate_map = true;
    let mut coupled = renderer(config);
    config.restir.decoupled_shading = true;
    let mut decoupled = renderer(config);
    let mut different_pixels = 0;
    for sequence in 0..3 {
        for renderer in [&mut coupled, &mut decoupled] {
            complete_rgba(
                &renderer
                    .render(&fixture, &camera(), WIDTH, HEIGHT, sequence)
                    .unwrap(),
                WIDTH,
                HEIGHT,
            );
        }
        let (expected_records, expected_linear) = temporal_snapshot_words(&coupled, WIDTH, HEIGHT);
        let (records, linear) = temporal_snapshot_words(&decoupled, WIDTH, HEIGHT);
        finite_output(&records, &linear);
        assert_eq!(
            records, expected_records,
            "decoupled output changed next-frame reservoir winner/payload"
        );
        different_pixels += linear
            .as_chunks::<4>()
            .0
            .iter()
            .zip(expected_linear.as_chunks::<4>().0)
            .filter(|(a, b)| a[..3] != b[..3])
            .count();
        let state = decoupled.restir.as_ref().unwrap();
        let aux = state.auxiliary_for_test();
        let ids = read_buffer(&decoupled, aux.sample_ids.as_ref().unwrap());
        let duplicates = read_buffer(&decoupled, aux.duplicate_counts.as_ref().unwrap());
        let shading = read_buffer(&decoupled, aux.shading.as_ref().unwrap());
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                let index = offset(x, y);
                let record = records[(y * WIDTH + x) as usize];
                let own = if f32::from_bits(record[1]) == 0.0 {
                    0
                } else {
                    record[6]
                };
                assert_eq!(word(&ids, index * 4), own);
                let mut count = 0;
                if own != 0 {
                    for dy in -8..=8 {
                        for dx in -8..=8 {
                            let nx = x as i32 + dx;
                            let ny = y as i32 + dy;
                            if nx < 0
                                || ny < 0
                                || nx >= WIDTH as i32
                                || ny >= HEIGHT as i32
                                || (dx == 0 && dy == 0)
                            {
                                continue;
                            }
                            count += u32::from(word(&ids, offset(nx as u32, ny as u32) * 4) == own);
                        }
                    }
                }
                assert_eq!(
                    word(&duplicates, index * 4),
                    count,
                    "frame={sequence} pixel={x},{y}"
                );
                for channel in 0..3 {
                    assert!(f32::from_bits(word(&shading, index * 16 + channel * 4)).is_finite());
                }
            }
        }
    }
    assert!(
        different_pixels > 0,
        "fixture must exercise actual multi-contribution output"
    );
    eprintln!(
        "decoupled_changed_pixels={different_pixels}; all 1581 duplicate counts match independent 17x17 oracle"
    );
}
