use super::*;
use crate::Texture;

fn scene() -> SourceScene {
    let mut scene = SourceScene {
        epoch: 1,
        ..Default::default()
    };
    scene.textures.insert(
        1,
        Texture {
            width: 1,
            height: 1,
            pixels: Arc::from([255; 4]),
        },
    );
    scene.texture_bytes = 4;
    scene.texture_lifetime.owned(1);
    scene
}
fn triangle() -> Triangle {
    Triangle {
        positions: [[0., 0., 0.], [1., 0., 0.], [0., 1., 0.]],
        colors: [[1.; 4]; 3],
        uvs: [[0., 0.], [1., 0.], [0., 1.]],
        texture_id: 1,
        flags: 0,
    }
}
fn plan(scene: &SourceScene, triangles: Vec<Triangle>) -> CompiledSection {
    scene.prepare_compiled(7, [0.; 3], [triangles, Vec::new(), Vec::new()])
}

#[test]
fn equal_geometry_retains_identity_and_advances_only_completion() {
    let mut scene = scene();
    let first = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let triangles = scene.meshes[&(7, 0)].triangles.clone();
    let revision = scene.revision;
    scene.edits = Default::default();
    let equal = plan(&scene, vec![triangle()]);
    let result = scene.publish_compiled(1, 2, vec![equal], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert_eq!(scene.revision, revision);
    assert_eq!(scene.section_completed, SectionSequence(2));
    assert_eq!(scene.meshes[&(7, 0)].revision.number(), 1);
    assert_eq!(scene.triangle_count, 1);
    assert!(scene.edits.meshes.is_empty());
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &triangles,
        &scene.meshes[&(7, 0)].triangles
    ));

    let stale = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 3, vec![], &[]).unwrap();
    assert!(scene.publish_compiled(1, 4, vec![stale], &[]).is_err());
    assert_eq!(scene.section_completed, SectionSequence(3));
}

#[test]
fn fragmented_preparation_preserves_order_bounds_and_snapshot_identity() {
    let mut scene = scene();
    let a = triangle();
    let mut b = a;
    b.positions[2] = [-2., 3., 4.];
    b.colors[0][1] = 0.25;
    let first = plan(&scene, vec![a, b, a]);
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let old = scene.meshes[&(7, 0)].triangles.clone();
    let fragments = [
        [vec![a], vec![], vec![]],
        [vec![], vec![], vec![]],
        [vec![b, a], vec![], vec![]],
    ];
    let equal = scene.prepare_compiled_parts(7, [0.; 3], &fragments.iter().collect::<Vec<_>>());
    drop(fragments); // The proof never borrows the producer's work buffers.
    let result = scene.publish_compiled(1, 2, vec![equal], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &old,
        &scene.meshes[&(7, 0)].triangles
    ));
    let parts = [[vec![b], vec![], vec![]], [vec![a, b], vec![], vec![]]];
    let changed = scene.prepare_compiled_parts(7, [0.; 3], &[&parts[0], &parts[1]]);
    let result = scene.publish_compiled(1, 3, vec![changed], &[]).unwrap();
    assert_eq!(result.replaced_layers, 1);
    let mesh = &scene.meshes[&(7, 0)];
    assert!(
        mesh.triangles
            .iter()
            .zip([b, a, b])
            .all(|(a, b)| same_triangle(&a, &b))
    );
    assert_eq!(mesh.bounds, [[-2., 0., 0.], [1., 3., 4.]]);
    assert_eq!(old.len(), 3); // Another consumer's previous snapshot remains valid.
}

#[test]
fn compact_quads_preserve_corner_bits_winding_bounds_and_retention() {
    let mut scene = scene();
    let a = CompiledQuad {
        positions: [[-0., 0., 0.], [2., -3., 0.], [1., 4., 5.], [-2., 1., 0.5]],
        uvs: [[-0., 0.125], [0.25, 0.5], [0.875, 1.], [1.25, -0.5]],
        color: [0.1, -0., 0.75, 0.25],
        texture_id: 1,
        flags: 0,
    };
    let mut b = a;
    b.positions[3][1] = -7.;
    b.color[0] = 0.5;
    let triangles = |quad: CompiledQuad| {
        [
            Triangle {
                positions: [quad.positions[0], quad.positions[1], quad.positions[2]],
                uvs: [quad.uvs[0], quad.uvs[1], quad.uvs[2]],
                colors: [quad.color; 3],
                texture_id: quad.texture_id,
                flags: quad.flags,
            },
            Triangle {
                positions: [quad.positions[2], quad.positions[3], quad.positions[0]],
                uvs: [quad.uvs[2], quad.uvs[3], quad.uvs[0]],
                colors: [quad.color; 3],
                texture_id: quad.texture_id,
                flags: quad.flags,
            },
        ]
    };
    let expected = [a, b, a]
        .into_iter()
        .flat_map(triangles)
        .collect::<Vec<_>>();
    let first = plan(&scene, expected.clone());
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let old = scene.meshes[&(7, 0)].triangles.clone();
    let mut parts = [
        [vec![a], vec![], vec![]],
        [vec![], vec![], vec![]],
        [vec![b, a], vec![], vec![]],
    ];
    let prepare = |scene: &SourceScene, parts: &[[Vec<CompiledQuad>; 3]; 3]| {
        scene.prepare_compiled_quads(7, [0.; 3], &[&parts[0], &parts[1], &parts[2]])
    };
    let equal = prepare(&scene, &parts);
    let result = scene.publish_compiled(1, 2, vec![equal], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &old,
        &scene.meshes[&(7, 0)].triangles
    ));
    parts[2][0][1] = b;
    let changed = prepare(&scene, &parts);
    drop(parts);
    scene.publish_compiled(1, 3, vec![changed], &[]).unwrap();
    let mesh = &scene.meshes[&(7, 0)];
    assert_eq!(mesh.triangles.len(), 6);
    for (actual, expected) in mesh
        .triangles
        .iter()
        .zip([a, b, b].into_iter().flat_map(triangles))
    {
        assert!(same_triangle(&actual, &expected));
    }
    assert_eq!(mesh.bounds, [[-2., -7., 0.], [2., 4., 5.]]);
    assert!(old.iter().zip(expected).all(|(a, b)| same_triangle(&a, &b)));
    let compact = mesh.triangles.clone();
    assert!(matches!(compact, MeshGeometry::Quads(_)));
    let same = scene.prepare_compiled_quads(7, [0.; 3], &[&[vec![a, b, b], vec![], vec![]]]);
    let result = scene.publish_compiled(1, 4, vec![same], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert!(compact.ptr_eq(&scene.meshes[&(7, 0)].triangles));
    let same = plan(&scene, [a, b, b].into_iter().flat_map(triangles).collect());
    let result = scene.publish_compiled(1, 5, vec![same], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert!(compact.ptr_eq(&scene.meshes[&(7, 0)].triangles));
}

#[test]
fn owned_fragments_move_buffers_share_equal_blocks_and_retain_across_partitions() {
    let mut scene = scene();
    let a = CompiledQuad {
        positions: [[-0., 0., 0.], [2., -3., 0.], [1., 4., 5.], [-2., 1., 0.5]],
        uvs: [[-0., 0.125], [0.25, 0.5], [0.875, 1.], [1.25, -0.5]],
        color: [0.1, -0., 0.75, 0.25],
        texture_id: 1,
        flags: 0,
    };
    let mut b = a;
    b.positions[3][1] = -7.;
    let buffer = vec![a; 3];
    let address = buffer.as_ptr();
    let first = scene.prepare_compiled_quad_fragments(
        7,
        [0.; 3],
        [
            [buffer, vec![], vec![]],
            [vec![], vec![], vec![]],
            [vec![b], vec![], vec![]],
        ],
    );
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let old = scene.meshes[&(7, 0)].triangles.clone();
    let MeshGeometry::QuadFragments(old_parts) = &old else {
        panic!("expected fragments")
    };
    assert_eq!(old_parts.blocks[0].values.as_ptr(), address);
    assert_eq!(scene.meshes[&(7, 0)].bounds, [[-2., -7., 0.], [2., 4., 5.]]);
    let mut edit = b;
    edit.color[0] = 0.5;
    let next = scene.prepare_compiled_quad_fragments(
        7,
        [0.; 3],
        [
            [vec![a; 3], vec![], vec![]],
            [vec![], vec![], vec![]],
            [vec![edit], vec![], vec![]],
        ],
    );
    let mut retired = scene.publish_compiled(1, 2, vec![next], &[]).unwrap();
    retired.release_retired(None).unwrap();
    let current = scene.meshes[&(7, 0)].triangles.clone();
    let MeshGeometry::QuadFragments(parts) = &current else {
        panic!("expected fragments")
    };
    assert!(Arc::ptr_eq(&old_parts.blocks[0], &parts.blocks[0]));
    assert!(!Arc::ptr_eq(&old_parts.blocks[2], &parts.blocks[2]));
    assert!(old_parts.blocks[2].values[0].same_bits(&b));
    assert!(parts.blocks[2].values[0].same_bits(&edit));
    let equal = scene.prepare_compiled_quad_fragments(
        7,
        [0.; 3],
        [
            [vec![a], vec![], vec![]],
            [vec![a, a, edit], vec![], vec![]],
        ],
    );
    let result = scene.publish_compiled(1, 3, vec![equal], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));
    assert!(current.ptr_eq(&scene.meshes[&(7, 0)].triangles));
    let borrowed =
        scene.prepare_compiled_quads(7, [0.; 3], &[&[vec![a, a, a, edit], vec![], vec![]]]);
    let result = scene.publish_compiled(1, 4, vec![borrowed], &[]).unwrap();
    assert_eq!((result.replaced_layers, result.retained_layers), (0, 1));

    let mut translated = crate::incremental::TranslatedScene::default();
    translated.update(&mut scene, [0.; 3]).unwrap();
    let weak = Arc::downgrade(&parts.blocks[2]);
    drop(current);
    let mut retired = scene.publish_compiled(1, 5, vec![], &[7]).unwrap();
    retired.release_retired(None).unwrap();
    assert_eq!(weak.strong_count(), 1);
    assert!(same_triangle(
        &translated.input().meshes[&(7, 0)].triangles.triangle(7),
        &edit.triangle(1)
    ));
    translated.update(&mut scene, [0.; 3]).unwrap();
    assert!(weak.upgrade().is_none());
}

#[test]
fn compact_retirement_keeps_translated_readers_alive_until_their_last_use() {
    let mut scene = scene();
    let quad = CompiledQuad {
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [[0., 0.]; 4],
        color: [1.; 4],
        texture_id: 1,
        flags: 0,
    };
    let plans = (7..9)
        .map(|key| {
            scene.prepare_compiled_quads(
                key,
                [16. * (key - 7) as f64, 0., 0.],
                &[&[vec![quad; 50_000], vec![], vec![]]],
            )
        })
        .collect();
    scene.publish_compiled(1, 1, plans, &[]).unwrap();
    let mut translated = crate::incremental::TranslatedScene::default();
    translated.update(&mut scene, [0.; 3]).unwrap();
    let weak = match &scene.meshes[&(7, 0)].triangles {
        MeshGeometry::Quads(data) => Arc::downgrade(data),
        _ => panic!("expected compact geometry"),
    };
    let mut retired = scene.publish_compiled(1, 2, vec![], &[7, 8]).unwrap();
    retired
        .release_retired(Some(&crate::workers::CpuWorkers::new(4).unwrap()))
        .unwrap();
    assert_eq!(weak.strong_count(), 1);
    assert_eq!(translated.input().meshes[&(7, 0)].triangles.len(), 100_000);
    translated.update(&mut scene, [0.; 3]).unwrap();
    assert!(weak.upgrade().is_none());
    assert!(translated.input().meshes.is_empty());
}

#[test]
fn compact_retention_compares_all_fields_exactly() {
    let quad = CompiledQuad {
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [[0., 0.]; 4],
        color: [1., 0., 0.5, 0.75],
        texture_id: 1,
        flags: 0,
    };
    for owned in [false, true] {
        let prepare = |scene: &SourceScene, quad| {
            if owned {
                scene.prepare_compiled_quad_fragments(7, [0.; 3], [[vec![quad], vec![], vec![]]])
            } else {
                scene.prepare_compiled_quads(7, [0.; 3], &[&[vec![quad], vec![], vec![]]])
            }
        };
        for field in 0..6 {
            let mut scene = scene();
            let first = prepare(&scene, quad);
            scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
            let old = scene.meshes[&(7, 0)].triangles.clone();
            let mut edit = quad;
            match field {
                0 => edit.positions[3][2] = -0.,
                1 => edit.uvs[3][0] = -0.,
                2 => edit.color[1] = -0.,
                3 => edit.color[3] = 0.5,
                4 => edit.texture_id = 0,
                5 => edit.flags = 1,
                _ => unreachable!(),
            }
            let next = prepare(&scene, edit);
            let result = scene.publish_compiled(1, 2, vec![next], &[]).unwrap();
            assert_eq!(result.replaced_layers, 1, "field {field}");
            assert!(!old.ptr_eq(&scene.meshes[&(7, 0)].triangles));
        }
    }
}

#[test]
fn each_rendered_attribute_and_origin_participates_in_retention() {
    for field in 0..7 {
        let mut scene = scene();
        let first = plan(&scene, vec![triangle()]);
        scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
        let old = scene.meshes[&(7, 0)].triangles.clone();
        let mut triangle = triangle();
        match field {
            0 => triangle.positions[0][0] = -0., // Numeric equality alone is insufficient.
            1 => triangle.colors[1][2] = 0.25,
            2 => triangle.uvs[2][1] = 0.125,
            3 => triangle.texture_id = 0,
            4 => triangle.flags = 2,
            _ => (),
        }
        let origin = if field == 5 { [16., 0., 0.] } else { [0.; 3] };
        let mut triangles = vec![triangle];
        if field == 6 {
            triangles.push(triangle);
        }
        let count = triangles.len();
        let changed = scene.prepare_compiled(7, origin, [triangles, vec![], vec![]]);
        let result = scene.publish_compiled(1, 2, vec![changed], &[]).unwrap();
        assert_eq!((result.replaced_layers, result.retained_layers), (1, 0));
        assert!(!crate::geometry::MeshGeometry::ptr_eq(
            &old,
            &scene.meshes[&(7, 0)].triangles
        ));
        assert_eq!(scene.meshes[&(7, 0)].revision.number(), 2);
        assert_eq!(scene.triangle_count, count);
        assert_eq!(scene.meshes[&(7, 0)].bounds, [[0., 0., 0.], [1., 1., 0.]]);
        assert_eq!(scene.sections.origin(7), Some(&origin));
    }
}

#[test]
fn invalid_publication_is_atomic_and_proofs_are_owner_bound() {
    let mut scene = scene();
    let first = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let old = scene.meshes[&(7, 0)].triangles.clone();
    let revision = scene.revision;
    let other = super::tests::scene();
    let foreign = plan(&other, vec![triangle()]);
    assert!(scene.publish_compiled(1, 2, vec![foreign], &[]).is_err());
    let duplicate = plan(&scene, vec![]);
    assert!(scene.publish_compiled(1, 2, vec![duplicate], &[7]).is_err());
    assert!(scene.publish_compiled(2, 2, vec![], &[7]).is_err());
    assert!(scene.publish_compiled(1, 1, vec![], &[7]).is_err());
    assert_eq!(scene.revision, revision);
    assert_eq!(scene.section_completed, SectionSequence(1));
    assert_eq!(scene.triangle_count, 1);
    assert!(crate::geometry::MeshGeometry::ptr_eq(
        &old,
        &scene.meshes[&(7, 0)].triangles
    ));
    scene.revision = u64::MAX;
    assert!(scene.publish_compiled(1, 2, vec![], &[7]).is_err());
    assert_eq!(scene.triangle_count, 1);
    let equal = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 2, vec![equal], &[]).unwrap();
    assert_eq!(scene.revision, u64::MAX);

    let mut missing = SourceScene {
        epoch: 1,
        ..Default::default()
    };
    let geometry = plan(&missing, vec![triangle()]);
    assert!(missing.publish_compiled(1, 1, vec![geometry], &[]).is_err());
    assert!(missing.meshes.is_empty());
    assert!(missing.sections.origin(7).is_none());
    assert_eq!(missing.section_completed, SectionSequence(0));
}

#[test]
fn empty_completion_removal_and_layer_clear_have_distinct_effects() {
    let mut scene = scene();
    let empty = plan(&scene, vec![]);
    scene.publish_compiled(1, 1, vec![empty], &[]).unwrap();
    assert_eq!(scene.sections.origin(7), Some(&[0.; 3]));
    let revision = scene.revision;
    let empty = plan(&scene, vec![]);
    scene.publish_compiled(1, 2, vec![empty], &[]).unwrap();
    assert_eq!(scene.revision, revision);
    let geometry = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 3, vec![geometry], &[]).unwrap();
    let empty = plan(&scene, vec![]);
    let result = scene.publish_compiled(1, 4, vec![empty], &[]).unwrap();
    assert_eq!(result.replaced_layers, 1);
    assert!(scene.meshes.is_empty());
    assert_eq!(scene.triangle_count, 0);
    assert!(scene.sections.origin(7).is_some());
    scene.texture_lifetime.retire(1);
    scene.collect_textures().unwrap();
    assert!(!scene.textures.contains_key(&1));
    let revision = scene.revision;
    scene.publish_compiled(1, 5, vec![], &[7]).unwrap();
    assert_eq!(scene.revision, revision + 1);
    assert!(scene.sections.origin(7).is_none());
    scene.publish_compiled(1, 6, vec![], &[7]).unwrap();
    assert_eq!(scene.revision, revision + 1);
}

#[test]
fn retirement_releases_only_the_publishers_reference_and_joins() {
    let mut scene = scene();
    let first = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 1, vec![first], &[]).unwrap();
    let consumer = scene.meshes[&(7, 0)].triangles.clone();
    let weak = Arc::downgrade(triangle_storage(&consumer));
    let mut publication = scene.publish_compiled(1, 2, vec![], &[7]).unwrap();
    assert!(scene.meshes.is_empty());
    assert_eq!(Arc::strong_count(triangle_storage(&consumer)), 2);
    publication
        .release_retired(Some(&crate::workers::CpuWorkers::new(2).unwrap()))
        .unwrap();
    assert_eq!(Arc::strong_count(triangle_storage(&consumer)), 1);
    assert!(same_triangle(&consumer.triangle(0), &triangle()));
    drop(consumer);
    assert!(weak.upgrade().is_none());
    // Caller may also use ordinary RAII instead of a pool; correctness is identical.
    let next = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 3, vec![next], &[]).unwrap();
    let weak = Arc::downgrade(triangle_storage(&scene.meshes[&(7, 0)].triangles));
    let publication = scene.publish_compiled(1, 4, vec![], &[7]).unwrap();
    assert!(weak.upgrade().is_some());
    drop(publication);
    assert!(weak.upgrade().is_none());
}

#[test]
fn large_parallel_retirement_preserves_external_snapshots() {
    let mut scene = scene();
    let first = plan(&scene, vec![triangle(); 100_000]);
    let second = scene.prepare_compiled(
        8,
        [16., 0., 0.],
        [vec![triangle(); 100_000], vec![], vec![]],
    );
    scene
        .publish_compiled(1, 1, vec![first, second], &[])
        .unwrap();
    let consumer = scene.meshes[&(7, 0)].triangles.clone();
    let unowned = Arc::downgrade(triangle_storage(&scene.meshes[&(8, 0)].triangles));
    let mut publication = scene.publish_compiled(1, 2, vec![], &[7, 8]).unwrap();
    publication
        .release_retired(Some(&crate::workers::CpuWorkers::new(2).unwrap()))
        .unwrap();
    assert!(unowned.upgrade().is_none());
    assert_eq!(Arc::strong_count(triangle_storage(&consumer)), 1);
    assert_eq!(consumer.len(), 100_000);
}

fn triangle_storage(value: &MeshGeometry) -> &Arc<[Triangle]> {
    let MeshGeometry::Triangles(values) = value else {
        panic!("expected triangle source")
    };
    values
}
