//! Windowless production dispatch regressions. Offline retains the reference trace loop.
use super::*;
use prime_scene::{
    geometry::{CompiledQuad, MeshGeometry},
    scene::{SceneMesh, Texture, TextureMaterial},
    settings::DiagnosticView,
    spatial::Cell,
    surface::{Emission, Medium, Optics, SurfaceFace, SurfaceMesh},
};

pub(super) fn camera() -> Camera {
    Camera {
        position: [8., 8., 4.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 1.,
    }
}

pub(super) fn face(z: f32) -> SurfaceFace {
    SurfaceFace::from_quad(CompiledQuad {
        positions: [[0., 0., z], [16., 0., z], [16., 16., z], [0., 16., z]],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        color: [0.7, 0.8, 0.9, 1.],
        texture_id: 0,
        flags: 0,
    })
}

pub(super) fn texture(width: u32, height: u32, pixels: Vec<u8>) -> Texture {
    Texture {
        width,
        height,
        pixels: pixels.into(),
        region: None,
        sampling: None,
        material: None,
    }
}

pub(super) fn scene(revision: u64, faces: Vec<SurfaceFace>) -> Scene {
    let mut scene = Scene {
        revision,
        epoch: 1,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    let mut groups = std::collections::BTreeMap::<u32, Vec<SurfaceFace>>::new();
    for face in faces {
        groups.entry(face.flags()).or_default().push(face);
    }
    for (flags, faces) in groups {
        scene.meshes.insert(
            (1, flags),
            SceneMesh {
                revision,
                flags,
                origin: [0.; 3],
                triangles: MeshGeometry::Surfaces(Arc::new(
                    SurfaceMesh::from_resolved(revision, faces).unwrap(),
                )),
            },
        );
    }
    scene
}

fn emission(radiance: [f32; 3]) -> Emission {
    Emission {
        radiance,
        two_sided: true,
        textured: false,
    }
}

fn lamp() -> SurfaceFace {
    let mut lamp = face(3.);
    // Outside the primary footprint, facing the receiver at z = 0.
    lamp.geometry.positions = [[12., 8., 3.], [12., 10., 3.], [14., 10., 3.], [14., 8., 3.]];
    lamp.emission = emission([4., 2., 1.]);
    lamp
}

fn fixtures() -> Vec<(&'static str, Scene)> {
    let opaque = face(0.);
    let mut emissive = opaque.clone();
    emissive.emission = emission([0.6, 0.3, 0.1]);
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    let mut cutout = opaque.clone();
    cutout.geometry.texture_id = 7;
    cutout.geometry.flags = 1;
    let mut cutout_scene = scene(6, vec![cutout]);
    cutout_scene.textures.insert(
        7,
        texture(
            2,
            2,
            vec![
                255, 80, 40, 0, 255, 80, 40, 255, 255, 80, 40, 255, 255, 80, 40, 0,
            ],
        ),
    );
    let mut alpha = opaque.clone();
    alpha.geometry.texture_id = 7;
    alpha.geometry.flags = 2;
    let mut alpha_scene = scene(7, vec![alpha, face(-1.), lamp()]);
    alpha_scene
        .textures
        .insert(7, texture(1, 1, vec![80, 230, 140, 96]));
    let mut absorbing = emissive.clone();
    absorbing.geometry.texture_id = 7;
    absorbing.media = [0, 7];
    absorbing.optics = Some(Optics {
        negative: Medium::default(),
        positive: Medium {
            ior: 1.5,
            extinction: [0.2, 0.4, 0.7],
        },
        ior_textures: [None; 2],
        transmit: false,
        thin: false,
    });
    let mut absorbing_scene = scene(8, vec![absorbing]);
    let mut material = texture(1, 1, vec![255; 4]);
    material.material = Some(Arc::new(TextureMaterial {
        // Authored roughness keeps this optical landing outside the delta prefix.
        specular: Some(texture(1, 1, vec![80, 10, 0, 255])),
        ..Default::default()
    }));
    absorbing_scene.textures.insert(7, material);
    vec![
        ("escape", scene(1, vec![])),
        ("opaque", scene(2, vec![opaque.clone()])),
        ("first-hit emission", scene(3, vec![emissive])),
        ("local NEE", scene(4, vec![opaque.clone(), lamp()])),
        ("multiple bounces", scene(5, vec![opaque, rear, lamp()])),
        ("cutout coverage", cutout_scene),
        ("stochastic alpha", alpha_scene),
        ("initial-medium absorption", absorbing_scene),
    ]
}

fn settings(mode: RenderMode) -> RenderSettings {
    RenderSettings {
        mode,
        bounces: 6,
        exposure: 0.25,
        sun: 1. / 256.,
        sky: 1. / 256.,
        ray_reconstruction: false,
        opacity_micromap: false,
        ..Default::default()
    }
}

fn complete_rgba(image: &[u8], width: u32, height: u32) {
    assert_eq!(image.len(), width as usize * height as usize * 4);
    assert!(image.as_chunks::<4>().0.iter().all(|pixel| pixel[3] == 255));
}

fn equivalent(actual: &[u8], expected: &[u8], label: &str) {
    assert_eq!(actual.len(), expected.len(), "{label}");
    let max_error = actual
        .iter()
        .zip(expected)
        .map(|(a, b)| a.abs_diff(*b))
        .max()
        .unwrap();
    // Splitting prefix + tail changes floating-point addition association. One
    // display-code step tolerates that rounding, but not repeated emission/Beer.
    assert!(max_error <= 1, "{label}: maximum RGBA8 error {max_error}");
}

#[test]
#[ignore = "requires windowless Vulkan; production K1/K2/post versus offline traceSample"]
fn gpu_realtime_split_matches_offline_single_sample_on_nondelta_paths() {
    let mut realtime = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let mut offline = Renderer::with_mode(RenderMode::Offline).unwrap();
    let camera = camera();
    let fixtures = fixtures();
    let mut first_samples = Vec::new();
    for (name, scene) in &fixtures {
        for bounces in [1, 6] {
            for seed in [0x1357_2468, 0x91e1_0da5] {
                let config = RenderSettings {
                    bounces,
                    seed,
                    ..settings(RenderMode::Realtime)
                };
                realtime.configure(config).unwrap();
                offline
                    .configure(RenderSettings {
                        mode: RenderMode::Offline,
                        ..config
                    })
                    .unwrap();
                // A fresh offline accumulation and raw realtime sequence zero
                // use identical ZSobol camera jitter and path sampling domains.
                let actual = realtime.render(scene, &camera, 31, 17, 0).unwrap();
                let expected = offline.render(scene, &camera, 31, 17, 0).unwrap();
                complete_rgba(&actual, 31, 17);
                complete_rgba(&expected, 31, 17);
                equivalent(
                    &actual,
                    &expected,
                    &format!("{name}, {bounces} bounces, seed {seed}"),
                );
                if bounces == 1 && seed == 0x1357_2468 {
                    first_samples.push(actual);
                }
            }
        }
    }
    // Keep these cases observably distinct so agreement cannot pass because an
    // emitter, optical medium or coverage material silently failed to upload.
    for (a, b, label) in [
        (1, 2, "landing emission"),
        (1, 3, "off-camera NEE emitter"),
        (1, 5, "cutout coverage"),
        (1, 6, "stochastic coverage"),
        (2, 7, "incident medium attenuation"),
    ] {
        assert_ne!(first_samples[a], first_samples[b], "inert fixture: {label}");
    }
}

#[test]
#[ignore = "requires windowless Vulkan; odd dispatch edges, scratch reuse, diagnostics and mode transitions"]
fn gpu_realtime_split_reuses_scratch_without_history_and_preserves_offline_batches() {
    let mut renderer = Renderer::with_mode(RenderMode::Realtime).unwrap();
    let mut reference = Renderer::with_mode(RenderMode::Offline).unwrap();
    let scene = scene(1, vec![face(0.), lamp()]);
    let camera = camera();
    let realtime = settings(RenderMode::Realtime);
    let offline = settings(RenderMode::Offline);
    renderer.configure(realtime).unwrap();
    reference.configure(offline).unwrap();
    for [width, height] in [[31, 17], [17, 31], [1, 1], [33, 7], [31, 17]] {
        let actual = renderer.render(&scene, &camera, width, height, 0).unwrap();
        let expected = reference.render(&scene, &camera, width, height, 0).unwrap();
        complete_rgba(&actual, width, height);
        equivalent(&actual, &expected, &format!("extent {width}x{height}"));
    }
    let first = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
    let later = renderer.render(&scene, &camera, 31, 17, 23).unwrap();
    assert_ne!(first, later, "realtime sample sequence did not advance");
    assert_eq!(renderer.samples, 1);
    assert_eq!(renderer.render(&scene, &camera, 31, 17, 0).unwrap(), first);
    for (view, expected) in [
        (DiagnosticView::LinearDepth, [128, 128, 128, 255]),
        (DiagnosticView::Normal, [128, 128, 255, 255]),
    ] {
        renderer
            .configure(RenderSettings {
                view,
                depth_range: 8.,
                ..realtime
            })
            .unwrap();
        let pixels = renderer.render(&scene, &camera, 31, 17, 0).unwrap();
        assert!(
            pixels
                .as_chunks::<4>()
                .0
                .iter()
                .all(|pixel| *pixel == expected)
        );
    }
    renderer.configure(realtime).unwrap();
    assert_eq!(renderer.render(&scene, &camera, 31, 17, 0).unwrap(), first);
    let top = renderer.geometry.as_ref().unwrap().top.handle();
    renderer.configure(offline).unwrap();
    renderer.set_scene_frozen(true);
    let mut sequential = vec![];
    for sequence in 0..4 {
        sequential = renderer.render(&scene, &camera, 31, 17, sequence).unwrap();
    }
    assert_eq!(renderer.samples, 4);
    assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
    renderer.configure(realtime).unwrap();
    renderer.set_scene_frozen(false);
    assert_eq!(renderer.render(&scene, &camera, 31, 17, 0).unwrap(), first);
    renderer
        .configure(RenderSettings {
            offline_samples: 4,
            ..offline
        })
        .unwrap();
    renderer.set_scene_frozen(true);
    assert_eq!(
        renderer.render(&scene, &camera, 31, 17, 0).unwrap(),
        sequential,
        "offline batch must retain the same sample sequence and online mean"
    );
    assert_eq!(renderer.samples, 4);
    assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
    renderer
        .configure(RenderSettings {
            offline_samples: 2,
            ..offline
        })
        .unwrap();
    renderer.render(&scene, &camera, 31, 17, 1).unwrap();
    assert_eq!(renderer.samples, 6, "batch size change destroyed history");
    let resized = renderer.render(&scene, &camera, 17, 31, 2).unwrap();
    complete_rgba(&resized, 17, 31);
    assert_eq!(renderer.samples, 2, "resize did not restart accumulation");
}
