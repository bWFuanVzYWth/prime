//! Windowless contracts for the production grid proposal, MIS, and publication lifetime.
#![cfg(feature = "shader-tests")]

use super::*;
use prime_scene::{
    SceneMesh,
    geometry::{CompiledQuad, MeshGeometry},
    spatial::Cell,
    surface::{Emission, LayerMode, Optics, SurfaceDetail, SurfaceFace, SurfaceLayer, SurfaceMesh},
};
use std::collections::BTreeSet;

const SAMPLES: usize = 4096;
const CENTERS: [f32; 3] = [8., 24., 44.];
const LIGHTS: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/lights.spv"));

struct ExpectedLight {
    source: usize,
    center: [f64; 3],
    power: f64,
    area: f64,
}

fn quad(center: f32) -> CompiledQuad {
    CompiledQuad {
        positions: [
            [center - 1., 7., 0.],
            [center + 1., 7., 0.],
            [center + 1., 9., 0.],
            [center - 1., 9., 0.],
        ],
        uvs: [[0.; 2]; 4],
        color: [1.; 4],
        texture_id: 0,
        flags: 0,
    }
}

fn fixture(
    revision: u64,
    mask: u32,
    format_rotation: usize,
    anchor: [f64; 3],
) -> (Scene, Vec<ExpectedLight>) {
    // One source mesh deliberately contains a non-emissive format-0 range and
    // format-1/2/3 light ranges. Splitting/reordering ranges must preserve identity.
    let mut faces = vec![SurfaceFace::from_quad(quad(58.))];
    let mut sources = Vec::new();
    for (source, center) in CENTERS.into_iter().enumerate() {
        if mask & (1 << source) == 0 {
            continue;
        }
        let mut face = SurfaceFace::from_quad(quad(center));
        face.emission = Emission {
            radiance: [(1 << source) as f32; 3],
            two_sided: true,
            textured: false,
        };
        match (source + format_rotation) % 3 {
            1 => {
                face.optics = Some(Optics {
                    negative: Default::default(),
                    positive: Default::default(),
                    transmit: false,
                    thin: false,
                });
            }
            2 => {
                face.detail = Some(Arc::new(SurfaceDetail {
                    mode: LayerMode::Bilateral,
                    layer: SurfaceLayer {
                        colors: [[1.; 4]; 4],
                        uvs: [[0.; 2]; 4],
                        texture_id: 0,
                        flags: 0,
                        repeat: None,
                        emission: face.emission,
                    },
                }));
            }
            _ => {}
        }
        sources.push(source);
        faces.push(face);
    }
    let mesh = SurfaceMesh::from_resolved(revision, faces).unwrap();
    let expected = mesh
        .lights
        .emitters
        .iter()
        .zip(sources)
        .map(|(emitter, source)| ExpectedLight {
            source,
            center: [f64::from(CENTERS[source]), 8., 0.],
            power: f64::from(emitter.power),
            area: f64::from(emitter.area),
        })
        .collect();
    let mut scene = Scene {
        revision,
        epoch: 1,
        anchor,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    scene.meshes.insert(
        (1, 0),
        SceneMesh {
            revision,
            flags: 0,
            origin: [0.; 3],
            triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
        },
    );
    (scene, expected)
}

fn camera(anchor: [f64; 3]) -> Camera {
    Camera {
        position: std::array::from_fn(|i| ([24., 8., 64.][i] - anchor[i]) as f32),
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.9,
    }
}

// Independent B formula for this simple fixture. Exact represented aliases can
// differ by a few 24-bit atoms; the PDF tolerance below covers that rounding only.
fn probabilities(lights: &[ExpectedLight], receiver: [f64; 3]) -> Vec<f64> {
    let center = receiver.map(|v| (v / 16.).floor() * 16. + 8.);
    let local: Vec<_> = lights
        .iter()
        .map(|light| {
            let d2: f64 = (0..3).map(|i| (light.center[i] - center[i]).powi(2)).sum();
            if d2 <= 24. * 24. {
                // All emitters are 2x2: half-edge squared lengths sum to 2.
                light.power / d2.max(1. + 16. * 16. / 4. + 2. / 3.)
            } else {
                0.
            }
        })
        .collect();
    let local_sum: f64 = local.iter().sum();
    let total: f64 = lights.iter().map(|light| light.power).sum();
    let tail = if local_sum > 0. { 0.25 } else { 1. };
    lights
        .iter()
        .zip(local)
        .map(|(light, weight)| {
            tail * light.power / total
                + if local_sum > 0. {
                    (1. - tail) * weight / local_sum
                } else {
                    0.
                }
        })
        .collect()
}

fn check_selection(renderer: &Renderer, scene: &Scene, lights: &[ExpectedLight], samples: usize) {
    let receivers = [
        [8., 8., 8.],
        [24., 8., 8.],
        [8., 8., -8.],
        [f64::from(16_f32.next_down()), 8., 8.],
        [16., 8., 8.],
        [f64::from(16_f32.next_up()), 8., 8.],
        [200., 8., 8.],
    ];
    let mut inputs = Vec::with_capacity(receivers.len() * samples * 8);
    for (r, receiver) in receivers.into_iter().enumerate() {
        for i in 0..samples {
            inputs.extend((0..3).map(|a| ((receiver[a] - scene.anchor[a]) as f32).to_bits()));
            inputs.extend([
                0x13572468 ^ (r as u32 * 7919),
                (i % 16) as u32,
                (i / 16 % 16) as u32,
                (i / 256) as u32 + if r % 2 == 0 { 0 } else { 248 },
                if r % 2 == 0 { 0 } else { 63 },
            ]);
        }
    }
    let output = crate::shader_tests::run(
        &renderer.context,
        LIGHTS,
        &inputs,
        receivers.len() * samples * 20,
        [2, (receivers.len() * samples) as u32],
        renderer.geometry.as_ref(),
    );
    for (r, (receiver, rows)) in receivers
        .into_iter()
        .zip(output.chunks_exact(samples * 20))
        .enumerate()
    {
        let expected = probabilities(lights, receiver);
        let mut counts = vec![0usize; lights.len()];
        let mut identities = BTreeSet::new();
        for row in rows.as_chunks::<20>().0 {
            if lights.is_empty() {
                assert_eq!(row[0], 0, "removed lights remain selectable");
                continue;
            }
            assert_eq!(row[0], 1, "normal Z-Sobol light selection failed");
            assert_eq!(row[16], 1, "sampled light was not found from its receiver");
            assert_eq!(
                &row[1..3],
                &row[17..19],
                "mixed-format light identity drift"
            );
            assert_eq!(row[3], 1);
            let f = |i| f64::from(f32::from_bits(row[i]));
            let position: [f64; 3] = std::array::from_fn(|a| f(4 + a) + scene.anchor[a]);
            let at = lights
                .iter()
                .position(|light| (position[0] - light.center[0]).abs() <= 1.0001)
                .expect("selected a removed or unrelated source light");
            counts[at] += 1;
            identities.insert((row[1], row[2]));
            let radiance = (1 << lights[at].source) as f64;
            assert_eq!([f(8), f(9), f(10)], [radiance; 3]);
            let area_pdf = expected[at] / lights[at].area;
            assert!(
                (f(7) - area_pdf).abs() <= 2e-8 + area_pdf * 2e-5,
                "receiver={r} source={} area PDF: {} != {area_pdf}",
                lights[at].source,
                f(7)
            );
            let delta: [f64; 3] = std::array::from_fn(|a| position[a] - receiver[a]);
            let distance_squared: f64 = delta.iter().map(|v| v * v).sum();
            let cosine =
                ((0..3).map(|a| f(12 + a) * delta[a]).sum::<f64>() / distance_squared.sqrt()).abs();
            let solid_pdf = area_pdf * distance_squared / cosine;
            for value in [f(11), f(15)] {
                assert!(value.is_finite() && value > 0.);
                assert!(
                    (value - solid_pdf).abs() <= 2e-7 + solid_pdf * 5e-4,
                    "receiver={r} forward/reverse solid-angle PDF: {value} != {solid_pdf}"
                );
            }
        }
        if !lights.is_empty() {
            assert_eq!(
                identities.len(),
                lights.len(),
                "each source needs a distinct page/emitter pair"
            );
            for (count, probability) in counts.iter().zip(&expected) {
                let mean = samples as f64 * probability;
                // A loose behavioral bound, not an IID confidence interval for QMC.
                let tolerance = 8. * (mean * (1. - probability)).sqrt() + 16.;
                assert!(
                    (*count as f64 - mean).abs() <= tolerance,
                    "receiver={r} counts={counts:?}, expected={expected:?}"
                );
            }
        }
    }
}

#[test]
#[ignore = "windowless production grid selection/MIS; run with synchronization validation"]
fn gpu_light_grid_zsobol_selection_and_reverse_mis_share_receiver() {
    let (scene, lights) = fixture(1, 7, 0, [0.; 3]);
    let mut renderer = Renderer::new().unwrap();
    renderer
        .render(&scene, &camera(scene.anchor), 64, 64, 0)
        .unwrap();
    check_selection(&renderer, &scene, &lights, SAMPLES);
}

#[test]
#[ignore = "windowless light identity/PDF after removal, format replacement, and rebase"]
fn gpu_light_grid_mixed_pages_follow_replacement_and_rebase() {
    let mut renderer = Renderer::new().unwrap();
    for (stage, (mask, rotation, anchor)) in [
        (7, 0, [0.; 3]),
        (5, 0, [0.; 3]),
        (7, 1, [13., 5., -2.]),
        (0, 2, [13., 5., -2.]),
    ]
    .into_iter()
    .enumerate()
    {
        let (scene, lights) = fixture(stage as u64 + 1, mask, rotation, anchor);
        renderer
            .render(&scene, &camera(scene.anchor), 64, 64, 0)
            .unwrap();
        check_selection(&renderer, &scene, &lights, SAMPLES);
    }
}

#[test]
#[ignore = "windowless submitted light-grid replacements; run with synchronization validation"]
fn gpu_light_grid_publications_retire_after_submitted_frames() {
    let mut host = HostBenchmark::new(32, 32).unwrap();
    let masks = [7, 3, 5, 6, 1, 7, 0, 4, 7, 0, 3, 0];
    for (frame, mask) in masks.into_iter().enumerate() {
        let anchor = if frame % 2 == 0 {
            [0.; 3]
        } else {
            [13., 5., -2.]
        };
        let (mut scene, _) = fixture(frame as u64 + 1, mask, frame % 3, anchor);
        scene.epoch = 1 + (frame / 6) as u64;
        // No explicit drain at publication boundaries: previous submissions keep
        // their ownership until the host's completion timeline permits retirement.
        let submission = host.enqueue(&scene, &camera(anchor), frame as u32).unwrap();
        assert_eq!(submission.serial, frame as u64 + 1);
    }
    let completed = host.drain().unwrap();
    assert_eq!(
        completed
            .iter()
            .map(|frame| frame.serial)
            .collect::<Vec<_>>(),
        (1..=12).collect::<Vec<_>>()
    );
    assert!(host.drain().unwrap().is_empty());
    assert_eq!(
        host.triangle_count(),
        2,
        "only the non-emissive quad remains"
    );
}

#[test]
#[ignore = "windowless rare-light lattice witnesses and forward/reverse MIS after support repair"]
fn gpu_light_grid_extreme_power_retains_rare_emitters_without_aborting() {
    let mut renderer = Renderer::new().unwrap();
    for split_pages in [false, true] {
        for weak in 0..3 {
            let mut scene = Scene {
                revision: 1 + weak as u64 + u64::from(split_pages) * 3,
                epoch: 1,
                ..Default::default()
            };
            let mut faces = Vec::new();
            for source in 0..3 {
                let mut face = SurfaceFace::from_quad(quad(8. + source as f32 * 4.));
                face.emission = Emission {
                    radiance: [if source == weak { 1e-30 } else { 1e10 }; 3],
                    two_sided: true,
                    textured: false,
                };
                if split_pages {
                    let origin = [source as f64 * 64., 0., 0.];
                    scene
                        .ready_terrain
                        .insert(Cell::containing(origin).unwrap());
                    scene.meshes.insert(
                        (source as u64 + 1, 0),
                        SceneMesh {
                            revision: scene.revision,
                            flags: 0,
                            origin,
                            triangles: MeshGeometry::Surfaces(Arc::new(
                                SurfaceMesh::from_resolved(scene.revision, vec![face]).unwrap(),
                            )),
                        },
                    );
                } else {
                    faces.push(face);
                }
            }
            if !split_pages {
                scene
                    .ready_terrain
                    .insert(Cell::containing([0.; 3]).unwrap());
                scene.meshes.insert(
                    (1, 0),
                    SceneMesh {
                        revision: scene.revision,
                        flags: 0,
                        origin: [0.; 3],
                        triangles: MeshGeometry::Surfaces(Arc::new(
                            SurfaceMesh::from_resolved(scene.revision, faces).unwrap(),
                        )),
                    },
                );
            }
            renderer
                .render(&scene, &camera([0.; 3]), 64, 64, 0)
                .unwrap();
            let first = ((weak as u32) << 24).div_ceil(3);
            let mut inputs = Vec::new();
            let mut witnesses = Vec::new();
            for local in [false, true] {
                if local && split_pages {
                    continue;
                }
                let receiver = if local {
                    [8_f32, 8., 8.]
                } else {
                    [8., 8., 1000.]
                };
                for k in first.saturating_sub(1)..=first + 1 {
                    let sample = k as f32 / 16777216.;
                    inputs.extend(receiver.map(f32::to_bits));
                    inputs.push(17);
                    inputs.extend(
                        [
                            if local { 0.5_f32 } else { 0. },
                            sample,
                            if split_pages { sample } else { 0. },
                            if split_pages { 0. } else { sample },
                            0.36,
                            0.42,
                            0.,
                            0.,
                        ]
                        .map(f32::to_bits),
                    );
                    witnesses.push(k == first);
                }
            }
            let output = crate::shader_tests::run(
                &renderer.context,
                LIGHTS,
                &inputs,
                witnesses.len() * 20,
                [3, witnesses.len() as u32],
                renderer.geometry.as_ref(),
            );
            for (row, rare) in output.as_chunks::<20>().0.iter().zip(witnesses) {
                assert_eq!(row[0], 1);
                assert_eq!(row[16], 1);
                assert_eq!(&row[1..3], &row[17..19]);
                let f = |i| f32::from_bits(row[i]);
                let weak_x = 8. + weak as f32 * if split_pages { 68. } else { 4. };
                assert_eq!(
                    (f(4) - weak_x).abs() < 1.01,
                    rare,
                    "split={split_pages} weak={weak}: exact rare interval changed"
                );
                if rare {
                    assert_eq!(f(7), 1. / 16777216. / 4.);
                }
                assert!(f(11).is_finite() && f(11) > 0.);
                assert!(
                    (f(11) - f(15)).abs() <= f(15) * 5e-4,
                    "rare-light forward/reverse PDF mismatch"
                );
            }
        }
    }
}
