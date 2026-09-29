use super::*;
use prime_scene::{
    SceneMesh, Texture,
    geometry::{CompiledQuad, MeshGeometry},
    spatial::Cell,
};

fn scene(edge: usize, pattern: &str, flags: u32) -> Scene {
    let mut scene = Scene {
        revision: 1,
        ..Default::default()
    };
    // A nontrivial atlas with distinct RGB/coverage and margins outside the repeated region.
    let pixels = (0..32)
        .flat_map(|y| {
            (0..32).flat_map(move |x| {
                [
                    (x * 7) as u8,
                    (y * 7) as u8,
                    ((x * 3 + y * 5) % 256) as u8,
                    if (x / 2 + y / 2) % 3 == 0 { 0 } else { 255 },
                ]
            })
        })
        .collect::<Vec<_>>();
    scene.textures.insert(
        1,
        Texture {
            width: 32,
            height: 32,
            pixels: pixels.into(),
        },
    );
    for cy in 0..edge {
        for cx in 0..edge {
            let mut quads = (0..64)
                .flat_map(|y| {
                    (0..64).map(move |x| {
                        let mut q = CompiledQuad {
                            positions: [
                                [x as f32, y as f32, 0.],
                                [x as f32 + 1., y as f32, 0.],
                                [x as f32 + 1., y as f32 + 1., 0.],
                                [x as f32, y as f32 + 1., 0.],
                            ],
                            uvs: [[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]],
                            color: [0.7, 0.8, 0.9, if flags == 2 { 0.625 } else { 1. }],
                            texture_id: 1,
                            flags,
                        };
                        match pattern {
                            "tiled" => q.color[0] = if (x / 8 + y / 8) % 2 == 0 { 0.5 } else { 1. },
                            "checker" => q.color[0] = if (x + y) % 2 == 0 { 0.5 } else { 1. },
                            "sloped" => q.positions[2][2] = 0.125,
                            "rotated" => q.uvs.rotate_right(1),
                            "uniform" | "layers" => {}
                            _ => panic!("unknown fixture"),
                        }
                        q
                    })
                })
                .collect::<Vec<_>>();
            if pattern == "layers" {
                for layer in 1..8 {
                    for i in 0..4096 {
                        let mut q = quads[i];
                        q.positions
                            .iter_mut()
                            .for_each(|p| p[2] += layer as f32 * 8.);
                        q.uvs.rotate_right(layer % 4);
                        quads.push(q);
                    }
                }
            }
            let origin = [(cx * 64) as f64, (cy * 64) as f64, 0.];
            scene
                .ready_terrain
                .insert(Cell::containing(origin).unwrap());
            scene.meshes.insert(
                ((cy * edge + cx) as u64, flags),
                SceneMesh {
                    revision: 1,
                    flags,
                    origin,
                    triangles: MeshGeometry::Quads(quads.into()),
                },
            );
        }
    }
    scene
}

fn camera(edge: usize) -> Camera {
    let size = (edge * 64) as f32;
    Camera {
        position: [size * 0.5, size * 0.5, size],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.9,
    }
}

#[test]
#[ignore = "windowless GPU behavioral comparison; run with synchronization validation"]
fn gpu_surface_rectangles_preserve_texture_coverage_and_replacements() {
    let mut legacy = Renderer::new().unwrap();
    legacy.set_surface_compiler(false).unwrap();
    let mut compiled = Renderer::new().unwrap();
    compiled.set_surface_compiler(true).unwrap();
    for flags in 0..3 {
        for (step, pattern) in ["uniform", "tiled", "checker", "sloped", "rotated"]
            .into_iter()
            .enumerate()
        {
            let mut input = scene(1, pattern, flags);
            input.revision = u64::from(flags) * 5 + step as u64 + 1;
            for mesh in input.meshes.values_mut() {
                mesh.revision = input.revision;
            }
            let a = legacy.render(&input, &camera(1), 512, 320, 0).unwrap();
            let b = compiled.render(&input, &camera(1), 512, 320, 0).unwrap();
            let changed = a
                .as_chunks::<4>()
                .0
                .iter()
                .zip(b.as_chunks::<4>().0)
                .filter(|(a, b)| a != b)
                .count();
            let abs: u64 = a
                .iter()
                .zip(&b)
                .map(|(a, b)| u64::from(a.abs_diff(*b)))
                .sum();
            println!(
                "surface pixels flags={flags} pattern={pattern}: changed={changed}/163840 mean_abs={:.6} triangles={}->{}",
                abs as f64 / a.len() as f64,
                legacy.geometry.as_ref().unwrap().triangle_count,
                compiled.geometry.as_ref().unwrap().triangle_count
            );
            // Retriangulation changes floating-point barycentrics at texture/coverage edges.
            // The independent reference bounds those rare boundary differences, not whole-image
            // mean alone (which could hide a small but completely wrong surface).
            assert!(changed <= 64, "too many changed pixels: {changed}");
            assert!(abs as f64 / (a.len() as f64) < 0.025);
            let expected = match pattern {
                "uniform" | "rotated" => 2,
                "tiled" => 128,
                _ => 8192,
            };
            assert_eq!(compiled.geometry.as_ref().unwrap().triangle_count, expected);
        }
    }
    assert!(compiled.set_surface_compiler(false).is_err());
}

#[test]
#[ignore = "native 1080p steady GPU comparison; run alone in release without validation"]
fn surface_steady_cost_matrix() {
    use std::io::Write;
    let samples = std::env::var("PRIME_SURFACE_SAMPLES").map_or(120, |v| v.parse::<u32>().unwrap());
    let rounds = std::env::var("PRIME_SURFACE_ROUNDS").map_or(3, |v| v.parse::<u32>().unwrap());
    let warmup = std::env::var("PRIME_SURFACE_WARMUP").map_or(1024, |v| v.parse::<u32>().unwrap());
    let pattern_filter = std::env::var("PRIME_SURFACE_PATTERN").ok();
    let mut csv =
        std::fs::File::create(std::env::var("PRIME_SURFACE_CSV").expect("set PRIME_SURFACE_CSV"))
            .unwrap();
    writeln!(csv, "round,pattern,flags,compiler,sample,warmup,triangles,record_ns,wall_ns,gpu_ns,prepare_ns,render_ns").unwrap();
    for round in 0..rounds {
        for pattern in ["uniform", "tiled", "checker", "sloped", "layers"] {
            if pattern_filter.as_ref().is_some_and(|p| p != pattern) {
                continue;
            }
            for flags in [0, 1, 2] {
                let input = scene(4, pattern, flags);
                for enabled in if round % 2 == 0 {
                    [false, true]
                } else {
                    [true, false]
                } {
                    let mut host = HostBenchmark::new(1920, 1080).unwrap();
                    host.set_surface_compiler(enabled).unwrap();
                    println!(
                        "surface steady: round={round} pattern={pattern} flags={flags} compiler={enabled} device={} native=1920x1080 warmup={warmup} samples={samples}",
                        host.device_name()
                    );
                    for sample in 0..samples + warmup {
                        let frame = host.enqueue(&input, &camera(4), sample).unwrap();
                        // Completion per sample gives unambiguous CPU/GPU attribution. It does
                        // not simulate a game's presentation or CPU/GPU overlap.
                        let completed = host.drain().unwrap();
                        assert_eq!(completed.len(), 1);
                        let gpu = completed[0];
                        writeln!(
                            csv,
                            "{round},{pattern},{flags},{enabled},{sample},{},{},{},{},{},{},{}",
                            sample < warmup,
                            host.triangle_count(),
                            frame.record_ns,
                            frame.wall_ns,
                            gpu.gpu_ns,
                            gpu.preparation_ns.unwrap_or(0),
                            gpu.render_ns.unwrap_or(0)
                        )
                        .unwrap();
                    }
                    csv.flush().unwrap();
                }
            }
        }
    }
}

#[test]
#[ignore = "windowless mixed record strides, replacement and in-flight resource ownership"]
fn gpu_surface_mixed_records_follow_material_ranges_and_format_replacement() {
    let mut compiled = Renderer::new().unwrap();
    compiled.set_surface_compiler(true).unwrap();
    let mut host = HostBenchmark::new(128, 128).unwrap();
    host.set_surface_compiler(true).unwrap();
    for revision in 1..=8 {
        let mut input = scene(1, "uniform", 0);
        // This test isolates record stride/ownership from repeated-atlas boundary rounding,
        // which has a separate high-frequency texture/coverage image comparison above.
        input.textures.insert(
            1,
            Texture {
                width: 1,
                height: 1,
                pixels: Arc::from([99, 133, 177, 255]),
            },
        );
        for flags in 1..=2 {
            if revision == 4 && flags == 1 {
                continue;
            }
            let pattern = if (revision + u64::from(flags)) % 2 == 0 {
                "sloped"
            } else {
                "tiled"
            };
            for (key, mut mesh) in scene(1, pattern, flags).meshes {
                mesh.origin[2] = f64::from(flags) * 4.;
                input.meshes.insert(key, mesh);
            }
        }
        input.revision = revision;
        for mesh in input.meshes.values_mut() {
            mesh.revision = revision;
        }
        let b = compiled.render(&input, &camera(1), 256, 256, 11).unwrap();
        let mut fresh = Renderer::new().unwrap();
        fresh.set_surface_compiler(true).unwrap();
        assert_eq!(
            fresh.render(&input, &camera(1), 256, 256, 11).unwrap(),
            b,
            "format replacement must match a fresh compiled scene"
        );
        // Two publications can remain in flight while the next one changes an allocation's
        // stride and physical page. Validation observes the production queue dependencies.
        host.enqueue(&input, &camera(1), 11).unwrap();
    }
    host.drain().unwrap();
}

#[cfg(feature = "shader-tests")]
#[test]
#[ignore = "windowless production GPU light-tree selection/PDF and publication test"]
fn gpu_surface_light_trees_match_reverse_pdf_and_follow_scene_replacement() {
    use prime_scene::surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad};
    let mut renderer = Renderer::new().unwrap();
    renderer.set_surface_compiler(true).unwrap();
    let mut scene = Scene {
        revision: 1,
        ..Default::default()
    };
    let mut compiler = SurfaceCompiler::new();
    for i in 0..3 {
        let q = SurfaceQuad {
            geometry: CompiledQuad {
                positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
                uvs: [[0.; 2]; 4],
                color: [1.; 4],
                texture_id: 0,
                flags: 0,
            },
            provenance: Provenance {
                domain: 0,
                source: i,
            },
            emission: Emission {
                radiance: [(1 << i) as f32; 3],
                two_sided: true,
            },
            rule: prime_scene::surface::SurfaceRule::Preserve,
        };
        let mesh = compiler.compile(1, &[q]).unwrap();
        let origin = [i as f64 * 64., 0., 0.];
        scene
            .ready_terrain
            .insert(Cell::containing(origin).unwrap());
        scene.meshes.insert(
            (i, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin,
                triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
            },
        );
    }
    let samples = 8192_u32;
    let inputs: Vec<_> = (0..samples)
        .flat_map(|i| {
            [
                ((i as f32 + 0.5) / samples as f32).to_bits(),
                0.25_f32.to_bits(),
                0.75_f32.to_bits(),
                0,
            ]
        })
        .collect();
    for stage in 0..3 {
        if stage == 1 {
            scene.meshes.remove(&(1, 0));
        } else if stage == 2 {
            scene.meshes.clear();
        }
        scene.revision += 1;
        renderer.render(&scene, &camera(3), 64, 64, 0).unwrap();
        let out = crate::shader_tests::run(
            &renderer.context,
            include_bytes!(concat!(env!("OUT_DIR"), "/lights.spv")),
            &inputs,
            samples as usize * 16,
            [0, samples],
            renderer.geometry.as_ref(),
        );
        let mut counts = [0; 3];
        for row in out.as_chunks::<16>().0 {
            if stage == 2 {
                assert_eq!(row[0], 0);
                continue;
            }
            assert_eq!(row[0], 1);
            let f = |i| f32::from_bits(row[i]);
            let x = f(4);
            let source = (x / 64.).floor() as usize;
            counts[source] += 1;
            assert!(source < 3 && (stage == 0 || source != 1));
            assert_eq!(row[3], 1);
            assert_eq!(
                row[1],
                if stage == 1 && source == 2 {
                    1
                } else {
                    source as u32
                }
            );
            assert!(row[2] < 2);
            assert_eq!([f(8), f(9), f(10)], [(1 << source) as f32; 3]);
            let expected_area_pdf = (1 << source) as f32 / if stage == 0 { 7. } else { 5. };
            assert!((f(7) - expected_area_pdf).abs() < 1e-6);
            assert!(
                (f(11) - expected_area_pdf * 4.).abs() < 1e-5,
                "forward={} reverse={}",
                f(7),
                f(11)
            );
            assert_eq!([f(12), f(13), f(14)], [0., 0., 1.]);
            assert!(f(15) > 0.);
        }
        if stage < 2 {
            for (i, count) in counts.into_iter().enumerate() {
                let expected = if stage == 1 && i == 1 {
                    0.
                } else {
                    samples as f64 * (1 << i) as f64 / if stage == 0 { 7. } else { 5. }
                };
                assert!(
                    (count as f64 - expected).abs() <= 2.,
                    "counts={counts:?} stage={stage}"
                );
            }
        }
    }
}

fn lit_scene(revision: u64, emission: f32) -> Scene {
    use prime_scene::surface::{Emission, Provenance, SurfaceCompiler, SurfaceQuad};
    let patch = |positions, radiance| SurfaceQuad {
        geometry: CompiledQuad {
            positions,
            uvs: [[0.; 2]; 4],
            color: [0.5, 0.5, 0.5, 1.],
            texture_id: 0,
            flags: 0,
        },
        provenance: Provenance {
            domain: 1,
            source: if radiance == 0. { 0 } else { 1 },
        },
        emission: Emission {
            radiance: [radiance; 3],
            two_sided: false,
        },
        rule: prime_scene::surface::SurfaceRule::Preserve,
    };
    let floor = patch(
        [
            [-16., -16., 0.],
            [16., -16., 0.],
            [16., 16., 0.],
            [-16., 16., 0.],
        ],
        0.,
    );
    // Facing the receiver, outside the camera frustum. A one-bounce image can only see its
    // contribution by actually selecting it through the world/local trees and tracing a shadow.
    let light = patch(
        [[2., 2., 4.], [2., 4., 4.], [4., 4., 4.], [4., 2., 4.]],
        emission,
    );
    let mesh = SurfaceCompiler::new()
        .compile(revision, &[floor, light])
        .unwrap();
    let mut scene = Scene {
        revision,
        ..Default::default()
    };
    scene
        .ready_terrain
        .insert(Cell::containing([0.; 3]).unwrap());
    scene.meshes.insert(
        (0, 0),
        SceneMesh {
            revision,
            flags: 0,
            origin: [0.; 3],
            triangles: MeshGeometry::Surfaces(Arc::new(mesh)),
        },
    );
    scene
}

#[test]
#[ignore = "windowless direct-light transport and completion-owned resource replacement"]
fn gpu_surface_lights_illuminate_receivers_and_retire_with_inflight_frames() {
    let camera = Camera {
        position: [0., 0., 6.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.9,
    };
    let mut renderer = Renderer::new().unwrap();
    renderer.set_surface_compiler(true).unwrap();
    renderer
        .configure(RenderSettings {
            mode: RenderMode::Offline,
            bounces: 1,
            sun: 1. / 256.,
            sky: 1. / 256.,
            ..Default::default()
        })
        .unwrap();
    let dark = renderer
        .render(&lit_scene(1, 0.), &camera, 128, 128, 0)
        .unwrap();
    let lit = renderer
        .render(&lit_scene(2, 12.), &camera, 128, 128, 0)
        .unwrap();
    let removed = renderer
        .render(&lit_scene(3, 0.), &camera, 128, 128, 0)
        .unwrap();
    assert_eq!(
        removed, dark,
        "removed lights must leave neither stale IDs nor stale radiance"
    );
    let energy = |pixels: &[u8]| {
        pixels
            .as_chunks::<4>()
            .0
            .iter()
            .map(|p| u64::from(p[0]) + u64::from(p[1]) + u64::from(p[2]))
            .sum::<u64>()
    };
    println!(
        "one-bounce surface light: dark={} lit={}",
        energy(&dark),
        energy(&lit)
    );
    assert!(energy(&lit) > energy(&dark) * 4);
    drop(renderer);

    let mut host = HostBenchmark::new(128, 128).unwrap();
    host.set_surface_compiler(true).unwrap();
    for revision in 1..=24 {
        let mut scene = lit_scene(
            revision,
            if revision % 3 == 0 {
                0.
            } else {
                4. + revision as f32
            },
        );
        // Rebase while older pointers/trees are still consumed by the queue as well as replacing
        // emitter contents. The host only waits when its two-slot ring is full.
        scene.anchor[0] = (revision % 2) as f64 * 64.;
        let frame = host.enqueue(&scene, &camera, 0).unwrap();
        assert_eq!(frame.serial, revision);
    }
    host.drain().unwrap();
}
