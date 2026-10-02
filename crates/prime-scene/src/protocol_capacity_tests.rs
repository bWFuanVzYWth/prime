use super::*;
use crate::instances::InstanceContext;
use crate::scene::{Mesh, MeshVersion, SectionSequence};
use std::sync::Arc;

fn section(key: u64, sequence: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [MAGIC, ABI_VERSION, 8, 0] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [1_u64, key, sequence] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [0_f64; 3] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [1_u32, 0, 0, 0, 0, 4, 4, 24, 0, 12, 16, 0] {
        bytes.extend(value.to_le_bytes());
    }
    for position in [
        [0_f32, 0.0, 0.0],
        [1.0, 0.0, 0.0],
        [1.0, 1.0, 0.0],
        [0.0, 1.0, 0.0],
    ] {
        for value in position {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend([255; 4]);
        bytes.extend([0; 8]);
    }
    bytes
}

#[test]
fn source_section_publication_crosses_signed_32_bit_total_without_large_allocations() {
    // All mesh counts and aggregate invariants are real. Sharing immutable fixture
    // arrays avoids allocating 232 GiB merely to test aggregate protocol arithmetic.
    let triangle = Triangle {
        positions: [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        colors: [[1.0; 4]; 3],
        uvs: [[0.0; 2]; 3],
        texture_id: 0,
        flags: 0,
    };
    let shared: Arc<[Triangle]> = vec![triangle; 32768].into();
    let mut source = SourceScene {
        epoch: 1,
        instances: InstanceContext::new(1),
        ..Default::default()
    };
    for key in 0..65536_u64 {
        let triangles = if key == 65535 {
            shared[..32766].into()
        } else {
            shared.clone()
        };
        source.triangle_count += triangles.len();
        source.meshes.insert(
            (key, 0),
            Mesh {
                revision: MeshVersion::captured(SectionSequence(1)),
                origin: [0.0; 3],
                triangles: triangles.into(),
                bounds: [[0.0; 3], [1.0, 1.0, 0.0]],
                texture_id: 0,
                flags: 0,
            },
        );
    }
    assert_eq!(source.triangle_count, (1_usize << 31) - 2);
    source.submit(&section(65536, 1)).unwrap();
    assert_eq!(source.triangle_count, 1_usize << 31);
    source.submit(&section(65537, 1)).unwrap();
    assert_eq!(source.triangle_count, (1_usize << 31) + 2);
    source.submit(&section(65536, 2)).unwrap();
    assert_eq!(source.triangle_count, (1_usize << 31) + 2);
    assert_eq!(
        source
            .meshes
            .values()
            .map(|mesh| mesh.triangles.len())
            .sum::<usize>(),
        source.triangle_count
    );
}

#[test]
fn source_capacity_checks_address_bytes_without_a_small_scene_budget() {
    validate_triangle_capacity((1_usize << 31) + 1).unwrap();
    assert!(validate_triangle_capacity(usize::MAX / std::mem::size_of::<Triangle>()).is_ok());
    assert!(validate_triangle_capacity(usize::MAX / std::mem::size_of::<Triangle>() + 1).is_err());
}

#[test]
fn content_revision_exhaustion_does_not_consume_source_sequences_or_independent_streams() {
    let mut source = SourceScene {
        epoch: 1,
        instances: InstanceContext::new(1),
        ..Default::default()
    };
    source.submit(&section(1, 1)).unwrap();
    source.revision = u64::MAX;
    source.submit(&section(1, 2)).unwrap();
    let mut changed = section(1, 3);
    *changed.last_mut().unwrap() = 1;
    assert!(source.submit(&changed).is_err());
    source.submit(&section(1, 3)).unwrap();
    for op in [6_u32, 7] {
        let mut bytes = Vec::new();
        for value in [MAGIC, ABI_VERSION, op, 0] {
            bytes.extend(value.to_le_bytes());
        }
        for value in [1_u64, 1] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.resize(if op == 6 { 64 } else { 48 }, 0);
        if op == 7 {
            bytes[36..40].copy_from_slice(&1_u32.to_le_bytes());
            for value in [1_u64, 1] {
                bytes.extend(value.to_le_bytes());
            }
        }
        source.submit(&bytes).unwrap();
    }
    assert_eq!(source.revision, u64::MAX);
    assert_eq!(source.dynamic_revision(), 1);
    assert_eq!(source.instance_sequence(), 1);
}

#[test]
fn completed_source_watermarks_bound_history_without_permanent_identity_limits() {
    use crate::protocol::MAGIC;
    fn packet(op: u32, values: &[u64]) -> Vec<u8> {
        let mut bytes = Vec::new();
        for n in [MAGIC, crate::protocol::ABI_VERSION, op, 0] {
            bytes.extend(n.to_le_bytes());
        }
        bytes.extend(1u64.to_le_bytes());
        for n in values {
            bytes.extend(n.to_le_bytes());
        }
        bytes
    }
    let mut source = SourceScene::default();
    source.submit(&packet(1, &[])).unwrap();
    for batch in 0..65u64 {
        let mut bytes = packet(11, &[]);
        bytes.extend(4097u32.to_le_bytes());
        bytes.extend(0u32.to_le_bytes());
        for i in 0..4097u64 {
            let id = batch * 4097 + i + 1;
            bytes.extend(id.to_le_bytes());
            bytes.extend(id.to_le_bytes());
        }
        source.submit(&bytes).unwrap();
        assert_eq!(source.removed.len(), 4097);
        let end = (batch + 1) * 4097;
        source.submit(&packet(10, &[end])).unwrap();
        assert!(source.removed.is_empty());
        assert!(source.submit(&bytes).is_err());
        assert!(source.removed.is_empty());
    }
    assert!(source.section_completed.0 > 262_144);
}
