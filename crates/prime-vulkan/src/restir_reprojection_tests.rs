//! Read executed temporal pixel addresses, independently of RR guides and SDK motion.
use super::*;
use prime_scene::workers::CpuWorkers;
use realtime_tests::{camera, face, scene};

fn morton_offset([x, y]: [u32; 2], [width, _]: [u32; 2]) -> usize {
    let mut local = 0;
    for bit in 0..4 {
        local |= ((x >> bit) & 1) << (2 * bit);
        local |= ((y >> bit) & 1) << (2 * bit + 1);
    }
    (((y >> 4) * width.div_ceil(16) + (x >> 4)) * 256 + local) as usize
}

pub(super) fn expected_pixel(
    current: Camera,
    previous: Camera,
    pixel: [u32; 2],
    extent: [u32; 2],
) -> [i32; 2] {
    expected_pixel_with_jitters(current, previous, pixel, extent, [[0.; 2]; 2])
}

fn expected_pixel_with_jitters(
    current: Camera,
    previous: Camera,
    pixel: [u32; 2],
    extent: [u32; 2],
    [jitter, _previous_jitter]: [[f32; 2]; 2],
) -> [i32; 2] {
    // Independent f64 intersection with the authored z=0 plane and old camera projection.
    // No production barycentrics, motion image, restir helper, or matrix is reused.
    let aspect = extent[0] as f64 / extent[1] as f64;
    let tangent = (current.vertical_fov_radians as f64 * 0.5).tan();
    let screen = [
        (pixel[0] as f64 + 0.5 + f64::from(jitter[0])) * 2.0 / extent[0] as f64 - 1.0,
        (pixel[1] as f64 + 0.5 + f64::from(jitter[1])) * 2.0 / extent[1] as f64 - 1.0,
    ];
    let direction: [f64; 3] = std::array::from_fn(|i| {
        current.forward[i] as f64 + current.right[i] as f64 * screen[0] * aspect * tangent
            - current.up[i] as f64 * screen[1] * tangent
    });
    let distance = -(current.position[2] as f64) / direction[2];
    let delta: [f64; 3] = std::array::from_fn(|i| {
        current.position[i] as f64 + direction[i] * distance - previous.position[i] as f64
    });
    let dot = |axis: [f32; 3]| {
        delta
            .into_iter()
            .zip(axis)
            .map(|(a, b)| a * b as f64)
            .sum::<f64>()
    };
    let z = dot(previous.forward);
    let tangent = (previous.vertical_fov_radians as f64 * 0.5).tan();
    // RA-002: donor motion excludes sampling jitter, unlike source-ray replay.
    // Independent current projection of this authored pinhole hit is pixel+.5+jitter;
    // subtract current jitter from the old no-jitter projection, not accepted old jitter.
    let location = [
        (dot(previous.right) / (z * aspect * tangent) * 0.5 + 0.5) * extent[0] as f64
            - f64::from(jitter[0]),
        (-dot(previous.up) / (z * tangent) * 0.5 + 0.5) * extent[1] as f64 - f64::from(jitter[1]),
    ];
    if z <= 0.0
        || location
            .into_iter()
            .zip(extent)
            .any(|(value, size)| value < 0.0 || value >= size as f64)
    {
        [-1; 2]
    } else {
        location.map(|value| value.floor() as i32)
    }
}

#[test]
#[ignore = "requires windowless Vulkan; executed RR-off ReSTIR temporal pixel addresses"]
fn gpu_restir_native_temporal_pixels_stay_fixed_and_follow_camera_projection() {
    let extent = [33, 17]; // Cross Morton tiles, workgroups, and both padded boundaries.
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        bounces: 2,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        auto_exposure_compensation: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::new(1).unwrap()))
            .unwrap();
    let scene = scene(1, vec![face(0.)]);
    let first = camera();
    let moved = Camera {
        position: [8.2, 7.88, 4.],
        ..first
    };
    let yaw = 0.07_f32;
    let rotated = Camera {
        forward: [yaw.sin(), 0., -yaw.cos()],
        right: [yaw.cos(), 0., yaw.sin()],
        vertical_fov_radians: 1.13,
        ..moved
    };
    let cameras = [first, first, moved, rotated, rotated, first, first];
    renderer
        .render(&scene, &first, extent[0], extent[1], 0)
        .unwrap();
    for (index, pair) in cameras.windows(2).enumerate() {
        let [previous, current] = [pair[0], pair[1]];
        renderer
            .render(&scene, &current, extent[0], extent[1], index as u32 + 1)
            .unwrap();
        assert!(renderer.reconstruction.is_none());
        let state = renderer.restir.as_ref().unwrap();
        assert!(state.temporal_this_frame);
        let constants = state
            .uniform_for_test(0)
            .read(restir::UNIFORM_BYTES as usize)
            .unwrap();
        assert_eq!(&constants[400..416], &[0; 16], "native jitter was not zero");
        let (storage, _, _) = state.history_for_test();
        let readback = Buffer::new_readback(&renderer.context, storage.size).unwrap();
        renderer
            .context
            .submit_named("restir_temporal_pixels", |command| unsafe {
                renderer.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                        .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
                    &[],
                    &[],
                );
                renderer.context.device.cmd_copy_buffer(
                    command,
                    storage.buffer,
                    readback.buffer,
                    &[vk::BufferCopy::default().size(storage.size)],
                );
                renderer.context.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::HOST | vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[vk::MemoryBarrier::default()
                        .src_access_mask(
                            vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE,
                        )
                        .dst_access_mask(
                            vk::AccessFlags::HOST_READ
                                | vk::AccessFlags::SHADER_READ
                                | vk::AccessFlags::SHADER_WRITE,
                        )],
                    &[],
                    &[],
                );
            })
            .unwrap();
        let bytes = readback.read(storage.size as usize).unwrap();
        let region = |position: usize| {
            (u64::from_le_bytes(constants[position..position + 8].try_into().unwrap())
                - storage.address()) as usize
        };
        let primary = region(288);
        let reprojection = region(312);
        for y in 0..extent[1] {
            for x in 0..extent[0] {
                let offset = morton_offset([x, y], extent);
                let hit = u32::from_le_bytes(
                    bytes[primary + 20 * offset..primary + 20 * offset + 4]
                        .try_into()
                        .unwrap(),
                );
                assert_ne!(hit, u32::MAX, "authored plane missed [{x},{y}]");
                let position = reprojection + 8 * offset;
                let actual = std::array::from_fn(|axis| {
                    i32::from_le_bytes(
                        bytes[position + 4 * axis..position + 4 * axis + 4]
                            .try_into()
                            .unwrap(),
                    )
                });
                assert_eq!(
                    actual,
                    expected_pixel(current, previous, [x, y], extent),
                    "frame {} pixel [{x},{y}]: temporal address drifted",
                    index + 1
                );
            }
        }
    }
}

fn read_storage(renderer: &Renderer) -> Vec<u8> {
    let (storage, _, _) = renderer.restir.as_ref().unwrap().history_for_test();
    let readback = Buffer::new_readback(&renderer.context, storage.size).unwrap();
    renderer
        .context
        .submit_named("restir_jittered_temporal_pixels", |command| unsafe {
            renderer.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
                &[],
                &[],
            );
            renderer.context.device.cmd_copy_buffer(
                command,
                storage.buffer,
                readback.buffer,
                &[vk::BufferCopy::default().size(storage.size)],
            );
            renderer.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(
                        vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE,
                    )
                    .dst_access_mask(
                        vk::AccessFlags::HOST_READ
                            | vk::AccessFlags::SHADER_READ
                            | vk::AccessFlags::SHADER_WRITE,
                    )],
                &[],
                &[],
            );
        })
        .unwrap();
    readback.read(storage.size as usize).unwrap()
}

#[test]
#[ignore = "requires windowless Vulkan; executed temporal address with unequal accepted frame jitter"]
fn gpu_restir_temporal_nonzero_jitter_uses_current_and_accepted_previous_samples() {
    let extent = [33, 17];
    // Both actual source ray jitters remain in the UBO. Donor addressing removes
    // current jitter; changed accepted jitter alone must not move a stationary donor.
    let a = [0.125, -0.25];
    let b = [-0.3125, 0.28125];
    let base = camera();
    let moved = |axis: usize, distance: f32| {
        let mut result = base;
        result.position[axis] += distance;
        result
    };
    let yaw = 0.063_f32;
    let rotated = Camera {
        forward: [yaw.sin(), 0., -yaw.cos()],
        right: [yaw.cos(), 0., yaw.sin()],
        ..base
    };
    let wider = Camera {
        vertical_fov_radians: 1.117,
        ..base
    };
    let frames = [
        ("cold", base, Some(a)),
        ("static-b", base, Some(b)),
        ("static-a", base, Some(a)),
        ("x+", moved(0, 0.2), Some(a)),
        ("return-x-", base, Some(a)),
        ("x-", moved(0, -0.2), Some(a)),
        ("return-x+", base, Some(a)),
        ("y+", moved(1, 0.2), Some(a)),
        ("return-y-", base, Some(a)),
        ("y-", moved(1, -0.2), Some(a)),
        ("return-y+", base, Some(a)),
        ("yaw+", rotated, Some(b)),
        ("return-yaw-", base, Some(a)),
        ("fov-wide", wider, Some(b)),
        ("return-fov", base, Some(a)),
        // No override proves that it was consumed by one prepare, while accepted a remains.
        ("one-shot-expired", base, None),
    ];
    for method in [LightSampling::Tree] {
        let settings = RenderSettings {
            integrator: Integrator::RestirPt,
            mode: RenderMode::Realtime,
            light_sampling: method,
            bounces: 2,
            sun: 1. / 256.,
            sky: 1. / 256.,
            stars: 0.,
            auto_exposure_compensation: 0.,
            native_noisy_output: true,
            opacity_micromap: false,
            ..Default::default()
        };
        let mut renderer =
            Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::new(1).unwrap()))
                .unwrap();
        // Start with a cold bank, rather than warming a zero-jitter bank and editing a UBO.
        renderer.restir = Some(restir::State::new(&renderer.context).unwrap());
        let scene = scene(1, vec![face(0.)]);
        let mut previous = None::<(Camera, [f32; 2])>;
        let mut checked = 0;
        let mut rejected_border = 0;
        let mut changed_address = 0;
        for (index, (label, current, override_jitter)) in frames.into_iter().enumerate() {
            if let Some(jitter) = override_jitter {
                renderer
                    .restir
                    .as_mut()
                    .unwrap()
                    .override_next_jitter_for_test(jitter);
            }
            let jitter = override_jitter.unwrap_or([0.; 2]);
            renderer
                .render(&scene, &current, extent[0], extent[1], index as u32)
                .unwrap();
            assert!(renderer.reconstruction.is_none());
            let state = renderer.restir.as_ref().unwrap();
            assert_eq!(state.temporal_this_frame, previous.is_some());
            assert_eq!(state.accepted_history(), (true, (index + 1) & 1));
            let constants = state
                .uniform_for_test(0)
                .read(restir::UNIFORM_BYTES as usize)
                .unwrap();
            let previous_jitter = previous.map_or(jitter, |(_, jitter)| jitter);
            let expected_jitter: Vec<_> = jitter
                .into_iter()
                .chain(previous_jitter)
                .flat_map(f32::to_le_bytes)
                .collect();
            assert_eq!(
                &constants[400..416],
                expected_jitter.as_slice(),
                "{method:?}/{label}: prepare did not use current and accepted previous jitter"
            );
            let (storage, _, _) = state.history_for_test();
            let region = |position: usize| {
                (u64::from_le_bytes(constants[position..position + 8].try_into().unwrap())
                    - storage.address()) as usize
            };
            let primary = region(288);
            let reprojection = region(312);
            let bytes = read_storage(&renderer);
            for y in 0..extent[1] {
                for x in 0..extent[0] {
                    let offset = morton_offset([x, y], extent);
                    let hit = u32::from_le_bytes(
                        bytes[primary + 20 * offset..primary + 20 * offset + 4]
                            .try_into()
                            .unwrap(),
                    );
                    assert_ne!(hit, u32::MAX, "{method:?}/{label}: plane missed [{x},{y}]");
                    // Cold dispatch has no temporal workload, so its reprojection region
                    // is unwritten. Primary, jitter UBO and accepted bank are still checked.
                    let Some((old, old_jitter)) = previous else {
                        continue;
                    };
                    let position = reprojection + 8 * offset;
                    let actual: [i32; 2] = std::array::from_fn(|axis| {
                        i32::from_le_bytes(
                            bytes[position + 4 * axis..position + 4 * axis + 4]
                                .try_into()
                                .unwrap(),
                        )
                    });
                    let expected = expected_pixel_with_jitters(
                        current,
                        old,
                        [x, y],
                        extent,
                        [jitter, old_jitter],
                    );
                    assert_eq!(
                        actual, expected,
                        "{method:?}/{label} pixel [{x},{y}]: jittered temporal address drifted"
                    );
                    if current == old {
                        assert_eq!(expected, [x as i32, y as i32]);
                    }
                    rejected_border += usize::from(expected[0] < 0);
                    changed_address +=
                        usize::from(expected[0] >= 0 && expected != [x as i32, y as i32]);
                    checked += 1;
                }
            }
            previous = Some((current, jitter));
        }
        assert!(
            rejected_border > 0,
            "{method:?}: missing border rejection coverage"
        );
        assert!(
            changed_address > 0,
            "{method:?}: missing changed address coverage"
        );
        eprintln!(
            "ReSTIR unequal-jitter temporal {method:?}: {checked} actual temporal addresses; {rejected_border} rejected border addresses, {changed_address} changed addresses; static/±x/±y/yaw/FOV/one-shot commit, no-jitter donor motion and accepted-jitter source replay"
        );
    }
}

#[test]
#[ignore = "requires windowless Vulkan; actual stationary temporal addresses for two production Halton cycles"]
fn gpu_restir_production_halton_cycles_record_static_temporal_address_transport() {
    use std::fmt::Write;

    // RA-002, docs/restir-adaptations.md: exercise the real 32-phase RR Performance
    // jitter without the SDK. The expanded A/B test remains a separate witness.
    let extent = [33, 17];
    let phases = 32;
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        light_sampling: LightSampling::Tree,
        bounces: 2,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        auto_exposure_compensation: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::new(1).unwrap()))
            .unwrap();
    renderer.restir = Some(restir::State::new(&renderer.context).unwrap());
    let scene = scene(1, vec![face(0.)]);
    let camera = camera();
    let mut previous_jitter = [0.; 2];
    let mut previous_seeds = Vec::<u32>::new();
    let mut pixels = String::from(
        "frame,phase,x,y,jx,jy,previous_jx,previous_jy,actual_x,actual_y,expected_x,expected_y,expected_checked_y,x_positive_half_tie,x_mismatch,y_mismatch,init_seed,previous_address_seed,previous_seed_match,M,weight\n",
    );
    let mut stats = String::from(
        "frame,phase,jx,jy,previous_jx,previous_jy,x_negative,x_zero,x_positive,y_negative,y_zero,y_positive,border,center_dx,center_dy,x_tie_mismatches,x_non_tie_mismatches,y_mismatches,positive,previous_seed_matches\n",
    );
    let mut mismatches = [0usize; 3]; // x exact half tie, x non-tie, y
    let mut checked = 0usize;
    let mut positive_total = 0usize;
    let mut retained_total = 0usize;
    let mut border_total = 0usize;
    let mut cycle_offsets = [[0i32; 2]; 2];
    let mut continuous_offsets = [[0f64; 2]; 2];
    for frame in 0..=2 * phases {
        // Accept index32 in the cold frame, then index1..32 twice. This includes
        // the cycle-boundary transition in each measured whole cycle.
        let phase = if frame == 0 {
            phases - 1
        } else {
            (frame - 1) % phases
        };
        let jitter = reconstruction_history::jitter(phase, extent[0], 2 * extent[0]);
        renderer
            .restir
            .as_mut()
            .unwrap()
            .override_next_jitter_for_test(jitter);
        renderer
            .render(&scene, &camera, extent[0], extent[1], frame)
            .unwrap();
        assert!(renderer.reconstruction.is_none());
        let state = renderer.restir.as_ref().unwrap();
        assert_eq!(state.temporal_this_frame, frame != 0);
        assert_eq!(state.accepted_history(), (true, (frame as usize + 1) & 1));
        let constants = state
            .uniform_for_test(0)
            .read(restir::UNIFORM_BYTES as usize)
            .unwrap();
        let accepted_jitter = if frame == 0 { jitter } else { previous_jitter };
        if frame != 0 {
            for axis in 0..2 {
                continuous_offsets[((frame - 1) / phases) as usize][axis] +=
                    f64::from(jitter[axis]) - f64::from(accepted_jitter[axis]);
            }
        }
        let expected_jitter: Vec<_> = jitter
            .into_iter()
            .chain(accepted_jitter)
            .flat_map(f32::to_le_bytes)
            .collect();
        assert_eq!(&constants[400..416], expected_jitter.as_slice());
        let (storage, reservoir_offset, _) = state.history_for_test();
        let region = |position: usize| {
            (u64::from_le_bytes(constants[position..position + 8].try_into().unwrap())
                - storage.address()) as usize
        };
        let primary = region(288);
        let reprojection = region(312);
        let reservoir = reservoir_offset as usize;
        let bytes = read_storage(&renderer);
        let mut seeds = Vec::with_capacity((extent[0] * extent[1]) as usize);
        let mut histograms = [[0usize; 3]; 2];
        let mut border = 0usize;
        let mut frame_mismatches = [0usize; 3];
        let mut positive = 0usize;
        let mut retained = 0usize;
        let mut center = [0i32; 2];
        // Preserve the before-bank CSV columns, but the aligned donor chart's
        // stationary location is pixel+.5: no exact-half tie exceptions apply.
        let x_half_tie = false;
        for y in 0..extent[1] {
            for x in 0..extent[0] {
                let offset = morton_offset([x, y], extent);
                let word = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
                assert_ne!(word(primary + 20 * offset), u32::MAX);
                let at = reservoir + 80 * offset;
                let seed = word(at + 24);
                let m = f32::from_bits(word(at));
                let weight = f32::from_bits(word(at + 4));
                let active = m > 0. && weight > 0.;
                positive += usize::from(active);
                seeds.push(seed);
                if frame == 0 {
                    continue; // Cold temporal workload is unwritten.
                }
                let actual: [i32; 2] = std::array::from_fn(|axis| {
                    i32::from_le_bytes(
                        bytes[reprojection + 8 * offset + 4 * axis..][..4]
                            .try_into()
                            .unwrap(),
                    )
                });
                // Independent static pinhole projection has qp=qc=pixel+.5+currentJitter.
                // Upstream no-jitter donor motion qp-qc cancels, so all x/y addresses are
                // strictly the same pixel, including boundaries and every Halton phase.
                let expected = [x as i32, y as i32];
                let x_mismatch = actual[0] != expected[0];
                let expected_y = expected[1];
                let y_mismatch = actual[1] != expected_y;
                frame_mismatches[usize::from(!x_half_tie)] += usize::from(x_mismatch);
                frame_mismatches[2] += usize::from(y_mismatch);
                let in_bounds = actual
                    .into_iter()
                    .zip(extent)
                    .all(|(value, size)| value >= 0 && value < size as i32);
                let (old_seed, same) = if in_bounds {
                    let old_seed =
                        previous_seeds[(actual[1] as u32 * extent[0] + actual[0] as u32) as usize];
                    (old_seed, active && seed == old_seed)
                } else {
                    border += 1;
                    (0, false)
                };
                retained += usize::from(same);
                if in_bounds && x > 0 && y > 0 && x + 1 < extent[0] && y + 1 < extent[1] {
                    for axis in 0..2 {
                        let delta = actual[axis] - [x, y][axis] as i32;
                        assert!((-1..=1).contains(&delta));
                        histograms[axis][(delta + 1) as usize] += 1;
                    }
                }
                if [x, y] == [extent[0] / 2, extent[1] / 2] {
                    assert!(in_bounds);
                    center = [actual[0] - x as i32, actual[1] - y as i32];
                    for axis in 0..2 {
                        cycle_offsets[((frame - 1) / phases) as usize][axis] += center[axis];
                    }
                }
                writeln!(pixels, "{frame},{phase},{x},{y},{},{},{},{},{},{},{},{},{expected_y},{},{},{},{seed},{old_seed},{},{m},{weight}",
                    jitter[0], jitter[1], accepted_jitter[0], accepted_jitter[1],
                    actual[0], actual[1], expected[0], expected[1], u8::from(x_half_tie),
                    u8::from(x_mismatch), u8::from(y_mismatch), u8::from(same)).unwrap();
                checked += 1;
            }
        }
        if frame != 0 {
            writeln!(stats, "{frame},{phase},{},{},{},{},{},{},{},{},{},{},{border},{},{},{},{},{},{positive},{retained}",
                jitter[0], jitter[1], accepted_jitter[0], accepted_jitter[1],
                histograms[0][0], histograms[0][1], histograms[0][2],
                histograms[1][0], histograms[1][1], histograms[1][2], center[0], center[1],
                frame_mismatches[0], frame_mismatches[1], frame_mismatches[2]).unwrap();
            eprintln!(
                "ReSTIR Halton static frame={frame} phase={phase} current={jitter:?} accepted_previous={accepted_jitter:?} offsets={histograms:?} center={center:?} mismatches_tie_nontie_y={frame_mismatches:?} retained_seed={retained} positive={positive} border={border}"
            );
            for category in 0..3 {
                mismatches[category] += frame_mismatches[category];
            }
            positive_total += positive;
            retained_total += retained;
            border_total += border;
        }
        previous_jitter = jitter;
        previous_seeds = seeds;
    }
    let output = std::env::var_os("PRIME_RESTIR_HALTON_ARTIFACTS")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../artifacts/restir-followup-2026-10-05/halton-cycle-addresses")
        });
    std::fs::create_dir_all(&output).unwrap();
    std::fs::write(output.join("pixels.csv"), pixels).unwrap();
    std::fs::write(output.join("phases.csv"), stats).unwrap();
    eprintln!(
        "ReSTIR production Halton cycles: actual_addresses={checked} continuous_jitter_cycle_sum={continuous_offsets:?} center_previous_minus_current_cycle_sum={cycle_offsets:?} mismatches_tie_nontie_y={mismatches:?} positive={positive_total} border={border_total} previous_address_seed_matches={retained_total}; no-jitter donor motion, full-strict static x/y; this does not prove game visual closure"
    );
    assert_eq!(checked, (2 * phases * extent[0] * extent[1]) as usize);
    assert!(positive_total > 8);
    assert_eq!(continuous_offsets, [[0.; 2]; 2]);
    assert_eq!(cycle_offsets, [[0; 2]; 2]);
    assert_eq!(border_total, 0);
    assert_eq!(
        mismatches, [0; 3],
        "strict authored static-plane no-jitter motion oracle for every x/y address"
    );
}
