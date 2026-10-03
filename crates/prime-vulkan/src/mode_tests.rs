use super::*;
use prime_scene::scene::{SceneMesh, Texture, Triangle};
use prime_scene::settings::DiagnosticView;

#[test]
#[ignore = "requires Vulkan with synchronization validation; renderer resource ownership and real host submission"]
fn gpu_resource_prepare_world_reset_and_quality_reuse() {
    use prime_scene::{settings::ReconstructionQuality, workers::CpuWorkers};
    let owner = Context::new().unwrap();
    let timeline = unsafe {
        let mut ty =
            vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
        owner
            .device
            .create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty), None)
    }
    .unwrap();
    struct Timeline(Arc<Context>, vk::Semaphore);
    impl Drop for Timeline {
        fn drop(&mut self) {
            if self.0.can_destroy() {
                unsafe {
                    self.0.device.destroy_semaphore(self.1, None);
                }
            }
        }
    }
    let _timeline = Timeline(owner.clone(), timeline);
    let workers = Arc::new(CpuWorkers::new(1).unwrap());
    let settings = RenderSettings {
        mode: RenderMode::Realtime,
        ray_reconstruction: false,
        reconstruction_quality: ReconstructionQuality::Quality,
        view: DiagnosticView::LinearDepth,
        ..Default::default()
    };
    let mut renderer = unsafe {
        Renderer::borrowed_with_settings_and_workers(
            owner.instance_handle(),
            owner.physical.as_raw(),
            owner.device.handle().as_raw(),
            owner.queue.as_raw(),
            owner.queue_family,
            timeline.as_raw(),
            u32::from(owner.opacity_micromap.is_some()),
            settings,
            workers.clone(),
        )
    }
    .unwrap();
    assert!(Arc::ptr_eq(&renderer.workers, &workers));
    assert_eq!(renderer.settings, settings);
    assert!(renderer.reconstruction.is_none());
    assert!(renderer.atmosphere.is_none());
    assert!(!renderer.energy_lut.is_ready());
    let pipeline = renderer.pipeline.as_ref().unwrap().pipelines;
    let lut = renderer.energy_lut.descriptor().image_view;
    let mut scene = plane();
    let texture = scene.textures.get_mut(&7).unwrap();
    texture.width = 2;
    texture.height = 2;
    texture.region = Some([0, 0, 2, 2]);
    texture.pixels = vec![
        255, 255, 255, 255, 255, 255, 255, 0, 255, 255, 255, 0, 255, 255, 255, 255,
    ]
    .into();
    let image = Image::new(&owner, 19, 13).unwrap();
    let camera = camera();
    let mut key = None;
    for serial in 1..=2 {
        if serial == 2 {
            let allocated = renderer
                .context
                .live_allocations
                .load(std::sync::atomic::Ordering::Relaxed);
            assert!(renderer.geometry.is_some());
            assert!(renderer.output.is_some());
            renderer.reset_world().unwrap();
            assert!(renderer.geometry.is_none());
            assert!(renderer.output.is_none());
            assert!(renderer.camera.is_none());
            assert_eq!(renderer.samples, 0);
            assert!(
                renderer
                    .context
                    .live_allocations
                    .load(std::sync::atomic::Ordering::Relaxed)
                    < allocated,
                "World GPU allocations must retire before another frame is recorded"
            );
            assert_eq!(renderer.pipeline.as_ref().unwrap().pipelines, pipeline);
            assert_eq!(renderer.energy_lut.descriptor().image_view, lut);
            // World epoch changes do not change this independently owned resource identity.
            scene.epoch += 1;
            scene.revision += 1;
            renderer
                .configure(RenderSettings {
                    reconstruction_quality: ReconstructionQuality::Performance,
                    ..settings
                })
                .unwrap();
        }
        let mut result = None;
        owner
            .submit_named("prepare_then_record_resource_test", |command| {
                result = Some((|| -> Result<(), String> {
                    unsafe {
                        renderer.prepare_host_resources(
                            (&scene).into(),
                            command.as_raw(),
                            serial,
                        )?;
                    }
                    assert!(renderer.energy_lut.is_ready());
                    assert!(renderer.atmosphere.is_some());
                    if serial == 1 {
                        assert!(renderer.geometry.is_none());
                    }
                    let resources = renderer.scene_resources.as_ref().unwrap().clone();
                    let current = {
                        let resources = resources.borrow();
                        (
                            resources.textures.metadata.buffer,
                            resources.textures.texels.buffer,
                            resources.pool.stats(),
                        )
                    };
                    if let Some(previous) = key {
                        assert_eq!(current, previous);
                    } else {
                        key = Some(current);
                    }
                    if owner.opacity_micromap.is_some() {
                        assert!(current.2[0] > 0);
                    }
                    let uploaded = renderer.context.cpu_upload_bytes();
                    unsafe {
                        renderer.prepare_host_resources(
                            (&scene).into(),
                            command.as_raw(),
                            serial,
                        )?;
                    }
                    assert_eq!(
                        renderer.context.cpu_upload_bytes(),
                        uploaded,
                        "Repeated explicit prepare reuploaded immutable resources"
                    );
                    unsafe {
                        renderer.record_host(
                            &scene,
                            &camera,
                            19,
                            13,
                            serial as u32,
                            command.as_raw(),
                            image.image.as_raw(),
                            image.view.as_raw(),
                            serial,
                        )?;
                    }
                    assert_eq!(renderer.pipeline.as_ref().unwrap().pipelines, pipeline);
                    assert_eq!(renderer.energy_lut.descriptor().image_view, lut);
                    assert!(std::rc::Rc::ptr_eq(
                        renderer.scene_resources.as_ref().unwrap(),
                        &resources
                    ));
                    Ok(())
                })());
            })
            .unwrap();
        // The owner's fence completed this exact command before publishing its completion value.
        unsafe {
            owner.device.signal_semaphore(
                &vk::SemaphoreSignalInfo::default()
                    .semaphore(timeline)
                    .value(serial),
            )
        }
        .unwrap();
        result.unwrap().unwrap();
        assert!(renderer.submission_accepted(serial + 1).is_err());
        renderer.submission_accepted(serial).unwrap();
        assert!(renderer.submission_accepted(serial).is_err());
    }
    // A completed realtime exposure may be frozen when entering Offline, except when
    // the same settings transaction also changes the meter's compensation strength.
    renderer.exposure_time = Some(std::time::Instant::now());
    renderer.exposure_reset = false;
    renderer
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            auto_exposure_compensation: 0.8,
            ..settings
        })
        .unwrap();
    assert!(renderer.exposure_reset);
    assert!(!renderer.exposure_frozen);
    renderer.exposure_reset = false;
    renderer
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            auto_exposure_compensation: 0.0,
            ..settings
        })
        .unwrap();
    renderer
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            auto_exposure_compensation: 0.6,
            ..settings
        })
        .unwrap();
    assert!(renderer.exposure_reset);
    assert!(!renderer.exposure_frozen);
    renderer.shutdown().unwrap();
}

pub(crate) fn camera() -> Camera {
    Camera {
        position: [0.0, 0.0, 2.0],
        forward: [0.0, 0.0, -1.0],
        right: [1.0, 0.0, 0.0],
        up: [0.0, 1.0, 0.0],
        vertical_fov_radians: 1.0,
    }
}
pub(crate) fn plane() -> Scene {
    let mut scene = Scene {
        ready_terrain: [[0.0; 3], [128.0, 0.0, 0.0]]
            .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
            .into(),
        revision: 1,
        epoch: 1,
        ..Default::default()
    };
    scene.textures.insert(
        7,
        Texture {
            region: None,
            sampling: None,
            material: None,
            width: 1,
            height: 1,
            pixels: vec![255; 4].into(),
        },
    );
    let positions = [
        [[-20.0, -20.0, 0.0], [20.0, -20.0, 0.0], [20.0, 20.0, 0.0]],
        [[-20.0, -20.0, 0.0], [20.0, 20.0, 0.0], [-20.0, 20.0, 0.0]],
    ];
    scene.meshes.insert(
        (1, 0),
        SceneMesh {
            revision: 1,
            flags: 2,
            origin: [0.0; 3],
            triangles: positions
                .map(|positions| Triangle {
                    positions,
                    colors: [[0.65, 0.65, 0.65, 1.0]; 3],
                    uvs: [[0.5; 2]; 3],
                    texture_id: 7,
                    flags: 2,
                })
                .to_vec()
                .into(),
        },
    );
    scene
}
#[test]
#[ignore = "requires Vulkan with synchronization validation; realtime primary sample and diagnostic display"]
fn gpu_realtime_views_match_primary_visibility_and_have_no_history() {
    let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
    // This oracle compares transport and deterministic manual display, independently of
    // wall-clock-driven exposure adaptation and the optional celestial display pass.
    let settings = RenderSettings {
        stars: 0.0,
        auto_exposure_compensation: 0.0,
        ray_reconstruction: false,
        ..Default::default()
    };
    renderer.configure(settings).unwrap();
    let mut scene = plane();
    let camera = camera();
    let first = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert!(renderer.output.as_ref().unwrap().accumulation.is_none());
    let mut offline = Renderer::new().unwrap();
    offline
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            ..settings
        })
        .unwrap();
    assert_eq!(
        first,
        offline.render(&scene, &camera, 31, 17, 0).unwrap(),
        "Realtime display must match the same single offline sample"
    );
    assert_ne!(
        renderer.render(&scene, &camera, 31, 17, 23).unwrap(),
        first,
        "Static realtime frame must advance the noise sequence"
    );
    assert_eq!(renderer.samples, 1);
    assert_eq!(
        renderer.render(&scene, &camera, 31, 17, 0).unwrap(),
        first,
        "Frame zero must be independent of prior noise"
    );
    for (view, expected) in [
        (DiagnosticView::LinearDepth, [128, 128, 128, 255]),
        (DiagnosticView::Normal, [128, 128, 255, 255]),
    ] {
        renderer
            .configure(RenderSettings {
                view,
                depth_range: 4.0,
                ..settings
            })
            .unwrap();
        let image = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
        assert!(
            image
                .as_chunks::<4>()
                .0
                .iter()
                .all(|rgba| *rgba == expected)
        );
    }
    renderer
        .configure(RenderSettings {
            view: DiagnosticView::NoisyColor,
            ..settings
        })
        .unwrap();
    let image = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert_ne!(image, first, "Raw view must bypass primeDRT");
    renderer.configure(settings).unwrap();
    assert_eq!(
        renderer.render(&scene, &camera, 31, 17, 0).unwrap(),
        first,
        "Diagnostic switching must preserve the radiance sequence"
    );
    scene.textures.get_mut(&7).unwrap().pixels = vec![255, 255, 255, 0].into();
    scene.revision += 1;
    for view in [DiagnosticView::LinearDepth, DiagnosticView::Normal] {
        renderer
            .configure(RenderSettings { view, ..settings })
            .unwrap();
        let sky = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
        assert!(
            sky.as_chunks::<4>()
                .0
                .iter()
                .all(|rgba| *rgba == [0, 0, 0, 255]),
            "Alpha-rejected sky has no surface guide"
        );
    }
    let resized = renderer.render(&scene, &camera, 17, 31, 1).unwrap();
    assert_eq!(resized.len(), 17 * 31 * 4);
}
#[test]
#[ignore = "requires Vulkan; dense, sloped and layered coverage through both output pipelines"]
fn gpu_realtime_matches_offline_single_sample_on_surface_paths() {
    let mut realtime = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let mut offline = Renderer::with_mode(RenderMode::Offline).unwrap();
    let settings = RenderSettings {
        stars: 0.0,
        auto_exposure_compensation: 0.0,
        ray_reconstruction: false,
        ..Default::default()
    };
    realtime.configure(settings).unwrap();
    offline
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            ..settings
        })
        .unwrap();
    for flags in 0..3 {
        for (index, pattern) in ["checker", "sloped", "layers"].into_iter().enumerate() {
            let mut scene = crate::surface_tests::scene(1, pattern, flags);
            scene.revision = u64::from(flags) * 3 + index as u64 + 1;
            for mesh in scene.meshes.values_mut() {
                mesh.revision = scene.revision;
            }
            let camera = crate::surface_tests::camera(1);
            let actual = realtime.render(&scene, &camera, 97, 61, 0).unwrap();
            let expected = offline.render(&scene, &camera, 97, 61, 0).unwrap();
            assert!(
                actual == expected,
                "fused output differs: {pattern}, flags={flags}"
            );
        }
    }
}

#[test]
#[ignore = "requires Vulkan; exclusive modes, shared geometry and pure batched accumulation"]
fn gpu_mode_switch_preserves_scene_and_offline_batching_matches_sequential_samples() {
    let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let settings = RenderSettings {
        stars: 0.0,
        auto_exposure_compensation: 0.0,
        ray_reconstruction: false,
        ..Default::default()
    };
    renderer.configure(settings).unwrap();
    let scene = plane();
    let camera = camera();
    renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    let top = renderer.geometry.as_ref().unwrap().top.handle();
    let offline = RenderSettings {
        mode: RenderMode::Offline,
        ..settings
    };
    renderer.configure(offline).unwrap();
    renderer.set_scene_frozen(true);
    assert!(
        renderer.output.is_none(),
        "Old mode resources survive switch"
    );
    let mut four = vec![];
    for frame in 0..4 {
        four = renderer.render(&scene, &camera, 31, 17, frame).unwrap();
    }
    assert_eq!(renderer.samples, 4);
    assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
    assert!(renderer.output.as_ref().unwrap().accumulation.is_some());
    renderer
        .configure(RenderSettings {
            exposure: 0.25,
            ..offline
        })
        .unwrap();
    assert_eq!(renderer.samples, 4, "Display control destroyed history");
    let darker = renderer.render(&scene, &camera, 31, 17, 4).unwrap();
    assert_eq!(renderer.samples, 5);
    assert_ne!(darker, four);
    renderer.configure(settings).unwrap();
    renderer.set_scene_frozen(false);
    assert!(renderer.output.is_none());
    renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
    renderer
        .configure(RenderSettings {
            offline_samples: 4,
            ..offline
        })
        .unwrap();
    renderer.set_scene_frozen(true);
    let batch = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert_eq!(
        batch, four,
        "Batched sequence must equal sequential pure accumulation"
    );
    assert_eq!(renderer.samples, 4);
    renderer
        .configure(RenderSettings {
            offline_samples: 2,
            ..offline
        })
        .unwrap();
    renderer.render(&scene, &camera, 31, 17, 1).unwrap();
    assert_eq!(renderer.samples, 6);
    renderer.render(&scene, &camera, 17, 31, 2).unwrap();
    assert_eq!(renderer.samples, 2, "Resize must restart accumulation");
}
