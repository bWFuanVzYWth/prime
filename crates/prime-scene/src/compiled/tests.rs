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
    assert!(Arc::ptr_eq(&triangles, &scene.meshes[&(7, 0)].triangles));

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
    assert!(Arc::ptr_eq(&old, &scene.meshes[&(7, 0)].triangles));
    let parts = [[vec![b], vec![], vec![]], [vec![a, b], vec![], vec![]]];
    let changed = scene.prepare_compiled_parts(7, [0.; 3], &[&parts[0], &parts[1]]);
    let result = scene.publish_compiled(1, 3, vec![changed], &[]).unwrap();
    assert_eq!(result.replaced_layers, 1);
    let mesh = &scene.meshes[&(7, 0)];
    assert!(
        mesh.triangles
            .iter()
            .zip([b, a, b])
            .all(|(a, b)| same_triangle(a, &b))
    );
    assert_eq!(mesh.bounds, [[-2., 0., 0.], [1., 3., 4.]]);
    assert_eq!(old.len(), 3); // Another consumer's previous snapshot remains valid.
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
        assert!(!Arc::ptr_eq(&old, &scene.meshes[&(7, 0)].triangles));
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
    assert!(Arc::ptr_eq(&old, &scene.meshes[&(7, 0)].triangles));
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
    let weak = Arc::downgrade(&consumer);
    let mut publication = scene.publish_compiled(1, 2, vec![], &[7]).unwrap();
    assert!(scene.meshes.is_empty());
    assert_eq!(Arc::strong_count(&consumer), 2);
    publication
        .release_retired(Some(&crate::workers::CpuWorkers::new(2).unwrap()))
        .unwrap();
    assert_eq!(Arc::strong_count(&consumer), 1);
    assert!(same_triangle(&consumer[0], &triangle()));
    drop(consumer);
    assert!(weak.upgrade().is_none());
    // Caller may also use ordinary RAII instead of a pool; correctness is identical.
    let next = plan(&scene, vec![triangle()]);
    scene.publish_compiled(1, 3, vec![next], &[]).unwrap();
    let weak = Arc::downgrade(&scene.meshes[&(7, 0)].triangles);
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
    let unowned = Arc::downgrade(&scene.meshes[&(8, 0)].triangles);
    let mut publication = scene.publish_compiled(1, 2, vec![], &[7, 8]).unwrap();
    publication
        .release_retired(Some(&crate::workers::CpuWorkers::new(2).unwrap()))
        .unwrap();
    assert!(unowned.upgrade().is_none());
    assert_eq!(Arc::strong_count(&consumer), 1);
    assert_eq!(consumer.len(), 100_000);
}
