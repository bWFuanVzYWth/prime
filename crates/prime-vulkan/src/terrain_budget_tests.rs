//! Windowless tests of published terrain, resource revocation and bounded reconstruction.
use crate::{Renderer, shader_tests};
use prime_scene::{
    Camera, Scene, SceneMesh, Texture, Triangle,
    geometry::MeshGeometry,
    settings::{RenderMode, RenderSettings},
    spatial::Cell,
};
use std::sync::Arc;

const WIDTH: u32 = 144;
const HEIGHT: u32 = 48;

fn texture(rgb: [u8; 3]) -> Texture {
    Texture {
        width: 1,
        height: 1,
        pixels: vec![rgb[0], rgb[1], rgb[2], 255].into(),
        region: None,
        sampling: None,
        material: None,
    }
}

fn scene() -> Scene {
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    scene.textures.insert(7, texture([255, 0, 0]));
    for cell in 0..3 {
        let origin = [f64::from(cell) * 64., 0., 0.];
        let positions = [[8., 8., 0.], [56., 8., 0.], [56., 56., 0.], [8., 56., 0.]];
        let triangles = [[0, 1, 2], [0, 2, 3]].map(|corners| Triangle {
            positions: corners.map(|i| positions[i]),
            colors: [[1.; 4]; 3],
            uvs: [[0.5; 2]; 3],
            texture_id: if cell == 2 { 7 } else { 0 },
            flags: 0,
        });
        scene.meshes.insert(
            (cell as u64, 0),
            SceneMesh {
                origin,
                revision: 1,
                flags: 0,
                triangles: triangles.into(),
            },
        );
        scene
            .ready_terrain
            .insert(Cell::containing(origin).unwrap());
    }
    scene
}

fn camera() -> Camera {
    Camera {
        position: [96., 32., 160.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 1.,
    }
}

fn settings(budget: u32) -> RenderSettings {
    RenderSettings {
        mode: RenderMode::Offline,
        bounces: 1,
        terrain_batches_per_frame: budget,
        opacity_micromap: false,
        ..Default::default()
    }
}

fn renderer(budget: u32) -> Renderer {
    let mut renderer = Renderer::with_mode(RenderMode::Offline).unwrap();
    renderer.configure(settings(budget)).unwrap();
    renderer
}

fn render(renderer: &mut Renderer, scene: &Scene, sequence: u32) -> Vec<u8> {
    renderer
        .render(scene, &camera(), WIDTH, HEIGHT, sequence)
        .unwrap()
}

// Production closest-hit and shadow consumers, one off-diagonal ray per Cell. A stale
// descriptor can produce a plausible color; checking a real miss also catches that case.
fn query(renderer: &Renderer) -> [[f32; 12]; 3] {
    let input: Vec<_> = (0..3)
        .flat_map(|cell| {
            [
                24.37 + cell as f32 * 64.,
                25.61,
                32.,
                64.,
                0.,
                0.,
                -1.,
                1.,
                0.,
                0.,
                0.,
                0.,
            ]
            .map(f32::to_bits)
        })
        .collect();
    let result = shader_tests::run(
        &renderer.context,
        prime_shader_tests::optics(),
        &input,
        input.len(),
        [0, 3],
        renderer.geometry.as_ref(),
    );
    std::array::from_fn(|cell| std::array::from_fn(|i| f32::from_bits(result[cell * 12 + i])))
}

fn assert_visible(rows: &[[f32; 12]; 3], expected: [bool; 3]) {
    for (cell, (row, hit)) in rows.iter().zip(expected).enumerate() {
        assert!(
            row.iter().all(|v| v.is_finite()),
            "Cell {cell}: non-finite query {row:?}"
        );
        assert_eq!(
            row[11] > 0.,
            hit,
            "Cell {cell}: closest-hit visibility {row:?}"
        );
        assert_eq!(
            &row[..3],
            if hit { &[0.; 3] } else { &[1.; 3] },
            "Cell {cell}: shadow visibility"
        );
    }
}

#[test]
#[ignore = "requires Vulkan; run with validation and synchronization validation"]
fn gpu_terrain_budget_revoked_texture_withdraws_before_descriptor_slot_reuse() {
    let mut scene = scene();
    let mut current = renderer(128);
    render(&mut current, &scene, 0);
    let initial = query(&current);
    assert_visible(&initial, [true; 3]);
    assert!(
        initial[2][8] > initial[2][10],
        "initial texture A must be red"
    );
    let old_slot = current.geometry.as_ref().unwrap().textures().indices[&7];

    current.configure(settings(1)).unwrap();
    // Every Cell is dirty, so the round-robin scheduler spends this frame's allowance
    // on Cell 0. Cell 2 must disappear now, rather than retain geometry sampling B's slot.
    for mesh in scene.meshes.values_mut() {
        mesh.revision += 1;
    }
    let MeshGeometry::Triangles(triangles) = &mut scene.meshes.get_mut(&(2, 0)).unwrap().triangles
    else {
        panic!("triangle fixture");
    };
    for triangle in Arc::make_mut(triangles) {
        triangle.texture_id = 9;
    }
    scene.textures.remove(&7);
    scene.textures.insert(9, texture([0, 0, 255]));
    scene.revision += 1;
    current.set_scene_frozen(true);
    render(&mut current, &scene, 1);
    let geometry = current.geometry.as_ref().unwrap();
    assert_eq!(
        geometry.textures().indices[&9],
        old_slot,
        "fixture must actually reuse A's descriptor slot"
    );
    assert_eq!(geometry.rebuilt_clusters, 1);
    assert_eq!(geometry.triangle_count, 4);
    assert!(geometry.needs_update((&scene).into()));
    assert_visible(&query(&current), [true, true, false]);
    assert_eq!(current.samples, 1);

    render(&mut current, &scene, 2);
    assert_eq!(current.geometry.as_ref().unwrap().rebuilt_clusters, 1);
    assert_visible(&query(&current), [true, true, false]);
    let drained = render(&mut current, &scene, 3);
    let final_rows = query(&current);
    assert_visible(&final_rows, [true; 3]);
    assert!(
        final_rows[2][10] > final_rows[2][8],
        "replacement must use blue texture B"
    );
    assert!(
        !current
            .geometry
            .as_ref()
            .unwrap()
            .needs_update((&scene).into())
    );
    assert_eq!(
        current.samples, 1,
        "last published replacement must reset offline history"
    );
    let mut fresh = renderer(128);
    assert_eq!(
        drained,
        render(&mut fresh, &scene, 0),
        "drained revocation differs from fresh geometry"
    );
}

#[test]
#[ignore = "requires Vulkan; run with validation and synchronization validation"]
fn gpu_terrain_budget_resource_generation_replaces_entire_old_domain_before_drain() {
    let mut scene = scene();
    let mut current = renderer(128);
    render(&mut current, &scene, 0);
    assert_visible(&query(&current), [true; 3]);
    current.configure(settings(1)).unwrap();
    // Keep mesh revision values equal: the explicit resource generation itself must
    // force rebuilding geometry decoded from the replacement catalog.
    for mesh in scene.meshes.values_mut() {
        let MeshGeometry::Triangles(triangles) = &mut mesh.triangles else {
            panic!("triangle fixture");
        };
        for triangle in Arc::make_mut(triangles) {
            for position in &mut triangle.positions {
                position[2] = 8.;
            }
        }
    }
    scene.terrain_resource_generation += 1;
    scene.revision += 1;
    current.set_scene_frozen(true);
    let mut drained = Vec::new();
    for frame in 1..=3 {
        drained = render(&mut current, &scene, frame);
        let geometry = current.geometry.as_ref().unwrap();
        assert_eq!(geometry.rebuilt_clusters, 1);
        assert_eq!(geometry.triangle_count, u64::from(frame) * 2);
        assert_visible(&query(&current), [true, frame >= 2, frame >= 3]);
        assert_eq!(
            current.samples, 1,
            "resource-domain drain must not accumulate partial geometry"
        );
    }
    assert!(
        !current
            .geometry
            .as_ref()
            .unwrap()
            .needs_update((&scene).into())
    );
    let mut fresh = renderer(128);
    assert_eq!(
        drained,
        render(&mut fresh, &scene, 0),
        "new resource generation differs from a fresh full build"
    );
}
