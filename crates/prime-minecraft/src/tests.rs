use super::*;
use prime_scene::protocol::{ABI_VERSION, MAGIC as SCENE_MAGIC};

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
fn response(batch: u64, requests: &[Section], choose: impl Fn(Section) -> Option<bool>) -> Vec<u8> {
    let mut v = header(2, batch);
    for (id, flags, name) in [(0, 1, "minecraft:air"), (1, 4, "minecraft:stone")] {
        for n in [1, id, flags, 0] {
            u32_to(&mut v, n);
        }
        string(&mut v, name);
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
        let triangle = &mesh.triangles[0];
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
        assert_eq!(ctx.stats.jobs, 16);
        scene
            .translate([0.; 3])
            .unwrap()
            .meshes
            .values()
            .flat_map(|m| m.triangles.iter().copied())
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
                        same(a, b);
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
                s.neighbors()
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
        1 << 5
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
                flags,
                model: 0,
                name: name.into(),
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
        63
    );
    assert_eq!(changed_boundaries(Some(&uniform(0)), None, &catalog), 0);
    assert_eq!(changed_boundaries(Some(&uniform(1)), None, &catalog), 63);
    for (axis, faces) in [[4, 5], [0, 1], [2, 3]].into_iter().enumerate() {
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
