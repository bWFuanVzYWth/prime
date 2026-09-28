use super::*;
use crate::protocol::{ABI_VERSION, MAGIC};

fn header(op: u32) -> Vec<u8> {
    let mut b = Vec::new();
    for v in [MAGIC, ABI_VERSION, op, 0] {
        b.extend(v.to_le_bytes());
    }
    b.extend(1u64.to_le_bytes());
    b
}
fn initialized() -> SourceScene {
    let mut s = SourceScene::default();
    s.submit(&header(1)).unwrap();
    let mut b = header(4);
    for v in [1u32, 1, 1, 0] {
        b.extend(v.to_le_bytes());
    }
    b.extend([255; 4]);
    s.submit(&b).unwrap();
    s
}
fn geometry() -> Vec<u8> {
    let mut b = header(13);
    for v in [1u32, 0] {
        b.extend(v.to_le_bytes());
    }
    b.extend(7u64.to_le_bytes());
    for v in [1u32, 1, 1, 6, 0] {
        b.extend(v.to_le_bytes());
    }
    for p in [[0f32, 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]] {
        for v in p {
            b.extend(v.to_le_bytes());
        }
        b.extend([208, 224, 240, 255]);
        for v in [p[0], p[1]] {
            b.extend(v.to_le_bytes());
        }
    }
    b
}
fn section(sequence: u64, n: u32) -> Vec<u8> {
    let mut b = header(12);
    b.extend(91u64.to_le_bytes());
    b.extend(sequence.to_le_bytes());
    for v in [16f64, 64., -16.] {
        b.extend(v.to_le_bytes());
    }
    b.extend(n.to_le_bytes());
    b.extend(0u32.to_le_bytes());
    for _ in 0..n {
        b.extend(7u64.to_le_bytes());
        for v in [2f32, 3., 4.] {
            b.extend(v.to_le_bytes());
        }
        b.extend(64u32.to_le_bytes());
        b.extend(1u32.to_le_bytes());
        b.extend([112, 144, 63, 255]);
    }
    b
}
#[test]
fn detached_geometry_retirement_noop_publication_empty_and_epoch_reset() {
    let mut s = initialized();
    let mut resource = geometry();
    s.submit(&resource).unwrap();
    resource.fill(0);
    let mut source = section(1, 1);
    s.submit(&source).unwrap();
    source.fill(0);
    let first = s.meshes[&(91, 1)].triangles.clone();
    assert_eq!(first.len(), 2);
    assert_eq!(
        first.triangle(0).positions,
        [[2., 3., 4.], [3., 3., 4.], [3., 4., 4.]]
    );
    assert_eq!(
        first.triangle(0).colors[0],
        [91f32 / 255., 126f32 / 255., 59f32 / 255., 1.]
    );
    let revision = s.revision();
    s.submit(&section(2, 1)).unwrap();
    assert_eq!(s.revision(), revision);
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &first,
        &s.meshes[&(91, 1)].triangles
    ));
    assert!(s.submit(&section(2, 1)).is_err());
    let mut retired = header(13);
    retired.extend(0u32.to_le_bytes());
    retired.extend(1u32.to_le_bytes());
    retired.extend(7u64.to_le_bytes());
    s.submit(&retired).unwrap();
    assert!(s.routing.geometry.is_empty());
    assert_eq!(s.meshes[&(91, 1)].triangles.len(), 2);
    assert!(s.submit(&section(3, 1)).is_err());
    s.submit(&section(3, 0)).unwrap();
    assert!(s.meshes.is_empty());
    assert_eq!(s.sections.origin(91), Some(&[16., 64., -16.]));
    let mut reset = header(1);
    reset[16..24].copy_from_slice(&2u64.to_le_bytes());
    s.submit(&reset).unwrap();
    assert!(s.sections.origin(91).is_none());
    assert!(s.routing.geometry.is_empty());
}
#[test]
fn malformed_source_batches_are_atomic_and_do_not_consume_identity_or_sequence() {
    let resource = geometry();
    for end in 0..resource.len() {
        let mut s = initialized();
        assert!(s.submit(&resource[..end]).is_err());
        assert!(s.routing.geometry.is_empty());
        s.submit(&resource).unwrap();
    }
    let mut s = initialized();
    s.submit(&resource).unwrap();
    s.submit(&section(1, 1)).unwrap();
    let source = section(2, 2);
    let revision = s.revision();
    let first = s.meshes[&(91, 1)].triangles.clone();
    for end in 0..source.len() {
        assert!(s.submit(&source[..end]).is_err());
        assert_eq!(s.revision(), revision);
    }
    let mut invalid = source.clone();
    invalid[72..80].copy_from_slice(&999u64.to_le_bytes());
    assert!(s.submit(&invalid).is_err());
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &first,
        &s.meshes[&(91, 1)].triangles
    ));
    s.submit(&source).unwrap();
    assert_eq!(s.meshes[&(91, 1)].triangles.len(), 4);
    let mut bad_resource = geometry();
    bad_resource[32..40].copy_from_slice(&8u64.to_le_bytes());
    bad_resource[48..52].copy_from_slice(&3u32.to_le_bytes());
    assert!(s.submit(&bad_resource).is_err());
    assert_eq!(s.routing.geometry.len(), 1);
}
#[test]
fn native_workers_compile_identical_outputs_and_only_visible_layers() {
    let mut one = initialized();
    let mut many = initialized();
    one.routing.workers = Some(CpuWorkers::new(1).unwrap());
    many.routing.workers = Some(CpuWorkers::new(4).unwrap());
    for s in [&mut one, &mut many] {
        s.submit(&geometry()).unwrap();
        s.submit(&section(1, 10000)).unwrap();
    }
    let a = &one.meshes[&(91, 1)];
    let b = &many.meshes[&(91, 1)];
    assert_eq!(a.bounds, b.bounds);
    assert_eq!(a.triangles.len(), 20000);
    for (a, b) in a.triangles.iter().zip(b.triangles.iter()) {
        assert_eq!(a.positions, b.positions);
        assert_eq!(a.uvs, b.uvs);
        assert_eq!(a.colors, b.colors);
    }
    let mut culled = geometry();
    culled[32..40].copy_from_slice(&8u64.to_le_bytes());
    culled[52..56].copy_from_slice(&0u32.to_le_bytes());
    one.submit(&culled).unwrap();
    let mut source = section(2, 1);
    source[72..80].copy_from_slice(&8u64.to_le_bytes());
    one.submit(&source).unwrap();
    assert!(one.meshes.is_empty());
}

#[test]
fn retained_prefix_matches_fresh_compilation_for_sparse_attribute_and_resource_changes() {
    let mut resource = geometry();
    resource[32..40].copy_from_slice(&8u64.to_le_bytes());
    // A second immutable source differs in both UV and material layer.
    resource[48..52].copy_from_slice(&2u32.to_le_bytes());
    resource[76..80].copy_from_slice(&0.375f32.to_le_bytes());
    let mut uv = geometry();
    uv[32..40].copy_from_slice(&9u64.to_le_bytes());
    uv[148..152].copy_from_slice(&0.375f32.to_le_bytes()); // fourth corner only
    let mut color = geometry();
    color[32..40].copy_from_slice(&10u64.to_le_bytes());
    color[144..148].copy_from_slice(&[11, 22, 33, 44]);
    let mut equivalent = geometry();
    equivalent[32..40].copy_from_slice(&11u64.to_le_bytes());
    for at in [0usize, 2048, 4096] {
        for field in [
            "position",
            "tint",
            "geometry",
            "origin",
            "uv",
            "color",
            "equivalent",
        ] {
            let mut incremental = initialized();
            let mut fresh = initialized();
            for scene in [&mut incremental, &mut fresh] {
                scene.submit(&geometry()).unwrap();
                scene.submit(&resource).unwrap();
                scene.submit(&uv).unwrap();
                scene.submit(&color).unwrap();
                scene.submit(&equivalent).unwrap();
            }
            incremental.submit(&section(1, 4097)).unwrap();
            let retained = incremental.meshes[&(91, 1)].triangles.clone();
            let mut update = section(2, 4097);
            let first = 72 + at * 32;
            match field {
                "position" => {
                    update[first + 8..first + 12].copy_from_slice(&(-2.5f32).to_le_bytes())
                }
                "tint" => update[first + 28..first + 32].copy_from_slice(&[11, 22, 33, 44]),
                "geometry" => update[first..first + 8].copy_from_slice(&8u64.to_le_bytes()),
                "origin" => update[40..48].copy_from_slice(&(-128f64).to_le_bytes()),
                "uv" => update[first..first + 8].copy_from_slice(&9u64.to_le_bytes()),
                "color" => update[first..first + 8].copy_from_slice(&10u64.to_le_bytes()),
                "equivalent" => update[first..first + 8].copy_from_slice(&11u64.to_le_bytes()),
                _ => unreachable!(),
            }
            incremental.submit(&update).unwrap();
            fresh.submit(&update).unwrap();
            assert_eq!(incremental.meshes.len(), fresh.meshes.len());
            for (key, expected) in &fresh.meshes {
                let actual = &incremental.meshes[key];
                assert_eq!(actual.bounds, expected.bounds);
                assert_eq!(actual.origin, expected.origin);
                assert_eq!(actual.triangles.len(), expected.triangles.len());
                for (a, b) in actual.triangles.iter().zip(expected.triangles.iter()) {
                    assert_eq!(
                        a.positions.map(|p| p.map(f32::to_bits)),
                        b.positions.map(|p| p.map(f32::to_bits))
                    );
                    assert_eq!(
                        a.colors.map(|p| p.map(f32::to_bits)),
                        b.colors.map(|p| p.map(f32::to_bits))
                    );
                    assert_eq!(
                        a.uvs.map(|p| p.map(f32::to_bits)),
                        b.uvs.map(|p| p.map(f32::to_bits))
                    );
                    assert_eq!(a.flags, b.flags);
                    assert_eq!(a.texture_id, b.texture_id);
                }
            }
            assert_eq!(retained.triangle(0).positions[0], [2., 3., 4.]);
            let current = incremental.meshes[&(91, 1)].triangles.clone();
            assert_eq!(
                crate::geometry::MeshGeometry::ptr_eq(&retained, &current),
                field == "equivalent"
            );
            update[32..40].copy_from_slice(&3u64.to_le_bytes());
            incremental.submit(&update).unwrap();
            assert!(crate::geometry::MeshGeometry::ptr_eq(
                &current,
                &incremental.meshes[&(91, 1)].triangles
            ));
        }
    }
}

fn particles(sequence: u64, count: u32) -> Vec<u8> {
    let mut b = header(6);
    b.extend(sequence.to_le_bytes());
    for v in [0f64; 3] {
        b.extend(v.to_le_bytes());
    }
    for v in [1u32, 0, 1, 2, 1, count, 52, 0, 48, 32] {
        b.extend(v.to_le_bytes());
    }
    for i in 0..count {
        for v in [i as f32, 0., 0., 0., 0., 0., 2., 0.5, 0.1, 0.9, 0.2, 0.8] {
            b.extend(v.to_le_bytes());
        }
        b.extend([33, 77, 99, 200]);
    }
    b
}

#[test]
fn parameter_particles_join_before_atomic_publication_and_release_input_borrows() {
    let mut one = initialized();
    let mut many = initialized();
    one.routing.workers = Some(CpuWorkers::new(1).unwrap());
    many.routing.workers = Some(CpuWorkers::new(4).unwrap());
    let mut source = particles(1, 4100);
    one.submit(&source).unwrap();
    many.submit(&source).unwrap();
    source.fill(0);
    let original = many.dynamic.triangles.clone();
    assert_eq!(original.len(), 8200);
    assert_eq!(
        original[0].positions,
        [[0.5, -0.5, 0.], [0.5, 0.5, 0.], [-0.5, 0.5, 0.]]
    );
    assert_eq!(
        original[0].colors[0],
        [33f32 / 255., 77f32 / 255., 99f32 / 255., 200f32 / 255.]
    );
    for (a, b) in one.dynamic.triangles.iter().zip(original.iter()) {
        assert!(close(a, b));
    }
    let short = particles(2, 1);
    for end in 0..short.len() {
        assert!(many.submit(&short[..end]).is_err());
        assert_eq!(many.dynamic.revision, 1);
    }
    let mut invalid = short.clone();
    invalid[88..92].copy_from_slice(&12u32.to_le_bytes());
    assert!(many.submit(&invalid).unwrap_err().contains("layout"));
    // A late bad record can fail after other worker ranges have already completed.
    let good = particles(2, 4100);
    for value in [0f32, f32::NAN, f32::MAX] {
        let mut invalid = good.clone();
        let w = 96 + 4099 * 52 + 24;
        invalid[w..w + 4].copy_from_slice(&value.to_le_bytes());
        assert!(many.submit(&invalid).is_err());
        assert_eq!(many.dynamic.revision, 1);
        assert!(Arc::ptr_eq(&original, &many.dynamic.triangles));
    }
    many.submit(&good).unwrap();
    assert_eq!(many.dynamic.revision, 2);
    assert!(close(&many.dynamic.triangles[8199], &original[8199]));
    many.submit(&particles(3, 0)).unwrap();
    assert!(many.dynamic.triangles.is_empty());
    assert_eq!(original.len(), 8200);
}

fn close(a: &Triangle, b: &Triangle) -> bool {
    a.texture_id == b.texture_id
        && a.flags == b.flags
        && a.colors == b.colors
        && a.positions
            .iter()
            .flatten()
            .zip(b.positions.iter().flatten())
            .all(|(a, b)| (a - b).abs() <= 0.000002)
        && a.uvs
            .iter()
            .flatten()
            .zip(b.uvs.iter().flatten())
            .all(|(a, b)| (a - b).abs() <= 0.00005)
}
/// CPU smoke generates these with each actual Fabric/MC version and its vanilla reference output.
/// Explicit because it requires those Java fixtures; missing fixtures are errors, never skipped passes.
#[test]
#[ignore = "run both adapters' cpuSmoke first"]
fn java_routing_matches_both_versions_actual_source_and_fluid_particle_oracles() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../adapters");
    for version in ["26.2", "26.3"] {
        let dir = root.join(format!("mc-{version}/build/routing-fixtures"));
        // This directory also contains the section source ABI. Only these files carry
        // the legacy pair-of-packet-streams format; require every declared oracle.
        let names = ["terrain-false", "terrain-true", "particles"]
            .into_iter()
            .map(str::to_owned)
            .chain((0..8).map(|i| format!("fluid-{i}")));
        let mut cases = 0;
        for name in names {
            let path = dir.join(format!("{name}.bin"));
            let bytes = std::fs::read(&path).unwrap();
            let mut input = bytes.as_slice();
            let number = |input: &mut &[u8]| {
                let n = u32::from_be_bytes(input[..4].try_into().unwrap()) as usize;
                *input = &input[4..];
                n
            };
            let mut scenes = Vec::new();
            for _ in 0..2 {
                let mut s = initialized();
                let n = number(&mut input);
                for _ in 0..n {
                    let size = number(&mut input);
                    s.submit(&input[..size])
                        .unwrap_or_else(|e| panic!("{}: {e}", path.display()));
                    input = &input[size..];
                }
                let mut triangles = Vec::new();
                for mesh in s.meshes.values() {
                    triangles.extend(mesh.triangles.iter());
                }
                triangles.extend_from_slice(&s.dynamic.triangles);
                scenes.push(triangles);
            }
            assert!(input.is_empty());
            let mut actual = scenes.remove(0);
            let expected = scenes.remove(0);
            assert_eq!(actual.len(), expected.len(), "{}", path.display());
            if actual.len() > 100 {
                for (i, (a, b)) in actual.iter().zip(&expected).enumerate() {
                    assert!(
                        close(a, b),
                        "{} triangle {i}: {a:?} != {b:?}",
                        path.display()
                    );
                }
            } else {
                for b in expected {
                    let index = actual.iter().position(|a| close(a, &b)).unwrap_or_else(|| {
                        panic!("{} missing {b:?}, actual={actual:?}", path.display())
                    });
                    actual.swap_remove(index);
                }
            }
            cases += 1;
        }
        assert_eq!(
            cases, 11,
            "Must cover all generated terrain, fluid and particle fixtures"
        );
    }
}

#[test]
#[ignore = "explicit release CPU cost matrix; excludes Java, FFM, GPU and packet generation"]
fn routing_cost_matrix() {
    use std::{hint::black_box, time::Instant};
    let label = std::env::var("PRIME_ROUTING_COST_LABEL").unwrap_or_else(|_| "current".into());
    let mut csv = String::from("case,placements_per_section,sample,warmup,sections,submit_ns\n");
    for count in [512, 4096] {
        for case in ["unchanged", "last_edit", "all_edit"] {
            let mut scene = initialized();
            scene.routing.workers = Some(CpuWorkers::new(8).unwrap());
            scene.submit(&geometry()).unwrap();
            let mut packets: Vec<_> = (0..16u64)
                .map(|id| {
                    let mut packet = section(1, count);
                    packet[24..32].copy_from_slice(&(100 + id).to_le_bytes());
                    packet
                })
                .collect();
            let warmup_start = Instant::now();
            let mut sample = -1i32;
            let mut sequence = 0u64;
            while sample < 30 {
                sequence += 1;
                for packet in &mut packets {
                    packet[32..40].copy_from_slice(&sequence.to_le_bytes());
                    let changed = if sequence.is_multiple_of(2) {
                        2.0f32
                    } else {
                        2.25
                    };
                    if case != "unchanged" {
                        for index in 0..count as usize {
                            if case == "all_edit" || index + 1 == count as usize {
                                let offset = 72 + index * 32 + 8;
                                packet[offset..offset + 4].copy_from_slice(&changed.to_le_bytes());
                            }
                        }
                    }
                }
                let start = Instant::now();
                for packet in &packets {
                    black_box(&mut scene).submit(black_box(packet)).unwrap();
                }
                let elapsed = start.elapsed().as_nanos();
                csv += &format!("{case},{count},{sample},{},16,{elapsed}\n", sample < 0);
                sample = if sample >= 0 {
                    sample + 1
                } else if warmup_start.elapsed().as_millis() >= 500 {
                    0
                } else {
                    sample - 1
                };
            }
        }
    }
    let dir =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/routing-stutter");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join(format!("native-{label}.csv")), csv).unwrap();
}
