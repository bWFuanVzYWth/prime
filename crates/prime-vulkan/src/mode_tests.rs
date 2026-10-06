use super::*;
use prime_scene::scene::{SceneMesh, Texture, Triangle};
use prime_scene::settings::DiagnosticView;

#[test]
#[ignore = "requires Vulkan; windowless production linear display after FG becomes unavailable"]
fn gpu_frozen_linear_route_writes_host_after_fg_disabled() {
    use prime_scene::workers::CpuWorkers;
    let context = Context::new().unwrap();
    let settings = RenderSettings {
        stars: 0.0,
        auto_exposure_compensation: 0.0,
        native_noisy_output: true,
        frame_generation: true,
        ..Default::default()
    };
    let mut renderer = Renderer::from_context(
        context.clone(),
        settings,
        Arc::new(CpuWorkers::new(1).unwrap()),
    )
    .unwrap();
    let extent = [19, 13];
    let mut output = Output::new(&context, extent[0], extent[1], RenderMode::Realtime).unwrap();
    // Model the resources and bound destination chosen while FG was the only linear consumer.
    output
        .prepare_display(&context, RenderMode::Realtime, true, false, true)
        .unwrap();
    let linear = output.linear.as_ref().unwrap().image;
    let image = output.image.as_ref().unwrap().image;
    let view = output.image.as_ref().unwrap().view;
    let readback = output.readback.as_ref().unwrap().buffer;
    renderer.output = Some(output);
    renderer.linear_display = Some(display_pipeline::LinearDisplay::new(&context).unwrap());
    // There is no supported SDK FG now. Recomputing the route here would skip the host write.
    assert!(!renderer.frame_generation_active());
    assert!(!renderer.needs_linear_display());
    let mut recorded = Ok(());
    context
        .submit_named("frozen_linear_route_after_fg_failure", |command| unsafe {
            context.device.cmd_clear_color_image(
                command,
                linear,
                vk::ImageLayout::GENERAL,
                &vk::ClearColorValue {
                    float32: [0.25, 0.125, 0.5, 1.0],
                },
                &[target::color_range()],
            );
            context.device.cmd_clear_color_image(
                command,
                image,
                vk::ImageLayout::GENERAL,
                &vk::ClearColorValue { float32: [0.0; 4] },
                &[target::color_range()],
            );
            recorded = renderer.record_display(command, 0, view, false, true);
            context.device.cmd_pipeline_barrier(
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
            context.device.cmd_copy_image_to_buffer(
                command,
                image,
                vk::ImageLayout::GENERAL,
                readback,
                &[vk::BufferImageCopy::default()
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D {
                        width: extent[0],
                        height: extent[1],
                        depth: 1,
                    })],
            );
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::HOST_READ)],
                &[],
                &[],
            );
        })
        .unwrap();
    recorded.unwrap();
    let bytes = renderer
        .output
        .as_ref()
        .unwrap()
        .readback
        .as_ref()
        .unwrap()
        .read(extent[0] as usize * extent[1] as usize * 4)
        .unwrap();
    let expected = &bytes[..4];
    assert_eq!(
        expected[3], 255,
        "The frozen route did not write the host target"
    );
    assert!(expected[..3].iter().all(|v| *v > 0));
    assert!(bytes.chunks_exact(4).all(|pixel| pixel == expected));
}

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
        native_noisy_output: true,
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

#[test]
#[ignore = "windowless real host submissions; budget changes, retained light geometry and frozen history"]
fn gpu_budget_changes_reuse_light_geometry_and_restart_offline_history() {
    use prime_scene::{
        geometry::{CompiledQuad, MeshGeometry},
        settings::LightSampling,
        surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad, SurfaceRule},
        workers::CpuWorkers,
    };
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
                unsafe { self.0.device.destroy_semaphore(self.1, None) };
            }
        }
    }
    let _timeline = Timeline(owner.clone(), timeline);
    let settings = RenderSettings {
        mode: RenderMode::Realtime,
        light_sampling: LightSampling::Tree,
        native_noisy_output: true,
        view: DiagnosticView::LinearDepth,
        depth_range: 4.0,
        stars: 0.0,
        auto_exposure_compensation: 0.0,
        sun: 1.0 / 256.0,
        sky: 1.0 / 256.0,
        bounces: 1,
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
            Arc::new(CpuWorkers::new(1).unwrap()),
        )
    }
    .unwrap();
    renderer.context.set_diagnostics(true);
    let mut scene = plane();
    for (index, origin) in [64.0, 128.0].into_iter().enumerate() {
        // Explicit rich emitters are necessary: closed Triangle inputs declare no emission.
        // Separate source cells exercise both the world and local light tables.
        let x = 4.0 - origin as f32;
        let y = index as f32 * 2.0;
        let mesh = SurfaceCompiler::new()
            .compile(
                1,
                &[SurfaceQuad {
                    geometry: CompiledQuad {
                        positions: [
                            [x, y, 4.0],
                            [x, y + 1.0, 4.0],
                            [x + 1.0, y + 1.0, 4.0],
                            [x + 1.0, y, 4.0],
                        ],
                        uvs: [[0.5; 2]; 4],
                        color: [1.0; 4],
                        texture_id: 7,
                        flags: 0,
                    },
                    provenance: Provenance {
                        domain: 1,
                        source: index as u64,
                    },
                    emission: Emission {
                        radiance: [1000.0; 3],
                        two_sided: false,
                        textured: false,
                    },
                    rule: SurfaceRule::Preserve,
                }],
            )
            .unwrap();
        assert_eq!(mesh.lights.emitters.len(), 1);
        let origin = [origin, 0.0, 0.0];
        scene
            .ready_terrain
            .insert(prime_scene::spatial::Cell::containing(origin).unwrap());
        scene.meshes.insert(
            (index as u64 + 10, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin,
                triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
            },
        );
    }
    let image = Image::new(&owner, 19, 13).unwrap();
    let readback = Buffer::new_readback(&owner, 19 * 13 * 4).unwrap();
    let camera = camera();
    let mut serial = 0_u64;
    let mut frame = |renderer: &mut Renderer| {
        serial += 1;
        let mut result = Ok(());
        owner
            .submit_named("light_sampler_switch_frame", |command| {
                result = (|| -> Result<(), String> {
                    unsafe {
                        renderer.prepare_host_resources(
                            (&scene).into(),
                            command.as_raw(),
                            serial,
                        )?;
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
                        owner.device.cmd_pipeline_barrier(
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
                        owner.device.cmd_copy_image_to_buffer(
                            command,
                            image.image,
                            vk::ImageLayout::GENERAL,
                            readback.buffer,
                            &[vk::BufferImageCopy::default()
                                .image_subresource(
                                    vk::ImageSubresourceLayers::default()
                                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                                        .layer_count(1),
                                )
                                .image_extent(vk::Extent3D {
                                    width: 19,
                                    height: 13,
                                    depth: 1,
                                })],
                        );
                        owner.device.cmd_pipeline_barrier(
                            command,
                            vk::PipelineStageFlags::TRANSFER,
                            vk::PipelineStageFlags::HOST,
                            vk::DependencyFlags::empty(),
                            &[vk::MemoryBarrier::default()
                                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                                .dst_access_mask(vk::AccessFlags::HOST_READ)],
                            &[],
                            &[],
                        );
                    }
                    Ok(())
                })();
            })
            .unwrap();
        result.unwrap();
        // submit_named waited for this exact GPU fence; only now publish host completion.
        unsafe {
            owner.device.signal_semaphore(
                &vk::SemaphoreSignalInfo::default()
                    .semaphore(timeline)
                    .value(serial),
            )
        }
        .unwrap();
        renderer.submission_accepted(serial).unwrap();
        readback.read(19 * 13 * 4).unwrap()
    };
    for warmup in 0..8 {
        frame(&mut renderer);
        if warmup >= 2
            && !renderer
                .geometry
                .as_ref()
                .unwrap()
                .needs_update((&scene).into())
        {
            break;
        }
    }
    assert!(
        !renderer
            .geometry
            .as_ref()
            .unwrap()
            .needs_update((&scene).into()),
        "Compaction did not settle"
    );
    let expected = frame(&mut renderer);
    assert!(
        expected
            .as_chunks::<4>()
            .0
            .iter()
            .all(|rgba| *rgba == [128, 128, 128, 255])
    );
    let geometry = renderer.geometry.as_ref().unwrap();
    let top = geometry.top.handle();
    let retained = geometry.light_sampling_snapshot();
    assert_eq!(retained.pages.len(), 2);
    assert!(retained.pages.iter().all(|page| page.2.emitters.len() == 1));
    let assert_retained = |renderer: &Renderer| {
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.top.handle(), top, "Budget change rebuilt TLAS");
        let actual = geometry.light_sampling_snapshot();
        assert_eq!(actual.blas, retained.blas, "Budget change rebuilt BLAS");
        assert!(actual.has_lights);
        assert_eq!(actual.pages.len(), retained.pages.len());
        for (actual, previous) in actual.pages.iter().zip(&retained.pages) {
            assert_eq!(actual.0, previous.0);
            assert!(
                Arc::ptr_eq(&actual.1, &previous.1),
                "Budget change reuploaded emitter records"
            );
            assert!(
                Arc::ptr_eq(&actual.2, &previous.2),
                "Budget change recompiled source lights"
            );
        }
    };
    for budget in [2, 3] {
        let selected = RenderSettings {
            bounces: budget,
            ..settings
        };
        renderer.configure(selected).unwrap();
        assert_eq!(frame(&mut renderer), expected);
        assert_retained(&renderer);
        let uploaded = renderer.context.cpu_upload_bytes();
        let pipeline = renderer.pipeline.as_ref().unwrap().pipelines;
        renderer.configure(selected).unwrap();
        assert_eq!(frame(&mut renderer), expected);
        assert_eq!(
            renderer.context.cpu_upload_bytes(),
            uploaded,
            "Stable sampler reuploaded data"
        );
        assert_eq!(renderer.pipeline.as_ref().unwrap().pipelines, pipeline);
        renderer
            .configure(RenderSettings {
                view: DiagnosticView::NoisyColor,
                ..selected
            })
            .unwrap();
        assert!(
            frame(&mut renderer)
                .as_chunks::<4>()
                .0
                .iter()
                .any(|rgba| rgba[..3] != [0; 3]),
            "Emissive fixture rendered black"
        );
        assert_retained(&renderer);
        renderer.configure(selected).unwrap();
    }
    let offline = RenderSettings {
        mode: RenderMode::Offline,
        // Offline always accumulates radiance; its diagnostic controls do not produce depth.
        view: DiagnosticView::NoisyColor,
        ..settings
    };
    renderer.configure(offline).unwrap();
    renderer.set_scene_frozen(true);
    frame(&mut renderer);
    frame(&mut renderer);
    assert_eq!(renderer.samples, 2);
    for budget in [2, 3] {
        let selected = RenderSettings {
            bounces: budget,
            ..offline
        };
        renderer.configure(selected).unwrap();
        assert_eq!(
            renderer.samples, 0,
            "Budget change retained old accumulation"
        );
        assert_eq!(renderer.camera, Some(camera));
        assert!(renderer.scene_frozen);
        assert!(
            frame(&mut renderer)
                .as_chunks::<4>()
                .0
                .iter()
                .any(|rgba| rgba[..3] != [0; 3]),
            "Frozen emissive fixture rendered black"
        );
        assert_eq!(renderer.samples, 1);
        assert_retained(&renderer);
        let uploaded = renderer.context.cpu_upload_bytes();
        assert!(
            frame(&mut renderer)
                .as_chunks::<4>()
                .0
                .iter()
                .any(|rgba| rgba[..3] != [0; 3])
        );
        assert_eq!(renderer.samples, 2);
        assert_eq!(
            renderer.context.cpu_upload_bytes(),
            uploaded,
            "Frozen stable sampler reuploaded data"
        );
    }
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
        native_noisy_output: true,
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
        native_noisy_output: true,
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
        native_noisy_output: true,
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
