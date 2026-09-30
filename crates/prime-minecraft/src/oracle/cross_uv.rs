//! Actual FaceBakery fields are sampled by side, independently of source triangulation.
use super::*;
use prime_scene::{geometry::MeshGeometry, surface::LayerMode};

struct SourceQuad {
    positions: [[f64; 3]; 4],
    uvs: [[f64; 2]; 4],
}
fn subtract(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|i| a[i] - b[i])
}
fn normal(p: [[f64; 3]; 3]) -> [f64; 3] {
    let a = subtract(p[1], p[0]);
    let b = subtract(p[2], p[0]);
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a.into_iter().zip(b).map(|(a, b)| a * b).sum()
}
fn source_uv(q: &SourceQuad, point: [f64; 3], side: [f64; 3], front: bool) -> Option<[f64; 2]> {
    for corners in [[0, 1, 2], [2, 3, 0]] {
        let positions = corners.map(|i| q.positions[i]);
        let n = normal(positions);
        if (dot(n, side) > 0.) != front {
            continue;
        }
        let a = subtract(positions[1], positions[0]);
        let b = subtract(positions[2], positions[0]);
        let d = subtract(point, positions[0]);
        if dot(n, d).abs() > 1e-7 {
            continue;
        }
        let axis = (0..3)
            .max_by(|&a, &b| n[a].abs().total_cmp(&n[b].abs()))
            .unwrap();
        let (x, y) = ((axis + 1) % 3, (axis + 2) % 3);
        let determinant = a[x] * b[y] - a[y] * b[x];
        let u = (d[x] * b[y] - d[y] * b[x]) / determinant;
        let v = (a[x] * d[y] - a[y] * d[x]) / determinant;
        if u >= -1e-8 && v >= -1e-8 && u + v <= 1. + 1e-8 {
            let weights = [1. - u - v, u, v];
            return Some(std::array::from_fn(|a| {
                (0..3).map(|i| q.uvs[corners[i]][a] * weights[i]).sum()
            }));
        }
    }
    None
}

#[test]
#[ignore = "generate actual dual-version cross/tinted_cross FaceBakery fixtures with cpuSmoke"]
fn actual_cross_models_preserve_independent_front_and_back_uv_fields() {
    for version in [262, 263] {
        let root = suite_root(version).join("cross-uv");
        for case in ["cross", "tinted_cross"] {
            let metadata =
                std::fs::read_to_string(root.join(format!("{case}.properties"))).unwrap();
            assert!(
                metadata
                    .lines()
                    .any(|line| line == format!("gameVersion={version}"))
            );
            assert!(metadata.lines().any(|line| line == "state=minecraft:stone"));
            let raw = std::fs::read(root.join(format!("{case}.quads"))).unwrap();
            assert_eq!(raw.len(), 324);
            assert_eq!(u32::from_be_bytes(raw[..4].try_into().unwrap()), 4);
            let values: Vec<_> = raw[4..]
                .as_chunks::<4>()
                .0
                .iter()
                .map(|&bytes| f64::from(f32::from_be_bytes(bytes)))
                .collect();
            let source: Vec<_> = values
                .as_chunks::<20>()
                .0
                .iter()
                .map(|q| SourceQuad {
                    positions: std::array::from_fn(|i| std::array::from_fn(|a| q[5 * i + a])),
                    uvs: std::array::from_fn(|i| [q[5 * i + 3], q[5 * i + 4]]),
                })
                .collect();
            let mut context = TerrainContext::default();
            let mut output = scene();
            let input = std::fs::read(root.join(format!("{case}.frame"))).unwrap();
            assert_eq!(requests(&mut context, &input), [Section(0, 0, 0)]);
            let packet = std::fs::read(root.join(format!("{case}.source"))).unwrap();
            context.accept(&[&packet], &mut output).unwrap();
            if case == "tinted_cross" {
                assert_eq!(context.stats.tint_requests, 1);
                let colors = std::fs::read(root.join(format!("{case}.tint"))).unwrap();
                context.accept(&[&colors], &mut output).unwrap();
            }
            assert!(context.tint_requests().is_empty());
            let snapshot = output.translate([0.; 3]).unwrap();
            assert_eq!(snapshot.meshes.len(), 1);
            let mesh = snapshot.meshes.values().next().unwrap();
            assert_eq!(mesh.origin, [0.; 3]);
            let MeshGeometry::Surfaces(surface) = &mesh.triangles else {
                panic!("{version}/{case}: coincident cross sides were not resolved");
            };
            assert_eq!(surface.quads.len(), 2);
            for face in &surface.quads {
                let detail = face.detail.as_ref().unwrap();
                assert_eq!(detail.mode, LayerMode::Bilateral);
                assert_ne!(face.geometry.uvs, detail.layer.uvs);
                for corners in [[0, 1, 2], [2, 3, 0]] {
                    let positions = corners.map(|i| face.geometry.positions[i].map(f64::from));
                    let side = normal(positions);
                    for weights in [[0.23, 0.31, 0.46], [0.13, 0.63, 0.24], [0.38, 0.41, 0.21]] {
                        let point = std::array::from_fn(|a| {
                            (0..3).map(|i| positions[i][a] * weights[i]).sum()
                        });
                        for front in [false, true] {
                            let uvs = if front {
                                face.geometry.uvs
                            } else {
                                detail.layer.uvs
                            };
                            let actual: [f64; 2] = std::array::from_fn(|a| {
                                (0..3)
                                    .map(|i| f64::from(uvs[corners[i]][a]) * weights[i])
                                    .sum()
                            });
                            let expected: Vec<_> = source
                                .iter()
                                .filter_map(|q| source_uv(q, point, side, front))
                                .collect();
                            assert_eq!(
                                expected.len(),
                                1,
                                "{version}/{case} point={point:?} front={front}"
                            );
                            assert!(
                                (0..2).all(|a| (actual[a] - expected[0][a]).abs() < 2e-6),
                                "{version}/{case} point={point:?} front={front} actual={actual:?} expected={expected:?}"
                            );
                        }
                    }
                }
            }
        }
    }
}
