use super::*;
use prime_scene::Triangle;
use prime_scene::protocol::{ABI_VERSION, MAGIC as SCENE_MAGIC};

#[test]
fn packed_palette_validation_matches_scalar_for_all_widths_and_padding() {
    for bits in 1..=32 {
        let maximum = (1_u64 << bits).min(4096) as usize;
        let half = 1_usize << (bits - 1);
        let mut counts = vec![
            1,
            2,
            3,
            5,
            half.saturating_sub(1),
            half,
            half + 1,
            maximum.saturating_sub(1),
            maximum,
        ];
        counts.retain(|&n| n != 0 && n <= maximum);
        counts.sort_unstable();
        counts.dedup();
        for count in counts {
            let per_word = 64 / bits as usize;
            let mut data = SectionData {
                palette: vec![0; count],
                storage: vec![u64::MAX; 4096_usize.div_ceil(per_word)],
                bits,
                per_word,
            };
            let mask = (1_u64 << bits) - 1;
            let mut random = 173_u64;
            for index in 0..4096 {
                random = random.wrapping_mul(6364136223846793005).wrapping_add(1);
                let shift = index % per_word * bits as usize;
                let word = &mut data.storage[index / per_word];
                *word = (*word & !(mask << shift)) | ((random % count as u64) << shift);
            }
            // Unused high bits, and unused fields of a partial final word, stay all-ones.
            assert!(data.valid_indices(), "bits={bits}, count={count}");
            assert!((0..4096).all(|i| data.index(i) < count as u32));
            if count as u64 > mask {
                continue;
            }
            for index in [
                0,
                per_word - 1,
                per_word,
                8 * per_word - 1,
                8 * per_word,
                4095,
            ] {
                let shift = index % per_word * bits as usize;
                let word = index / per_word;
                let saved = data.storage[word];
                for invalid in [count as u64, mask] {
                    data.storage[word] = (saved & !(mask << shift)) | (invalid << shift);
                    assert!(
                        !data.valid_indices(),
                        "bits={bits}, count={count}, index={index}, value={invalid}"
                    );
                    assert!(data.index(index) >= count as u32);
                }
                data.storage[word] = saved;
            }
        }
    }
}

pub(super) fn header(kind: u32, batch: u64) -> Vec<u8> {
    let mut v = Vec::new();
    for n in [wire::MAGIC, wire::VERSION, 262, kind] {
        u32_to(&mut v, n);
    }
    u64_to(&mut v, 1);
    u64_to(&mut v, batch);
    v
}
pub(super) fn frame(
    batch: u64,
    center: f64,
    radius: i32,
    ys: [i32; 2],
    events: &[(u32, Section)],
) -> Vec<u8> {
    let mut v = header(1, batch);
    u64_to(&mut v, center.to_bits());
    u64_to(&mut v, 0f64.to_bits());
    for n in [radius, ys[0], ys[1], -64, 64, -64, 64] {
        u32_to(&mut v, n as u32);
    }
    for &(kind, s) in events {
        for n in [kind, s.0 as u32, s.1 as u32, s.2 as u32] {
            u32_to(&mut v, n);
        }
    }
    u32_to(&mut v, 0);
    v
}
pub(super) fn requests(context: &mut TerrainContext, input: &[u8]) -> Vec<Section> {
    let data = context.plan(&[input], 1).unwrap();
    let count = u64::from_le_bytes(data[8..16].try_into().unwrap()) as usize;
    (0..count)
        .map(|i| {
            let p = &data[32 + i * 16..];
            Section(
                i32::from_le_bytes(p[0..4].try_into().unwrap()),
                i32::from_le_bytes(p[4..8].try_into().unwrap()),
                i32::from_le_bytes(p[8..12].try_into().unwrap()),
            )
        })
        .collect()
}
pub(super) fn string(v: &mut Vec<u8>, s: &str) {
    u32_to(v, s.len() as u32);
    v.extend(s.as_bytes());
    while !v.len().is_multiple_of(4) {
        v.push(0);
    }
}
pub(super) fn state_source(v: &mut Vec<u8>, flags: u32) {
    for _ in 0..6 {
        u32_to(v, u32::from(flags & 4 != 0));
    }
    u32_to(v, 0);
    string(v, "minecraft:empty");
    for _ in 0..3 {
        u32_to(v, 0);
    }
}
fn response(batch: u64, requests: &[Section], choose: impl Fn(Section) -> Option<bool>) -> Vec<u8> {
    let mut v = header(2, batch);
    for (id, flags, name) in [(0, 1, "minecraft:air"), (1, 4, "minecraft:stone")] {
        for n in [1, id, flags, 0] {
            u32_to(&mut v, n);
        }
        string(&mut v, name);
        state_source(&mut v, flags);
    }
    for &s in requests {
        for n in [3, s.0 as u32, s.1 as u32, s.2 as u32] {
            u32_to(&mut v, n);
        }
        if let Some(solid) = choose(s) {
            for n in [1, 0, 1, 0, u32::from(solid)] {
                u32_to(&mut v, n);
            }
        } else {
            u32_to(&mut v, 0);
        }
    }
    u32_to(&mut v, 0);
    v
}
pub(super) fn scene() -> SourceScene {
    fn h(op: u32) -> Vec<u8> {
        let mut v = Vec::new();
        for n in [SCENE_MAGIC, ABI_VERSION, op, 0] {
            u32_to(&mut v, n);
        }
        u64_to(&mut v, 1);
        v
    }
    let mut scene = SourceScene::default();
    scene.submit(&h(1)).unwrap();
    let mut tex = h(4);
    for n in [1, 1, 1, 0, u32::MAX] {
        u32_to(&mut tex, n);
    }
    scene.submit(&tex).unwrap();
    scene
}
#[test]
fn full_cell_waits_for_all_64_and_unchanged_frames_do_no_work() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let mut events = Vec::new();
    for x in 0..4 {
        for z in 0..4 {
            events.push((1, Section(x, 0, z)));
        }
    }
    let mut input = frame(1, 16., 3, [0, 3], &events);
    // Camera Z=16 makes the symmetric radius contain the complete cell.
    input[40..48].copy_from_slice(&16f64.to_bits().to_le_bytes());
    let req = requests(&mut ctx, &input);
    assert_eq!(req.len(), 64);
    let missing = Section(3, 3, 3);
    ctx.accept(
        &[&response(1, &req, |s| {
            if s == missing { None } else { Some(false) }
        })],
        &mut scene,
    )
    .unwrap();
    assert!(scene.translate([0.; 3]).unwrap().ready_terrain.is_empty());
    input = frame(2, 16., 3, [0, 3], &[(3, missing)]);
    input[40..48].copy_from_slice(&16f64.to_bits().to_le_bytes());
    let req = requests(&mut ctx, &input);
    assert_eq!(req, vec![missing]);
    ctx.accept(&[&response(2, &req, |_| Some(true))], &mut scene)
        .unwrap();
    assert_eq!(scene.translate([0.; 3]).unwrap().ready_terrain.len(), 1);
    assert_eq!(
        scene.translate([0.; 3]).unwrap().triangle_count(),
        6 * 256 * 2
    );
    let revision = scene.revision();
    for batch in 3..20 {
        input = frame(batch, 16., 3, [0, 3], &[]);
        input[40..48].copy_from_slice(&16f64.to_bits().to_le_bytes());
        let req = requests(&mut ctx, &input);
        assert!(req.is_empty());
        ctx.accept(&[&response(batch, &req, |_| unreachable!())], &mut scene)
            .unwrap();
        assert_eq!(
            (ctx.stats.requested, ctx.stats.compiled, ctx.stats.jobs),
            (0, 0, 0)
        );
        assert_eq!(scene.revision(), revision);
    }
}
#[test]
fn halo_is_dependency_only_and_neighbor_change_invalidates_surface() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let a = Section(0, 0, 0);
    let b = Section(1, 0, 0);
    let req = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, a), (1, b)]));
    assert_eq!(req.len(), 2);
    ctx.accept(&[&response(1, &req, |s| Some(s == a))], &mut scene)
        .unwrap();
    assert_eq!(scene.translate([0.; 3]).unwrap().triangle_count(), 3072);
    assert_eq!(ctx.scheduler.active.len(), 1);
    let dirty = vec![(3, b); 10000];
    let req = requests(&mut ctx, &frame(2, 0., 0, [0, 0], &dirty));
    assert_eq!(req, vec![b]);
    ctx.accept(&[&response(2, &req, |_| Some(true))], &mut scene)
        .unwrap();
    assert_eq!(scene.translate([0.; 3]).unwrap().triangle_count(), 2560);
    assert_eq!(ctx.stats.compiled, 1);
    let req = requests(&mut ctx, &frame(3, 0., 0, [0, 0], &[(3, b)]));
    ctx.accept(&[&response(3, &req, |_| Some(true))], &mut scene)
        .unwrap();
    assert_eq!(ctx.stats.compiled, 0);
    let req = requests(&mut ctx, &frame(4, 0., 0, [0, 0], &[(2, b)]));
    assert!(req.is_empty());
    ctx.accept(&[&response(4, &req, |_| unreachable!())], &mut scene)
        .unwrap();
    assert_eq!(scene.translate([0.; 3]).unwrap().triangle_count(), 3072);
}
#[test]
fn move_retires_old_sources_and_negative_coordinates_are_floor_divided() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let s = Section(-1, 0, 0);
    let req = requests(&mut ctx, &frame(1, -0.1, 0, [0, 0], &[(1, s)]));
    assert_eq!(req, vec![s]);
    ctx.accept(&[&response(1, &req, |_| Some(true))], &mut scene)
        .unwrap();
    assert_eq!(
        scene
            .translate([0.; 3])
            .unwrap()
            .meshes
            .values()
            .next()
            .unwrap()
            .origin,
        [-16., 0., 0.]
    );
    let req = requests(&mut ctx, &frame(2, 10000., 0, [0, 0], &[]));
    assert!(req.is_empty());
    ctx.accept(&[&response(2, &req, |_| unreachable!())], &mut scene)
        .unwrap();
    assert!(scene.translate([0.; 3]).unwrap().meshes.is_empty());
    assert!(ctx.sections.is_empty());
}
#[test]
fn page_boundaries_and_response_identity_are_checked_before_publication() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let input = frame(1, 0., 0, [0, 0], &[(1, Section(0, 0, 0))]);
    let req = requests(&mut ctx, &input);
    assert!(ctx.plan(&[&input], 1).is_err());
    assert!(
        ctx.accept(&[&response(2, &req, |_| Some(true))], &mut scene)
            .is_err()
    );
    let output = response(1, &req, |_| Some(true));
    let revision = scene.revision();
    assert!(
        ctx.accept(&[&output[..output.len() - 1]], &mut scene)
            .is_err()
    );
    assert_eq!(scene.revision(), revision);
    let pages: Vec<_> = output.chunks(7).collect();
    ctx.accept(&pages, &mut scene).unwrap();
    assert!(scene.translate([0.; 3]).unwrap().triangle_count() > 0);
}
#[test]
fn local_and_global_packing_decode_without_java_expansion() {
    for bits in [0, 1, 4, 5, 8, 9, 15, 32] {
        let mut v = Vec::new();
        let local = bits <= 8;
        u32_to(&mut v, bits);
        u32_to(&mut v, if local { 1 } else { 0 });
        let words = 64u32
            .checked_div(bits)
            .map_or(0, |per_word| 4096usize.div_ceil(per_word as usize));
        u32_to(&mut v, words as u32);
        if local {
            u32_to(&mut v, 123);
        }
        for _ in 0..words {
            u64_to(&mut v, 0);
        }
        let pages = [v.as_slice()];
        let mut r = Reader::new(&pages).unwrap();
        let data = SectionData::read(&mut r).unwrap();
        assert_eq!(data.state(4095), if local { 123 } else { 0 });
        if local && bits != 0 {
            let per_word = 64 / bits as usize;
            let used = 4096 - (words - 1) * per_word;
            let offset = 16 + (words - 1) * 8;
            if used < per_word {
                let padding = u64::MAX << (used * bits as usize);
                v[offset..offset + 8].copy_from_slice(&padding.to_le_bytes());
                let pages = [v.as_slice()];
                assert!(SectionData::read(&mut Reader::new(&pages).unwrap()).is_ok());
            }
            // Invalid final used lane must fail, while unused packed padding is not a cell.
            let invalid = 1u64 << ((used - 1) * bits as usize);
            v[offset..offset + 8].copy_from_slice(&invalid.to_le_bytes());
            let pages: Vec<_> = v.chunks(7).collect();
            assert!(SectionData::read(&mut Reader::new(&pages).unwrap()).is_err());
        }
    }
}

#[test]
#[ignore = "first generate both MC fixtures with :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke"]
fn actual_mc_field_packets_produce_renderable_cells_and_exact_quad_values() {
    for version in [262, 263] {
        let name = if version == 262 { "26.2" } else { "26.3" };
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
            "../../adapters/mc-{name}/build/routing-fixtures/mc-section-source.bin"
        ));
        let bytes = std::fs::read(path).expect("generate the matching real MC field fixture first");
        let mut ctx = TerrainContext::default();
        let mut scene = scene();
        let mut events = Vec::new();
        for x in 0..4 {
            for z in 0..4 {
                events.push((1, Section(x, 0, z)));
            }
        }
        let mut input = frame(1, 16., 3, [0, 3], &events);
        input[8..12].copy_from_slice(&u32::to_le_bytes(version));
        input[40..48].copy_from_slice(&16f64.to_bits().to_le_bytes());
        assert_eq!(requests(&mut ctx, &input).len(), 64);
        ctx.accept(&[&bytes], &mut scene).unwrap();
        let translated = scene.translate([0.; 3]).unwrap();
        assert_eq!(translated.ready_terrain.len(), 1);
        assert_eq!(translated.triangle_count(), 2);
        let mesh = translated.meshes.values().next().unwrap();
        let triangle = &mesh.triangles.triangle(0);
        assert_eq!(
            triangle.positions,
            [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.]]
        );
        assert_eq!(triangle.uvs, [[0.1, 0.2], [0.3, 0.4], [0.5, 0.6]]);
        assert_eq!(triangle.colors, [[1.; 4]; 3]);
        assert_eq!(triangle.texture_id, 1);
        assert_eq!(triangle.flags, 1);
    }
}

#[test]
fn replacement_and_catalog_reset_refresh_sources_without_idle_polling() {
    let mut ctx = TerrainContext::default();
    assert!(
        ctx.diagnostics()
            .contains("request_batches=0 response_batches=0")
    );
    let mut scene = scene();
    let a = Section(0, 0, 0);
    let req = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, a)]));
    ctx.accept(&[&response(1, &req, |_| None)], &mut scene)
        .unwrap();
    let req = requests(&mut ctx, &frame(2, 0., 0, [0, 0], &[]));
    assert!(req.is_empty());
    ctx.accept(&[&response(2, &req, |_| unreachable!())], &mut scene)
        .unwrap();
    let req = requests(&mut ctx, &frame(3, 0., 0, [0, 0], &[(2, a), (1, a)]));
    assert_eq!(req, [a]);
    ctx.accept(&[&response(3, &req, |_| Some(true))], &mut scene)
        .unwrap();
    let req = requests(&mut ctx, &frame(4, 0., 0, [0, 0], &[(2, a), (1, a)]));
    assert_eq!(req, [a]);
    assert_eq!(
        u64::from_le_bytes(ctx.requests[16..24].try_into().unwrap()),
        1
    );
    ctx.accept(&[&response(4, &req, |_| Some(false))], &mut scene)
        .unwrap();
    assert_eq!(scene.translate([0.; 3]).unwrap().triangle_count(), 0);
    let req = requests(&mut ctx, &frame(5, 0., 0, [0, 0], &[(4, a)]));
    assert_eq!(req, [a]);
    ctx.accept(&[&response(5, &req, |_| Some(false))], &mut scene)
        .unwrap();
    assert_eq!(ctx.stats.compiled, 1); // Equal palette does not prove equal resource content after reload.
    assert!(
        ctx.diagnostics()
            .contains("request_batches=1 response_batches=1")
    );
    let req = requests(&mut ctx, &frame(6, 0., 0, [0, 0], &[(5, a), (1, a)]));
    assert!(req.is_empty()); // Raw inventory refresh is not a new chunk packet.
    ctx.accept(&[&response(6, &req, |_| unreachable!())], &mut scene)
        .unwrap();
    let req = requests(&mut ctx, &frame(7, 0., 0, [0, 0], &[(1, a)]));
    assert_eq!(req, [a]); // An actual packet can replace an already loaded column.
    ctx.accept(&[&response(7, &req, |_| Some(true))], &mut scene)
        .unwrap();
    assert!(scene.translate([0.; 3]).unwrap().triangle_count() > 0);
}

#[test]
fn synchronous_workers_preserve_geometry_order_and_values() {
    fn compile(threads: usize, mode: &str) -> Vec<Triangle> {
        let mut ctx = TerrainContext {
            workers: Some(CpuWorkers::new(threads).unwrap()),
            ..Default::default()
        };
        let mut scene = scene();
        let req = requests(
            &mut ctx,
            &frame(
                1,
                0.,
                1,
                [0, 1],
                &[(1, Section(0, 0, 0)), (1, Section(1, 0, 0))],
            ),
        );
        ctx.accept(&[&crate::perf::packet(1, &req, mode, false)], &mut scene)
            .unwrap();
        test_tints(&mut ctx, &mut scene);
        assert_eq!(ctx.stats.jobs, 16);
        scene
            .translate([0.; 3])
            .unwrap()
            .meshes
            .values()
            .flat_map(|m| m.triangles.iter())
            .collect()
    }
    for mode in ["dense", "terrain", "decorated"] {
        let one = compile(1, mode);
        let many = compile(4, mode);
        assert!(!one.is_empty());
        assert_eq!(one.len(), many.len());
        for (a, b) in one.iter().zip(&many) {
            assert_eq!(a.positions, b.positions);
            assert_eq!(a.uvs, b.uvs);
            assert_eq!(a.colors, b.colors);
            assert_eq!(a.texture_id, b.texture_id);
            assert_eq!(a.flags, b.flags);
        }
    }
}

#[test]
fn lowered_slabs_match_scalar_geometry_for_models_palettes_and_halos() {
    let mut ctx = TerrainContext::default();
    let mut source = scene();
    let key = Section(-1, 0, -1);
    let events: Vec<_> = (-2..=0)
        .flat_map(|x| (-2..=0).map(move |z| (1, Section(x, 0, z))))
        .collect();
    let req = requests(&mut ctx, &frame(1, -16., 2, [-1, 1], &events));
    ctx.accept(
        &[&crate::perf::packet(1, &req, "terrain", false)],
        &mut source,
    )
    .unwrap();
    // Weighted, multipart, missing and recursion-limit paths remain deterministic native defaults.
    ctx.catalog
        .models
        .insert(3, model::Model::Weighted(vec![(1, 1), (3, 2)], 4));
    ctx.catalog
        .models
        .insert(4, model::Model::Multipart(vec![3, 1]));
    for id in 10..76 {
        ctx.catalog.models.insert(id, model::Model::Alias(id + 1));
    }
    ctx.catalog
        .models
        .insert(76, model::Model::Mesh(Vec::new()));
    ctx.catalog.prepare();
    let same = |a: &Triangle, b: &Triangle| {
        assert_eq!(
            a.positions.map(|v| v.map(f32::to_bits)),
            b.positions.map(|v| v.map(f32::to_bits))
        );
        assert_eq!(
            a.uvs.map(|v| v.map(f32::to_bits)),
            b.uvs.map(|v| v.map(f32::to_bits))
        );
        assert_eq!(
            a.colors.map(|v| v.map(f32::to_bits)),
            b.colors.map(|v| v.map(f32::to_bits))
        );
        assert_eq!((a.texture_id, a.flags), (b.texture_id, b.flags));
    };
    for model_id in [1, 2, 3, 4, 10, 999] {
        ctx.catalog.states.get_mut(&1).unwrap().model = model_id;
        for bits in [0, 4, 5, 9, 15, 32] {
            for &s in &req {
                let ids: Vec<_> = (0..4096)
                    .map(|i| {
                        if bits == 0 {
                            1
                        } else {
                            ((i * 31 + i / 7 + s.0.unsigned_abs() as usize) % 5) as u32
                        }
                    })
                    .collect();
                let global = bits >= 9;
                let per_word = 64u32.checked_div(bits).unwrap_or(0) as usize;
                let mut data = SectionData {
                    palette: if bits == 0 {
                        vec![1]
                    } else if global {
                        vec![]
                    } else {
                        (0..5).collect()
                    },
                    storage: Vec::new(),
                    bits,
                    per_word,
                };
                if bits != 0 {
                    data.storage = ids
                        .chunks(per_word)
                        .map(|values| {
                            values.iter().enumerate().fold(0, |word, (i, &v)| {
                                word | ((v as u64) << (i * bits as usize))
                            })
                        })
                        .collect();
                }
                ctx.sections.insert(s, data);
            }
            // One missing neighbor exercises the explicit open halo boundary.
            ctx.sections.remove(&Section(-2, 0, -1));
            for first_y in [0, 4, 8, 12] {
                let make = || Job {
                    key,
                    first_y,
                    layers: Default::default(),
                    hacks: Default::default(),
                    compiled: None,
                    tints: Default::default(),
                    color_start: 0,
                };
                let mut expected = make();
                let mut actual = make();
                crate::reference::compile_slab(&mut expected, &ctx.catalog, &ctx.sections);
                compile_slab(&mut actual, &ctx.catalog, &ctx.sections);
                for (a, b) in expected.layers.iter().zip(&actual.layers) {
                    assert_eq!(
                        a.len(),
                        b.len(),
                        "model={model_id} bits={bits} first_y={first_y}"
                    );
                    for (a, b) in a.iter().zip(b) {
                        for (a, b) in a.triangles().iter().zip(b.triangles().iter()) {
                            same(a, b);
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn column_deltas_match_complete_window_membership_across_height_and_inventory_changes() {
    let mut ctx = TerrainContext::default();
    let mut source = scene();
    let mut loaded = BTreeSet::new();
    for batch in 1..70 {
        let x = (batch % 11) as i32 - 5;
        let z = (batch * 3 % 13) as i32 - 6;
        let mut events = vec![(1, Section(x, 0, z)), (2, Section(x - 1, 0, z))];
        loaded.insert((x, z));
        loaded.remove(&(x - 1, z));
        if batch % 9 == 0 {
            events.push((5, Section(0, 0, 0)));
            events.extend(loaded.iter().map(|&(x, z)| (1, Section(x, 0, z))));
        }
        let ys = if batch % 7 < 3 { [-2, 3] } else { [-1, 2] };
        let camera = if batch % 2 == 0 { 0. } else { -32. };
        let input = frame(batch, camera, 3, ys, &events);
        let req = requests(&mut ctx, &input);
        let cx = (camera / 16.) as i32;
        let columns: BTreeSet<_> = loaded
            .iter()
            .copied()
            .filter(|&(x, z)| (cx - 3..=cx + 3).contains(&x) && (-3..=3).contains(&z))
            .collect();
        let active: HashSet<_> = columns
            .iter()
            .flat_map(|&(x, z)| (ys[0]..=ys[1]).map(move |y| Section(x, y, z)))
            .collect();
        let mut cache = active.clone();
        for &s in &active {
            cache.extend(
                s.halo()
                    .into_iter()
                    .filter(|n| (ys[0]..=ys[1]).contains(&n.1) && loaded.contains(&(n.0, n.2))),
            );
        }
        assert_eq!(ctx.scheduler.active, active);
        assert_eq!(ctx.scheduler.cache, cache);
        ctx.accept(&[&response(batch, &req, |_| Some(false))], &mut source)
            .unwrap();
        assert_eq!(ctx.sections.keys().copied().collect::<HashSet<_>>(), cache);
    }
}

#[test]
fn interior_mutations_do_not_invalidate_neighbors_but_changed_boundary_faces_do() {
    let mut ctx = TerrainContext::default();
    let mut source = scene();
    let a = Section(0, 0, 0);
    let b = Section(1, 0, 0);
    let req = requests(&mut ctx, &frame(1, 0., 1, [0, 0], &[(1, a), (1, b)]));
    ctx.accept(&[&response(1, &req, |_| Some(true))], &mut source)
        .unwrap();
    let old = SectionData {
        palette: vec![1],
        storage: Vec::new(),
        bits: 0,
        per_word: 0,
    };
    let mut new = SectionData {
        palette: vec![1, 0],
        storage: vec![0; 64],
        bits: 1,
        per_word: 64,
    };
    let interior = 8 * 256 + 8 * 16 + 8;
    new.storage[interior / 64] |= 1 << (interior % 64);
    assert_eq!(changed_boundaries(Some(&old), Some(&new), &ctx.catalog), 0);
    fn update(batch: u64, key: Section, data: &SectionData) -> Vec<u8> {
        let mut v = header(2, batch);
        for n in [
            3,
            key.0 as u32,
            key.1 as u32,
            key.2 as u32,
            1,
            data.bits,
            data.palette.len() as u32,
            data.storage.len() as u32,
        ] {
            u32_to(&mut v, n);
        }
        for &n in &data.palette {
            u32_to(&mut v, n);
        }
        for &n in &data.storage {
            u64_to(&mut v, n);
        }
        u32_to(&mut v, 0);
        v
    }
    assert_eq!(requests(&mut ctx, &frame(2, 0., 1, [0, 0], &[(3, a)])), [a]);
    ctx.accept(&[&update(2, a, &new)], &mut source).unwrap();
    assert_eq!(ctx.stats.compiled, 1);
    let edge = 8 * 256 + 8 * 16 + 15;
    new.storage[edge / 64] |= 1 << (edge % 64);
    assert_eq!(
        changed_boundaries(Some(&old), Some(&new), &ctx.catalog),
        1 << 14
    );
    requests(&mut ctx, &frame(3, 0., 1, [0, 0], &[(3, a)]));
    ctx.accept(&[&update(3, a, &new)], &mut source).unwrap();
    assert_eq!(ctx.stats.compiled, 2);
}

#[test]
fn boundary_dependencies_follow_face_occlusion_and_not_state_identity() {
    let mut catalog = Catalog::default();
    for (id, flags, name) in [
        (0, 1, "minecraft:air"),
        (1, 4, "minecraft:stone"),
        (2, 4, "minecraft:dirt"),
        (3, 0, "minecraft:glass"),
        (4, 0, "minecraft:glass"),
        (5, 0, "minecraft:red_stained_glass"),
    ] {
        catalog.states.insert(
            id,
            model::State {
                id,
                flags,
                model: 0,
                name: name.into(),
                faces: [crate::shape::FaceId(u32::from(flags & 4 != 0)); 6],
                fluid: Default::default(),
                support: 0,
            },
        );
    }
    let uniform = |id| SectionData {
        palette: vec![id],
        storage: vec![],
        bits: 0,
        per_word: 0,
    };
    assert_eq!(
        changed_boundaries(Some(&uniform(1)), Some(&uniform(2)), &catalog),
        0
    );
    assert_eq!(
        changed_boundaries(Some(&uniform(3)), Some(&uniform(4)), &catalog),
        0
    );
    assert_eq!(
        changed_boundaries(Some(&uniform(3)), Some(&uniform(5)), &catalog),
        ((1 << 27) - 1) ^ (1 << 13)
    );
    assert_eq!(changed_boundaries(Some(&uniform(0)), None, &catalog), 0);
    assert_eq!(
        changed_boundaries(Some(&uniform(1)), None, &catalog),
        ((1 << 27) - 1) ^ (1 << 13)
    );
    for (axis, faces) in [[12, 14], [4, 22], [10, 16]].into_iter().enumerate() {
        for (side, face) in [0, 15].into_iter().zip(faces) {
            let mut position = [8; 3];
            position[axis] = side;
            let index = position[1] * 256 + position[2] * 16 + position[0];
            let mut edited = SectionData {
                palette: vec![1, 0],
                storage: vec![0; 64],
                bits: 1,
                per_word: 64,
            };
            edited.storage[index / 64] |= 1 << (index % 64);
            assert_eq!(
                changed_boundaries(Some(&uniform(1)), Some(&edited), &catalog),
                1 << face
            );
        }
    }
}

#[test]
fn boundary_queries_are_skipped_only_when_every_active_consumer_is_already_queued() {
    let key = Section(0, 0, 0);
    let neighbors = key.halo();
    let active: HashSet<_> = neighbors.into_iter().collect();
    let mut compile = active.clone();
    invalidate_neighbors(
        key,
        || panic!("all consumers already queued"),
        &active,
        &mut compile,
    );
    let corner = Section(1, 1, 1);
    let bit = 1 << neighbors.iter().position(|n| *n == corner).unwrap();
    compile.remove(&corner);
    let evaluated = std::cell::Cell::new(false);
    invalidate_neighbors(
        key,
        || {
            evaluated.set(true);
            bit
        },
        &active,
        &mut compile,
    );
    assert!(evaluated.get());
    assert_eq!(compile, active);
    compile.remove(&corner);
    invalidate_neighbors(key, || 0, &active, &mut compile);
    assert!(!compile.contains(&corner));
    let isolated = HashSet::from([key]);
    invalidate_neighbors(
        key,
        || panic!("no active neighbors"),
        &isolated,
        &mut HashSet::new(),
    );
}

#[test]
fn packed_boundary_differences_match_scalar_regions_for_every_layout() {
    let mut catalog = Catalog::default();
    catalog.states.insert(0, model::State::default());
    catalog.states.insert(
        1,
        model::State {
            id: 1,
            flags: 4,
            faces: [crate::shape::FaceId(1); 6],
            ..Default::default()
        },
    );
    let pack = |bits: u32, local: bool, cells: &[u32; 4096]| {
        let per_word = 64 / bits as usize;
        let mut storage = vec![0; 4096_usize.div_ceil(per_word)];
        for (i, &value) in cells.iter().enumerate() {
            storage[i / per_word] |= (value as u64) << ((i % per_word) * bits as usize);
        }
        SectionData {
            palette: if local { vec![0, 1] } else { vec![] },
            storage,
            bits,
            per_word,
        }
    };
    let reference = |a: &SectionData, b: &SectionData| {
        let mut result = 0;
        for i in 0..4096 {
            if catalog.states[&a.state(i)].same_boundary(&catalog.states[&b.state(i)]) {
                continue;
            }
            let xyz = [i & 15, i >> 8, (i >> 4) & 15];
            for dy in -1..=1 {
                for dz in -1..=1 {
                    for dx in -1..=1 {
                        if [dx, dy, dz] == [0; 3] {
                            continue;
                        }
                        if [dx, dy, dz].into_iter().zip(xyz).all(|(d, v)| match d {
                            -1 => v == 0,
                            0 => true,
                            1 => v == 15,
                            _ => unreachable!(),
                        }) {
                            result |= 1 << ((dy + 1) * 9 + (dz + 1) * 3 + dx + 1);
                        }
                    }
                }
            }
        }
        result
    };
    for bits in 1..=32 {
        for local in [false, true] {
            let before = pack(bits, local, &[0; 4096]);
            for index in [0, 15, 16, 255, 256, 2048, 2184, 3840, 4095, 4096] {
                let mut cells = [0; 4096];
                if index == 4096 {
                    cells.fill(1);
                } else {
                    cells[index] = 1;
                }
                let after = pack(bits, local, &cells);
                assert_eq!(
                    changed_boundaries(Some(&before), Some(&after), &catalog),
                    reference(&before, &after),
                    "bits={bits} local={local} index={index}"
                );
            }
            let mut padding = pack(bits, local, &[0; 4096]);
            for (word, value) in padding.storage.iter_mut().enumerate() {
                let used = (4096 - word * padding.per_word).min(padding.per_word) * bits as usize;
                if used < 64 {
                    *value |= u64::MAX << used;
                }
            }
            assert_eq!(
                changed_boundaries(Some(&before), Some(&padding), &catalog),
                0
            );
            let cells = std::array::from_fn(|i| u32::from(i % 7 == 0));
            let differently_packed = pack(if bits == 1 { 2 } else { 1 }, local, &cells);
            assert_eq!(
                changed_boundaries(Some(&before), Some(&differently_packed), &catalog),
                reference(&before, &differently_packed)
            );
            if local {
                let old = pack(bits, true, &cells);
                let mut reordered = pack(bits, true, &cells.map(|v| 1 - v));
                reordered.palette.reverse();
                assert_eq!(
                    changed_boundaries(Some(&old), Some(&reordered), &catalog),
                    0
                );
            }
        }
    }
}

#[test]
fn source_definitions_are_immutable_until_explicit_resource_invalidation() {
    let mut ctx = TerrainContext::default();
    let mut source = scene();
    let key = Section(0, 0, 0);
    let req = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, key)]));
    ctx.accept(&[&response(1, &req, |_| Some(true))], &mut source)
        .unwrap();
    let revision = source.revision();
    let req = requests(&mut ctx, &frame(2, 0., 0, [0, 0], &[(3, key)]));
    let mut wrong = header(2, 2);
    for v in [1, 1, 0, 0] {
        u32_to(&mut wrong, v);
    }
    string(&mut wrong, "minecraft:stone");
    state_source(&mut wrong, 0);
    for v in [3, 0, 0, 0, 1, 0, 1, 0, 1, 0] {
        u32_to(&mut wrong, v);
    }
    assert!(
        ctx.accept(&[&wrong], &mut source)
            .unwrap_err()
            .contains("without catalog invalidation")
    );
    assert_eq!(source.revision(), revision);
    assert_eq!(ctx.catalog.states[&1].faces, [crate::shape::FaceId(1); 6]);
    assert!(ctx.pending.is_some());
    ctx.accept(&[&response(2, &req, |_| Some(true))], &mut source)
        .unwrap();
    assert_eq!(ctx.stats.compiled, 0);
}

#[test]
fn corner_dependency_mask_includes_diagonals_for_same_fluid_height_changes() {
    let mut catalog = Catalog::default();
    for (id, amount) in [(1, 8), (2, 1)] {
        catalog.states.insert(
            id,
            model::State {
                fluid: fluid::Fluid {
                    kind: 1,
                    amount,
                    falling: false,
                    material: 1,
                },
                ..Default::default()
            },
        );
    }
    let old = SectionData {
        palette: vec![1],
        storage: vec![],
        bits: 0,
        per_word: 0,
    };
    for (x, y, z) in [(0, 0, 0), (15, 0, 15), (0, 15, 15), (15, 15, 0)] {
        let index = y * 256 + z * 16 + x;
        let mut new = SectionData {
            palette: vec![1, 2],
            storage: vec![0; 64],
            bits: 1,
            per_word: 64,
        };
        new.storage[index / 64] |= 1 << (index % 64);
        let mask = changed_boundaries(Some(&old), Some(&new), &catalog);
        assert_eq!(mask.count_ones(), 7);
        let d = |c| if c == 0 { -1 } else { 1 };
        let diagonal = ((d(y) + 1) * 9 + (d(z) + 1) * 3 + d(x) + 1) as usize;
        assert_ne!(mask & (1 << diagonal), 0);
        assert_eq!(mask & (1 << 13), 0);
    }
}

/// Synthetic source workload has an explicit constant host callback, not a guessed vanilla color.
pub(super) fn test_tints(ctx: &mut TerrainContext, scene: &mut SourceScene) {
    if ctx.tint_requests.is_empty() {
        return;
    }
    let response = tint_response(ctx, 0x8070903f);
    ctx.accept(&[&response], scene).unwrap();
}
fn tint_response(ctx: &TerrainContext, color: u32) -> Vec<u8> {
    let mut response = header(3, ctx.awaiting_colors.as_ref().unwrap().batch);
    u64_to(&mut response, ctx.stats.tint_requests as u64);
    u32_to(&mut response, 0); // blend radius
    for _ in 0..ctx.stats.tint_requests {
        u32_to(&mut response, 0);
        u32_to(&mut response, color);
    }
    u32_to(&mut response, 0); // No biome definitions for evaluated colors.
    response
}
fn biome_definitions(response: &mut Vec<u8>) {
    u32_to(response, 1);
    u64_to(response, 0);
    for p in 0..256 {
        u32_to(response, p);
    }
    for v in [0f64, 0., 1., 1.] {
        u64_to(response, v.to_bits());
    }
    for _ in 0..3 {
        u32_to(response, 0);
    }
}
fn biome_response(ctx: &TerrainContext, color: u32) -> Vec<u8> {
    let mut response = header(4, ctx.awaiting_colors.as_ref().unwrap().batch);
    u64_to(&mut response, ctx.stats.biome_pages as u64);
    u32_to(&mut response, 1); // One actual source biome with three explicit overrides.
    for value in [0, 0, color, color, color, color, 7, 0] {
        u32_to(&mut response, value);
    }
    for _ in 0..ctx.stats.biome_host_cells {
        u32_to(&mut response, 0);
    }
    response
}
#[test]
fn tint_batch_is_unique_atomic_and_validated_before_publication() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let rev = scene.revision();
    let key = Section(0, 0, 0);
    let req = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, key)]));
    ctx.accept(
        &[&crate::perf::packet(1, &req, "decorated", false)],
        &mut scene,
    )
    .unwrap();
    assert_eq!(scene.revision(), rev); // No white placeholder can escape while colors are missing.
    assert!(ctx.plan(&[&frame(2, 0., 0, [0, 0], &[])], 1).is_err());
    let entries: HashSet<_> = ctx.tint_requests[32..].as_chunks::<20>().0.iter().collect();
    assert_eq!(entries.len(), ctx.stats.tint_requests); // Several quads share exactly one callback.
    let response = tint_response(&ctx, 0x8070903f);
    let mut wrong = response.clone();
    wrong[24] += 1;
    assert!(ctx.accept(&[&wrong], &mut scene).is_err());
    wrong = response.clone();
    wrong[32] ^= 1;
    assert!(ctx.accept(&[&wrong], &mut scene).is_err());
    assert!(
        ctx.accept(&[&response[..response.len() - 1]], &mut scene)
            .is_err()
    );
    assert_eq!(scene.revision(), rev);
    ctx.accept(&[&response], &mut scene).unwrap();
    assert!(scene.revision() > rev);
    assert!(ctx.tint_requests.is_empty());
    assert!(ctx.accept(&[&response], &mut scene).is_err());
    // Same palette dirty event does no work. A separate biome event re-evaluates only consumers.
    for (batch, event, compiled) in [(2, 3, 0), (3, 6, 1), (4, 7, 1)] {
        let req = requests(&mut ctx, &frame(batch, 0., 0, [0, 0], &[(event, key)]));
        ctx.accept(
            &[&crate::perf::packet(batch, &req, "decorated", false)],
            &mut scene,
        )
        .unwrap();
        assert_eq!(ctx.stats.compiled, compiled);
        if compiled == 0 {
            assert!(ctx.tint_requests.is_empty());
        } else {
            assert!(!ctx.tint_requests.is_empty());
            test_tints(&mut ctx, &mut scene);
        }
    }
}

#[test]
fn biome_stage_cannot_publish_incomplete_or_wrong_phase_results() {
    let mut ctx = TerrainContext::default();
    let mut scene = scene();
    let rev = scene.revision();
    let requests = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, Section(0, 0, 0))]));
    ctx.accept(
        &[&crate::perf::packet(1, &requests, "decorated", false)],
        &mut scene,
    )
    .unwrap();
    let mut response = header(3, 1);
    u64_to(&mut response, ctx.stats.tint_requests as u64);
    u32_to(&mut response, 2);
    for _ in 0..ctx.stats.tint_requests {
        u32_to(&mut response, 1);
        u32_to(&mut response, 0);
    }
    biome_definitions(&mut response);
    ctx.accept(&[&response], &mut scene).unwrap();
    assert_eq!(ctx.tint_requests[28], 3);
    assert!(ctx.stats.biome_samples > 0);
    assert_eq!(scene.revision(), rev);
    assert!(ctx.accept(&[&response], &mut scene).is_err());
    response = biome_response(&ctx, 0xff1200ff);
    // No definition or palette can become visible from a malformed source response.
    for at in [68, 72, 76] {
        // override flags, modifier and first source-biome ID
        let mut invalid = response.clone();
        invalid[at..at + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(ctx.accept(&[&invalid], &mut scene).is_err());
        assert_eq!(scene.revision(), rev);
    }
    assert!(
        ctx.accept(&[&response[..response.len() - 1]], &mut scene)
            .is_err()
    );
    assert_eq!(scene.revision(), rev);
    ctx.accept(&[&response], &mut scene).unwrap();
    assert!(scene.revision() > rev);
    assert!(ctx.tint_requests.is_empty());
    assert_eq!(ctx.stats.response_batches, 3);
}

#[test]
fn cached_raw_biomes_finish_the_source_response_without_a_host_sample_round() {
    fn compile(warm: bool) -> Vec<Triangle> {
        let mut ctx = TerrainContext::default();
        let mut output = scene();
        let key = Section(0, 0, 0);
        let req = requests(&mut ctx, &frame(1, 0., 0, [0, 0], &[(1, key)]));
        ctx.accept(
            &[&crate::perf::packet(1, &req, "decorated", false)],
            &mut output,
        )
        .unwrap();
        let queries: Vec<_> = ctx
            .awaiting_colors
            .as_ref()
            .unwrap()
            .jobs
            .iter()
            .flat_map(|j| j.tints.requests.iter().copied())
            .collect();
        let recipes = vec![
            biome::Recipe::Biome {
                resolver: biome::Resolver::Grass,
                below: false
            };
            queries.len()
        ];
        if warm {
            let plan = ctx.biomes.prepare(queries.iter().copied(), &recipes, 7);
            let colors = vec![0xff1270e4; plan.samples.len()];
            ctx.biomes.finish(plan, &colors);
        }
        let mut response = header(3, 1);
        u64_to(&mut response, queries.len() as u64);
        u32_to(&mut response, 2);
        for _ in queries {
            u32_to(&mut response, 1);
            u32_to(&mut response, 0);
        }
        biome_definitions(&mut response);
        ctx.accept(&[&response], &mut output).unwrap();
        if warm {
            assert!(ctx.tint_requests().is_empty());
            assert_eq!(ctx.stats.biome_samples, 0);
            assert!(ctx.stats.biome_cached_samples > 0);
            assert_eq!(ctx.stats.response_batches, 2);
        } else {
            assert!(!ctx.tint_requests().is_empty());
            let response = biome_response(&ctx, 0xff1270e4);
            ctx.accept(&[&response], &mut output).unwrap();
            assert_eq!(ctx.stats.response_batches, 3);
        }
        // A departed dependency column must recompile colored neighbors even when
        // their palettes are unchanged; getBiome can now return the missing-column biome.
        let req = requests(&mut ctx, &frame(2, 0., 0, [0, 0], &[(2, Section(1, 0, 0))]));
        assert!(req.is_empty());
        ctx.accept(
            &[&crate::perf::packet(2, &req, "decorated", false)],
            &mut output,
        )
        .unwrap();
        assert_eq!(ctx.stats.compiled, 1);
        assert!(!ctx.tint_requests().is_empty());
        // Prior published colors remain visible until the replacement batch closes.
        output
            .translate([0.; 3])
            .unwrap()
            .meshes
            .values()
            .flat_map(|m| m.triangles.iter())
            .collect()
    }
    let cold = compile(false);
    let warm = compile(true);
    assert!(!cold.is_empty());
    assert_eq!(cold.len(), warm.len());
    for (a, b) in cold.iter().zip(warm) {
        assert_eq!(a.positions, b.positions);
        assert_eq!(a.colors, b.colors);
        assert_eq!(a.uvs, b.uvs);
    }
}
