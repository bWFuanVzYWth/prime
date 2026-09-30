use super::*;
use prime_scene::scene::{SceneMesh, Texture, Triangle};
use prime_scene::settings::DiagnosticView;

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
    let mut scene = plane();
    let camera = camera();
    let first = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert!(renderer.output.as_ref().unwrap().accumulation.is_none());
    let mut offline = Renderer::new().unwrap();
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
    }
    renderer
        .configure(RenderSettings {
            view: DiagnosticView::NoisyColor,
            ..Default::default()
        })
        .unwrap();
    let image = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    assert_ne!(image, first, "Raw view must bypass primeDRT");
    renderer.configure(RenderSettings::default()).unwrap();
    assert_eq!(
        renderer.render(&scene, &camera, 31, 17, 0).unwrap(),
        first,
        "Diagnostic switching must preserve the radiance sequence"
    );
    scene.textures.get_mut(&7).unwrap().pixels = vec![255, 255, 255, 0].into();
    scene.revision += 1;
    for view in [DiagnosticView::LinearDepth, DiagnosticView::Normal] {
        renderer
            .configure(RenderSettings {
                view,
                ..Default::default()
            })
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
    renderer.configure(RenderSettings::default()).unwrap();
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
