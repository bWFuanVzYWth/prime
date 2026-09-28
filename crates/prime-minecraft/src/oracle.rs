//! Differential test input comes from each version's actual SectionCompiler in Fabric preLaunch.
//! The resources and lighting are controlled; this is not the optimized kernel's scalar reference.
use super::*;
use crate::tests::{frame, requests, scene};

fn triangles(scene: &SourceScene) -> Vec<Triangle> {
    let snapshot = scene.translate([0.; 3]).unwrap();
    let mut result = Vec::new();
    for mesh in snapshot.meshes.values() {
        for triangle in mesh.triangles.iter() {
            let mut t = *triangle;
            for p in &mut t.positions {
                for (i, v) in p.iter_mut().enumerate() {
                    *v += mesh.origin[i] as f32;
                }
            }
            // Triangle order and cyclic starting corner may differ, but winding and duplicates matter.
            let first = (0..3)
                .min_by(|&a, &b| compare(&t.positions[a], &t.positions[b]))
                .unwrap();
            t.positions = std::array::from_fn(|i| t.positions[(first + i) % 3]);
            t.colors = std::array::from_fn(|i| t.colors[(first + i) % 3]);
            t.uvs = std::array::from_fn(|i| t.uvs[(first + i) % 3]);
            result.push(t);
        }
    }
    result.sort_by(|a, b| compare(&values(a), &values(b)));
    result
}
fn values(t: &Triangle) -> Vec<f32> {
    t.positions
        .iter()
        .flatten()
        .chain(t.uvs.iter().flatten())
        .chain(t.colors.iter().flatten())
        .copied()
        .chain([t.texture_id as f32, t.flags as f32])
        .collect()
}
fn compare(a: &[f32], b: &[f32]) -> std::cmp::Ordering {
    a.iter()
        .zip(b)
        .map(|(a, b)| a.total_cmp(b))
        .find(|v| !v.is_eq())
        .unwrap_or(std::cmp::Ordering::Equal)
}
fn reference(bytes: &[u8]) -> SourceScene {
    let mut input = bytes;
    fn count(input: &mut &[u8]) -> usize {
        let n = u32::from_be_bytes(input[..4].try_into().unwrap()) as usize;
        *input = &input[4..];
        n
    }
    let mut expected = scene();
    for _ in 0..count(&mut input) {
        let n = count(&mut input);
        expected.submit(&input[..n]).unwrap();
        input = &input[n..];
    }
    assert!(input.is_empty());
    expected
}

#[test]
#[ignore = "generate both actual SectionCompiler fixtures with the two Fabric cpuSmoke tasks"]
fn actual_section_compilers_match_native_geometry() {
    let mut failures = Vec::new();
    for version in [262, 263] {
        let name = if version == 262 { "26.2" } else { "26.3" };
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
            "../../adapters/mc-{name}/build/routing-fixtures/section-oracle"
        ));
        let cases = std::fs::read_to_string(root.join("cases.txt"))
            .expect("run both Fabric cpuSmoke tasks first");
        for case in cases.lines() {
            let source = std::fs::read(root.join(format!("{case}.source"))).unwrap();
            let expected = triangles(&reference(
                &std::fs::read(root.join(format!("{case}.expected"))).unwrap(),
            ));
            let events: Vec<_> = (-1..=1)
                .flat_map(|x| (-1..=1).map(move |z| (1, Section(x, 0, z))))
                .collect();
            let mut input = frame(1, 0., 1, [-1, 1], &events);
            input[8..12].copy_from_slice(&(version as u32).to_le_bytes());
            let mut context = TerrainContext::default();
            let mut output = scene();
            assert_eq!(requests(&mut context, &input).len(), 27);
            context.accept(&[&source], &mut output).unwrap();
            let actual = triangles(&output);
            let same = expected.len() == actual.len()
                && expected.iter().zip(&actual).all(|(a, b)| {
                    values(a)
                        .iter()
                        .zip(values(b))
                        .all(|(a, b)| (*a - b).abs() < 0.000005)
                });
            if !same {
                failures.push(format!(
                    "{name}/{case}: original={} native={} triangles",
                    expected.len(),
                    actual.len()
                ));
            }
        }
    }
    assert!(
        failures.is_empty(),
        "Original compiler mismatches:\n{}",
        failures.join("\n")
    );
}

#[test]
#[ignore = "generate both actual SectionCompiler fixtures with the two Fabric cpuSmoke tasks"]
fn fluid_corner_edits_invalidate_diagonal_consumers_and_match_full_recompile() {
    for version in [262u32, 263] {
        let name = if version == 262 { "26.2" } else { "26.3" };
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
            "../../adapters/mc-{name}/build/routing-fixtures/section-oracle"
        ));
        let events: Vec<_> = (-1..=1)
            .flat_map(|x| (-1..=1).map(move |z| (1, Section(x, 0, z))))
            .collect();
        let mut input = frame(1, 0., 1, [-1, 1], &events);
        input[8..12].copy_from_slice(&version.to_le_bytes());
        let mut context = TerrainContext::default();
        let mut output = scene();
        let sections = requests(&mut context, &input);
        let initial = std::fs::read(root.join("water_cross_section.source")).unwrap();
        context.accept(&[&initial], &mut output).unwrap();
        let before = triangles(&output);
        let expected = triangles(&reference(
            &std::fs::read(root.join("water_cross_section_changed.expected")).unwrap(),
        ));
        assert!(
            before
                .iter()
                .zip(&expected)
                .any(|(a, b)| values(a) != values(b))
        );
        let mut source = std::fs::read(root.join("water_cross_section_changed.source")).unwrap();
        let dirty: Vec<_> = sections.iter().map(|&s| (3, s)).collect();
        for batch in [2, 3] {
            input = frame(batch, 0., 1, [-1, 1], &dirty);
            input[8..12].copy_from_slice(&version.to_le_bytes());
            assert_eq!(requests(&mut context, &input).len(), 27);
            source[24..32].copy_from_slice(&batch.to_le_bytes());
            context.accept(&[&source], &mut output).unwrap();
            let actual = triangles(&output);
            assert_eq!(expected.len(), actual.len());
            for (a, b) in expected.iter().zip(&actual) {
                assert!(
                    values(a)
                        .iter()
                        .zip(values(b))
                        .all(|(a, b)| (*a - b).abs() < 0.000005)
                );
            }
            assert_eq!(context.stats.changed, if batch == 2 { 1 } else { 0 });
            // Changed voxel (0,15,0) is a corner of its section: 7 dependents plus itself.
            assert_eq!(context.stats.compiled, if batch == 2 { 8 } else { 0 });
        }
        // A narrower active window still requests the diagonal columns only as dependencies.
        let mut narrow = TerrainContext::default();
        let mut narrow_output = scene();
        input = frame(1, 0., 0, [-1, 1], &events);
        input[8..12].copy_from_slice(&version.to_le_bytes());
        assert_eq!(requests(&mut narrow, &input).len(), 27);
        narrow.accept(&[&initial], &mut narrow_output).unwrap();
        assert_eq!(narrow.scheduler.active.len(), 3);
        assert_eq!(narrow.scheduler.cache.len(), 27);
        let expected = narrow_output.translate([0.; 3]).unwrap();
        assert!(
            expected
                .meshes
                .values()
                .all(|m| m.origin[0] == 0. && m.origin[2] == 0.)
        );
        assert!(expected.triangle_count() > 0);
    }
}
