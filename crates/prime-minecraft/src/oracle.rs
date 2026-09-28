//! Differential test input comes from each version's actual SectionCompiler in Fabric preLaunch.
//! The resources and lighting are controlled; this is not the optimized kernel's scalar reference.
use super::*;
use crate::tests::{frame, requests, scene};
use prime_scene::Triangle;

/// Keep the section origin in f64. Casting it to f32 hides local geometry errors far from spawn.
#[derive(Clone, Debug, PartialEq)]
struct ObservedTriangle {
    positions: [[f64; 3]; 3],
    uvs: [[f64; 2]; 3],
    colors: [[f64; 4]; 3],
    texture: u32,
    flags: u32,
}
fn triangles(scene: &SourceScene) -> Vec<ObservedTriangle> {
    triangles_at(scene, [0.; 3])
}
fn triangles_at(scene: &SourceScene, anchor: [f64; 3]) -> Vec<ObservedTriangle> {
    let snapshot = scene.translate(anchor).unwrap();
    let mut result = Vec::new();
    for mesh in snapshot.meshes.values() {
        for t in mesh.triangles.iter() {
            let positions = t
                .positions
                .map(|p| std::array::from_fn(|i| f64::from(p[i]) + mesh.origin[i]));
            // Preserve winding, corner attributes and multiplicity; only cyclic starting corner is irrelevant.
            let first = (0..3)
                .min_by(|&a, &b| compare(&positions[a], &positions[b]))
                .unwrap();
            result.push(ObservedTriangle {
                positions: std::array::from_fn(|i| positions[(first + i) % 3]),
                uvs: std::array::from_fn(|i| t.uvs[(first + i) % 3].map(f64::from)),
                colors: std::array::from_fn(|i| t.colors[(first + i) % 3].map(f64::from)),
                texture: t.texture_id,
                flags: t.flags,
            });
        }
    }
    // Fixed arrays: sorting a large real mesh must not allocate one Vec per comparison.
    result.sort_unstable_by(|a, b| {
        a.texture
            .cmp(&b.texture)
            .then(a.flags.cmp(&b.flags))
            .then_with(|| compare(&values(a), &values(b)))
    });
    result
}
fn values(t: &ObservedTriangle) -> [f64; 27] {
    let mut result = [0.; 27];
    for (out, value) in result.iter_mut().zip(
        t.positions
            .iter()
            .flatten()
            .chain(t.uvs.iter().flatten())
            .chain(t.colors.iter().flatten()),
    ) {
        *out = *value;
    }
    result
}
fn compare(a: &[f64], b: &[f64]) -> std::cmp::Ordering {
    a.iter()
        .zip(b)
        .map(|(a, b)| a.total_cmp(b))
        .find(|v| !v.is_eq())
        .unwrap_or(std::cmp::Ordering::Equal)
}
const ABS_TOLERANCE: f64 = 0.000005;
fn difference(expected: &[ObservedTriangle], actual: &[ObservedTriangle]) -> Result<(), String> {
    if expected.len() != actual.len() {
        return Err(format!(
            "triangle count original={} native={}",
            expected.len(),
            actual.len()
        ));
    }
    for (i, (a, b)) in expected.iter().zip(actual).enumerate() {
        if a.texture != b.texture || a.flags != b.flags {
            return Err(format!(
                "triangle {i}: material original=({}, {}) native=({}, {})",
                a.texture, a.flags, b.texture, b.flags
            ));
        }
        for (attribute, (a, b)) in values(a).iter().zip(values(b)).enumerate() {
            if !a.is_finite() || !b.is_finite() || (a - b).abs() > ABS_TOLERANCE {
                let (name, offset) = if attribute < 9 {
                    ("position", attribute)
                } else if attribute < 15 {
                    ("uv", attribute - 9)
                } else {
                    ("color", attribute - 15)
                };
                return Err(format!(
                    "triangle {i} {name}[{offset}]: original={a:.12} native={b:.12} abs_error={:.12}, tolerance={ABS_TOLERANCE}\noriginal={:?}\nnative={:?}",
                    (a - b).abs(),
                    expected[i],
                    actual[i]
                ));
            }
        }
    }
    Ok(())
}
fn suite_root(version: u32) -> std::path::PathBuf {
    let name = if version == 262 { "26.2" } else { "26.3" };
    let base = std::env::var_os("PRIME_SECTION_SUITE_ROOT").map(std::path::PathBuf::from);
    base.map(|p| p.join(format!("mc-{name}/section-oracle")))
        .unwrap_or_else(|| {
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
                "../../adapters/mc-{name}/build/routing-fixtures/section-oracle"
            ))
        })
}

fn workload_size(root: &std::path::Path, case: &str) -> (usize, usize) {
    let path = root.join(format!("{case}.workload.properties"));
    // Historical fixtures predate configurable workloads and always used 27 / 8 sections.
    if !path.exists() {
        return (27, 8);
    }
    let text = std::fs::read_to_string(path).unwrap();
    let count = |name: &str| {
        text.lines()
            .find_map(|line| line.strip_prefix(name))
            .unwrap()
            .parse::<usize>()
            .unwrap()
    };
    (count("requested="), count("edited="))
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

fn finish_tints(
    context: &mut TerrainContext,
    output: &mut SourceScene,
    root: &std::path::Path,
    case: &str,
) {
    replay_tints(context, output, root, case, None);
}

#[test]
#[ignore = "generate actual dual-version biome math and tint source fixtures"]
fn actual_vanilla_biome_math_and_tint_source_fields_match() {
    for version in [262, 263] {
        let root = suite_root(version);
        for variant in 0..2 {
            biome_source::check_math_fixture(
                &std::fs::read(root.join(format!("biome-math-{variant}.bin"))).unwrap(),
            );
        }
        let expected = std::fs::read(root.join("tint-sources.expected")).unwrap();
        let bytes = std::fs::read(root.join("tint-sources.bin")).unwrap();
        let pages = [bytes.as_slice()];
        let mut r = Reader::new(&pages).unwrap();
        assert_eq!(r.header(3).unwrap(), (version, 1, 1));
        let count = r.u64().unwrap() as usize;
        assert_eq!(count, 29);
        assert_eq!(r.i32().unwrap(), 0);
        let mut callbacks = 0;
        for (index, expected) in expected[4..].as_chunks::<4>().0.iter().enumerate() {
            let kind = r.u32().unwrap();
            callbacks += usize::from(kind == 0);
            let biome::Recipe::Color(value) = biome::Recipe::read(kind, r.u32().unwrap()).unwrap()
            else {
                panic!("unexpected biome source")
            };
            assert_eq!(
                value,
                u32::from_le_bytes(*expected),
                "MC {version} source {index}"
            );
        }
        assert_eq!(callbacks, 1); // The sole unknown source was called exactly once by the host oracle.
        assert_eq!(r.u32().unwrap(), 0);
        r.finish().unwrap();
    }
}
fn replay_tints(
    context: &mut TerrainContext,
    output: &mut SourceScene,
    root: &std::path::Path,
    case: &str,
    batch: Option<u64>,
) {
    for _ in 0..2 {
        if context.tint_requests().is_empty() {
            return;
        }
        let suffix = if context.tint_requests()[28] == 3 {
            "biome"
        } else {
            "tint"
        };
        let mut requests = std::fs::read(root.join(format!("{case}.{suffix}.requests")))
            .expect("missing source requests");
        let mut response = std::fs::read(root.join(format!("{case}.{suffix}")))
            .expect("missing actual host results");
        if let Some(batch) = batch {
            requests[..8].copy_from_slice(&batch.to_le_bytes());
            response[24..32].copy_from_slice(&batch.to_le_bytes());
        }
        assert!(
            context.tint_requests() == requests,
            "host/native request ordering: {case}/{suffix}; first differing byte {:?}, lengths {}/{}",
            context
                .tint_requests()
                .iter()
                .zip(&requests)
                .position(|(a, b)| a != b),
            context.tint_requests().len(),
            requests.len()
        );
        context.accept(&[&response], output).unwrap();
    }
    assert!(context.tint_requests().is_empty());
}

#[test]
#[ignore = "generate both actual SectionCompiler fixtures with the two Fabric cpuSmoke tasks"]
fn actual_section_compilers_match_native_geometry() {
    let mut failures = Vec::new();
    for version in [262, 263] {
        let name = if version == 262 { "26.2" } else { "26.3" };
        let root = suite_root(version);
        let manifest = std::fs::read_to_string(root.join("suite.properties"))
            .expect("generate the current section suite first");
        for line in [
            "format=1".to_owned(),
            format!("sourceVersion={}", wire::VERSION),
            format!("gameVersion={version}"),
        ] {
            assert!(
                manifest.lines().any(|v| v == line),
                "fixture manifest mismatch: {line}"
            );
        }
        let cases = std::fs::read_to_string(root.join("cases.txt"))
            .expect("run both Fabric cpuSmoke tasks first");
        for case in cases.lines() {
            let source = std::fs::read(root.join(format!("{case}.source"))).unwrap();
            let expected = triangles(&reference(
                &std::fs::read(root.join(format!("{case}.expected"))).unwrap(),
            ));
            if case.starts_with("bench_") {
                let parallel = triangles(&reference(
                    &std::fs::read(root.join(format!("{case}.parallel.expected")))
                        .expect("parallel reference output missing"),
                ));
                difference(&expected, &parallel).unwrap_or_else(|e| {
                    panic!("{name}/{case}: parallel MC reference differs from serial: {e}")
                });
            }
            let input = std::fs::read(root.join(format!("{case}.frame"))).unwrap();
            let mut context = TerrainContext::default();
            let mut output = scene();
            assert_eq!(
                requests(&mut context, &input).len(),
                workload_size(&root, case).0
            );
            context.accept(&[&source], &mut output).unwrap();
            finish_tints(&mut context, &mut output, &root, case);
            let actual = triangles(&output);
            if let Err(error) = difference(&expected, &actual) {
                failures.push(format!("{name}/{case}: {error}"));
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
        let root = suite_root(version);
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
            difference(&expected, &actual).unwrap();
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

#[test]
fn oracle_rejects_material_winding_multiplicity_and_numerical_drift() {
    let t = ObservedTriangle {
        positions: [
            [30_000_000.125, 0., 0.],
            [30_000_000.875, 0., 0.],
            [30_000_000.5, 1., 0.],
        ],
        uvs: [[0.; 2]; 3],
        colors: [[1.; 4]; 3],
        texture: (1 << 24) + 1,
        flags: 1,
    };
    let expected = [t.clone()];
    let mut actual = expected.clone();
    actual[0].positions[0][0] += 0.000001;
    difference(&expected, &actual).unwrap();
    actual[0].positions[0][0] += 0.001;
    assert!(
        difference(&expected, &actual)
            .unwrap_err()
            .contains("position")
    );
    actual = expected.clone();
    actual[0].texture -= 1;
    assert!(difference(&expected, &actual).is_err());
    actual = expected.clone();
    actual[0].positions.swap(1, 2);
    assert!(difference(&expected, &actual).is_err());
    actual = expected.clone();
    actual[0].uvs[0][0] = f64::NAN;
    assert!(difference(&expected, &actual).is_err());
    assert!(difference(&expected, &[]).is_err());
}

#[test]
#[ignore = "generate the complete dual-version section suite first"]
fn benchmark_edits_match_original_and_unchanged_inputs_do_no_compile() {
    for version in [262u32, 263] {
        let root = suite_root(version);
        let cases = std::fs::read_to_string(root.join("cases.txt")).unwrap();
        let names: Vec<_> = cases
            .lines()
            .filter(|c| c.starts_with("bench_") && !c.ends_with("_edited"))
            .collect();
        // cpuSmoke's smaller historical entry deliberately omits throughput cases.
        if names.is_empty() {
            assert!(
                std::env::var_os("PRIME_SECTION_SUITE_ROOT").is_none(),
                "full suite has no benchmarks"
            );
            continue;
        }
        for name in names {
            let (initial_count, edited_count) = workload_size(&root, name);
            let mut context = TerrainContext::default();
            let mut output = scene();
            let input = std::fs::read(root.join(format!("{name}.frame"))).unwrap();
            assert_eq!(requests(&mut context, &input).len(), initial_count);
            let source = std::fs::read(root.join(format!("{name}.source"))).unwrap();
            context.accept(&[&source], &mut output).unwrap();
            finish_tints(&mut context, &mut output, &root, name);
            let expected = triangles(&reference(
                &std::fs::read(root.join(format!("{name}_edited.expected"))).unwrap(),
            ));
            for batch in [2, 3] {
                let suffix = if batch == 2 { "edit" } else { "unchanged" };
                let input = std::fs::read(root.join(format!("{name}.{suffix}.frame"))).unwrap();
                let edited = std::fs::read(root.join(format!("{name}.{suffix}.source"))).unwrap();
                assert_eq!(requests(&mut context, &input).len(), edited_count);
                context.accept(&[&edited], &mut output).unwrap();
                replay_cached_tints(
                    &mut context,
                    &mut output,
                    &root,
                    &format!("{name}_edited"),
                    batch,
                );
                difference(&expected, &triangles(&output)).unwrap();
                assert_eq!(
                    context.stats.changed,
                    if batch == 2 { edited_count } else { 0 }
                );
                assert_eq!(
                    context.stats.compiled,
                    if batch == 2 { edited_count } else { 0 }
                );
            }
        }
    }
}

#[test]
fn oracle_observes_local_precision_at_large_world_origins() {
    let mut source = scene();
    let t = Triangle {
        positions: [[0.125, 0., 0.], [0.875, 0., 0.], [0.5, 1., 0.]],
        uvs: [[0.; 2]; 3],
        colors: [[1.; 4]; 3],
        texture_id: (1 << 24) + 1,
        flags: 0,
    };
    let compiled = source.prepare_compiled(7, [30_000_000., 0., 0.], [vec![t], vec![], vec![]]);
    source.publish_compiled(1, 1, vec![compiled], &[]).unwrap();
    let observed = triangles_at(&source, [30_000_000., 0., 0.]);
    assert_eq!(observed.len(), 1);
    assert_eq!(observed[0].positions[0][0], 30_000_000.125);
    assert_eq!(observed[0].positions[1][0], 30_000_000.875);
    assert_eq!(observed[0].texture, (1 << 24) + 1);
}

#[test]
#[ignore = "generate the dual-version tint fixtures first"]
fn biome_only_invalidation_matches_vanilla_without_resending_sections() {
    for version in [262u32, 263] {
        let root = suite_root(version);
        for (base, changed) in [0, 2, 7]
            .map(|radius| {
                (
                    format!("tint_biomes_{radius}_0"),
                    format!("tint_biomes_{radius}_1"),
                )
            })
            .into_iter()
            .chain(std::iter::once((
                "bench_tinted".into(),
                "tint_dense_biome_changed".into(),
            )))
        {
            let input = std::fs::read(root.join(format!("{base}.frame"))).unwrap();
            let source = std::fs::read(root.join(format!("{base}.source"))).unwrap();
            let mut ctx = TerrainContext::default();
            let mut output = scene();
            requests(&mut ctx, &input);
            ctx.accept(&[&source], &mut output).unwrap();
            finish_tints(&mut ctx, &mut output, &root, &base);
            let before = triangles(&output);
            let expected = triangles(&reference(
                &std::fs::read(root.join(format!("{changed}.expected"))).unwrap(),
            ));
            assert!(
                difference(&before, &expected).is_err(),
                "biome fixture must change actual colors"
            );
            let window = input[32..76].to_vec();
            let mut input = frame(2, 0., 1, [-1, 1], &[(7, Section(0, 0, 0))]);
            // Keep the generated workload's window when testing only color invalidation.
            input[32..76].copy_from_slice(&window);
            input[8..12].copy_from_slice(&version.to_le_bytes());
            assert!(requests(&mut ctx, &input).is_empty());
            let mut source = crate::tests::header(2, 2);
            source[8..12].copy_from_slice(&version.to_le_bytes());
            u32_to(&mut source, 0);
            ctx.accept(&[&source], &mut output).unwrap();
            assert_eq!(ctx.stats.changed, 0);
            assert!(ctx.stats.compiled > 0 && ctx.stats.compiled < workload_size(&root, &base).0);
            replay_tints(&mut ctx, &mut output, &root, &changed, Some(2));
            difference(&expected, &triangles(&output)).unwrap();
            // An unrelated biome arrival cannot rebuild these sections.
            input = frame(3, 0., 1, [-1, 1], &[(6, Section(30, 0, 30))]);
            input[32..76].copy_from_slice(&window);
            input[8..12].copy_from_slice(&version.to_le_bytes());
            assert!(requests(&mut ctx, &input).is_empty());
            source[24..32].copy_from_slice(&3u64.to_le_bytes());
            ctx.accept(&[&source], &mut output).unwrap();
            assert_eq!(ctx.stats.compiled, 0);
            assert!(ctx.tint_requests().is_empty());
        }
    }
}

/// Select actual host results by request identity. Cold fixtures still use the
/// strict ordered replay above; incremental caches legitimately request a subset.
fn replay_cached_tints(
    ctx: &mut TerrainContext,
    output: &mut SourceScene,
    root: &std::path::Path,
    case: &str,
    batch: u64,
) {
    for _ in 0..2 {
        let request = ctx.tint_requests();
        if request.is_empty() {
            return;
        }
        let suffix = if request[28] == 3 { "biome" } else { "tint" };
        let keys = std::fs::read(root.join(format!("{case}.{suffix}.requests"))).unwrap();
        let values = std::fs::read(root.join(format!("{case}.{suffix}"))).unwrap();
        let mut response = values[..44].to_vec();
        response[24..32].copy_from_slice(&batch.to_le_bytes());
        response[32..40].copy_from_slice(&request[8..16]);
        if suffix == "tint" {
            let count = (keys.len() - 32) / 20;
            let end = 44 + count * 8;
            let lookup: HashMap<_, _> = keys[32..]
                .as_chunks::<20>()
                .0
                .iter()
                .zip(values[44..end].as_chunks::<8>().0.iter())
                .collect();
            for key in request[32..].as_chunks::<20>().0 {
                response.extend_from_slice(
                    *lookup
                        .get(key)
                        .expect("incremental tint outside cold source union"),
                );
            }
            if request[28] == 2 {
                response.extend_from_slice(&values[end..]);
            } else {
                u32_to(&mut response, 0);
            }
        } else {
            let count = u32::from_le_bytes(values[40..44].try_into().unwrap()) as usize;
            let end = 44 + count * 32;
            response.extend_from_slice(&values[44..end]);
            let mut ids = values[end..].as_chunks::<4>().0.iter();
            let mut lookup = HashMap::new();
            for page in keys[32..].as_chunks::<20>().0 {
                let section: [u8; 12] = page[..12].try_into().unwrap();
                let mut mask = u64::from_le_bytes(page[12..20].try_into().unwrap());
                while mask != 0 {
                    lookup.insert((section, mask.trailing_zeros()), ids.next().unwrap());
                    mask &= mask - 1;
                }
            }
            assert!(ids.next().is_none());
            for page in request[32..].as_chunks::<20>().0 {
                let section: [u8; 12] = page[..12].try_into().unwrap();
                let mut mask = u64::from_le_bytes(page[12..20].try_into().unwrap());
                while mask != 0 {
                    response.extend_from_slice(
                        *lookup
                            .get(&(section, mask.trailing_zeros()))
                            .expect("incremental quart outside cold source union"),
                    );
                    mask &= mask - 1;
                }
            }
        }
        ctx.accept(&[&response], output).unwrap();
    }
    assert!(ctx.tint_requests().is_empty());
}

#[test]
#[ignore = "generate dual-version column biome fixtures first"]
fn column_biome_updates_reuse_samples_and_match_actual_vanilla() {
    for version in [262u32, 263] {
        let root = suite_root(version);
        for (base, changed) in [0, 2, 7]
            .map(|radius| {
                (
                    format!("tint_biomes_{radius}_0"),
                    format!("tint_biomes_{radius}_3"),
                )
            })
            .into_iter()
            .chain(std::iter::once((
                "bench_tinted".into(),
                "tint_dense_biome_columns".into(),
            )))
        {
            let original = std::fs::read(root.join(format!("{base}.frame"))).unwrap();
            let source = std::fs::read(root.join(format!("{base}.source"))).unwrap();
            let mut ctx = TerrainContext::default();
            let mut output = scene();
            requests(&mut ctx, &original);
            ctx.accept(&[&source], &mut output).unwrap();
            finish_tints(&mut ctx, &mut output, &root, &base);
            let before = triangles(&output);
            let cold_samples = ctx.stats.biome_samples;
            let expected = triangles(&reference(
                &std::fs::read(root.join(format!("{changed}.expected"))).unwrap(),
            ));
            assert!(
                difference(&before, &expected).is_err(),
                "column fixture must change colors: {base}"
            );
            let window = schedule::FrameInput::read(&[&original]).unwrap();
            let mut events = Vec::new();
            for x in (0..=window.source.x1 + 1).step_by(3) {
                for z in (0..=window.source.z1 + 1).step_by(3) {
                    events.push((6, Section(x, 0, z)));
                }
            }
            for (batch, case, expected) in [(2, &changed, &expected), (3, &base, &before)] {
                let mut input = frame(batch, 0., 1, [-1, 1], &events);
                input[32..76].copy_from_slice(&original[32..76]);
                input[8..12].copy_from_slice(&version.to_le_bytes());
                assert!(requests(&mut ctx, &input).is_empty());
                let mut response = crate::tests::header(2, batch);
                response[8..12].copy_from_slice(&version.to_le_bytes());
                u32_to(&mut response, 0);
                ctx.accept(&[&response], &mut output).unwrap();
                replay_cached_tints(&mut ctx, &mut output, &root, case, batch);
                assert!(ctx.stats.biome_samples < cold_samples);
                if base == "bench_tinted" {
                    assert!(ctx.stats.biome_cached_samples > 0 && ctx.stats.biome_hits > 0);
                }
                difference(expected, &triangles(&output)).unwrap();
            }
        }
    }
}
