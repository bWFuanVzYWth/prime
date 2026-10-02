//! Exercise budgeted source batches through their production decode/compile/publication path.
use super::*;
use crate::tests::{frame, header, response, scene};

fn requests(context: &mut TerrainContext, input: &[u8], budget: u32) -> Vec<Section> {
    let data = context.plan_with_budget(&[input], 1, budget).unwrap();
    let count = u64::from_le_bytes(data[8..16].try_into().unwrap()) as usize;
    data[32..32 + count * 16]
        .as_chunks::<16>()
        .0
        .iter()
        .map(|entry| {
            Section(
                i32::from_le_bytes(entry[0..4].try_into().unwrap()),
                i32::from_le_bytes(entry[4..8].try_into().unwrap()),
                i32::from_le_bytes(entry[8..12].try_into().unwrap()),
            )
        })
        .collect()
}

fn columns(x: std::ops::Range<i32>, z: std::ops::Range<i32>) -> Vec<(u32, Section)> {
    x.flat_map(|x| z.clone().map(move |z| (1, Section(x, 0, z))))
        .collect()
}

#[test]
fn cell_budgets_one_eight_and_128_publish_complete_empty_cells_and_drain_idle_frames() {
    for budget in [1, 8, 128] {
        let mut context = TerrainContext::default();
        let mut source = scene();
        let count = budget + 1;
        let ys = [0, count as i32 * 4 - 1];
        let input = frame(1, 16., 3, ys, &columns(0..4, 0..4));
        let requested = requests(&mut context, &input, budget);
        assert_eq!(requested.len(), count as usize * 64);
        context
            .accept(&[&response(1, &requested, |_| Some(false))], &mut source)
            .unwrap();
        assert_eq!(context.stats.compiled_cells, budget as usize);
        assert_eq!(context.stats.compiled, budget as usize * 64);
        assert_eq!(context.stats.jobs, 0); // Empty section proofs still consume cell admission.
        assert_eq!(context.chunks.pending_cells(), 1);
        let ready = source.translate([0.; 3]).unwrap().ready_terrain;
        assert_eq!(ready.len(), budget as usize);
        assert!(!ready.contains(
            &prime_scene::spatial::Cell::containing([0., f64::from(budget) * 64., 0.]).unwrap()
        ));

        let requested = requests(&mut context, &frame(2, 16., 3, ys, &[]), budget);
        assert!(requested.is_empty());
        context
            .accept(&[&response(2, &requested, |_| unreachable!())], &mut source)
            .unwrap();
        assert_eq!(
            (context.stats.compiled_cells, context.stats.compiled),
            (1, 64)
        );
        assert_eq!(context.stats.availability_updates, 0);
        assert_eq!(context.chunks.pending_cells(), 0);
        assert_eq!(
            source.translate([0.; 3]).unwrap().ready_terrain.len(),
            count as usize
        );

        let revision = source.revision();
        let requested = requests(&mut context, &frame(3, 16., 3, ys, &[]), budget);
        context
            .accept(&[&response(3, &requested, |_| unreachable!())], &mut source)
            .unwrap();
        assert_eq!(
            (context.stats.compiled_cells, context.stats.compiled),
            (0, 0)
        );
        assert_eq!(source.revision(), revision);
    }
}

#[test]
fn repeated_deferred_edits_use_the_latest_owned_section_once() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let events: Vec<_> = [0, 4, 8].map(|x| (1, Section(x, 0, 0))).into();
    let requested = requests(&mut context, &frame(1, 64., 4, [0, 0], &events), 1);
    context
        .accept(&[&response(1, &requested, |_| Some(false))], &mut source)
        .unwrap();
    assert_eq!(context.chunks.pending_cells(), 2);
    let edited = Section(8, 0, 0);
    for (batch, solid, remaining) in [(2, true, 1), (3, false, 0)] {
        let requested = requests(
            &mut context,
            &frame(batch, 64., 4, [0, 0], &[(3, edited)]),
            1,
        );
        assert_eq!(requested, [edited]);
        context
            .accept(
                &[&response(batch, &requested, |_| Some(solid))],
                &mut source,
            )
            .unwrap();
        assert_eq!(
            (context.stats.compiled_cells, context.stats.compiled),
            (1, 1)
        );
        assert_eq!(context.chunks.pending_cells(), remaining);
        assert_eq!(context.stats.availability_updates, 0);
    }
    assert_eq!(context.chunks.sources()[&edited].state(0), 0);
    assert!(source.translate([0.; 3]).unwrap().meshes.is_empty());
}

#[test]
fn boundary_invalidations_share_the_cell_budget_and_finish_on_an_idle_batch() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let left = Section(3, 0, 0);
    let right = Section(4, 0, 0);
    let requested = crate::tests::requests(
        &mut context,
        &frame(1, 48., 1, [0, 0], &[(1, left), (1, right)]),
    );
    context
        .accept(&[&response(1, &requested, |_| Some(true))], &mut source)
        .unwrap();
    assert_eq!(context.stats.compiled, 2);
    let requested = requests(&mut context, &frame(2, 48., 1, [0, 0], &[(3, left)]), 1);
    assert_eq!(requested, [left]);
    context
        .accept(&[&response(2, &requested, |_| Some(false))], &mut source)
        .unwrap();
    assert_eq!(
        (context.stats.compiled_cells, context.stats.compiled),
        (1, 1)
    );
    assert_eq!(context.chunks.pending_sections(), [right]);
    let before = source.translate([0.; 3]).unwrap().triangle_count();

    let requested = requests(&mut context, &frame(3, 48., 1, [0, 0], &[]), 1);
    assert!(requested.is_empty());
    context
        .accept(&[&response(3, &requested, |_| unreachable!())], &mut source)
        .unwrap();
    assert_eq!(
        (context.stats.compiled_cells, context.stats.compiled),
        (1, 1)
    );
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(
        source.translate([0.; 3]).unwrap().triangle_count(),
        before + 256 * 2
    );
}

#[test]
fn unload_cancels_deferred_work_and_removes_resident_geometry_without_spending_quota() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let events: Vec<_> = [0, 4, 8].map(|x| (1, Section(x, 0, 0))).into();
    let requested = requests(&mut context, &frame(1, 64., 4, [0, 0], &events), 1);
    context
        .accept(&[&response(1, &requested, |s| Some(s.0 == 0))], &mut source)
        .unwrap();
    assert!(!source.translate([0.; 3]).unwrap().meshes.is_empty());
    assert_eq!(context.chunks.pending_cells(), 2);

    let requested = requests(
        &mut context,
        &frame(
            2,
            64.,
            4,
            [0, 0],
            &[(2, Section(0, 0, 0)), (2, Section(4, 0, 0))],
        ),
        1,
    );
    assert!(requested.is_empty());
    context
        .accept(&[&response(2, &requested, |_| unreachable!())], &mut source)
        .unwrap();
    assert_eq!(
        (context.stats.compiled_cells, context.stats.compiled),
        (1, 1)
    );
    assert_eq!(context.chunks.pending_cells(), 0);
    assert!(source.translate([0.; 3]).unwrap().meshes.is_empty());
    assert_eq!(context.chunks.sources().len(), 1);
}

#[test]
fn incomplete_negative_cells_compile_available_sections_without_becoming_ready() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let missing = Section(-1, -1, -1);
    let mut input = frame(1, -16., 3, [-4, -1], &columns(-4..0, -4..0));
    input[40..48].copy_from_slice(&(-16f64).to_bits().to_le_bytes());
    let requested = requests(&mut context, &input, 1);
    assert_eq!(requested.len(), 64);
    context
        .accept(
            &[&response(1, &requested, |s| {
                (s != missing).then_some(false)
            })],
            &mut source,
        )
        .unwrap();
    assert_eq!(
        (context.stats.compiled_cells, context.stats.compiled),
        (1, 63)
    );
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(context.chunks.availability()[&[-1, -1, -1]], 63);
    assert!(source.translate([0.; 3]).unwrap().ready_terrain.is_empty());

    let mut input = frame(2, -16., 3, [-4, -1], &[(3, missing)]);
    input[40..48].copy_from_slice(&(-16f64).to_bits().to_le_bytes());
    let requested = requests(&mut context, &input, 1);
    assert_eq!(requested, [missing]);
    context
        .accept(&[&response(2, &requested, |_| Some(false))], &mut source)
        .unwrap();
    assert_eq!(context.stats.compiled_cells, 1);
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(
        source.translate([0.; 3]).unwrap().ready_terrain,
        BTreeSet::from([prime_scene::spatial::Cell::containing([-64.; 3]).unwrap()]),
    );
}

#[test]
fn invalid_budget_and_source_response_do_not_consume_pending_work() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let input = frame(1, 16., 3, [0, 7], &columns(0..4, 0..4));
    for budget in [0, 129, u32::MAX] {
        assert!(context.plan_with_budget(&[&input], 1, budget).is_err());
        assert!(context.pending.is_none());
    }
    let requested = requests(&mut context, &input, 1);
    context
        .accept(&[&response(1, &requested, |_| Some(false))], &mut source)
        .unwrap();
    assert_eq!(context.chunks.pending_cells(), 1);
    let revision = source.revision();
    let requested = requests(&mut context, &frame(2, 16., 3, [0, 7], &[]), 1);
    let valid = response(2, &requested, |_| unreachable!());
    assert!(
        context
            .accept(&[&valid[..valid.len() - 1]], &mut source)
            .is_err()
    );
    assert_eq!(context.chunks.pending_cells(), 1);
    assert_eq!(context.last_batch, 1);
    assert_eq!(source.revision(), revision);
    context.accept(&[&valid], &mut source).unwrap();
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(context.last_batch, 2);
}

#[test]
fn tint_response_failure_keeps_the_selected_cell_pending_until_publication() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let input = frame(1, 0., 0, [0, 0], &[(1, Section(0, 0, 0))]);
    let requested = requests(&mut context, &input, 1);
    let revision = source.revision();
    context
        .accept(
            &[&crate::perf::packet(1, &requested, "decorated", false)],
            &mut source,
        )
        .unwrap();
    assert!(!context.tint_requests.is_empty());
    assert_eq!(context.chunks.pending_cells(), 1);
    assert_eq!(source.revision(), revision);
    assert!(context.accept(&[&header(3, 1)], &mut source).is_err());
    assert_eq!(context.chunks.pending_cells(), 1);
    assert_eq!(context.last_batch, 0);
    crate::tests::test_tints(&mut context, &mut source);
    assert_eq!(context.chunks.pending_cells(), 0);
    assert_eq!(context.last_batch, 1);
    assert!(source.revision() > revision);
}

#[test]
fn failed_publication_leaves_selected_dirty_bits_unacknowledged() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    source.publish_compiled(1, 1, Vec::new(), &[]).unwrap();
    let input = frame(1, 0., 0, [0, 0], &[(1, Section(0, 0, 0))]);
    let requested = requests(&mut context, &input, 1);
    let revision = source.revision();
    assert!(
        context
            .accept(&[&response(1, &requested, |_| Some(false))], &mut source)
            .is_err(),
    );
    assert_eq!(context.chunks.pending_cells(), 1);
    assert_eq!(context.last_batch, 0);
    assert_eq!(source.revision(), revision);
    assert_eq!(context.chunks.pending_sections(), [Section(0, 0, 0)]);
}

#[test]
fn epoch_change_discards_previous_worlds_cpu_queue() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let input = frame(1, 16., 3, [0, 7], &columns(0..4, 0..4));
    let requested = requests(&mut context, &input, 1);
    context
        .accept(&[&response(1, &requested, |_| Some(false))], &mut source)
        .unwrap();
    assert_eq!(context.chunks.pending_cells(), 1);
    let mut next = frame(1, 0., 0, [0, 0], &[]);
    next[16..24].copy_from_slice(&2u64.to_le_bytes());
    context.plan_with_budget(&[&next], 2, 1).unwrap();
    assert_eq!(context.chunks.pending_cells(), 0);
    assert!(context.chunks.sources().is_empty());
    assert_eq!(context.last_batch, 0);
}

#[test]
fn catalog_replacement_withdraws_deferred_cells_but_ordinary_edits_keep_them() {
    let mut context = TerrainContext::default();
    let mut source = scene();
    let mut translated = prime_scene::incremental::TranslatedScene::default();
    let a = Section(0, 0, 0);
    let b = Section(0, 4, 0);
    let requested = crate::tests::requests(
        &mut context,
        &frame(1, 16., 3, [0, 7], &columns(0..4, 0..4)),
    );
    context
        .accept(
            &[&response(1, &requested, |s| Some(s == a || s == b))],
            &mut source,
        )
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(translated.input().ready_terrain.len(), 2);
    assert_eq!(translated.input().terrain_resource_generation, 0);

    let requested = requests(
        &mut context,
        &frame(2, 16., 3, [0, 7], &[(3, a), (3, b)]),
        1,
    );
    context
        .accept(&[&response(2, &requested, |_| Some(false))], &mut source)
        .unwrap();
    let edited = source.translate([0.; 3]).unwrap();
    assert_eq!(edited.terrain_resource_generation, 0);
    assert_eq!(edited.ready_terrain.len(), 2);
    assert!(edited.meshes.values().any(|mesh| mesh.origin == b.origin()));
    assert_eq!(context.chunks.pending_cells(), 1);

    let requested = requests(&mut context, &frame(3, 16., 3, [0, 7], &[(4, a)]), 1);
    let replacement = response(3, &requested, |_| Some(false));
    assert!(
        context
            .accept(&[&replacement[..replacement.len() - 1]], &mut source)
            .is_err()
    );
    assert_eq!(
        source
            .translate([0.; 3])
            .unwrap()
            .terrain_resource_generation,
        0
    );
    context.accept(&[&replacement], &mut source).unwrap();
    let replacement = source.translate([0.; 3]).unwrap();
    assert_eq!(replacement.terrain_resource_generation, 1);
    assert!(replacement.meshes.is_empty());
    assert_eq!(replacement.ready_terrain.len(), 1);
    assert_eq!(
        (context.stats.compiled_cells, context.stats.compiled),
        (1, 64)
    );
    assert_eq!(context.chunks.sources().len(), 128);
    assert_eq!(context.chunks.pending_cells(), 1);
    // Skip the ordinary-edit translation: the durable generation must still be observed.
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(translated.input().terrain_resource_generation, 1);
    assert_eq!(translated.input().ready_terrain.len(), 1);

    let requested = requests(&mut context, &frame(4, 16., 3, [0, 7], &[]), 1);
    assert!(requested.is_empty());
    context
        .accept(&[&response(4, &requested, |_| unreachable!())], &mut source)
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(translated.input().terrain_resource_generation, 1);
    assert_eq!(translated.input().ready_terrain.len(), 2);
    assert_eq!(context.chunks.pending_cells(), 0);
}
