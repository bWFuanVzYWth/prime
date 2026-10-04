//! Windowless equivalence checks against the production mip-zero coverage consumer.
use crate::{Context, Geometry, Renderer, cpu_profile, shader_tests};
use prime_scene::{
    Texture, TextureSampling, Triangle,
    geometry::{CompiledQuad, MeshGeometry},
    scene::{Camera, InstanceScene, Scene, SceneMesh},
    settings::{RenderMode, RenderSettings},
    spatial::Cell,
    surface::{RepeatUv, SurfaceFace, SurfaceMesh},
};
use std::sync::Arc;

fn capable_context() -> Arc<Context> {
    let context = Context::new().unwrap();
    let support = context
        .opacity_micromap
        .as_ref()
        .expect("This OMM GPU test requires VK_EXT_opacity_micromap; unsupported is unverified");
    assert!(support.max_two_state >= 4 && support.max_four_state >= 4);
    eprintln!(
        "[OMM validation] device={} max_two={} max_four={}",
        context.name, support.max_two_state, support.max_four_state
    );
    context
}

fn texture(width: u32, height: u32) -> Texture {
    let mut pixels = Vec::new();
    for y in 0..height {
        for x in 0..width {
            // The source cutout threshold is 0.1: UNORM8 25 rejects and 26 accepts.
            let alpha = [0, 25, 26, 255][((x + y * 3) % 4) as usize];
            pixels.extend([190, 90, 40, alpha]);
        }
    }
    Texture {
        width,
        height,
        pixels: pixels.into(),
        region: Some([0, 0, width, height]),
        sampling: None,
        material: None,
    }
}

fn scene(texture: Texture, scale: [f32; 2], offset: [f32; 2], alpha: f32) -> Scene {
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    let uv = |x: f32, y: f32| [offset[0] + scale[0] * x, offset[1] + scale[1] * y];
    let first = Triangle {
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.]],
        colors: [[1., 1., 1., alpha]; 3],
        uvs: [uv(0., 0.), uv(1., 0.), uv(1., 1.)],
        texture_id: 7,
        flags: 1,
    };
    let second = Triangle {
        positions: [[0., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [uv(0., 0.), uv(1., 1.), uv(0., 1.)],
        ..first
    };
    scene.textures.insert(7, texture);
    scene.meshes.insert(
        (1, 1),
        SceneMesh {
            revision: 1,
            flags: 1,
            origin: [0.; 3],
            triangles: vec![first, second].into(),
        },
    );
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    scene
}

fn geometry(context: &Arc<Context>, scene: &Scene, enabled: bool) -> Geometry {
    let mut geometry = Geometry::new_with_omm(
        context,
        scene.into(),
        Arc::new(prime_scene::workers::CpuWorkers::new(1).unwrap()),
        enabled,
    )
    .unwrap();
    prepare(context, &mut geometry, scene);
    geometry
}

fn prepare(context: &Arc<Context>, geometry: &mut Geometry, scene: &Scene) {
    geometry
        .prepare_dynamic(
            context,
            scene,
            &InstanceScene::default(),
            0,
            &mut cpu_profile::FrameCpu::default(),
        )
        .unwrap();
}

fn repeated_scene(repeat: RepeatUv, span: [f32; 2], phase: [f32; 2]) -> Scene {
    let mut scene = scene(texture(16, 16), [1.; 2], [0.; 2], 1.);
    let mut face = SurfaceFace::from_quad(CompiledQuad {
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [
            phase,
            [phase[0] + span[0], phase[1]],
            [phase[0] + span[0], phase[1] + span[1]],
            [phase[0], phase[1] + span[1]],
        ],
        color: [1.; 4],
        texture_id: 7,
        flags: 1,
    });
    face.repeat = Some(repeat);
    scene.meshes.get_mut(&(1, 1)).unwrap().triangles =
        MeshGeometry::Surfaces(Arc::new(SurfaceMesh::from_resolved(1, vec![face]).unwrap()));
    scene
}

fn query_input(context: &Arc<Context>, geometry: &Geometry, input: &[u32]) -> Vec<u32> {
    shader_tests::run(
        context,
        prime_shader_tests::optics(),
        input,
        input.len(),
        [0, (input.len() / 12) as u32],
        Some(geometry),
    )
}

fn query(context: &Arc<Context>, geometry: &Geometry) -> Vec<u32> {
    query_at(context, geometry, [0.; 3])
}

fn query_at(context: &Arc<Context>, geometry: &Geometry, origin: [f32; 3]) -> Vec<u32> {
    let mut input = Vec::new();
    let mut ray = |x, y, z, dz| {
        input.extend(
            [
                x + origin[0],
                y + origin[1],
                z + origin[2],
                6.,
                0.,
                0.,
                dz,
                1.,
                0.,
                0.,
                0.,
                0.,
            ]
            .map(f32::to_bits),
        );
    };
    // Different subpixel offsets avoid the macro diagonal and regular texel boundaries.
    // Both ray directions exercise closest-hit and the optical shadow consumer.
    for y in 0..29 {
        for x in 0..31 {
            for (z, dz) in [(2., -1.), (-2., 1.)] {
                ray((x as f32 + 0.23) / 31., (y as f32 + 0.61) / 29., z, dz);
            }
        }
    }
    if origin == [0.; 3] {
        for edge in 1..16 {
            let edge = edge as f32 / 16.;
            for bits in [edge.to_bits() - 16, edge.to_bits() + 16] {
                let near_edge = f32::from_bits(bits);
                for (z, dz) in [(2., -1.), (-2., 1.)] {
                    ray(near_edge, 0.317, z, dz);
                    ray(0.317, near_edge, z, dz);
                }
            }
        }
    }
    query_input(context, geometry, &input)
}

fn assert_same_coverage(context: &Arc<Context>, current: &Geometry, scene: &Scene) {
    let baseline = geometry(context, scene, false);
    let actual = query(context, current);
    let expected = query(context, &baseline);
    assert_eq!(
        actual, expected,
        "OMM changed primary hit or shadow coverage"
    );
    let rows = actual.as_chunks::<12>().0;
    assert!(rows.iter().any(|r| r[0] == 0_f32.to_bits()));
    assert!(rows.iter().any(|r| r[0] == 1_f32.to_bits()));
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; run with validation and sync validation"]
fn gpu_omm_two_state_matches_cutout_primary_and_shadow_coverage() {
    let context = capable_context();
    for (width, height, alpha) in [(8, 8, 1.), (16, 8, 1.), (8, 8, 0.2)] {
        let scene = scene(texture(width, height), [1.; 2], [0.; 2], alpha);
        let geometry = geometry(&context, &scene, true);
        let stats = geometry.omm_stats();
        assert_eq!(geometry.omm_pool_stats()[0], 1);
        if alpha == 1. {
            assert!(stats[0] > 0 && stats[1] == 0 && stats[3] > 0, "{stats:?}");
            assert!(geometry.omm_work_counts[1] > 0);
        } else {
            assert_eq!(stats, [0; 4], "non-template tint must keep shader coverage");
            assert_eq!(geometry.omm_work_counts[1], 0);
        }
        assert_same_coverage(&context, &geometry, &scene);
        let result = query(&context, &geometry);
        for (ray, row) in result
            .as_chunks::<12>()
            .0
            .iter()
            .take(31 * 29 * 2)
            .enumerate()
        {
            let x = (ray / 2 % 31) as f32;
            let y = (ray / 2 / 31) as f32;
            let px = ((x + 0.23) / 31. * width as f32) as u32;
            let py = ((y + 0.61) / 29. * height as f32) as u32;
            let source_alpha = [0., 25., 26., 255.][((px + py * 3) % 4) as usize];
            let covered = source_alpha / 255. * alpha >= 0.1;
            assert_eq!(row[0], (if covered { 0_f32 } else { 1_f32 }).to_bits());
            assert_eq!(row[11] != 0, covered, "primary ray {ray}");
        }
    }
    // Special indices are legal with a null VkMicromap handle and no packed blocks.
    for alpha in [0, 255] {
        let mut uniform = texture(8, 8);
        uniform.pixels = uniform
            .pixels
            .as_chunks::<4>()
            .0
            .iter()
            .flat_map(|p| [p[0], p[1], p[2], alpha])
            .collect();
        let scene = scene(uniform, [1.; 2], [0.; 2], 1.);
        let current = geometry(&context, &scene, true);
        let stats = current.omm_stats();
        assert_eq!([stats[0], stats[1], stats[3]], [0; 3]);
        assert!(stats[2] >= 2);
        let baseline = geometry(&context, &scene, false);
        let actual = query(&context, &current);
        assert_eq!(actual, query(&context, &baseline));
        for row in actual.as_chunks::<12>().0 {
            assert_eq!(row[0], (if alpha == 0 { 1_f32 } else { 0_f32 }).to_bits());
            assert_eq!(row[11] != 0, alpha != 0);
        }
    }
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; exercises finite-template fallback and NPOT proofs"]
fn gpu_omm_templates_preserve_non_dyadic_tint_and_non_power_of_two_coverage() {
    let context = capable_context();
    for (width, height, scale, offset) in [
        (8, 8, [0.87, 0.93], [0.015, 0.021]),
        (7, 5, [1.; 2], [0.; 2]),
    ] {
        let scene = scene(texture(width, height), scale, offset, 1.);
        let geometry = geometry(&context, &scene, true);
        let stats = geometry.omm_stats();
        assert_eq!(geometry.omm_pool_stats()[0], 1);
        if width == 7 {
            assert!(
                stats[1] > 0 && stats[3] > 0,
                "NPOT resource templates: {stats:?}"
            );
        } else {
            assert_eq!(stats, [0; 4], "unprepared UV mapping acquired a template");
            assert_eq!(geometry.omm_work_counts[1], 0);
        }
        assert_same_coverage(&context, &geometry, &scene);
    }
    let mut tinted = scene(texture(8, 8), [1.; 2], [0.; 2], 1.);
    let mesh = tinted.meshes.get_mut(&(1, 1)).unwrap();
    let prime_scene::geometry::MeshGeometry::Triangles(triangles) = &mut mesh.triangles else {
        unreachable!()
    };
    let triangles = Arc::make_mut(triangles);
    triangles[0].colors[0][3] = 0.;
    triangles[0].colors[1][3] = 0.3;
    triangles[0].colors[2][3] = 1.;
    triangles[1].colors[0][3] = 0.;
    triangles[1].colors[1][3] = 1.;
    triangles[1].colors[2][3] = 0.3;
    let geometry = geometry(&context, &tinted, true);
    assert_eq!(geometry.omm_stats(), [0; 4]);
    assert_eq!(geometry.omm_work_counts[1], 0);
    assert_same_coverage(&context, &geometry, &tinted);
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; verifies switches and texture/animation invalidation"]
fn gpu_omm_toggle_and_texture_updates_rebuild_only_cutout_cells() {
    let context = capable_context();
    let mut scene = scene(texture(8, 8), [1.; 2], [0.; 2], 1.);
    let cutout_stats = geometry(&context, &scene, true).omm_stats();
    let mut opaque = scene.meshes[&(1, 1)].clone();
    opaque.flags = 0;
    opaque.origin = [64., 0., 0.];
    let prime_scene::geometry::MeshGeometry::Triangles(triangles) = &mut opaque.triangles else {
        unreachable!()
    };
    for triangle in Arc::make_mut(triangles) {
        triangle.flags = 0;
    }
    scene.meshes.insert((2, 0), opaque);
    scene
        .ready_terrain
        .insert(Cell::containing([64., 0., 0.]).unwrap());
    let mut geometry = geometry(&context, &scene, true);
    assert_eq!(geometry.rebuilt_clusters, 2);
    assert_eq!(
        geometry.omm_stats(),
        cutout_stats,
        "opaque geometry acquired OMM"
    );
    let reference = query(&context, &geometry);
    for enabled in [false, true, false, true] {
        geometry.set_omm(enabled);
        assert!(geometry.needs_update((&scene).into()));
        geometry.update(&context, (&scene).into()).unwrap();
        assert_eq!(geometry.rebuilt_clusters, 1);
        prepare(&context, &mut geometry, &scene);
        assert_eq!(query(&context, &geometry), reference);
        assert_eq!(geometry.omm_stats()[3] > 0, enabled);
        geometry.update(&context, (&scene).into()).unwrap();
        assert_eq!(geometry.rebuilt_clusters, 0);
    }

    // A captured atlas stores both immutable frames. Switching to unproved animated
    // coverage must discard static OMM; later blends can stay on the shader fallback.
    let mut animated = texture(16, 8);
    let mut pixels = animated.pixels.to_vec();
    for y in 0..8 {
        for x in 0..8 {
            pixels[((y * 16 + x + 8) * 4 + 3) as usize] =
                255 - pixels[((y * 16 + x) * 4 + 3) as usize];
        }
    }
    animated.pixels = pixels.into();
    animated.region = Some([0, 0, 8, 8]);
    for (frame, blend) in [0., 0.95, 0.].into_iter().enumerate() {
        animated.sampling = Some(Arc::new(TextureSampling {
            levels: Vec::new(),
            next: [8, 0],
            blend,
            coverage_frames: Default::default(),
        }));
        scene.textures.insert(7, animated.clone());
        scene.revision += 1;
        geometry.update(&context, (&scene).into()).unwrap();
        if frame == 0 {
            assert_eq!(geometry.rebuilt_clusters, 1);
        } else {
            assert!(geometry.rebuilt_clusters <= 1, "opaque cell was rebuilt");
        }
        prepare(&context, &mut geometry, &scene);
        assert_same_coverage(&context, &geometry, &scene);
    }
    scene.meshes.clear();
    scene.revision += 1;
    geometry.update(&context, (&scene).into()).unwrap();
    prepare(&context, &mut geometry, &scene);
    assert_eq!(geometry.omm_stats(), [0; 4]);
    assert!(
        query(&context, &geometry)
            .as_chunks::<12>()
            .0
            .iter()
            .all(|r| r[0] == 1_f32.to_bits())
    );
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; verifies frozen offline settings and history"]
fn gpu_omm_renderer_settings_apply_to_frozen_offline_scene() {
    let mut renderer = Renderer::with_mode(RenderMode::Offline).unwrap();
    renderer
        .context
        .opacity_micromap
        .as_ref()
        .expect("Frozen OMM settings test requires OMM-capable Vulkan hardware");
    let settings = RenderSettings {
        mode: RenderMode::Offline,
        bounces: 1,
        ..Default::default()
    };
    renderer.configure(settings).unwrap();
    let mut baseline = Renderer::with_mode(RenderMode::Offline).unwrap();
    baseline.configure(settings).unwrap();
    let scene = scene(texture(8, 8), [1.; 2], [0.; 2], 1.);
    let camera = Camera {
        position: [0.5, 0.5, 2.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.6,
    };
    assert_eq!(
        renderer.render(&scene, &camera, 48, 32, 0).unwrap(),
        baseline.render(&scene, &camera, 48, 32, 0).unwrap()
    );
    let publication = renderer.geometry.as_ref().unwrap().revision;
    let atmosphere_revision = renderer.atmosphere_scene_revision;
    assert!(renderer.geometry.as_ref().unwrap().omm_stats()[0] > 0);
    renderer.set_scene_frozen(true);
    baseline.set_scene_frozen(true);
    renderer.render(&scene, &camera, 48, 32, 1).unwrap();
    baseline.render(&scene, &camera, 48, 32, 1).unwrap();
    assert_eq!(renderer.samples, 2);
    for (step, enabled) in [false, true].into_iter().enumerate() {
        renderer
            .configure(RenderSettings {
                opacity_micromap: enabled,
                ..settings
            })
            .unwrap();
        let result = renderer.render(&scene, &camera, 48, 32, 7).unwrap();
        let reference = baseline.render(&scene, &camera, 48, 32, 7).unwrap();
        let geometry = renderer.geometry.as_ref().unwrap();
        assert_eq!(geometry.opacity_micromap, enabled);
        assert_eq!(geometry.omm_stats()[3] > 0, enabled);
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(geometry.revision, publication, "source publication changed");
        assert_eq!(
            renderer.samples,
            3 + step as u32,
            "OMM replacement lost offline history"
        );
        assert_eq!(renderer.atmosphere_scene_revision, atmosphere_revision);
        assert_eq!(
            result, reference,
            "OMM switching changed the frozen scene's accumulated samples"
        );
    }
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; checks deferred BLAS against current alpha"]
fn gpu_terrain_frame_budget_omm_fallback_preserves_current_coverage() {
    let context = capable_context();
    let mut scene = scene(texture(8, 8), [1.; 2], [0.; 2], 1.);
    let first = scene.meshes[&(1, 1)].clone();
    for id in 1..4 {
        let mut mesh = first.clone();
        mesh.origin[0] = f64::from(id) * 64.;
        scene
            .ready_terrain
            .insert(Cell::containing(mesh.origin).unwrap());
        scene.meshes.insert((id as u64 + 1, 1), mesh);
    }
    let mut current = geometry(&context, &scene, true);
    assert!(current.omm_stats()[3] > 0);
    let mut changed = texture(8, 8);
    let mut pixels = changed.pixels.to_vec();
    for texel in pixels.as_chunks_mut::<4>().0 {
        texel[3] = 255 - texel[3];
    }
    changed.pixels = pixels.into();
    scene.textures.insert(7, changed);
    scene.revision += 1;
    let reference = geometry(&context, &scene, false);
    for step in 0..4 {
        // Coverage invalidation changes resources, but OMM rebinding is content-equivalent.
        assert!(
            !current
                .update_limited(&context, (&scene).into(), 1)
                .unwrap()
        );
        assert_eq!(current.rebuilt_clusters, 1);
        prepare(&context, &mut current, &scene);
        for cell in 0..4 {
            let origin = [cell as f32 * 64., 0., 0.];
            assert_eq!(
                query_at(&context, &current, origin),
                query_at(&context, &reference, origin),
                "deferred OMM used obsolete alpha at step {step}, cell {cell}"
            );
        }
        assert_eq!(current.needs_update((&scene).into()), step != 3);
    }
    for enabled in [false, true] {
        current.set_omm(enabled);
        for step in 0..4 {
            assert!(
                !current
                    .update_limited(&context, (&scene).into(), 1)
                    .unwrap()
            );
            assert_eq!(current.rebuilt_clusters, 1);
            prepare(&context, &mut current, &scene);
            for cell in 0..4 {
                let origin = [cell as f32 * 64., 0., 0.];
                assert_eq!(
                    query_at(&context, &current, origin),
                    query_at(&context, &reference, origin),
                    "bounded OMM toggle {enabled} changed coverage at step {step}, cell {cell}"
                );
            }
        }
    }
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; validates complete-frame opacity proofs"]
fn gpu_omm_complete_animation_frames_preserve_coverage_without_rebuilding_blas() {
    let context = capable_context();
    for unstable in [false, true] {
        let mut animated = texture(16, 8);
        let mut pixels = animated.pixels.to_vec();
        for y in 0..8 {
            for x in 0..8 {
                let index = ((y * 16 + x + 8) * 4) as usize;
                pixels[index..index + 3].copy_from_slice(&[45, 210, 135]);
                if unstable && pixels[index + 3] == 25 {
                    pixels[index + 3] = 26;
                }
            }
        }
        animated.pixels = pixels.into();
        animated.region = Some([0, 0, 8, 8]);
        let coverage_frames: Arc<[[u32; 2]]> = Arc::from([[0, 0], [8, 0]]);
        animated.sampling = Some(Arc::new(TextureSampling {
            levels: Vec::new(),
            next: [8, 0],
            blend: 0.,
            coverage_frames: coverage_frames.clone(),
        }));
        let mut scene = scene(animated.clone(), [1.; 2], [0.; 2], 1.);
        let mut current = geometry(&context, &scene, true);
        let stats = current.omm_stats();
        assert!(
            stats[usize::from(unstable)] > 0 && stats[3] > 0,
            "{stats:?}"
        );
        if !unstable {
            assert_eq!(stats[1], 0);
        }
        let original = query(&context, &current);
        assert_same_coverage(&context, &current, &scene);
        let mut color_changed = false;
        for (origin, next, blend) in [
            ([0, 0], [8, 0], 0.73),
            ([8, 0], [0, 0], 0.36),
            ([8, 0], [0, 0], 0.),
            ([0, 0], [8, 0], 0.),
        ] {
            animated.region = Some([origin[0], origin[1], 8, 8]);
            animated.sampling = Some(Arc::new(TextureSampling {
                levels: Vec::new(),
                next,
                blend,
                coverage_frames: coverage_frames.clone(),
            }));
            scene.textures.insert(7, animated.clone());
            scene.revision += 1;
            current.update(&context, (&scene).into()).unwrap();
            assert_eq!(current.rebuilt_clusters, 0, "frame view rebuilt static OMM");
            assert_eq!(current.omm_stats(), stats);
            prepare(&context, &mut current, &scene);
            let actual = query(&context, &current);
            color_changed |= actual != original;
            assert_same_coverage(&context, &current, &scene);
        }
        assert!(color_changed, "animated RGB was not updated");
    }
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; validates prepared repeats and unprepared crop fallback"]
fn gpu_omm_templates_bind_supported_repeats_and_fallback_for_crops() {
    let context = capable_context();
    let straight = RepeatUv {
        origin: [0.25, 0.25],
        du: [0.125, 0.],
        dv: [0., 0.5],
        axes: 3,
    };
    for (name, repeat, span, phase) in [
        ("cropped", straight, [16.; 2], [0.; 2]),
        ("half_phase", straight, [16.; 2], [0.5; 2]),
        (
            "mirrored",
            RepeatUv {
                origin: [0.375, 0.75],
                du: [-0.125, 0.],
                dv: [0., -0.5],
                axes: 3,
            },
            [4., 2.],
            [0.; 2],
        ),
        (
            "rotated",
            RepeatUv {
                origin: [0.25, 0.25],
                du: [0., 0.5],
                dv: [0.125, 0.],
                axes: 3,
            },
            [4., 2.],
            [0.; 2],
        ),
        (
            "repeat_s",
            RepeatUv {
                axes: 1,
                ..straight
            },
            [4., 1.],
            [0.; 2],
        ),
        (
            "repeat_t",
            RepeatUv {
                axes: 2,
                ..straight
            },
            [1., 4.],
            [0.; 2],
        ),
    ] {
        let source = repeated_scene(repeat, span, phase);
        let current = geometry(&context, &source, true);
        let stats = current.omm_stats();
        assert_eq!(
            stats, [0; 4],
            "unprepared {name} mapping acquired a template"
        );
        assert_eq!(current.omm_work_counts[1], 0);
        assert_eq!(current.omm_pool_stats()[0], 1);
        assert_same_coverage(&context, &current, &source);
    }
    let whole = RepeatUv {
        origin: [0.; 2],
        du: [1., 0.],
        dv: [0., 1.],
        axes: 3,
    };
    for (repeat, span) in [
        (whole, [1.; 2]),
        (whole, [2.; 2]),
        (whole, [4.; 2]),
        (
            RepeatUv {
                origin: [1., 1.],
                du: [-1., 0.],
                dv: [0., -1.],
                ..whole
            },
            [2.; 2],
        ),
        (
            RepeatUv {
                origin: [1., 0.],
                du: [0., 1.],
                dv: [-1., 0.],
                ..whole
            },
            [4.; 2],
        ),
        (RepeatUv { axes: 1, ..whole }, [1.; 2]),
        (RepeatUv { axes: 2, ..whole }, [1.; 2]),
    ] {
        let source = repeated_scene(repeat, span, [0.; 2]);
        let current = geometry(&context, &source, true);
        let stats = current.omm_stats();
        assert!(
            stats[0] > 0 && stats[1] == 0 && stats[3] > 0,
            "{repeat:?} {span:?}: {stats:?}"
        );
        assert_eq!(current.omm_pool_stats()[0], 1);
        assert_same_coverage(&context, &current, &source);
    }
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; records all first-update OMM waves in one host command"]
fn gpu_omm_first_host_update_preserves_repeated_cells_and_ready_placements() {
    use ash::vk::{self, Handle};
    const CELLS: usize = 128;
    let owner = capable_context();
    let repeat = RepeatUv {
        origin: [0.; 2],
        du: [1., 0.],
        dv: [0., 1.],
        axes: 3,
    };
    let prototype = repeated_scene(repeat, [4.; 2], [0.; 2]);
    let single = geometry(&owner, &prototype, true);
    let single_stats = single.omm_stats();
    let pool_stats = single.omm_pool_stats();
    drop(single);
    let mut source = repeated_scene(repeat, [4.; 2], [0.; 2]);
    source.meshes.clear();
    source.ready_terrain.clear();
    for index in 0..=CELLS {
        // Tile-origin translations differ in source records, but carry identical coverage.
        let mut instance = repeated_scene(repeat, [4.; 2], [index as f32, index as f32 * 2.]);
        let mut mesh = instance.meshes.remove(&(1, 1)).unwrap();
        mesh.origin = [(index % 16) as f64 * 64., (index / 16) as f64 * 64., 0.];
        if index < CELLS {
            source
                .ready_terrain
                .insert(Cell::containing(mesh.origin).unwrap());
        }
        // The last captured cell is deliberately not ready and must have no placement.
        source.meshes.insert((index as u64 + 1, 1), mesh);
    }
    let timeline = unsafe {
        let mut ty = vk::SemaphoreTypeCreateInfo::default()
            .semaphore_type(vk::SemaphoreType::TIMELINE)
            .initial_value(0);
        owner
            .device
            .create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty), None)
    }
    .unwrap();
    struct TestTimeline {
        owner: Arc<Context>,
        handle: vk::Semaphore,
    }
    impl Drop for TestTimeline {
        fn drop(&mut self) {
            if self.owner.can_destroy() {
                unsafe { self.owner.device.destroy_semaphore(self.handle, None) };
            }
        }
    }
    let timeline_owner = TestTimeline {
        owner: owner.clone(),
        handle: timeline,
    };
    // The owner enabled the feature itself, so capability bit 0 is certified here.
    let host = unsafe {
        Context::borrowed_with_capabilities(
            owner.instance_handle(),
            owner.physical.as_raw(),
            owner.device.handle().as_raw(),
            owner.queue.as_raw(),
            owner.queue_family,
            timeline.as_raw(),
            1,
        )
    }
    .unwrap();
    assert!(host.opacity_micromap.is_some());
    let mut prepared = None;
    owner
        .submit_named("omm_first_host_update", |command| {
            prepared = Some((|| -> Result<(Geometry, Geometry), String> {
                host.begin_host_record(command, 1)?;
                let result = (|| {
                    let workers = Arc::new(prime_scene::workers::CpuWorkers::new(1)?);
                    let mut current =
                        Geometry::new_with_omm(&host, (&source).into(), workers.clone(), true)?;
                    let mut baseline =
                        Geometry::new_with_omm(&host, (&source).into(), workers, false)?;
                    for geometry in [&mut current, &mut baseline] {
                        geometry.prepare_dynamic(
                            &host,
                            &source,
                            &InstanceScene::default(),
                            0,
                            &mut cpu_profile::FrameCpu::default(),
                        )?;
                    }
                    Ok((current, baseline))
                })();
                host.end_host_record();
                result
            })());
        })
        .unwrap();
    // submit_named returned only after its fence completed. Publish that real completion
    // to the borrowed context, then close it before releasing any host-owned handle.
    unsafe {
        owner.device.signal_semaphore(
            &vk::SemaphoreSignalInfo::default()
                .semaphore(timeline)
                .value(1),
        )
    }
    .unwrap();
    host.finish_host().unwrap();
    let (current, baseline) = prepared.unwrap().unwrap();
    assert_eq!(current.rebuilt_clusters, CELLS as u32);
    assert_eq!(current.triangle_count, CELLS as u64 * 2);
    assert_eq!(
        current.omm_stats(),
        single_stats.map(|count| count * CELLS as u64)
    );
    assert_eq!(baseline.omm_stats(), [0; 4]);
    assert_eq!(
        current.omm_pool_stats(),
        pool_stats,
        "cell count duplicated resource templates"
    );
    assert_eq!(
        baseline.omm_pool_stats(),
        pool_stats,
        "disabled binding changed resource preparation"
    );
    assert_eq!(current.omm_work_counts[0], 1);
    assert_eq!(baseline.omm_work_counts, [1, 0]);
    assert_eq!(current.omm_work_counts, [1, 2 * CELLS as u64]);
    let mut input = Vec::new();
    for index in 0..=CELLS {
        let origin = [(index % 16) as f32 * 64., (index / 16) as f32 * 64.];
        for y in 0..5 {
            for x in 0..7 {
                for (z, dz) in [(2., -1.), (-2., 1.)] {
                    input.extend(
                        [
                            origin[0] + (x as f32 + 0.23) / 7.,
                            origin[1] + (y as f32 + 0.61) / 5.,
                            z,
                            6.,
                            0.,
                            0.,
                            dz,
                            1.,
                            0.,
                            0.,
                            0.,
                            0.,
                        ]
                        .map(f32::to_bits),
                    );
                }
            }
        }
    }
    let actual = query_input(&owner, &current, &input);
    assert_eq!(
        actual,
        query_input(&owner, &baseline, &input),
        "first host update changed coverage or a cell's OMM index mapping"
    );
    let rays_per_cell = 7 * 5 * 2;
    for (index, rows) in actual.as_chunks::<12>().0.chunks(rays_per_cell).enumerate() {
        if index == CELLS {
            assert!(
                rows.iter()
                    .all(|row| row[0] == 1_f32.to_bits() && row[11] == 0),
                "unready cell acquired a TLAS placement"
            );
        } else {
            assert!(
                rows.iter().any(|row| row[11] != 0),
                "ready cell {index} lost its placement"
            );
            assert!(
                rows.iter().any(|row| row[0] == 1_f32.to_bits()),
                "ready cell {index} lost its cutout holes"
            );
        }
    }
    eprintln!(
        "[OMM first host update] cells={CELLS} triangles={} bindings={:?} pool={:?} work={:?} prepare_ns={:?} arena={:?}",
        current.triangle_count,
        current.omm_stats(),
        current.omm_pool_stats(),
        current.omm_work_counts,
        current.omm_prepare_ns,
        current.assert_incremental_workspaces()
    );
    drop(current);
    drop(baseline);
    drop(host);
    drop(timeline_owner);
}

#[test]
#[ignore = "requires OMM-capable Vulkan hardware; validates resource-only builds across terrain lifecycle"]
fn gpu_omm_resource_library_survives_movement_unload_readd_and_setting_toggles() {
    let context = capable_context();
    let mut source = scene(texture(8, 8), [1.; 2], [0.; 2], 1.);
    let mut current = geometry(&context, &source, true);
    let mut baseline = geometry(&context, &source, false);
    let library = current.omm_pool_stats();
    assert_eq!(library[0], 1);
    assert_eq!(baseline.omm_pool_stats(), library);
    assert_eq!(current.omm_work_counts[0], 1);
    assert_eq!(baseline.omm_work_counts, [1, 0]);
    let mut detached = None;
    for stage in 0..8 {
        match stage {
            0 => {}
            1 | 6 => {
                let origin = if stage == 1 { [64., 0., 0.] } else { [0.; 3] };
                source.meshes.get_mut(&(1, 1)).unwrap().origin = origin;
                source.ready_terrain.clear();
                source
                    .ready_terrain
                    .insert(Cell::containing(origin).unwrap());
                source.revision += 1;
            }
            2 | 7 => {
                detached = source.meshes.remove(&(1, 1));
                source.ready_terrain.clear();
                source.revision += 1;
            }
            3 => {
                let mesh = detached.take().unwrap();
                source
                    .ready_terrain
                    .insert(Cell::containing(mesh.origin).unwrap());
                source.meshes.insert((1, 1), mesh);
                source.revision += 1;
            }
            4 | 5 => current.set_omm(stage == 5),
            _ => unreachable!(),
        }
        for geometry in [&mut current, &mut baseline] {
            geometry.begin_frame(&context, 0);
            geometry.update(&context, (&source).into()).unwrap();
            prepare(&context, geometry, &source);
            assert_eq!(
                geometry.omm_pool_stats(),
                library,
                "stage {stage} rebuilt resource templates"
            );
            assert_eq!(
                geometry.omm_work_counts[0], 0,
                "stage {stage} prepared CPU templates"
            );
            assert_eq!(geometry.omm_prepare_ns[0], 0);
            assert_eq!(geometry.omm_prepare_ns[2], 0);
        }
        assert_eq!(baseline.omm_work_counts[1], 0);
        let origin = source
            .meshes
            .get(&(1, 1))
            .map_or([0.; 3], |mesh| mesh.origin.map(|x| x as f32));
        let actual = query_at(&context, &current, origin);
        assert_eq!(
            actual,
            query_at(&context, &baseline, origin),
            "stage {stage} changed primary/shadow coverage"
        );
        if source.meshes.is_empty() || !current.opacity_micromap {
            assert_eq!(current.omm_stats(), [0; 4]);
            assert_eq!(current.omm_work_counts[1], 0);
        } else {
            assert!(current.omm_stats()[0] > 0);
        }
    }
    let mesh = detached.take().unwrap();
    source
        .ready_terrain
        .insert(Cell::containing(mesh.origin).unwrap());
    source.meshes.insert((1, 1), mesh);
    let texture = source.textures.get_mut(&7).unwrap();
    let mut pixels = texture.pixels.to_vec();
    for alpha in pixels.iter_mut().skip(3).step_by(4) {
        *alpha = 255 - *alpha;
    }
    texture.pixels = pixels.into();
    source.revision += 1;
    for geometry in [&mut current, &mut baseline] {
        geometry.begin_frame(&context, 0);
        geometry.update(&context, (&source).into()).unwrap();
        prepare(&context, geometry, &source);
        assert_eq!(
            geometry.omm_pool_stats()[0],
            2,
            "pixel replacement did not replace resource templates once"
        );
        assert_eq!(geometry.omm_work_counts[0], 1);
    }
    assert_eq!(query(&context, &current), query(&context, &baseline));
    assert!(current.omm_stats()[0] > 0);
    eprintln!(
        "[OMM resource lifecycle] stages=8 initial_pool={library:?} replacement_pool={:?}",
        current.omm_pool_stats()
    );
}

#[test]
#[ignore = "diagnostic: exact shared-edge OMM versus shader texel tie; not an equivalence acceptance"]
fn gpu_omm_exact_shared_edge_diagnostic() {
    use prime_scene::geometry::{CompiledQuad, MeshGeometry};
    let context = capable_context();
    let mut source = texture(512, 256);
    source.pixels = (0..256)
        .flat_map(|y| {
            (0..512).flat_map(move |x| [255, 255, 255, if (x + 3 * y) % 7 < 3 { 255 } else { 0 }])
        })
        .collect();
    let mut scene = scene(source, [1.; 2], [0.; 2], 1.);
    scene.meshes.get_mut(&(1, 1)).unwrap().triangles = MeshGeometry::Quads(
        vec![CompiledQuad {
            positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            color: [1.; 4],
            texture_id: 7,
            flags: 1,
        }]
        .into(),
    );
    let current = geometry(&context, &scene, true);
    let baseline = geometry(&context, &scene, false);
    assert!(current.omm_stats()[0] > 0);
    let x = 0.9875_f32;
    let y = 254_f32 / 256.;
    let mut input = Vec::new();
    for x_bits in [x.to_bits() - 1, x.to_bits(), x.to_bits() + 1] {
        for y_bits in [
            y.to_bits() - 16,
            y.to_bits() - 1,
            y.to_bits(),
            y.to_bits() + 1,
            y.to_bits() + 16,
        ] {
            input.extend([
                x_bits,
                y_bits,
                1_f32.to_bits(),
                6_f32.to_bits(),
                0,
                0,
                (-1_f32).to_bits(),
                1_f32.to_bits(),
                0,
                0,
                0,
                0,
            ]);
        }
    }
    let run = |geometry: &Geometry| {
        shader_tests::run(
            &context,
            prime_shader_tests::optics(),
            &input,
            input.len(),
            [0, (input.len() / 12) as u32],
            Some(geometry),
        )
    };
    let actual = run(&current);
    let expected = run(&baseline);
    let mut differences = 0;
    for (index, (on, off)) in actual
        .as_chunks::<12>()
        .0
        .iter()
        .zip(expected.as_chunks::<12>().0)
        .enumerate()
    {
        let ray = &input[index * 12..];
        let different = on != off;
        differences += usize::from(different);
        eprintln!(
            "[OMM boundary diagnostic] x={:?} y={:?} on_shadow={:?} off_shadow={:?} on_alpha={:?} off_alpha={:?} different={different}",
            f32::from_bits(ray[0]),
            f32::from_bits(ray[1]),
            f32::from_bits(on[0]),
            f32::from_bits(off[0]),
            f32::from_bits(on[11]),
            f32::from_bits(off[11])
        );
    }
    eprintln!(
        "[OMM boundary diagnostic] differences={differences}/{}; this diagnostic does not assert equivalence",
        input.len() / 12
    );
}
