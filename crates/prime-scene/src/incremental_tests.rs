use super::*;
use crate::{
    protocol::MAGIC,
    translation::{TerrainLimits, TerrainPlanner},
};

fn header(op: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, crate::protocol::ABI_VERSION, op, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(1_u64.to_le_bytes());
    bytes
}

fn section(key: u64, sequence: u64, origin: [f64; 3], color: Option<u8>) -> Vec<u8> {
    let mut bytes = header(8);
    bytes.extend(key.to_le_bytes());
    bytes.extend(sequence.to_le_bytes());
    for value in origin {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(u32::from(color.is_some()).to_le_bytes());
    bytes.extend(0_u32.to_le_bytes());
    if let Some(color) = color {
        for value in [0_u32, 0, 0, 4, 4, 24, 0, 12, 16, 0] {
            bytes.extend(value.to_le_bytes());
        }
        for position in [[0_f32, 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]] {
            for value in position {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend([color, 255, 255, 255]);
            bytes.extend([0_u8; 8]);
        }
    }
    bytes
}

fn origin(cell: usize, slot: usize) -> [f64; 3] {
    [
        ((cell % 128) * 64 + (slot / 16) * 16) as f64,
        ((slot / 4 % 4) * 16) as f64,
        ((cell / 128) * 64 + (slot % 4) * 16) as f64,
    ]
}

fn populate(cells: usize, density: usize) -> SourceScene {
    let mut source = SourceScene::default();
    source.submit(&header(1)).unwrap();
    for cell in 0..cells {
        for slot in 0..64 {
            source
                .submit(&section(
                    (cell * 64 + slot) as u64,
                    1,
                    origin(cell, slot),
                    (slot < density).then_some(255),
                ))
                .unwrap();
        }
    }
    source
}

fn planner() -> TerrainPlanner {
    TerrainPlanner::new(TerrainLimits {
        triangles_per_geometry: 1 << 25,
        geometry_records: 1 << 23,
    })
    .unwrap()
}

fn consume(planner: &mut TerrainPlanner, scene: &TranslatedScene) -> (usize, usize, usize) {
    let plan = planner.plan_input(scene.input()).unwrap();
    let work = (plan.geometry.len(), plan.meshes_visited, plan.cells_visited);
    planner.recycle(plan);
    work
}

#[test]
fn fixed_source_delta_visits_only_one_complete_cell_at_different_scene_sizes_and_densities() {
    for density in [1, 64] {
        for cells in [1, 16, 512] {
            let mut source = populate(cells, density);
            let mut translated = TranslatedScene::default();
            translated.update(&mut source, [0.; 3]).unwrap();
            let mut planner = planner();
            assert_eq!(consume(&mut planner, &translated).0, cells);
            for sequence in 2..8 {
                source
                    .submit(&section(0, sequence, origin(0, 0), Some(sequence as u8)))
                    .unwrap();
                let work = translated.update(&mut source, [0.; 3]).unwrap();
                assert_eq!((work.meshes_validated, work.meshes_published), (1, 1));
                assert_eq!(consume(&mut planner, &translated), (1, density, 1));
                assert_eq!(consume(&mut planner, &translated), (0, 0, 0));
            }
        }
    }
}

#[test]
fn source_order_texture_pixels_and_camera_rebase_do_not_regroup_terrain() {
    let mut source = populate(2, 64);
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    let vertices = translated.input().meshes[&(0, 0)].triangles.clone();
    let mut planner = planner();
    consume(&mut planner, &translated);
    source
        .submit(&section(0, 2, origin(0, 0), Some(255)))
        .unwrap();
    assert_eq!(
        translated.update(&mut source, [0.; 3]).unwrap(),
        TranslationWork::default()
    );
    assert!(
        source
            .submit(&section(0, 2, origin(0, 0), Some(128)))
            .is_err()
    );
    let mut texture = header(4);
    for value in [1_u32, 1, 1, 0] {
        texture.extend(value.to_le_bytes());
    }
    texture.extend([255; 4]);
    source.submit(&texture).unwrap();
    let work = translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(
        (
            work.meshes_validated,
            work.meshes_published,
            work.textures_published
        ),
        (0, 0, 1)
    );
    assert_eq!(consume(&mut planner, &translated), (0, 0, 0));
    translated.update(&mut source, [512.; 3]).unwrap();
    let plan = planner.plan_input(translated.input()).unwrap();
    assert!(plan.placements_changed);
    assert_eq!(
        (plan.geometry.len(), plan.meshes_visited, plan.cells_visited),
        (0, 0, 0)
    );
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &vertices,
        &translated.input().meshes[&(0, 0)].triangles
    ));
    planner.recycle(plan);
}

#[test]
fn missing_empty_slot_withdraws_whole_cell_and_reappearance_uses_cached_members() {
    let mut source = populate(2, 1);
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    let mut planner = planner();
    consume(&mut planner, &translated);
    let vertices = translated.input().meshes[&(0, 0)].triangles.clone();
    let mut removal = header(3);
    for value in [63_u64, 2] {
        removal.extend(value.to_le_bytes());
    }
    source.submit(&removal).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let plan = planner.plan_input(translated.input()).unwrap();
    assert_eq!(plan.removed, [Cell::containing([0.; 3]).unwrap()]);
    assert!(plan.geometry.is_empty());
    assert_eq!(plan.triangle_count, 2);
    planner.recycle(plan);
    // Updates in an incomplete cell are retained but do no grouping work.
    source
        .submit(&section(0, 3, origin(0, 0), Some(128)))
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(consume(&mut planner, &translated), (0, 0, 1));
    source.submit(&section(63, 3, origin(0, 63), None)).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(consume(&mut planner, &translated), (1, 1, 1));
    assert!(!crate::geometry::MeshGeometry::ptr_eq(
        &vertices,
        &translated.input().meshes[&(0, 0)].triangles
    ));
}

#[test]
fn failed_translation_keeps_source_edits_and_old_snapshot_until_successful_publication() {
    let mut source = populate(1, 64);
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    let previous = translated.input().meshes[&(0, 0)].triangles.clone();
    let revision = translated.input().revision;
    source
        .submit(&section(0, 2, origin(0, 0), Some(128)))
        .unwrap();
    assert!(translated.update(&mut source, [2_000_000.; 3]).is_err());
    assert_eq!(translated.input().revision, revision);
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &previous,
        &translated.input().meshes[&(0, 0)].triangles
    ));
    let work = translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(work.meshes_published, 1);
    assert!(!crate::geometry::MeshGeometry::ptr_eq(
        &previous,
        &translated.input().meshes[&(0, 0)].triangles
    ));
}

#[test]
fn missed_batches_and_independent_consumers_resynchronize_including_removed_cells() {
    let mut source = populate(2, 1);
    let mut translated = TranslatedScene::default();
    let mut other = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    other.update(&mut source, [0.; 3]).unwrap();
    let mut planner = planner();
    consume(&mut planner, &translated);
    source
        .submit(&section(0, 2, origin(0, 0), Some(128)))
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap(); // Deliberately skip this GPU consumer batch.
    source.submit(&section(64, 2, origin(1, 0), None)).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let work = other.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(work.meshes_published, 1); // Full source resynchronization after another consumer acknowledged.
    assert_eq!(other.input().meshes.len(), 1);
    let plan = planner.plan_input(translated.input()).unwrap();
    assert_eq!(plan.geometry.len(), 1);
    assert_eq!(plan.removed, [Cell::containing(origin(1, 0)).unwrap()]);
    assert_eq!(plan.triangle_count, 2);
    planner.recycle(plan);
}

#[test]
fn cell_migration_invalidation_reaches_old_and_new_membership() {
    let mut source = populate(2, 1);
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    let mut planner = planner();
    consume(&mut planner, &translated);
    source
        .submit(&section(0, 2, origin(1, 1), Some(128)))
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let plan = planner.plan_input(translated.input()).unwrap();
    assert_eq!(plan.removed, [Cell::containing(origin(0, 0)).unwrap()]);
    assert_eq!(plan.geometry.len(), 1);
    assert_eq!(
        (plan.cells_visited, plan.meshes_visited, plan.triangle_count),
        (2, 2, 4)
    );
    planner.recycle(plan);
}

#[test]
fn texture_cursor_filters_pixels_independently_and_recovers_a_missed_publication() {
    let mut source = populate(1, 1);
    let mut translated = TranslatedScene::default();
    let texture = |id: u32, red: u8| {
        let mut bytes = header(4);
        for value in [id, 1, 1, 0] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend([red, 255, 255, 255]);
        bytes
    };
    for id in 1..=128 {
        source.submit(&texture(id, 0)).unwrap();
    }
    translated.update(&mut source, [0.; 3]).unwrap();
    let (cursor, updates) = translated.input().texture_input().updates(None);
    assert_eq!(updates.count(), 128);
    source
        .submit(&section(0, 2, origin(0, 0), Some(128)))
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(
        translated.input().texture_input().updates(cursor).1.count(),
        0
    );
    source.submit(&texture(100, 12)).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let (_, updates) = translated.input().texture_input().updates(cursor);
    assert_eq!(updates.map(|(&id, _)| id).collect::<Vec<_>>(), [100]);
    source.submit(&texture(101, 42)).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let (latest, updates) = translated.input().texture_input().updates(cursor);
    assert!(updates.is_snapshot());
    assert_eq!(updates.count(), 128);
    assert_eq!(
        translated.input().texture_input().updates(latest).1.count(),
        0
    );
}

#[test]
fn failed_terrain_plan_does_not_advance_cursor_and_epoch_reset_revokes_previous_geometry() {
    let mut source = populate(1, 1);
    let mut translated = TranslatedScene::default();
    let mut planner = TerrainPlanner::new(TerrainLimits {
        triangles_per_geometry: 1024,
        geometry_records: 1,
    })
    .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    consume(&mut planner, &translated);
    for slot in 0..64 {
        source
            .submit(&section(
                64 + slot as u64,
                1,
                origin(1, slot),
                (slot == 0).then_some(255),
            ))
            .unwrap();
    }
    translated.update(&mut source, [0.; 3]).unwrap();
    assert!(planner.plan_input(translated.input()).is_err());
    source.submit(&section(0, 2, origin(0, 0), None)).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let plan = planner.plan_input(translated.input()).unwrap();
    assert_eq!(
        (plan.geometry.len(), plan.removed.len(), plan.triangle_count),
        (1, 1, 2)
    );
    planner.recycle(plan);
    let mut reset = header(1);
    reset[16..24].copy_from_slice(&2_u64.to_le_bytes());
    source.submit(&reset).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let plan = planner.plan_input(translated.input()).unwrap();
    assert_eq!(plan.removed, [Cell::containing(origin(1, 0)).unwrap()]);
    assert_eq!(plan.triangle_count, 0);
    assert!(planner.placements().is_empty());
}

#[test]
fn equal_numeric_versions_from_different_owners_cannot_alias_a_consumption_cursor() {
    let mut first = populate(1, 1);
    let mut second = populate(1, 1);
    let mut translated = TranslatedScene::default();
    let mut other = TranslatedScene::default();
    let mut planner = planner();
    translated.update(&mut first, [0.; 3]).unwrap();
    consume(&mut planner, &translated);
    // Equal public epoch/revision values do not prove source identity.
    assert_eq!(
        (first.epoch(), first.revision()),
        (second.epoch(), second.revision())
    );
    assert_eq!(
        translated
            .update(&mut second, [0.; 3])
            .unwrap()
            .meshes_published,
        1
    );
    assert_eq!(consume(&mut planner, &translated).0, 1);
    second
        .submit(&section(0, 2, origin(0, 0), Some(128)))
        .unwrap();
    other.update(&mut second, [0.; 3]).unwrap();
    assert_eq!(consume(&mut planner, &other).0, 1);
    assert!(
        !translated
            .input()
            .publication()
            .same_owner(other.input().publication())
    );
    // Switching back to an older publication is a resync, not a false clean hit.
    assert_eq!(consume(&mut planner, &translated).0, 1);
}
