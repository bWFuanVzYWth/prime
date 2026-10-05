//! Windowless production ReSTIR dispatch, independent pipelines and accepted temporal history.
use super::*;
use prime_scene::{settings::DiagnosticView, surface::Emission};
use realtime_tests::{camera, face, scene};

fn artifact_output() -> std::path::PathBuf {
    std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            // A repeated standalone run must not replace a previous experiment's CSVs.
            let time = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../artifacts/restir-tests")
                .join(format!("{}-{time}", std::process::id()))
        })
}

fn settings(mode: RenderMode) -> RenderSettings {
    RenderSettings {
        integrator: Integrator::RestirPt,
        mode,
        bounces: 6,
        exposure: 0.25,
        sun: 1. / 256.,
        sky: 1. / 256.,
        auto_exposure_compensation: 0.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    }
}

fn renderer(settings: RenderSettings) -> Renderer {
    Renderer::with_settings_and_workers(
        settings,
        Arc::new(prime_scene::workers::CpuWorkers::configured().unwrap()),
    )
    .unwrap()
}

fn illuminated_scene(revision: u64) -> Scene {
    let mut lamp = face(3.);
    lamp.geometry.positions = [[12., 8., 3.], [12., 10., 3.], [14., 10., 3.], [14., 8., 3.]];
    lamp.emission = Emission {
        radiance: [4., 2., 1.],
        two_sided: true,
        textured: false,
    };
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    scene(revision, vec![face(0.), rear, lamp])
}

fn multiple_light_scene(revision: u64) -> Scene {
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    let mut warm = face(3.);
    warm.geometry.positions = [[12., 8., 3.], [12., 10., 3.], [14., 10., 3.], [14., 8., 3.]];
    warm.emission = Emission {
        radiance: [4., 2., 1.],
        two_sided: true,
        textured: false,
    };
    let mut cool = face(2.);
    cool.geometry.positions = [[2., 5., 2.], [2., 8., 2.], [3., 8., 2.], [3., 5., 2.]];
    cool.emission = Emission {
        radiance: [0.5, 3., 7.],
        two_sided: true,
        textured: false,
    };
    scene(revision, vec![face(0.), rear, warm, cool])
}

fn complete_rgba(image: &[u8], width: u32, height: u32) {
    assert_eq!(image.len(), width as usize * height as usize * 4);
    assert!(image.as_chunks::<4>().0.iter().all(|pixel| pixel[3] == 255));
}

fn linear_history(renderer: &Renderer, width: u32, height: u32) -> Vec<f32> {
    let source = renderer.output.as_ref().unwrap().linear_buffer();
    let size = u64::from(width) * u64::from(height) * 16;
    let readback = Buffer::new_readback(&renderer.context, size).unwrap();
    renderer
        .context
        .submit_named("restir_test_history", |command| unsafe {
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
                &[vk::BufferCopy::default().size(size)],
            );
            let after = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            renderer.context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &after,
                &[],
                &[],
            );
        })
        .unwrap();
    readback
        .read(size as usize)
        .unwrap()
        .as_chunks::<4>()
        .0
        .iter()
        .map(|word| f32::from_le_bytes(*word))
        .collect()
}

#[test]
#[ignore = "requires windowless Vulkan; production ReSTIR diagnostics, temporal reuse and resets"]
fn gpu_restir_independent_pipeline_preserves_primary_and_history_boundaries() {
    let config = RenderSettings {
        view: DiagnosticView::LinearDepth,
        depth_range: 8.,
        ..settings(RenderMode::Realtime)
    };
    let mut renderer = renderer(config);
    let pipeline = renderer.pipeline.as_ref().unwrap();
    assert!(pipeline.restir.is_some());
    assert!(
        pipeline
            .pipelines
            .iter()
            .all(|handle| *handle == vk::Pipeline::null())
    );
    assert!(pipeline.primary_pipelines.is_none());
    assert!(pipeline.single_sample_pipelines.is_none());
    assert!(pipeline.realtime_post.is_none());
    assert!(renderer.reconstruction.is_none());
    let fixture = scene(1, vec![face(0.)]);
    let mut camera = camera();
    for (sequence, temporal) in [(10, false), (11, true)] {
        let pixels = renderer
            .render(&fixture, &camera, 31, 17, sequence)
            .unwrap();
        complete_rgba(&pixels, 31, 17);
        assert!(
            pixels
                .as_chunks::<4>()
                .0
                .iter()
                .all(|pixel| *pixel == [128, 128, 128, 255])
        );
        assert_eq!(
            renderer.restir.as_ref().unwrap().temporal_this_frame,
            temporal
        );
        assert!(!renderer.output.as_ref().unwrap().has_path_trace_scratch());
    }
    camera.position[0] += 0.1;
    renderer.render(&fixture, &camera, 31, 17, 12).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "camera motion should reproject accepted history"
    );
    let mut changed = scene(2, vec![face(-1.)]);
    changed.resources = fixture.resources;
    let pixels = renderer.render(&changed, &camera, 31, 17, 13).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "geometry publication must leave rejection to individual historical identities"
    );
    assert!(
        pixels
            .as_chunks::<4>()
            .0
            .iter()
            .all(|pixel| *pixel == [159, 159, 159, 255])
    );
    renderer.render(&changed, &camera, 31, 17, 14).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    complete_rgba(
        &renderer.render(&changed, &camera, 17, 31, 15).unwrap(),
        17,
        31,
    );
    assert!(
        !renderer.restir.as_ref().unwrap().temporal_this_frame,
        "extent change must invalidate history"
    );
    renderer.render(&changed, &camera, 17, 31, 0).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "sampling sequence restart must retain accepted history"
    );
    let mut environment = renderer.environment;
    environment.world_y += 1e-9;
    assert_eq!(
        renderer.environment.eye_radius_km(),
        environment.eye_radius_km()
    );
    renderer.set_environment(environment).unwrap();
    renderer.render(&changed, &camera, 17, 31, 16).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "altitude changes absent from the GPU float must preserve history"
    );
    environment.world_y += 1000.;
    renderer.set_environment(environment).unwrap();
    renderer.render(&changed, &camera, 17, 31, 17).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    renderer.render(&changed, &camera, 17, 31, 18).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    environment.sun_direction = [1., 0., 0.];
    renderer.set_environment(environment).unwrap();
    renderer.render(&changed, &camera, 17, 31, 19).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "changed sun radiance must update stored suffix radiance without resetting history"
    );
    renderer.set_environment(environment).unwrap();
    renderer.render(&changed, &camera, 17, 31, 20).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);

    let mut dynamic = scene(3, vec![]);
    dynamic.resources = changed.resources;
    let corners = face(0.).geometry.positions;
    dynamic.dynamic.revision = 1;
    dynamic.dynamic.triangles = [[0, 1, 2], [0, 2, 3]]
        .map(|indices| prime_scene::Triangle {
            positions: indices.map(|index| corners[index]),
            colors: [[0.7, 0.8, 0.9, 1.]; 3],
            uvs: [[0.; 2]; 3],
            texture_id: 0,
            flags: 0,
        })
        .to_vec()
        .into();
    renderer.render(&dynamic, &camera, 31, 17, 21).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().temporal_this_frame);
    camera.position[0] += 0.1;
    let pixels = renderer.render(&dynamic, &camera, 31, 17, 22).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "unchanged dynamic primary may use the accepted scene identity"
    );
    assert!(
        pixels
            .as_chunks::<4>()
            .0
            .iter()
            .all(|pixel| *pixel == [128, 128, 128, 255])
    );
    dynamic.dynamic.origin[2] = -1.;
    dynamic.dynamic.revision += 1;
    renderer.render(&dynamic, &camera, 31, 17, 23).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "dynamic geometry changes must reject only affected path identities"
    );
    camera.vertical_fov_radians *= 1.3;
    renderer.render(&dynamic, &camera, 31, 17, 101).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "FOV change and nonzero sequence gap must preserve accepted history"
    );
    let mut updated = renderer.settings;
    updated.seed = updated.seed.wrapping_add(17);
    renderer.configure(updated).unwrap();
    renderer.render(&dynamic, &camera, 31, 17, 102).unwrap();
    let state = renderer.restir.as_ref().unwrap();
    assert!(state.temporal_this_frame && !state.dynamic_update_this_frame);
    updated.sky *= 2.;
    updated.sun *= 2.;
    renderer.configure(updated).unwrap();
    renderer.render(&dynamic, &camera, 31, 17, 103).unwrap();
    let state = renderer.restir.as_ref().unwrap();
    assert!(state.temporal_this_frame && state.dynamic_update_this_frame);
    renderer.render(&dynamic, &camera, 31, 17, 104).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().dynamic_update_this_frame);
    updated.bounces += 1;
    renderer.configure(updated).unwrap();
    renderer.render(&dynamic, &camera, 31, 17, 105).unwrap();
    assert!(
        renderer.restir.as_ref().unwrap().temporal_this_frame,
        "vertex budget changes must use current-scene update and local path support"
    );
    assert!(renderer.restir.as_ref().unwrap().dynamic_update_this_frame);
}

#[test]
#[ignore = "requires windowless Vulkan; independent ReSTIR offline samples and FP32 online mean"]
fn gpu_restir_offline_batch_matches_sequential_fp32_for_each_light_sampler() {
    let camera = camera();
    let fixture = illuminated_scene(1);
    let config = settings(RenderMode::Offline);
    let mut sequential = renderer(config);
    let mut batched = renderer(RenderSettings {
        offline_samples: 3,
        ..config
    });
    {
        let method = LightSampling::Tree;
        let config = RenderSettings {
            light_sampling: method,
            ..config
        };
        sequential.configure(config).unwrap();
        batched
            .configure(RenderSettings {
                offline_samples: 3,
                ..config
            })
            .unwrap();
        let mut image = vec![];
        for sequence in 0..3 {
            image = sequential
                .render(&fixture, &camera, 31, 17, sequence)
                .unwrap();
            assert!(!sequential.restir.as_ref().unwrap().temporal_this_frame);
        }
        let batch_image = batched.render(&fixture, &camera, 31, 17, 0).unwrap();
        complete_rgba(&image, 31, 17);
        assert_eq!(image, batch_image, "{method:?} display output");
        assert_eq!(sequential.samples, 3);
        assert_eq!(batched.samples, 3);
        let expected = linear_history(&sequential, 31, 17);
        let actual = linear_history(&batched, 31, 17);
        assert!(expected.iter().all(|value| value.is_finite()));
        assert!(
            expected
                .as_chunks::<4>()
                .0
                .iter()
                .all(|pixel| pixel[3] == 1.)
        );
        assert!(
            expected
                .as_chunks::<4>()
                .0
                .iter()
                .any(|pixel| pixel[..3].iter().any(|value| *value > 0.01)),
            "inert lighting fixture"
        );
        for (index, (actual, expected)) in actual.iter().zip(&expected).enumerate() {
            assert_eq!(
                actual.to_bits(),
                expected.to_bits(),
                "{method:?} FP32 mean word {index}"
            );
        }
        batched
            .configure(RenderSettings {
                offline_samples: 2,
                ..config
            })
            .unwrap();
        batched.render(&fixture, &camera, 31, 17, 3).unwrap();
        assert_eq!(batched.samples, 5, "batch size must preserve accumulation");
        assert!(!batched.restir.as_ref().unwrap().temporal_this_frame);
    }
}

#[test]
#[ignore = "requires windowless Vulkan; borrowed queue acceptance, completion and descriptor slot reuse"]
fn gpu_restir_borrowed_history_commits_only_at_matching_acceptance() {
    let owner = Context::new().unwrap();
    let timeline = unsafe {
        let mut kind =
            vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
        owner.device.create_semaphore(
            &vk::SemaphoreCreateInfo::default().push_next(&mut kind),
            None,
        )
    }
    .unwrap();
    struct Timeline(Arc<Context>, vk::Semaphore);
    impl Drop for Timeline {
        fn drop(&mut self) {
            if self.0.can_destroy() {
                unsafe { self.0.device.destroy_semaphore(self.1, None) };
            }
        }
    }
    let _timeline = Timeline(owner.clone(), timeline);
    let config = RenderSettings {
        view: DiagnosticView::LinearDepth,
        depth_range: 8.,
        ..settings(RenderMode::Realtime)
    };
    // Context::new explicitly enables the scalar layout baseline required by the borrowed renderer.
    let mut renderer = unsafe {
        Renderer::borrowed_with_settings_and_workers(
            owner.instance_handle(),
            owner.physical.as_raw(),
            owner.device.handle().as_raw(),
            owner.queue.as_raw(),
            owner.queue_family,
            timeline.as_raw(),
            u32::from(owner.opacity_micromap.is_some()),
            config,
            Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
        )
    }
    .unwrap();
    let image = Image::new(&owner, 31, 17).unwrap();
    let fixture = scene(1, vec![face(0.)]);
    let camera = camera();
    for serial in 1..=4 {
        let previous = renderer
            .restir
            .as_ref()
            .map_or((false, 0), restir::State::accepted_history);
        let mut recorded = None;
        owner
            .submit_named("restir_test_borrowed", |command| {
                recorded = Some(unsafe {
                    renderer.record_host(
                        &fixture,
                        &camera,
                        31,
                        17,
                        serial as u32,
                        command.as_raw(),
                        image.image.as_raw(),
                        image.view.as_raw(),
                        serial,
                    )
                });
                if recorded.as_ref().unwrap().is_ok() {
                    let state = renderer.restir.as_ref().unwrap();
                    assert_eq!(
                        state.accepted_history(),
                        previous,
                        "recording advanced accepted history"
                    );
                    assert_eq!(state.temporal_this_frame, serial > 1);
                }
            })
            .unwrap();
        recorded.unwrap().unwrap();
        // The owner's fence proves exact completion before publishing the borrowed timeline.
        unsafe {
            owner.device.signal_semaphore(
                &vk::SemaphoreSignalInfo::default()
                    .semaphore(timeline)
                    .value(serial),
            )
        }
        .unwrap();
        assert_eq!(
            renderer.restir.as_ref().unwrap().accepted_history(),
            previous,
            "GPU completion alone advanced accepted history"
        );
        assert!(renderer.reset_world().is_err());
        assert!(
            !renderer.failed,
            "rejected world reset poisoned a pending frame"
        );
        assert_eq!(
            renderer.restir.as_ref().unwrap().accepted_history(),
            previous
        );
        assert!(renderer.submission_accepted(serial + 1).is_err());
        assert_eq!(
            renderer.restir.as_ref().unwrap().accepted_history(),
            previous
        );
        renderer.submission_accepted(serial).unwrap();
        assert_eq!(
            renderer.restir.as_ref().unwrap().accepted_history(),
            (true, previous.1 ^ 1)
        );
        assert!(renderer.submission_accepted(serial).is_err());
    }
    renderer.reset_world().unwrap();
    assert!(!renderer.restir.as_ref().unwrap().accepted_history().0);
    renderer.shutdown().unwrap();
}

fn rgb_mean(history: &[f32]) -> [f64; 3] {
    let mut sum = [0.; 3];
    for pixel in history.as_chunks::<4>().0 {
        for (total, value) in sum.iter_mut().zip(pixel) {
            assert!(value.is_finite() && *value >= 0.);
            *total += f64::from(*value);
        }
    }
    sum.map(|value| value / (history.len() / 4) as f64)
}

fn variance_of_mean(batches: &[[f64; 3]]) -> [f64; 3] {
    let n = batches.len() as f64;
    let mean = std::array::from_fn::<_, 3, _>(|channel| {
        batches.iter().map(|batch| batch[channel]).sum::<f64>() / n
    });
    std::array::from_fn(|channel| {
        batches
            .iter()
            .map(|batch| (batch[channel] - mean[channel]).powi(2))
            .sum::<f64>()
            / (n * (n - 1.))
    })
}

fn temporal_snapshot(renderer: &Renderer, width: u32, height: u32) -> (Vec<[f32; 5]>, Vec<f32>) {
    let (records, linear) = temporal_snapshot_words(renderer, width, height);
    (
        records
            .iter()
            .map(|record| std::array::from_fn(|i| f32::from_bits(record[i])))
            .collect(),
        linear,
    )
}

fn temporal_snapshot_words(
    renderer: &Renderer,
    width: u32,
    height: u32,
) -> (Vec<[u32; 20]>, Vec<f32>) {
    let (storage, offset, padded_pixels) = renderer.restir.as_ref().unwrap().history_for_test();
    let reservoir_bytes = u64::from(padded_pixels) * 80;
    let linear_bytes = u64::from(width) * u64::from(height) * 16;
    let readback = Buffer::new_readback(&renderer.context, reservoir_bytes + linear_bytes).unwrap();
    let image = renderer.output.as_ref().unwrap().linear_image();
    renderer
        .context
        .submit_named("restir_test_temporal_snapshot", |command| unsafe {
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
                storage.buffer,
                readback.buffer,
                &[vk::BufferCopy::default()
                    .src_offset(offset)
                    .size(reservoir_bytes)],
            );
            renderer.context.device.cmd_copy_image_to_buffer(
                command,
                image.image,
                vk::ImageLayout::GENERAL,
                readback.buffer,
                &[vk::BufferImageCopy::default()
                    .buffer_offset(reservoir_bytes)
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width,
                        height,
                        depth: 1,
                    })],
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
    let bytes = readback
        .read((reservoir_bytes + linear_bytes) as usize)
        .unwrap();
    let mut reservoirs = Vec::with_capacity((width * height) as usize);
    for y in 0..height {
        for x in 0..width {
            let morton = (0..4).fold(0, |offset, bit| {
                offset | (((x >> bit) & 1) << (2 * bit)) | (((y >> bit) & 1) << (2 * bit + 1))
            });
            let index = ((y / 16) * width.div_ceil(16) + x / 16) * 256 + morton;
            let record = &bytes[index as usize * 80..][..80];
            reservoirs.push(std::array::from_fn(|word| {
                u32::from_le_bytes(record[word * 4..word * 4 + 4].try_into().unwrap())
            }));
        }
    }
    let linear = bytes[reservoir_bytes as usize..]
        .as_chunks::<4>()
        .0
        .iter()
        .map(|word| f32::from_le_bytes(*word))
        .collect();
    (reservoirs, linear)
}

#[test]
#[ignore = "requires windowless Vulkan; actual illuminated temporal reservoir growth, cap and resets"]
fn gpu_restir_realtime_illuminated_history_grows_and_resets_actual_reservoirs() {
    let mut renderer = renderer(RenderSettings {
        bounces: 3,
        auto_exposure_compensation: 0.1,
        ..settings(RenderMode::Realtime)
    });
    let mut fixture = illuminated_scene(1);
    let mut camera = camera();
    let mut first_mean = 0.;
    let mut last_mean = 0.;
    let mut report =
        String::from("frame,temporal,mean_M,max_M,positive_paths,mean_R,mean_G,mean_B\n");
    let mut inspect = |renderer: &Renderer, sequence, initial: bool| {
        let (reservoirs, linear) = temporal_snapshot(renderer, 31, 17);
        assert!(
            reservoirs
                .iter()
                .flatten()
                .all(|value| value.is_finite() && *value >= 0.)
        );
        let maximum = reservoirs.iter().map(|record| record[0]).fold(0., f32::max);
        assert!(
            maximum <= 84.,
            "{sequence}: history M exceeds 4*(20+1): {maximum}"
        );
        if initial {
            assert!(
                maximum <= 4.,
                "{sequence}: reset retained temporal M: {maximum}"
            );
        }
        let positive = reservoirs
            .iter()
            .filter(|record| record[1] > 0. && record[2..].iter().any(|value| *value > 0.))
            .count();
        assert!(
            positive > 100,
            "{sequence}: inert reservoir lighting: {positive}"
        );
        assert!(linear.as_chunks::<4>().0.iter().all(|pixel| pixel[3] == 1.));
        let rgb = rgb_mean(&linear);
        assert!(
            rgb.iter().all(|value| *value > 0.001),
            "{sequence}: inert raw linear output: {rgb:?}"
        );
        let mean = reservoirs
            .iter()
            .map(|record| f64::from(record[0]))
            .sum::<f64>()
            / reservoirs.len() as f64;
        report.push_str(&format!(
            "{sequence},{},{mean:.6},{maximum},{positive},{:.9},{:.9},{:.9}\n",
            renderer.restir.as_ref().unwrap().temporal_this_frame,
            rgb[0],
            rgb[1],
            rgb[2]
        ));
        println!(
            "ReSTIR temporal frame {sequence}: meanM={mean:.3}, maxM={maximum}, positive={positive}, RGB={rgb:?}"
        );
        mean
    };
    for sequence in 1..=12 {
        renderer
            .render(&fixture, &camera, 31, 17, sequence)
            .unwrap();
        let mean = inspect(&renderer, sequence, sequence == 1);
        if sequence == 1 {
            first_mean = mean;
        }
        last_mean = mean;
    }
    assert!(
        last_mean > first_mean + 4.,
        "temporal history did not contribute actual GPU candidates: first={first_mean}, last={last_mean}"
    );
    // A different complete terrain cell changes the scene publication without replacing
    // the visible room's source pages. Read actual GPU reservoirs, not just a host flag.
    let far_origin = [64., 0., 0.];
    let far_cell = prime_scene::spatial::Cell::containing(far_origin).unwrap();
    fixture.ready_terrain.insert(far_cell);
    fixture.meshes.insert(
        (2, 0),
        prime_scene::scene::SceneMesh {
            revision: 1,
            flags: 0,
            origin: far_origin,
            triangles: prime_scene::geometry::MeshGeometry::Surfaces(Arc::new(
                prime_scene::surface::SurfaceMesh::from_resolved(1, vec![face(0.)]).unwrap(),
            )),
        },
    );
    fixture.revision = 2;
    for sequence in 13..=18 {
        match sequence {
            14 => {
                let corners = face(0.).geometry.positions;
                fixture.dynamic.origin = [80., 0., 0.];
                fixture.dynamic.revision = 1;
                fixture.dynamic.triangles = [[0, 1, 2], [0, 2, 3]]
                    .map(|indices| prime_scene::Triangle {
                        positions: indices.map(|index| corners[index]),
                        colors: [[0.7, 0.8, 0.9, 1.]; 3],
                        uvs: [[0.; 2]; 3],
                        texture_id: 0,
                        flags: 0,
                    })
                    .to_vec()
                    .into();
            }
            15 => {
                fixture.dynamic.origin[0] += 1.;
                fixture.dynamic.revision += 1;
            }
            16 => {
                fixture
                    .textures
                    .insert(77, realtime_tests::texture(1, 1, vec![255; 4]));
                fixture.revision += 1;
            }
            17 => {
                fixture.meshes.remove(&(2, 0));
                fixture.ready_terrain.remove(&far_cell);
                fixture.revision += 1;
            }
            18 => {
                fixture.anchor = [64., 0., 0.];
                camera.position[0] -= 64.;
            }
            _ => {}
        }
        renderer
            .render(&fixture, &camera, 31, 17, sequence)
            .unwrap();
        assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
        assert!(
            inspect(&renderer, sequence, false) > 4.,
            "local update {sequence} discarded unaffected room history"
        );
    }
    let mut environment = renderer.environment;
    environment.sun_direction = [1., 0., 0.];
    renderer.set_environment(environment).unwrap();
    renderer.render(&fixture, &camera, 31, 17, 19).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert!(inspect(&renderer, 19, false) > 4.);
    renderer.reset_world().unwrap();
    renderer.render(&fixture, &camera, 31, 17, 20).unwrap();
    inspect(&renderer, 20, true);
    renderer.render(&fixture, &camera, 31, 17, 21).unwrap();
    assert!(inspect(&renderer, 21, false) > first_mean);
    camera.position[0] += 0.05;
    renderer.render(&fixture, &camera, 31, 17, 22).unwrap();
    inspect(&renderer, 22, false);
    let mut changed = illuminated_scene(2);
    changed.resources = fixture.resources;
    camera.position[0] += 64.;
    renderer.render(&changed, &camera, 31, 17, 23).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    inspect(&renderer, 23, true);
    changed.epoch += 1;
    renderer.render(&changed, &camera, 31, 17, 24).unwrap();
    assert!(
        !renderer.restir.as_ref().unwrap().temporal_this_frame,
        "a new scene epoch cannot reuse the old identity watermark"
    );
    inspect(&renderer, 24, true);
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/restir-pt");
    std::fs::write(output.join("temporal-reservoir-history.csv"), report).unwrap();
}

#[test]
#[ignore = "requires windowless Vulkan; independent ReSTIR estimator mean versus PT with batch variance"]
fn gpu_restir_offline_mean_agrees_with_path_trace_on_direct_and_indirect_lighting() {
    const WIDTH: u32 = 31;
    const HEIGHT: u32 = 17;
    const BATCH: u32 = 32;
    const BATCHES: u32 = 16;
    let camera = camera();
    let fixture = illuminated_scene(1);
    let config = RenderSettings {
        offline_samples: BATCH,
        ..settings(RenderMode::Offline)
    };
    let mut restir = renderer(config);
    let mut reference = renderer(RenderSettings {
        integrator: Integrator::PathTrace,
        ..config
    });
    let mut report = format!(
        "device={}\nwidth={WIDTH},height={HEIGHT},samples={},batch={BATCH},seed={}\nlight,bounces,channel,restir_mean,pt_mean,restir_standard_error,pt_standard_error,error,allowed_error\n",
        restir.device_name(),
        BATCH * BATCHES,
        config.seed
    );
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        for bounces in [1, 3] {
            println!(
                "ReSTIR versus PT: {method:?}, {bounces} bounces, {} samples",
                BATCH * BATCHES
            );
            let settings = RenderSettings {
                light_sampling: method,
                bounces,
                ..config
            };
            restir.configure(settings).unwrap();
            reference
                .configure(RenderSettings {
                    integrator: Integrator::PathTrace,
                    ..settings
                })
                .unwrap();
            let mut previous = [[0.; 3]; 2];
            let mut batches = [Vec::new(), Vec::new()];
            for batch in 0..BATCHES {
                for (index, renderer) in [&mut restir, &mut reference].into_iter().enumerate() {
                    renderer
                        .render(
                            &fixture,
                            &camera,
                            WIDTH,
                            HEIGHT,
                            if batch == 0 { 0 } else { batch },
                        )
                        .unwrap();
                    let mean = rgb_mean(&linear_history(renderer, WIDTH, HEIGHT));
                    batches[index].push(std::array::from_fn(|channel| {
                        mean[channel] * f64::from(batch + 1)
                            - previous[index][channel] * f64::from(batch)
                    }));
                    previous[index] = mean;
                }
            }
            let variance = batches.each_ref().map(|samples| variance_of_mean(samples));
            assert_eq!(restir.samples, BATCH * BATCHES);
            assert_eq!(reference.samples, BATCH * BATCHES);
            assert!(!restir.restir.as_ref().unwrap().temporal_this_frame);
            assert!(
                previous[1].iter().any(|value| *value > 0.01),
                "inert lighting reference"
            );
            for channel in 0..3 {
                let error = (previous[0][channel] - previous[1][channel]).abs();
                // Pixels within each ReSTIR sample share candidates. Estimate uncertainty
                // from whole-image batches, without treating those pixels as independent.
                let sampling_error = 4. * (variance[0][channel] + variance[1][channel]).sqrt();
                let tolerance = 0.05 * previous[1][channel].abs() + sampling_error + 0.0001;
                report.push_str(&format!("{method:?},{bounces},{channel},{:.9},{:.9},{:.9},{:.9},{error:.9},{tolerance:.9}\n",
                    previous[0][channel], previous[1][channel], variance[0][channel].sqrt(), variance[1][channel].sqrt()));
                if error > tolerance {
                    failures.push(format!(
                        "{method:?}/{bounces} bounces channel{channel}: ReSTIR={} PT={}, error={error}, tolerance={tolerance}",
                        previous[0][channel], previous[1][channel]
                    ));
                }
            }
        }
    }
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/restir-pt");
    std::fs::create_dir_all(&output).unwrap();
    std::fs::write(output.join("offline-mean-comparison.csv"), &report).unwrap();
    println!("{report}");
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
#[ignore = "requires windowless Vulkan; independent continuous-reuse chains versus ordinary PT"]
fn gpu_restir_continuous_reuse_chain_means_agree_with_path_trace() {
    const WIDTH: u32 = 31;
    const HEIGHT: u32 = 17;
    const CHAINS: u32 = 8;
    const WARMUP: u32 = 16;
    const SAMPLES: u32 = 64;
    let camera = camera();
    let fixture = multiple_light_scene(1);
    // Request the existing linear display intermediate. Exposure is applied only
    // later in display, and does not alter the sampled FP32 radiance copied below.
    let config = RenderSettings {
        bounces: 3,
        auto_exposure_compensation: 0.1,
        ..settings(RenderMode::Realtime)
    };
    let mut restir = renderer(config);
    let mut reference = renderer(RenderSettings {
        integrator: Integrator::PathTrace,
        mode: RenderMode::Offline,
        offline_samples: SAMPLES,
        auto_exposure_compensation: 0.,
        ..config
    });
    let mut frames = String::from("sampler,chain,seed,frame,warmup,mean_M,max_M,max_rgb,R,G,B\n");
    let mut report = String::from("sampler,chain,seed,restir_R,restir_G,restir_B,pt_R,pt_G,pt_B\n");
    let mut failures = Vec::new();
    {
        let method = LightSampling::Tree;
        let mut means = [Vec::new(), Vec::new()];
        for chain in 0..CHAINS {
            let seed = 0x1357_2468_u32.wrapping_add(chain.wrapping_mul(0x9e37_79b9));
            restir
                .configure(RenderSettings {
                    seed,
                    light_sampling: method,
                    ..config
                })
                .unwrap();
            reference
                .configure(RenderSettings {
                    seed,
                    light_sampling: method,
                    integrator: Integrator::PathTrace,
                    mode: RenderMode::Offline,
                    offline_samples: SAMPLES,
                    auto_exposure_compensation: 0.,
                    ..config
                })
                .unwrap();
            // Each chain has its own accepted history. Frames and pixels within
            // one chain are correlated and are not treated as independent trials.
            restir.reset_world().unwrap();
            reference.reset_world().unwrap();
            let mut sum = [0.; 3];
            for frame in 0..WARMUP + SAMPLES {
                restir
                    .render(&fixture, &camera, WIDTH, HEIGHT, frame)
                    .unwrap();
                assert_eq!(
                    restir.restir.as_ref().unwrap().temporal_this_frame,
                    frame != 0
                );
                let (reservoirs, linear) = temporal_snapshot(&restir, WIDTH, HEIGHT);
                let rgb = rgb_mean(&linear);
                let mean_m = reservoirs
                    .iter()
                    .map(|record| record[0] as f64)
                    .sum::<f64>()
                    / reservoirs.len() as f64;
                let max_m = reservoirs.iter().map(|record| record[0]).fold(0., f32::max);
                let max_rgb = linear
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .flat_map(|pixel| &pixel[..3])
                    .copied()
                    .fold(0., f32::max);
                assert!(mean_m.is_finite() && mean_m >= 1.);
                if frame >= WARMUP {
                    for (total, value) in sum.iter_mut().zip(rgb) {
                        *total += value;
                    }
                }
                frames.push_str(&format!(
                    "{method:?},{chain},{seed},{frame},{},{mean_m},{max_m},{max_rgb},{},{},{}\n",
                    frame < WARMUP,
                    rgb[0],
                    rgb[1],
                    rgb[2]
                ));
            }
            reference
                .render(&fixture, &camera, WIDTH, HEIGHT, 0)
                .unwrap();
            let restir_mean = sum.map(|value| value / SAMPLES as f64);
            let pt_mean = rgb_mean(&linear_history(&reference, WIDTH, HEIGHT));
            assert!(pt_mean.iter().all(|value| *value > 0.001));
            report.push_str(&format!(
                "{method:?},{chain},{seed},{},{},{},{},{},{}\n",
                restir_mean[0], restir_mean[1], restir_mean[2], pt_mean[0], pt_mean[1], pt_mean[2]
            ));
            means[0].push(restir_mean);
            means[1].push(pt_mean);
        }
        let variance = means.each_ref().map(|batches| variance_of_mean(batches));
        for channel in 0..3 {
            let average = means
                .each_ref()
                .map(|chains| chains.iter().map(|mean| mean[channel]).sum::<f64>() / CHAINS as f64);
            let error = (average[0] - average[1]).abs();
            // Independent chain uncertainty includes temporal/spatial correlation;
            // the small relative allowance also covers center-versus-pixel-area primary sampling.
            let tolerance = 0.02 * average[1].abs()
                + 5. * (variance[0][channel] + variance[1][channel]).sqrt()
                + 0.0001;
            println!(
                "continuous reuse {method:?} channel{channel}: ReSTIR={} PT={} error={error} tolerance={tolerance}",
                average[0], average[1]
            );
            if error > tolerance {
                failures.push(format!(
                    "{method:?} channel{channel}: error {error} > {tolerance}"
                ));
            }
        }
    }
    let output = artifact_output();
    std::fs::create_dir_all(&output).unwrap();
    std::fs::write(output.join("continuous-reuse-frames.csv"), frames).unwrap();
    std::fs::write(output.join("continuous-reuse-chains.csv"), report).unwrap();
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
#[ignore = "requires windowless Vulkan; RR-off directional motion, estimator means and path lineage"]
fn gpu_restir_opposite_camera_motion_preserves_transport_mean() {
    opposite_camera_motion_preserves_transport_mean(1., "directional-motion", false);
}

#[test]
#[ignore = "requires windowless Vulkan; RR-off opposite motion with actual cross-pixel history lineage"]
fn gpu_restir_cross_pixel_opposite_camera_motion_preserves_transport_mean() {
    opposite_camera_motion_preserves_transport_mean(0.05, "cross-pixel-directional-motion", true);
}

fn opposite_camera_motion_preserves_transport_mean(
    vertical_fov_radians: f32,
    report_prefix: &str,
    require_changed_addresses: bool,
) {
    const WIDTH: u32 = 31;
    const HEIGHT: u32 = 17;
    const CHAINS: u32 = 4;
    const WARMUP: u32 = 16;
    const FRAMES: u32 = 64;
    let fixture = multiple_light_scene(1);
    let config = RenderSettings {
        bounces: 3,
        auto_exposure_compensation: 0.1,
        ..settings(RenderMode::Realtime)
    };
    let mut restir = renderer(config);
    let mut reference = renderer(RenderSettings {
        integrator: Integrator::PathTrace,
        mode: RenderMode::Offline,
        offline_samples: 8,
        auto_exposure_compensation: 0.,
        ..config
    });
    let mut frames = String::from(
        "sampler,direction,chain,seed,frame,warmup,camera_x,camera_y,mean_M,max_rgb,reprojected_lineage_fraction,R,G,B,pt_R,pt_G,pt_B\n",
    );
    let mut pixels = String::from(
        "sampler,direction,chain,frame,x,y,old_x,old_y,init_seed,rc_seed,path_length,rc_length,M,weight,R,G,B\n",
    );
    let mut chains =
        String::from("sampler,direction,chain,seed,restir_R,restir_G,restir_B,pt_R,pt_G,pt_B\n");
    let mut failures = Vec::new();
    // Each positive/negative pair visits the same set of camera positions in reverse order.
    // Compare radiance to PT at every moving position rather than to one static image.
    for (method, direction, axis, sign) in [LightSampling::Tree].into_iter().flat_map(|method| {
        [("x+", 0, 1.), ("x-", 0, -1.), ("y+", 1, 1.), ("y-", 1, -1.)]
            .map(move |(direction, axis, sign)| (method, direction, axis, sign))
    }) {
        let mut means = [Vec::new(), Vec::new()];
        for chain in 0..CHAINS {
            let seed = 0x1357_2468_u32.wrapping_add(chain.wrapping_mul(0x9e37_79b9));
            restir
                .configure(RenderSettings {
                    seed,
                    light_sampling: method,
                    ..config
                })
                .unwrap();
            reference
                .configure(RenderSettings {
                    seed,
                    light_sampling: method,
                    integrator: Integrator::PathTrace,
                    mode: RenderMode::Offline,
                    offline_samples: 8,
                    auto_exposure_compensation: 0.,
                    ..config
                })
                .unwrap();
            restir.reset_world().unwrap();
            reference.reset_world().unwrap();
            let mut sums = [[0.; 3]; 2];
            let mut retained_seed_families = 0;
            let mut changed_addresses = 0;
            let mut previous = None::<(Camera, Vec<[u32; 20]>)>;
            for frame in 0..WARMUP + FRAMES {
                let mut current = camera();
                current.vertical_fov_radians = vertical_fov_radians;
                let step = frame.saturating_sub(WARMUP).min(FRAMES - 1);
                current.position[axis] += sign * (step as f32 / (FRAMES - 1) as f32 - 0.5);
                restir
                    .render(&fixture, &current, WIDTH, HEIGHT, frame)
                    .unwrap();
                assert_eq!(
                    restir.restir.as_ref().unwrap().temporal_this_frame,
                    frame != 0,
                    "{direction} frame{frame}: camera motion triggered global reset"
                );
                let (records, linear) = temporal_snapshot_words(&restir, WIDTH, HEIGHT);
                let rgb = rgb_mean(&linear);
                let mut matches = 0;
                let mut eligible = 0;
                let mut mean_m = 0.;
                let max_rgb = linear
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .flat_map(|pixel| &pixel[..3])
                    .copied()
                    .fold(0., f32::max);
                for y in 0..HEIGHT {
                    for x in 0..WIDTH {
                        let index = (y * WIDTH + x) as usize;
                        let record = records[index];
                        let m = f32::from_bits(record[0]);
                        let weight = f32::from_bits(record[1]);
                        assert!(m.is_finite() && m >= 1. && m <= 84.);
                        assert!(weight.is_finite() && weight >= 0.);
                        mean_m += m as f64;
                        let old_pixel = previous.as_ref().map_or([-1; 2], |(old, _)| {
                            restir_reprojection_tests::expected_pixel(
                                current,
                                *old,
                                [x, y],
                                [WIDTH, HEIGHT],
                            )
                        });
                        if frame >= WARMUP && old_pixel[0] >= 0 && old_pixel != [x as i32, y as i32]
                        {
                            changed_addresses += 1;
                        }
                        if let Some((_, old)) = &previous {
                            if old_pixel[0] >= 0 && weight > 0. {
                                let old_index =
                                    (old_pixel[1] as u32 * WIDTH + old_pixel[0] as u32) as usize;
                                if f32::from_bits(old[old_index][1]) > 0. {
                                    eligible += 1;
                                    matches += usize::from(record[6] == old[old_index][6]);
                                }
                            }
                        }
                        // Include both sides of Morton tile boundaries and a small bright-source ROI.
                        if [7, 15, 16, 23].contains(&x) && [4, 8, 12].contains(&y) {
                            let color = &linear[4 * index..][..3];
                            pixels.push_str(&format!(
                                "{method:?},{direction},{chain},{frame},{x},{y},{},{},{},{},{},{},{m},{weight},{},{},{}\n",
                                old_pixel[0], old_pixel[1], record[6], record[7],
                                record[5] & 255, (record[5] >> 8) & 255, color[0], color[1], color[2]
                            ));
                        }
                    }
                }
                mean_m /= (WIDTH * HEIGHT) as f64;
                let mut pt = [0.; 3];
                if frame >= WARMUP {
                    retained_seed_families += matches;
                    reference
                        .render(&fixture, &current, WIDTH, HEIGHT, frame)
                        .unwrap();
                    assert_eq!(
                        reference.samples, 8,
                        "moving PT reference accumulated another viewpoint"
                    );
                    pt = rgb_mean(&linear_history(&reference, WIDTH, HEIGHT));
                    for channel in 0..3 {
                        sums[0][channel] += rgb[channel];
                        sums[1][channel] += pt[channel];
                    }
                }
                let lineage = if eligible == 0 {
                    0.
                } else {
                    matches as f64 / eligible as f64
                };
                frames.push_str(&format!(
                    "{method:?},{direction},{chain},{seed},{frame},{},{},{},{mean_m},{max_rgb},{lineage},{},{},{},{},{},{}\n",
                    frame < WARMUP, current.position[0], current.position[1],
                    rgb[0], rgb[1], rgb[2], pt[0], pt[1], pt[2]
                ));
                previous = Some((current, records));
            }
            // Require actual retained source seed families, not merely a dispatched
            // temporal stage. This does not assert that every reconstructed path is identical.
            assert!(
                retained_seed_families > 0,
                "{method:?} {direction}: no retained seed family"
            );
            if require_changed_addresses {
                assert!(
                    changed_addresses > 0,
                    "{method:?} {direction} chain{chain}: no measured cross-pixel address"
                );
            }
            eprintln!(
                "moving reuse {method:?} {direction} chain{chain}: fov={vertical_fov_radians} measured_changed_addresses={changed_addresses} retained_seed_families={retained_seed_families}"
            );
            let averages = sums.map(|sum| sum.map(|value| value / FRAMES as f64));
            for index in 0..2 {
                means[index].push(averages[index]);
            }
            chains.push_str(&format!(
                "{method:?},{direction},{chain},{seed},{},{},{},{},{},{}\n",
                averages[0][0],
                averages[0][1],
                averages[0][2],
                averages[1][0],
                averages[1][1],
                averages[1][2]
            ));
        }
        let variance = means.each_ref().map(|chains| variance_of_mean(chains));
        for channel in 0..3 {
            let average = means
                .each_ref()
                .map(|chains| chains.iter().map(|x| x[channel]).sum::<f64>() / CHAINS as f64);
            let error = (average[0] - average[1]).abs();
            let tolerance = 0.02 * average[1].abs()
                + 5. * (variance[0][channel] + variance[1][channel]).sqrt()
                + 0.0001;
            println!(
                "moving reuse {method:?} {direction} channel{channel}: ReSTIR={} PT={} error={error} tolerance={tolerance}",
                average[0], average[1]
            );
            if error > tolerance {
                failures.push(format!(
                    "{method:?} {direction} channel{channel}: error{error} > {tolerance}"
                ));
            }
        }
    }
    let output = artifact_output();
    std::fs::create_dir_all(&output).unwrap();
    std::fs::write(output.join(format!("{report_prefix}-frames.csv")), frames).unwrap();
    std::fs::write(output.join(format!("{report_prefix}-pixels.csv")), pixels).unwrap();
    std::fs::write(output.join(format!("{report_prefix}-chains.csv")), chains).unwrap();
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
