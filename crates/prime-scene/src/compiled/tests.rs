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
