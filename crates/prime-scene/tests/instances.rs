use prime_scene::{Instance, SourceScene, instances::InstanceContext, protocol::MAGIC};
use std::{collections::BTreeMap, sync::Arc};

fn header(op: u32, epoch: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, 1, op, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(epoch.to_le_bytes());
    bytes
}

fn prototype(id: u64, revision: u64, texture: u32, width: f32) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend(id.to_le_bytes());
    bytes.extend(revision.to_le_bytes());
    bytes.extend(1_u32.to_le_bytes());
    bytes.extend(0_u32.to_le_bytes());
    for value in [texture, 1, 4, 4, 24, 0, 12, 16] {
        bytes.extend(value.to_le_bytes());
    }
    for (index, position) in [
        [0.0, 0.0, 0.0],
        [width, 0.0, 0.0],
        [width, 1.0, 0.0],
        [0.0, 1.0, 0.0],
    ]
    .into_iter()
    .enumerate()
    {
        for coordinate in position {
            bytes.extend(coordinate.to_le_bytes());
        }
        bytes.extend([128 + index as u8, 64, 32, 127]);
        bytes.extend((index as f32 / 4.0).to_le_bytes());
        bytes.extend(0.75_f32.to_le_bytes());
    }
    bytes
}

fn instance(revision: u64, prototype_id: u64) -> Instance {
    Instance {
        revision,
        prototype_id,
        origin: [29_999_984.125, 64.0, -16.0],
        transform: [
            1.0, 0.0, 0.0, 0.25, 0.0, 1.0, 0.0, 0.5, 0.0, 0.0, 1.0, -0.75,
        ],
        texture_id: u32::MAX,
        flags: u32::MAX,
        tint: [0xab, 0xcd, 0xef, 0x7f],
        uv_transform: [0.25, 0.5, 0.125, 0.375],
    }
}

fn encoded_instance(id: u64, instance: &Instance) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [id, instance.revision, instance.prototype_id] {
        bytes.extend(value.to_le_bytes());
    }
    for value in instance.origin {
        bytes.extend(value.to_le_bytes());
    }
    for value in instance.transform {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(instance.texture_id.to_le_bytes());
    bytes.extend(instance.flags.to_le_bytes());
    bytes.extend(instance.tint);
    bytes.extend(0_u32.to_le_bytes());
    for value in instance.uv_transform {
        bytes.extend(value.to_le_bytes());
    }
    assert_eq!(bytes.len(), 128);
    bytes
}

fn batch(
    epoch: u64,
    sequence: u64,
    prototypes: &[Vec<u8>],
    prototype_removals: &[(u64, u64)],
    instances: &[(u64, Instance)],
    instance_removals: &[(u64, u64)],
) -> Vec<u8> {
    let mut bytes = header(7, epoch);
    bytes.extend(sequence.to_le_bytes());
    for length in [
        prototypes.len(),
        prototype_removals.len(),
        instances.len(),
        instance_removals.len(),
    ] {
        bytes.extend((length as u32).to_le_bytes());
    }
    for prototype in prototypes {
        let mut stamped = prototype.clone();
        stamped[8..16].copy_from_slice(&sequence.to_le_bytes());
        bytes.extend(stamped);
    }
    for &(id, _) in prototype_removals {
        bytes.extend(id.to_le_bytes());
        bytes.extend(sequence.to_le_bytes());
    }
    for (id, value) in instances {
        let mut stamped = value.clone();
        stamped.revision = sequence;
        bytes.extend(encoded_instance(*id, &stamped));
    }
    for &(id, _) in instance_removals {
        bytes.extend(id.to_le_bytes());
        bytes.extend(sequence.to_le_bytes());
    }
    bytes
}

fn initialized() -> SourceScene {
    let mut source = SourceScene::default();
    source.submit(&header(1, 1)).unwrap();
    source
        .submit(&batch(
            1,
            1,
            &[prototype(10, 1, 0, 1.0)],
            &[],
            &[(20, instance(1, 10))],
            &[],
        ))
        .unwrap();
    source
}

fn texture(id: u32) -> Vec<u8> {
    let mut bytes = header(4, 1);
    for value in [id, 1, 1, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend([20, 40, 80, 128]);
    bytes
}

#[test]
fn wire_preserves_double_world_origin_affine_rgba_and_uv_without_baking() {
    let source = initialized();
    let scene = source.instances();
    assert_eq!(scene.epoch, 1);
    assert_eq!(scene.resource_revision, 1);
    assert_eq!(scene.instance_revision, 1);
    assert_eq!(scene.instances[&20], instance(1, 10));
    let p = &scene.prototypes[&10];
    assert_eq!(p.bounds, [[0.0, 0.0, 0.0], [1.0, 1.0, 0.0]]);
    assert_eq!(p.triangles.len(), 2);
    assert_eq!(
        p.triangles[1].positions,
        [[1.0, 1.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 0.0]]
    );
    assert_eq!(
        p.triangles[0].colors[0],
        [128.0 / 255.0, 64.0 / 255.0, 32.0 / 255.0, 127.0 / 255.0]
    );
    assert_eq!(p.triangles[0].uvs[0], [0.0, 0.75]);
    let wire = encoded_instance(20, &scene.instances[&20]);
    assert_eq!(&wire[104..108], &[0xab, 0xcd, 0xef, 0x7f]);
    assert_eq!(
        u32::from_le_bytes(wire[104..108].try_into().unwrap()),
        0x7fefcdab
    );
    assert_eq!(&wire[112..116], &0.25_f32.to_le_bytes());
    // Static translation/rebase does not mutate persistent source transforms or bake tint/UV.
    source.translate([29_999_744.0, 0.0, 0.0]).unwrap();
    assert_eq!(source.instances().instances[&20], instance(1, 10));
}

#[test]
fn ten_thousand_instances_share_one_prototype_and_stationary_frames_do_no_work() {
    let mut source = SourceScene::default();
    source.submit(&header(1, 1)).unwrap();
    let instances: Vec<_> = (1..=10_000).map(|id| (id, instance(1, 10))).collect();
    source
        .submit(&batch(
            1,
            1,
            &[prototype(10, 1, 0, 1.0)],
            &[],
            &instances,
            &[],
        ))
        .unwrap();
    let triangles = source.instances().prototypes[&10].triangles.clone();
    let untouched = std::ptr::from_ref(&source.instances().instances[&9_999]);
    for _ in 0..120 {
        let scene = source.instances(); // No empty packet is sent on a stationary frame.
        assert_eq!(scene.instance_revision, 1);
        assert_eq!(scene.resource_revision, 1);
        assert_eq!(scene.instances.len(), 10_000);
    }
    let mut moved = instance(2, 10);
    moved.transform[3] = 4.0;
    source
        .submit(&batch(1, 2, &[], &[], &[(1, moved.clone())], &[]))
        .unwrap();
    assert!(Arc::ptr_eq(
        &triangles,
        &source.instances().prototypes[&10].triangles
    ));
    assert_eq!(
        std::ptr::from_ref(&source.instances().instances[&9_999]),
        untouched
    );
    assert_eq!(source.instances().resource_revision, 1);
    assert_eq!(source.instances().instance_revision, 2);
    assert_eq!(source.instances().instances[&1], moved);
    assert_eq!(source.revision, 1);
}

#[test]
fn final_state_validation_allows_atomic_prototype_replacement_and_instance_migration() {
    let mut source = initialized();
    let mut replacement = instance(2, 11);
    replacement.origin[1] = 80.0;
    let valid = batch(
        1,
        2,
        &[prototype(11, 1, 0, 2.0)],
        &[(10, 2)],
        &[(20, replacement.clone())],
        &[],
    );
    let invalid = batch(1, 2, &[prototype(11, 1, 0, 2.0)], &[(10, 2)], &[], &[]);
    assert!(source.submit(&invalid).is_err());
    assert_eq!(source.instance_sequence(), 1);
    assert_eq!(source.instances().prototypes.len(), 1);
    assert!(source.instances().prototypes.contains_key(&10));
    source.submit(&valid).unwrap();
    assert!(!source.instances().prototypes.contains_key(&10));
    assert_eq!(source.instances().instances[&20], replacement);
    assert_eq!(source.instances().resource_revision, 2);
    assert_eq!(source.instances().instance_revision, 2);

    // Prototype edits are seen by existing instances without resubmitting those instances.
    source
        .submit(&batch(1, 3, &[prototype(11, 2, 0, 3.0)], &[], &[], &[]))
        .unwrap();
    assert_eq!(source.instances().instance_revision, 2);
    assert_eq!(source.instances().instances[&20].revision, 2);
    assert_eq!(source.instances().prototypes[&11].bounds[1][0], 3.0);
    source
        .submit(&batch(1, 4, &[], &[(11, 3)], &[], &[(20, 3)]))
        .unwrap();
    assert!(source.instances().instances.is_empty());
    assert!(source.instances().prototypes.is_empty());
}

#[test]
fn every_truncation_and_late_invalid_record_is_atomic_and_does_not_consume_sequence() {
    let mut source = initialized();
    let old_geometry = source.instances().prototypes[&10].triangles.clone();
    let old_instance = source.instances().instances[&20].clone();
    let valid = batch(
        1,
        2,
        &[prototype(10, 2, 0, 2.0)],
        &[],
        &[(20, instance(2, 10)), (21, instance(1, 10))],
        &[],
    );
    for length in 0..valid.len() {
        assert!(
            source.submit(&valid[..length]).is_err(),
            "accepted prefix {length}"
        );
        assert_eq!(source.instance_sequence(), 1);
        assert_eq!(source.instances().resource_revision, 1);
        assert_eq!(source.instances().instance_revision, 1);
        assert_eq!(source.instances().instances[&20], old_instance);
        assert!(Arc::ptr_eq(
            &old_geometry,
            &source.instances().prototypes[&10].triangles
        ));
    }
    let mut bad = valid.clone();
    bad.push(0);
    assert!(source.submit(&bad).is_err());
    let bad = batch(
        1,
        2,
        &[prototype(10, 2, 0, 2.0)],
        &[],
        &[(20, instance(2, 10)), (21, instance(1, 99))],
        &[],
    );
    assert!(source.submit(&bad).is_err());
    assert_eq!(source.instances().instances.len(), 1);
    source.submit(&valid).unwrap();
    assert_eq!(source.instance_sequence(), 2);
    assert_eq!(source.instances().instances.len(), 2);
    assert_eq!(old_geometry[0].positions[1][0], 1.0);
}

#[test]
fn globally_ordered_batches_block_stale_replay_and_allow_visible_reappearance() {
    let mut source = initialized();
    source
        .submit(&batch(1, 2, &[], &[], &[], &[(20, 5)]))
        .unwrap();
    assert!(source.instances().instances.is_empty());
    for revision in [0_u64, 1, 2, 4] {
        let mut bad = batch(1, 3, &[], &[], &[(20, instance(3, 10))], &[]);
        bad[56..64].copy_from_slice(&revision.to_le_bytes());
        assert!(source.submit(&bad).is_err());
    }
    source
        .submit(&batch(1, 3, &[], &[], &[(20, instance(6, 10))], &[]))
        .unwrap();
    assert_eq!(source.instances().instances[&20].revision, 3);
    source
        .submit(&batch(1, 4, &[], &[(10, 2)], &[], &[(20, 7)]))
        .unwrap();
    let mut bad = batch(1, 5, &[prototype(10, 5, 0, 1.0)], &[], &[], &[]);
    bad[56..64].copy_from_slice(&2_u64.to_le_bytes());
    assert!(source.submit(&bad).is_err());
    source
        .submit(&batch(
            1,
            5,
            &[prototype(10, 3, 0, 1.0)],
            &[],
            &[(20, instance(8, 10))],
            &[],
        ))
        .unwrap();
    assert_eq!(source.instances().prototypes[&10].revision, 5);
    assert!(
        source
            .submit(&batch(1, 5, &[], &[], &[(20, instance(9, 10))], &[]))
            .is_err()
    );
    source.submit(&header(1, 2)).unwrap();
    assert_eq!(source.instance_sequence(), 0);
    assert_eq!(source.instances().epoch, 2);
    assert_eq!(source.instances().resource_revision, 0);
    assert!(source.instances().prototypes.is_empty());
    assert!(
        source
            .submit(&batch(1, 6, &[prototype(10, 4, 0, 1.0)], &[], &[], &[]))
            .is_err()
    );
    source
        .submit(&batch(
            2,
            1,
            &[prototype(10, 1, 0, 1.0)],
            &[],
            &[(20, instance(1, 10))],
            &[],
        ))
        .unwrap();
}

#[test]
fn material_references_are_checked_before_publication_and_max_means_inherit() {
    let mut source = initialized();
    let mut textured = instance(2, 10);
    textured.texture_id = 17;
    textured.flags = 2;
    let valid = batch(
        1,
        2,
        &[prototype(11, 1, 17, 1.0)],
        &[],
        &[(20, textured)],
        &[],
    );
    assert!(source.submit(&valid).is_err());
    assert_eq!(source.instance_sequence(), 1);
    source.submit(&texture(17)).unwrap();
    source.submit(&valid).unwrap();
    assert_eq!(source.instances().instances[&20].texture_id, 17);
    assert_eq!(source.instances().instances[&20].flags, 2);
    assert_eq!(
        source.instances().prototypes[&11].triangles[0].texture_id,
        17
    );
    let inherit = instance(3, 11);
    source
        .submit(&batch(1, 3, &[], &[], &[(20, inherit)], &[]))
        .unwrap();
    assert_eq!(source.instances().instances[&20].texture_id, u32::MAX);
    assert_eq!(source.instances().instances[&20].flags, u32::MAX);
}

#[test]
fn invalid_affine_material_uv_and_reserved_fields_cannot_publish() {
    let mut source = initialized();
    let valid = batch(1, 2, &[], &[], &[(20, instance(2, 10))], &[]);
    for (offset, value) in [
        (48 + 48, f32::NAN.to_bits()),
        (48 + 48, 0),
        (48 + 100, 3),
        (48 + 108, 1),
        (48 + 112, f32::INFINITY.to_bits()),
    ] {
        let mut bad = valid.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(
            source.submit(&bad).is_err(),
            "accepted invalid field {offset}"
        );
        assert_eq!(source.instance_sequence(), 1);
    }
    for origin in [f64::NAN, 32_000_001.0] {
        let mut bad = valid.clone();
        bad[72..80].copy_from_slice(&origin.to_le_bytes());
        assert!(source.submit(&bad).is_err());
    }
    let mut reflected = instance(2, 10);
    reflected.transform = [
        0.0, -2.0, 0.0, 4.0, 3.0, 0.0, 0.25, -5.0, 0.0, 0.0, -0.5, 6.0,
    ];
    source
        .submit(&batch(1, 2, &[], &[], &[(20, reflected.clone())], &[]))
        .unwrap();
    assert_eq!(source.instances().instances[&20], reflected);
}

#[test]
fn duplicate_ids_zero_ids_empty_batches_and_conflicting_commands_are_rejected() {
    let mut source = initialized();
    let candidates = [
        batch(1, 2, &[], &[], &[], &[]),
        batch(
            1,
            2,
            &[prototype(11, 1, 0, 1.0), prototype(11, 2, 0, 1.0)],
            &[],
            &[],
            &[],
        ),
        batch(1, 2, &[prototype(11, 1, 0, 1.0)], &[(11, 2)], &[], &[]),
        batch(
            1,
            2,
            &[],
            &[],
            &[(20, instance(2, 10)), (20, instance(3, 10))],
            &[],
        ),
        batch(1, 2, &[], &[], &[(20, instance(2, 10))], &[(20, 3)]),
        batch(1, 2, &[], &[], &[], &[(20, 2), (20, 3)]),
        batch(1, 2, &[prototype(0, 1, 0, 1.0)], &[], &[], &[]),
        batch(1, 2, &[], &[], &[(0, instance(1, 10))], &[]),
        batch(1, 2, &[], &[], &[], &[(0, 1)]),
    ];
    for bad in candidates {
        assert!(source.submit(&bad).is_err());
        assert_eq!(source.instance_sequence(), 1);
        assert_eq!(source.instances().instances.len(), 1);
        assert_eq!(source.instances().prototypes.len(), 1);
    }
}

#[test]
fn absent_removals_and_revision_only_instance_updates_preserve_render_versions() {
    let mut source = initialized();
    source.revision = u64::MAX; // The persistent stream does not spend terrain generations.
    source
        .submit(&batch(
            1,
            2,
            &[],
            &[(99, 5)],
            &[(20, instance(2, 10))],
            &[(98, 6)],
        ))
        .unwrap();
    assert_eq!(source.instances().resource_revision, 1);
    assert_eq!(source.instances().instance_revision, 1);
    assert_eq!(source.instances().instances[&20].revision, 2);
    assert_eq!(source.revision, u64::MAX);
    // Old batches cannot resurrect removed identities; a genuinely new batch can.
    assert!(
        source
            .submit(&batch(1, 2, &[prototype(99, 2, 0, 1.0)], &[], &[], &[]))
            .is_err()
    );
    source
        .submit(&batch(
            1,
            3,
            &[prototype(99, 3, 0, 1.0)],
            &[],
            &[(98, instance(3, 10))],
            &[],
        ))
        .unwrap();
}

#[test]
fn continuous_birth_and_death_does_not_retain_dead_identity_history() {
    let mut source = initialized();
    // More distinct identities than the resident cap, with at most 1,001 live.
    // This is 263 small atomic transactions rather than 263k individual calls.
    let mut previous = Vec::new();
    for group in 0..263_u64 {
        let sequence = group + 2;
        let additions: Vec<_> = (1..=1000)
            .map(|i| (1000 + group * 1000 + i, instance(sequence, 10)))
            .collect();
        source
            .submit(&batch(1, sequence, &[], &[], &additions, &previous))
            .unwrap();
        assert_eq!(source.instances().instances.len(), 1001);
        previous = additions
            .iter()
            .map(|(id, _)| (*id, sequence + 1))
            .collect();
    }
    assert_eq!(source.instances().resource_revision, 1);
    assert_eq!(source.instance_sequence(), 264);
}

#[test]
fn unique_geometry_capacity_is_shared_with_other_streams_and_not_multiplied_by_instance_count() {
    let mut context = InstanceContext::new(1);
    let textures = BTreeMap::new();
    let initial = batch(
        1,
        1,
        &[prototype(10, 1, 0, 1.0)],
        &[],
        &[(20, instance(1, 10))],
        &[],
    );
    assert!(context.submit(&initial, &textures, 7_999_999).is_err());
    assert_eq!(context.sequence(), 0);
    context.submit(&initial, &textures, 7_999_998).unwrap();
    assert_eq!(context.triangle_count(), 2);
    let many: Vec<_> = (21..=10_020).map(|id| (id, instance(1, 10))).collect();
    context
        .submit(&batch(1, 2, &[], &[], &many, &[]), &textures, 7_999_998)
        .unwrap();
    assert_eq!(context.triangle_count(), 2);
    assert_eq!(context.scene().instances.len(), 10_001);
    let replacement = batch(1, 3, &[prototype(11, 1, 0, 2.0)], &[], &[], &[]);
    assert!(context.submit(&replacement, &textures, 7_999_998).is_err());
    assert_eq!(context.sequence(), 2);
}
