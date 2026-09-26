use prime_scene::{Scene, SourceScene, protocol::MAGIC};
use std::sync::Arc;

fn header(op: u32, epoch: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, 1, op, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(epoch.to_le_bytes());
    bytes
}
fn layer(id: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [id, 0, id % 3, 4, 4, 24, 0, 12, 16, 0] {
        bytes.extend(value.to_le_bytes());
    }
    for ([x, y], [u, v]) in [[0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]
        .into_iter()
        .zip([[0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]])
    {
        for value in [x, y, 0.125] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend([128, 64, 32, 127]);
        bytes.extend(u.to_le_bytes());
        bytes.extend(v.to_le_bytes());
    }
    bytes
}
fn section(epoch: u64, sequence: u64, layers: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = header(8, epoch);
    bytes.extend(91_u64.to_le_bytes());
    bytes.extend(sequence.to_le_bytes());
    for value in [29_999_984_f64, 64.0, -16.0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend((layers.len() as u32).to_le_bytes());
    bytes.extend(0_u32.to_le_bytes());
    for layer in layers {
        bytes.extend(layer);
    }
    bytes
}
fn remove(sequence: u64) -> Vec<u8> {
    let mut bytes = header(3, 1);
    bytes.extend(91_u64.to_le_bytes());
    bytes.extend(sequence.to_le_bytes());
    bytes
}
fn legacy(sequence: u64, id: u32) -> Vec<u8> {
    let mut bytes = header(2, 1);
    bytes.extend(91_u64.to_le_bytes());
    bytes.extend(sequence.to_le_bytes());
    for value in [29_999_984_f64, 64.0, -16.0] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [4_u32, 24, 0, 12, 16, 4, 0, id % 3, id, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(&layer(id)[40..]);
    bytes
}
fn source() -> SourceScene {
    let mut source = SourceScene::default();
    source.submit(&header(1, 1)).unwrap();
    source
}
fn snapshot(source: &SourceScene) -> Scene {
    source.translate([29_999_984.0, 64.0, -16.0]).unwrap()
}
fn assert_same(before: &Scene, after: &Scene) {
    assert_eq!(before.revision, after.revision);
    assert_eq!(before.meshes.len(), after.meshes.len());
    for (key, mesh) in &before.meshes {
        let next = &after.meshes[key];
        assert_eq!(mesh.revision, next.revision);
        assert_eq!(mesh.origin, next.origin);
        assert!(Arc::ptr_eq(&mesh.triangles, &next.triangles));
    }
}
fn texture(source: &mut SourceScene, id: u32) {
    let mut bytes = header(4, 1);
    for value in [id, 1, 1, 0] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend([255; 4]);
    source.submit(&bytes).unwrap();
}

#[test]
fn exact_content_keeps_leases_and_generation_while_source_order_advances() {
    let mut source = source();
    source
        .submit(&section(1, 1, &[layer(0), layer(1)]))
        .unwrap();
    let before = snapshot(&source);
    source
        .submit(&section(1, 10, &[layer(1), layer(0)]))
        .unwrap();
    assert_same(&before, &snapshot(&source));
    // The old content generation is not the source admission sequence.
    for packet in [
        section(1, 10, &[layer(0)]),
        section(1, 9, &[]),
        legacy(9, 2),
        legacy(10, 0),
        remove(10),
    ] {
        assert!(source.submit(&packet).is_err());
    }
    assert_same(&before, &snapshot(&source));
    source.submit(&legacy(11, 2)).unwrap();
    assert!(source.submit(&section(1, 11, &[layer(0)])).is_err());
    source
        .submit(&section(1, 12, &[layer(0), layer(1), layer(2)]))
        .unwrap();
    let after = snapshot(&source);
    assert_eq!(after.meshes[&(91, 0)].revision, 1);
    assert_eq!(after.meshes[&(91, 2)].revision, 11);
    source.submit(&remove(13)).unwrap();
    for packet in [legacy(12, 3), section(1, 13, &[layer(0)])] {
        assert!(source.submit(&packet).is_err());
    }
    source.submit(&section(1, 14, &[layer(0)])).unwrap();
    assert_eq!(snapshot(&source).meshes[&(91, 0)].revision, 14);
    source.submit(&header(1, 2)).unwrap();
    assert!(source.submit(&section(1, 15, &[layer(0)])).is_err());
    source.submit(&section(2, 1, &[layer(0)])).unwrap();
}

#[test]
fn source_layout_and_equivalent_triangulation_do_not_invent_content_changes() {
    let mut source = source();
    source.submit(&section(1, 1, &[layer(0)])).unwrap();
    let before = snapshot(&source);
    let quad = layer(0);
    let mut padded = quad[..40].to_vec();
    padded[20..24].copy_from_slice(&28_u32.to_le_bytes());
    for vertex in quad[40..].as_chunks::<24>().0 {
        padded.extend(vertex);
        padded.extend([7, 8, 9, 10]);
    }
    source.submit(&section(1, 2, &[padded])).unwrap();
    assert_same(&before, &snapshot(&source));
    let mut triangles = quad[..40].to_vec();
    triangles[12..16].copy_from_slice(&3_u32.to_le_bytes());
    triangles[16..20].copy_from_slice(&6_u32.to_le_bytes());
    for vertex in [0, 1, 2, 2, 3, 0] {
        triangles.extend(&quad[40 + vertex * 24..40 + (vertex + 1) * 24]);
    }
    source.submit(&section(1, 3, &[triangles])).unwrap();
    assert_same(&before, &snapshot(&source));
}

#[test]
fn every_rendered_field_change_is_published_and_unaffected_layer_is_shared() {
    for (offset, value) in [
        (4, 1_u32),
        (8, 2),
        (40, 0.25_f32.to_bits()),
        (52, 0xff123456),
        (56, 0.5_f32.to_bits()),
        (60, 0.75_f32.to_bits()),
    ] {
        let mut source = source();
        texture(&mut source, 1);
        source
            .submit(&section(1, 1, &[layer(0), layer(1)]))
            .unwrap();
        let before = snapshot(&source);
        let mut changed = layer(0);
        changed[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        source.submit(&section(1, 2, &[changed, layer(1)])).unwrap();
        let after = snapshot(&source);
        assert_eq!(after.revision, before.revision + 1, "field {offset}");
        assert_eq!(after.meshes[&(91, 0)].revision, 2);
        assert!(!Arc::ptr_eq(
            &before.meshes[&(91, 0)].triangles,
            &after.meshes[&(91, 0)].triangles
        ));
        assert!(Arc::ptr_eq(
            &before.meshes[&(91, 1)].triangles,
            &after.meshes[&(91, 1)].triangles
        ));
    }
    let mut source = source();
    source.submit(&section(1, 1, &[layer(0)])).unwrap();
    let before = snapshot(&source);
    let mut moved = section(1, 2, &[layer(0)]);
    moved[40..48].copy_from_slice(&29_999_985_f64.to_le_bytes());
    source.submit(&moved).unwrap();
    let after = snapshot(&source);
    assert_eq!(after.revision, before.revision + 1);
    assert_eq!(after.meshes[&(91, 0)].origin, [1.0, 0.0, 0.0]);
}

#[test]
fn replacing_all_layers_clearing_and_empty_recompiles_are_atomic() {
    let mut source = source();
    source
        .submit(&section(1, 1, &[layer(0), layer(1)]))
        .unwrap();
    let before = snapshot(&source);
    source
        .submit(&section(1, 2, &[layer(1), layer(2)]))
        .unwrap();
    let after = snapshot(&source);
    assert_eq!(after.meshes.len(), 2);
    assert!(!after.meshes.contains_key(&(91, 0)));
    assert_eq!(after.revision, before.revision + 1);
    assert!(Arc::ptr_eq(
        &before.meshes[&(91, 1)].triangles,
        &after.meshes[&(91, 1)].triangles
    ));
    source.submit(&section(1, 3, &[])).unwrap();
    let empty = snapshot(&source);
    assert!(empty.meshes.is_empty());
    let mut empty_layer = layer(0);
    empty_layer.truncate(40);
    empty_layer[16..20].copy_from_slice(&0_u32.to_le_bytes());
    source.submit(&section(1, 4, &[empty_layer])).unwrap();
    assert_same(&empty, &snapshot(&source));
    assert!(source.submit(&legacy(4, 2)).is_err());
    source.submit(&section(1, 5, &[layer(0)])).unwrap();
    assert_eq!(snapshot(&source).triangle_count(), 2);
    assert_eq!(before.triangle_count(), 4);
}

#[test]
fn all_truncations_and_late_invalid_layers_preserve_content_and_admission_sequence() {
    let mut source = source();
    source
        .submit(&section(1, 1, &[layer(0), layer(1)]))
        .unwrap();
    let before = snapshot(&source);
    let mut changed = layer(0);
    changed[52..56].copy_from_slice(&0xffabcdef_u32.to_le_bytes());
    let candidate = section(1, 2, &[changed, layer(2)]);
    for length in 0..candidate.len() {
        assert!(
            source.submit(&candidate[..length]).is_err(),
            "prefix {length}"
        );
        assert_same(&before, &snapshot(&source));
    }
    let second = 72 + 40 + 96;
    for (offset, value) in [
        (second, 0_u32),
        (second, 256),
        (second + 4, 999),
        (second + 8, 3),
        (second + 12, 2),
        (second + 16, 5),
        (second + 20, 23),
        (second + 24, u32::MAX),
        (second + 28, u32::MAX),
        (second + 32, u32::MAX),
        (second + 36, 1),
        (second + 40, f32::NAN.to_bits()),
        (second + 56, f32::INFINITY.to_bits()),
    ] {
        let mut bad = candidate.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(source.submit(&bad).is_err(), "field {offset}: {value}");
        assert_same(&before, &snapshot(&source));
    }
    let mut trailing = candidate.clone();
    trailing.push(0);
    assert!(source.submit(&trailing).is_err());
    source.submit(&candidate).unwrap();
    assert_eq!(snapshot(&source).meshes[&(91, 0)].revision, 2);
    source.submit(&section(1, 3, &[])).unwrap();
    assert!(snapshot(&source).meshes.is_empty());
}

#[test]
fn malformed_headers_and_content_revision_overflow_never_consume_sequence() {
    let mut source = source();
    source.submit(&section(1, 1, &[layer(0)])).unwrap();
    let before = snapshot(&source);
    let candidate = section(1, 2, &[layer(0)]);
    for (offset, value) in [(64, 257_u32), (68, 1)] {
        let mut bad = candidate.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(source.submit(&bad).is_err());
    }
    for origin in [f64::NAN, 32_000_001.0] {
        let mut bad = candidate.clone();
        bad[40..48].copy_from_slice(&origin.to_le_bytes());
        assert!(source.submit(&bad).is_err());
    }
    assert_same(&before, &snapshot(&source));
    source.revision = u64::MAX;
    source.submit(&candidate).unwrap();
    assert_eq!(source.revision, u64::MAX);
    assert!(source.submit(&section(1, 3, &[])).is_err());
    source.submit(&section(1, 3, &[layer(0)])).unwrap();
    assert_eq!(source.revision, u64::MAX);
}
