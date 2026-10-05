//! Opt-in, completed final-frame evidence. Never read back inside the timed loop.
use crate::{Buffer, HostBenchmark};
use ash::vk;
use std::collections::HashMap;

pub(super) fn capture(host: &HostBenchmark, extent: [u32; 2]) -> serde_json::Value {
    let renderer = host.renderer_for_test();
    let state = renderer.restir.as_ref().expect("ReSTIR state");
    assert!(state.accepted_history().0, "final history was not accepted");
    let (storage, offset, padded_pixels) = state.history_for_test();
    let reservoir_bytes = u64::from(padded_pixels) * 80;
    let context = host.owner_for_test();
    let readback = Buffer::new_readback(context, reservoir_bytes + 16).unwrap();
    // The caller has drained every benchmark submission. This separate copy submission
    // also waits for completion before Buffer::read accesses its mapped destination.
    context
        .submit_named("register_final_restir_summary", |command| unsafe {
            let before = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::SHADER_WRITE | vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &before,
                &[],
                &[],
            );
            context.device.cmd_copy_buffer(
                command,
                storage.buffer,
                readback.buffer,
                &[vk::BufferCopy::default()
                    .src_offset(offset)
                    .size(reservoir_bytes)],
            );
            context.device.cmd_copy_buffer(
                command,
                state.queue_buffer(),
                readback.buffer,
                &[vk::BufferCopy::default()
                    .dst_offset(reservoir_bytes)
                    .size(16)],
            );
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(
                    vk::AccessFlags::HOST_READ
                        | vk::AccessFlags::SHADER_READ
                        | vk::AccessFlags::SHADER_WRITE,
                )];
            context.device.cmd_pipeline_barrier(
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
    let bytes = readback.read((reservoir_bytes + 16) as usize).unwrap();
    summarize(
        &bytes[..reservoir_bytes as usize],
        &bytes[reservoir_bytes as usize..],
        extent,
    )
}

fn summarize(reservoirs: &[u8], queue: &[u8], [width, height]: [u32; 2]) -> serde_json::Value {
    let word =
        |bytes: &[u8], i: usize| u32::from_le_bytes(bytes[4 * i..4 * i + 4].try_into().unwrap());
    let jobs = word(queue, 0);
    let groups = [word(queue, 1), word(queue, 2), word(queue, 3)];
    assert_eq!(groups, [jobs.div_ceil(64), 1, 1]);
    let pixels = u64::from(width) * u64::from(height);
    let mut positive_m = 0_u64;
    let mut contribution = 0_u64;
    let mut invalid = 0_u64;
    let mut sum_m = 0.0_f64;
    let mut max_m = 0.0_f32;
    let mut path_lengths = vec![0_u64; 256];
    let mut rc_lengths = vec![0_u64; 256];
    let mut light_types = [0_u64; 4];
    let mut last_nee = 0_u64;
    let mut rc_hits = 0_u64;
    let mut seeds = HashMap::<u32, u64>::new();
    for y in 0..height {
        for x in 0..width {
            let morton = (0..4).fold(0, |offset, bit| {
                offset | (((x >> bit) & 1) << (2 * bit)) | (((y >> bit) & 1) << (2 * bit + 1))
            });
            let index = ((y / 16) * width.div_ceil(16) + x / 16) * 256 + morton;
            let record = &reservoirs[index as usize * 80..index as usize * 80 + 80];
            let m = f32::from_bits(word(record, 0));
            let weight = f32::from_bits(word(record, 1));
            let integrand = [2, 3, 4].map(|i| f32::from_bits(word(record, i)));
            if !(m.is_finite()
                && m >= 0.
                && weight.is_finite()
                && weight >= 0.
                && integrand.iter().all(|v| v.is_finite() && *v >= 0.))
            {
                invalid += 1;
                continue;
            }
            sum_m += f64::from(m);
            max_m = max_m.max(m);
            positive_m += u64::from(m > 0.);
            if !(m > 0. && weight > 0. && integrand.iter().any(|v| *v > 0.)) {
                continue;
            }
            contribution += 1;
            let flags = word(record, 5);
            path_lengths[(flags & 255) as usize] += 1;
            rc_lengths[((flags >> 8) & 255) as usize] += 1;
            light_types[((flags >> 18) & 3) as usize] += 1;
            last_nee += u64::from(flags & (1 << 16) != 0);
            rc_hits += u64::from(word(record, 9) != u32::MAX);
            *seeds.entry(word(record, 6)).or_default() += 1;
        }
    }
    serde_json::json!({
        "extent": [width, height], "visible_pixels": pixels,
        "scope": "accepted final reservoirs; last spatial retrace queue only; no total ray/temporal counts or lineage age",
        "final_spatial_retrace_jobs": jobs, "final_spatial_dispatch_groups": groups,
        "final_spatial_thread_capacity": u64::from(groups[0]) * 64,
        "positive_m_reservoirs": positive_m, "positive_contribution_reservoirs": contribution,
        "positive_contribution_definition": "finite nonnegative M/weight/integrand, M>0, weight>0, any integrand>0; not a visibility/reprojection acceptance predicate",
        "invalid_numeric_reservoirs": invalid,
        "mean_m_visible_pixels": sum_m / pixels as f64, "maximum_m": max_m,
        "histogram_scope": "positive contribution reservoirs only; includes rc-length sentinel 255",
        "path_length_histogram": path_lengths, "rc_vertex_length_histogram": rc_lengths,
        "light_type_histogram": light_types, "last_vertex_nee": last_nee,
        "valid_reconnection_hit_identity": rc_hits,
        "distinct_initial_seed_identifiers": seeds.len(),
        "maximum_pixels_sharing_initial_seed_identifier": seeds.values().copied().max().unwrap_or(0),
        "seed_scope": "selected initRandomSeed identifiers; possible hash collisions; no frame age inference",
        "readback_bytes": reservoirs.len() + 16,
    })
}

#[test]
fn summary_excludes_padding_and_separates_queue_capacity_from_jobs() {
    let mut bytes = vec![0_u8; 256 * 80];
    let put = |bytes: &mut [u8], record: usize, word: usize, value: u32| {
        bytes[record * 80 + word * 4..record * 80 + word * 4 + 4]
            .copy_from_slice(&value.to_le_bytes());
    };
    // Visible 2x2 Morton slots are 0..4; padding deliberately contains a huge M.
    for slot in 0..4 {
        put(&mut bytes, slot, 0, (slot as f32 + 1.).to_bits());
        put(&mut bytes, slot, 1, 1_f32.to_bits());
        put(&mut bytes, slot, 2, 2_f32.to_bits());
        put(&mut bytes, slot, 5, 4 | (255 << 8) | (1 << 16));
        put(&mut bytes, slot, 6, (slot / 2) as u32);
        put(&mut bytes, slot, 9, u32::MAX);
    }
    put(&mut bytes, 4, 0, 1000_f32.to_bits());
    let queue = [65_u32, 2, 1, 1].map(u32::to_le_bytes);
    let result = summarize(&bytes, queue.as_flattened(), [2, 2]);
    assert_eq!(result["visible_pixels"], 4);
    assert_eq!(result["mean_m_visible_pixels"], 2.5);
    assert_eq!(result["positive_contribution_reservoirs"], 4);
    assert_eq!(result["rc_vertex_length_histogram"][255], 4);
    assert_eq!(result["path_length_histogram"][4], 4);
    assert_eq!(result["valid_reconnection_hit_identity"], 0);
    assert_eq!(result["distinct_initial_seed_identifiers"], 2);
    assert_eq!(result["maximum_pixels_sharing_initial_seed_identifier"], 2);
    assert_eq!(result["final_spatial_retrace_jobs"], 65);
    assert_eq!(result["final_spatial_thread_capacity"], 128);
}
