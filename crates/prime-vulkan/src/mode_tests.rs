use super::*;
use prime_scene::scene::{SceneMesh, Texture, Triangle};
use prime_scene::settings::DiagnosticView;

fn camera() -> Camera {
    Camera {
        position: [0.0, 0.0, 2.0],
        forward: [0.0, 0.0, -1.0],
        right: [1.0, 0.0, 0.0],
        up: [0.0, 1.0, 0.0],
        vertical_fov_radians: 1.0,
    }
}
fn plane() -> Scene {
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
// Readback exists solely in this fixture; production guide display is another GPU dispatch.
fn guide(renderer: &Renderer, image: &Image, components: usize) -> Vec<f32> {
    let output = renderer.output.as_ref().unwrap();
    let context = &renderer.context;
    let bytes = output.width as usize * output.height as usize * components * 4;
    let readback = Buffer::new_readback(context, bytes as u64).unwrap();
    context
        .submit_named("test_read_guide", |command| unsafe {
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[vk::MemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::MEMORY_WRITE)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)],
                &[],
                &[],
            );
            context.device.cmd_copy_image_to_buffer(
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
                        width: output.width,
                        height: output.height,
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
    readback
        .read(bytes)
        .unwrap()
        .as_chunks::<4>()
        .0
        .iter()
        .map(|bytes| f32::from_le_bytes(*bytes))
        .collect()
}
fn guides(renderer: &Renderer) -> (Vec<f32>, Vec<f32>, Vec<f32>) {
    let OutputStorage::Realtime {
        noisy,
        depth,
        normal,
    } = &renderer.output.as_ref().unwrap().storage
    else {
        panic!("Realtime allocated accumulation")
    };
    (
        guide(renderer, noisy, 4),
        guide(renderer, depth, 1),
        guide(renderer, normal, 4),
    )
}
#[test]
#[ignore = "requires Vulkan with synchronization validation; guide textures and diagnostic display"]
fn gpu_realtime_guides_match_primary_visibility_and_have_no_history() {
    let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let mut scene = plane();
    let camera = camera();
    let first = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    let (noise, depth, normal) = guides(&renderer);
    assert!(noise.iter().all(|v| v.is_finite() && *v >= 0.0));
    for (z, n) in depth.iter().zip(normal.as_chunks::<4>().0.iter()) {
        assert!((*z - 2.0).abs() < 2e-5, "linear view Z: {z}");
        assert_eq!(*n, [0.0, 0.0, 1.0, 1.0]);
    }
    renderer.render(&scene, &camera, 31, 17, 23).unwrap();
    let (next, _, _) = guides(&renderer);
    assert_ne!(
        next, noise,
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
                ..Default::default()
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
        assert_eq!(
            guides(&renderer).0,
            noise,
            "Diagnostic preview must not change raw radiance"
        );
    }
    renderer
        .configure(RenderSettings {
            view: DiagnosticView::NoisyColor,
            ..Default::default()
        })
        .unwrap();
    let image = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert_ne!(image, first, "Raw view must bypass primeDRT");
    assert_eq!(guides(&renderer).0, noise);
    scene.textures.get_mut(&7).unwrap().pixels = vec![255, 255, 255, 0].into();
    scene.revision += 1;
    renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    let (sky, depth, normal) = guides(&renderer);
    assert!(
        sky.as_chunks::<4>()
            .0
            .iter()
            .all(|p| p[0] > 0.0 && p[3] == 1.0)
    );
    assert!(depth.iter().all(|z| *z == f32::MAX));
    assert!(normal.iter().all(|n| *n == 0.0));
    renderer.render(&scene, &camera, 17, 31, 1).unwrap();
    let (_, depth, _) = guides(&renderer);
    assert_eq!(depth.len(), 17 * 31);
}
#[test]
#[ignore = "requires Vulkan; exclusive modes, shared geometry and pure batched accumulation"]
fn gpu_mode_switch_preserves_scene_and_offline_batching_matches_sequential_samples() {
    let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let scene = plane();
    let camera = camera();
    renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    let top = renderer.geometry.as_ref().unwrap().top.handle();
    let offline = RenderSettings {
        mode: RenderMode::Offline,
        ..Default::default()
    };
    renderer.configure(offline).unwrap();
    renderer.set_scene_frozen(true);
    assert!(
        renderer.output.is_none(),
        "Old mode resources survive switch"
    );
    assert_eq!(
        renderer.pipeline.as_ref().unwrap().resolve,
        vk::Pipeline::null()
    );
    let mut four = vec![];
    for frame in 0..4 {
        four = renderer.render(&scene, &camera, 31, 17, frame).unwrap();
    }
    assert_eq!(renderer.samples, 4);
    assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
    assert!(matches!(
        renderer.output.as_ref().unwrap().storage,
        OutputStorage::Offline(_)
    ));
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
    renderer.configure(RenderSettings::default()).unwrap();
    renderer.set_scene_frozen(false);
    assert!(renderer.output.is_none());
    assert_ne!(
        renderer.pipeline.as_ref().unwrap().resolve,
        vk::Pipeline::null()
    );
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
