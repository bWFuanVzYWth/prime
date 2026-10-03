use prime_abi::{
    scene::{DynamicView, InstancesView, TexturesView},
    *,
};
use prime_scene::{
    protocol::Frame,
    scene::{SourceScene, Texture},
    settings::RenderSettings,
};
use std::sync::Arc;

fn header<T>() -> PrimeHeader {
    PrimeHeader {
        struct_size: std::mem::size_of::<T>() as u32,
        abi_version: PRIME_ABI_VERSION,
    }
}
fn initialized() -> SourceScene {
    let mut scene = SourceScene::default();
    scene.reset_world(1).unwrap();
    scene
}
fn vertices() -> Vec<u8> {
    let mut bytes = Vec::new();
    for p in [[0.0_f32, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]] {
        for x in p {
            bytes.extend(x.to_le_bytes());
        }
        bytes.extend([128, 64, 32, 255]);
        bytes.extend(0.25_f32.to_le_bytes());
        bytes.extend(0.75_f32.to_le_bytes());
    }
    bytes
}
fn mesh(bytes: &[u8]) -> PrimeMeshSpan {
    PrimeMeshSpan {
        topology: 3,
        vertex_count: 3,
        stride: 24,
        color_offset: 12,
        uv_offset: 16,
        vertices: PrimeByteSpan {
            data: bytes.as_ptr(),
            count: bytes.len() as u64,
        },
        ..Default::default()
    }
}
fn dynamic(
    scene: &mut SourceScene,
    sequence: u64,
    origin: [f64; 3],
    spans: &[PrimeMeshSpan],
) -> Result<(), String> {
    let batch = PrimeDynamicBatch {
        header: header::<PrimeDynamicBatch>(),
        epoch: 1,
        sequence,
        origin,
        spans: spans.as_ptr(),
        count: spans.len() as u64,
    };
    scene.submit_dynamic_typed(unsafe { DynamicView::read(&batch)? })
}

#[test]
fn typed_dynamic_reuses_exact_content_across_pointer_changes_and_is_late_failure_atomic() {
    let mut scene = initialized();
    let bytes = vertices();
    let mut descriptor = mesh(&bytes);
    dynamic(&mut scene, 1, [0.0; 3], &[descriptor]).unwrap();
    let first = scene.translate([0.0; 3]).unwrap();
    assert_eq!(first.dynamic.triangles.len(), 1);
    assert_eq!(
        first.dynamic.triangles[0].colors[0],
        [128.0 / 255.0, 64.0 / 255.0, 32.0 / 255.0, 1.0]
    );
    let copied = bytes.clone();
    descriptor.vertices.data = copied.as_ptr();
    dynamic(&mut scene, 2, [0.0; 3], &[descriptor]).unwrap();
    let equal = scene.translate([0.0; 3]).unwrap();
    assert_eq!(first.dynamic.revision, equal.dynamic.revision);
    assert!(Arc::ptr_eq(
        &first.dynamic.triangles,
        &equal.dynamic.triangles
    ));
    let invalid = PrimeMeshSpan {
        texture_id: 901,
        ..descriptor
    };
    assert!(dynamic(&mut scene, 3, [1.0, 0.0, 0.0], &[descriptor, invalid]).is_err());
    let after = scene.translate([0.0; 3]).unwrap();
    assert_eq!(after.dynamic.revision, first.dynamic.revision);
    assert!(Arc::ptr_eq(
        &after.dynamic.triangles,
        &first.dynamic.triangles
    ));
    dynamic(&mut scene, 3, [1.0, 0.0, 0.0], &[descriptor]).unwrap();
    let moved = scene.translate([0.0; 3]).unwrap();
    assert_eq!(moved.dynamic.origin, [1.0, 0.0, 0.0]);
    assert_eq!(moved.dynamic.revision, 3);
    // Retained snapshots survive source reuse and complete empty publication.
    dynamic(&mut scene, 4, [0.0; 3], &[]).unwrap();
    assert!(
        scene
            .translate([0.0; 3])
            .unwrap()
            .dynamic
            .triangles
            .is_empty()
    );
    assert_eq!(first.dynamic.triangles.len(), 1);
}

#[test]
fn typed_dynamic_rejects_resegmented_content_proof_without_consuming_sequence() {
    let mut scene = initialized();
    let bytes = vertices();
    let descriptor = mesh(&bytes);
    dynamic(&mut scene, 1, [0.0; 3], &[descriptor, descriptor]).unwrap();
    let before = scene.translate([0.0; 3]).unwrap();
    assert_eq!(before.dynamic.triangles.len(), 2);

    // This single malformed span serializes to the same proof as the two valid
    // spans above: its payload swallows the second span's metadata and vertices.
    let mut merged = bytes.clone();
    for word in (prime_abi::scene::MeshView {
        descriptor: &descriptor,
        vertices: &bytes,
    })
    .words()
    {
        merged.extend(word.to_le_bytes());
    }
    merged.extend_from_slice(&bytes);
    let malformed = PrimeMeshSpan {
        vertices: PrimeByteSpan {
            data: merged.as_ptr(),
            count: merged.len() as u64,
        },
        ..descriptor
    };
    let error = dynamic(&mut scene, 2, [1.0, 0.0, 0.0], &[malformed]).unwrap_err();
    assert!(error.contains("payload length"));
    let after = scene.translate([0.0; 3]).unwrap();
    assert_eq!(after.dynamic.revision, before.dynamic.revision);
    assert_eq!(after.dynamic.origin, before.dynamic.origin);
    assert!(Arc::ptr_eq(
        &before.dynamic.triangles,
        &after.dynamic.triangles
    ));
    dynamic(&mut scene, 2, [0.0; 3], &[descriptor, descriptor]).unwrap();
    assert_eq!(scene.dynamic_revision(), before.dynamic.revision);
}

#[test]
fn typed_texture_batches_reject_late_errors_and_resource_owner_overwrites() {
    let mut scene = initialized();
    let pixels = [1, 2, 3, 255];
    let texture = PrimeTextureSource {
        id: 5,
        width: 1,
        height: 1,
        rgba: PrimeByteSpan {
            data: pixels.as_ptr(),
            count: 4,
        },
        ..Default::default()
    };
    let mut sources = [
        texture,
        PrimeTextureSource {
            id: 6,
            width: 0,
            ..texture
        },
    ];
    let mut batch = PrimeTextureBatch {
        header: header::<PrimeTextureBatch>(),
        epoch: 1,
        textures: sources.as_ptr(),
        count: 2,
    };
    assert!(
        scene
            .submit_textures_typed(unsafe { TexturesView::read(&batch).unwrap() })
            .is_err()
    );
    assert!(scene.translate([0.0; 3]).unwrap().textures.is_empty());
    sources[1].width = 1;
    scene
        .submit_textures_typed(unsafe { TexturesView::read(&batch).unwrap() })
        .unwrap();
    let before = scene.translate([0.0; 3]).unwrap();
    assert_eq!(&*before.textures[&5].pixels, &pixels);
    scene
        .publish_resource_textures(
            1,
            vec![(
                1,
                Texture {
                    width: 1,
                    height: 1,
                    pixels: pixels.into(),
                    region: None,
                    sampling: None,
                    material: None,
                },
            )],
        )
        .unwrap();
    sources[0].id = 1;
    batch.count = 1;
    batch.textures = sources.as_ptr();
    assert!(
        scene
            .submit_textures_typed(unsafe { TexturesView::read(&batch).unwrap() })
            .is_err()
    );
    assert!(scene.retire_textures_typed(1, &[5, 1]).is_err());
    scene.reset_world(2).unwrap();
    assert!(scene.is_resource_texture(1));
    assert!(
        scene
            .submit_textures_typed(unsafe { TexturesView::read(&batch).unwrap() })
            .is_err()
    );
}

#[test]
fn typed_instances_publish_grouped_arrays_atomically_and_deduplicate_unchanged_pose() {
    let mut scene = initialized();
    let vertices = vertices();
    let mesh = mesh(&vertices);
    let prototype = PrimePrototypeSource {
        id: 3,
        revision: 1,
        spans: &mesh,
        count: 1,
    };
    let mut instances = [PrimeInstanceSource {
        id: 7,
        revision: 1,
        prototype_id: 3,
        transform: [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0],
        texture_id: u32::MAX,
        flags: u32::MAX,
        rgba: 0xff204080,
        uv_transform: [1.0, 1.0, 0.0, 0.0],
        ..Default::default()
    }];
    let mut batch = PrimeInstanceBatch {
        header: header::<PrimeInstanceBatch>(),
        epoch: 1,
        sequence: 1,
        prototypes: &prototype,
        prototype_count: 1,
        instances: instances.as_ptr(),
        instance_count: 1,
        ..Default::default()
    };
    scene
        .submit_instances_typed(unsafe { InstancesView::read(&batch).unwrap() })
        .unwrap();
    assert_eq!(scene.instances().instances[&7].tint, [128, 64, 32, 255]);
    let old_revision = scene.instances().instance_revision;
    instances[0].revision = 2;
    batch.sequence = 2;
    batch.prototype_count = 0;
    scene
        .submit_instances_typed(unsafe { InstancesView::read(&batch).unwrap() })
        .unwrap();
    assert_eq!(scene.instances().instance_revision, old_revision);
    let mut invalid = instances[0];
    invalid.id = 8;
    invalid.revision = 3;
    invalid.prototype_id = 999;
    instances[0].revision = 3;
    instances[0].origin[0] = 4.0;
    let two = [instances[0], invalid];
    batch.instances = two.as_ptr();
    batch.instance_count = 2;
    batch.sequence = 3;
    assert!(
        scene
            .submit_instances_typed(unsafe { InstancesView::read(&batch).unwrap() })
            .is_err()
    );
    assert_eq!(scene.instance_sequence(), 2);
    assert_eq!(scene.instances().instances[&7].origin, [0.0; 3]);
    assert_eq!(scene.instances().instances.len(), 1);
}

#[test]
fn named_frame_and_settings_validate_semantics_without_a_wire_roundtrip() {
    let mut frame = PrimeFrame {
        header: header::<PrimeFrame>(),
        epoch: 9,
        position: [1.25, -3.0, 12.0],
        forward: [0.0, 0.0, -1.0],
        right: [1.0, 0.0, 0.0],
        up: [0.0, 1.0, 0.0],
        fov_y: 1.0,
        width: 1919,
        height: 1079,
        sample_index: 17,
        solar_hour_angle: 0.35,
    };
    let accepted = Frame::from_abi(&frame).unwrap();
    assert_eq!(accepted.world_position, frame.position);
    assert_eq!(accepted.sample_index, 17);
    assert_eq!(accepted.width, 1919);
    frame.forward[0] = f32::NAN;
    assert!(Frame::from_abi(&frame).is_err());
    let mut settings = PrimeSettings {
        header: header::<PrimeSettings>(),
        mode: 0,
        bounces: 12,
        offline_samples: 1,
        exposure: 1.0,
        hue: 0.75,
        saturation: 0.20,
        view: 0,
        sun: 1.0,
        sky: 1.0,
        depth_range: 128.0,
        seed: 0x13572468,
        latitude_degrees: 30,
        solar_longitude_degrees: 0,
        opacity_micromap: 1,
        ray_reconstruction: 1,
        reconstruction_quality: 3,
        terrain_batches_per_frame: 8,
        stars: 1.0,
        auto_exposure_compensation: 0.6,
        hdr: 0,
        hdr_reference_white: 0,
        frame_generation: 0,
        light_sampling: 0,
    };
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap(),
        RenderSettings::default()
    );
    settings.light_sampling = 1;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().light_sampling,
        prime_scene::settings::LightSampling::Tree
    );
    settings.light_sampling = 2;
    assert!(RenderSettings::from_abi(&settings).is_err());
    settings.light_sampling = 0;
    settings.saturation = 0.08;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().saturation,
        0.08
    );
    settings.terrain_batches_per_frame = 128;
    let changed = RenderSettings::from_abi(&settings).unwrap();
    assert!(changed.transport_matches(RenderSettings::default()));
    settings.ray_reconstruction = 2;
    assert!(RenderSettings::from_abi(&settings).is_err());
}

#[test]
fn pointer_boundaries_reject_bad_header_alignment_lengths_before_reading_payload() {
    let mut root = PrimeDynamicBatch {
        header: header::<PrimeDynamicBatch>(),
        ..Default::default()
    };
    assert!(unsafe { DynamicView::read(std::ptr::null()) }.is_err());
    assert!(unsafe { DynamicView::read((&root as *const PrimeDynamicBatch).byte_add(1)) }.is_err());
    root.header.struct_size -= 1;
    assert!(unsafe { DynamicView::read(&root) }.is_err());
    root.header = header::<PrimeDynamicBatch>();
    root.header.abi_version -= 1;
    assert!(unsafe { DynamicView::read(&root) }.is_err());
    root.header = header::<PrimeDynamicBatch>();
    root.count = u64::MAX;
    assert!(unsafe { DynamicView::read(&root) }.is_err());
    root.count = 1;
    assert!(unsafe { DynamicView::read(&root) }.is_err());
    let bad = PrimeMeshSpan {
        vertices: PrimeByteSpan {
            data: std::ptr::null(),
            count: 4,
        },
        ..Default::default()
    };
    root.spans = &bad;
    assert!(unsafe { DynamicView::read(&root) }.is_err());
    root.count = 0;
    assert_eq!(
        unsafe { DynamicView::read(&root).unwrap() }.spans().len(),
        0
    );
}
