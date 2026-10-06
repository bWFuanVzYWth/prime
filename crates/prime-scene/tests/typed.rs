use prime_abi::{
    scene::{DynamicView, InstancesView, TexturesView},
    *,
};
use prime_scene::settings::{
    DiagnosticView, Integrator, LightSampling, ReconstructionQuality, RenderMode, RestirRrMode,
    RestirSettings,
};
use prime_scene::{
    protocol::Frame,
    scene::{SourceScene, Texture},
    settings::RenderSettings,
};
use std::sync::Arc;

#[test]
fn typed_settings_distinct_fields_and_boolean_basis_detect_misrouting() {
    let input = PrimeSettings {
        header: header::<PrimeSettings>(),
        mode: 1,
        integrator: 1,
        bounces: 7,
        offline_samples: 3,
        terrain_batches_per_frame: 5,
        exposure: 1.25,
        hue: 0.375,
        saturation: 0.125,
        view: 3,
        sun: 2.0,
        sky: 0.5,
        stars: 1.5,
        depth_range: 256.0,
        seed: 0x2468_1357,
        latitude_degrees: -37,
        solar_longitude_degrees: 121,
        opacity_micromap: 0,
        native_noisy_output: 1,
        reconstruction_quality: 4,
        auto_exposure_compensation: 0.875,
        hdr: 1,
        hdr_reference_white: 140,
        frame_generation: 0,
        light_sampling: 1,
        ignore_global_history_resets: 1,
        restir_spatial_only: 0,
        restir_history_length: 31,
        restir_spatial_reuse: 0,
        restir_spatial_iterations: 2,
        restir_spatial_neighbors: 5,
        restir_pairing_radius: 45,
        restir_stochastic_reprojection: 1,
        restir_duplicate_map: 0,
        restir_duplication_power: 0.625,
        restir_decoupled_shading: 1,
        restir_initial_samples: 6,
        restir_distance_threshold: 0.03125,
        restir_distance_sigma: 0.0625,
        restir_roughness_threshold: 0.1875,
        restir_roughness_sigma: 0.25,
        restir_normal_threshold: -0.3125,
        restir_depth_threshold: 0.4375,
        restir_debug_view: 2,
        restir_rr_decorrelation: 0,
        restir_rr_mode: 1,
        restir_rr_factor: 0.5625,
        restir_rr_stagnancy_exponent: 0.75,
        restir_rr_ema: 0.6875,
        restir_rr_firefly_strength: 0.8125,
        restir_rr_multiply_bound: 17.0,
        restir_rr_bias_reduction: 1,
        restir_rr_firefly: 0,
    };
    // Expected semantics are specified separately from the decoder. Unequal values expose
    // swapped/dropped float and count fields; boolean basis cases distinguish equal bit values.
    let expected = RenderSettings {
        astronomy: prime_scene::environment::Astronomy {
            latitude_degrees: -37,
            solar_longitude_degrees: 121,
        },
        mode: RenderMode::Offline,
        integrator: Integrator::RestirPt,
        bounces: 7,
        offline_samples: 3,
        terrain_batches_per_frame: 5,
        exposure: 1.25,
        hue: 0.375,
        saturation: 0.125,
        view: DiagnosticView::Normal,
        sun: 2.0,
        sky: 0.5,
        stars: 1.5,
        depth_range: 256.0,
        seed: 0x2468_1357,
        opacity_micromap: false,
        native_noisy_output: true,
        reconstruction_quality: ReconstructionQuality::UltraPerformance,
        auto_exposure_compensation: 0.875,
        hdr: true,
        hdr_reference_white: 140,
        frame_generation: false,
        light_sampling: LightSampling::Tree,
        ignore_global_history_resets: true,
        restir_spatial_only: false,
        restir: RestirSettings {
            history_length: 31,
            spatial_reuse: false,
            spatial_iterations: 2,
            spatial_neighbors: 5,
            pairing_radius: 45,
            stochastic_reprojection: true,
            duplicate_map: false,
            duplication_power: 0.625,
            decoupled_shading: true,
            initial_samples: 6,
            distance_threshold: 0.03125,
            distance_sigma: 0.0625,
            roughness_threshold: 0.1875,
            roughness_sigma: 0.25,
            normal_threshold: -0.3125,
            depth_threshold: 0.4375,
            debug_view: 2,
            rr_decorrelation: false,
            rr_mode: RestirRrMode::Uniform,
            rr_factor: 0.5625,
            rr_stagnancy_exponent: 0.75,
            rr_ema: 0.6875,
            rr_firefly_strength: 0.8125,
            rr_multiply_bound: 17.0,
            rr_bias_reduction: true,
            rr_firefly: false,
        },
    };
    assert_eq!(RenderSettings::from_abi(&input).unwrap(), expected);
    type Toggle = (fn(&mut PrimeSettings), fn(&mut RenderSettings));
    let toggles: [Toggle; 13] = [
        (
            |s| s.opacity_micromap ^= 1,
            |s| s.opacity_micromap = !s.opacity_micromap,
        ),
        (
            |s| s.native_noisy_output ^= 1,
            |s| s.native_noisy_output = !s.native_noisy_output,
        ),
        (|s| s.hdr ^= 1, |s| s.hdr = !s.hdr),
        (
            |s| s.frame_generation ^= 1,
            |s| s.frame_generation = !s.frame_generation,
        ),
        (
            |s| s.ignore_global_history_resets ^= 1,
            |s| s.ignore_global_history_resets = !s.ignore_global_history_resets,
        ),
        (
            |s| s.restir_spatial_only ^= 1,
            |s| s.restir_spatial_only = !s.restir_spatial_only,
        ),
        (
            |s| s.restir_spatial_reuse ^= 1,
            |s| s.restir.spatial_reuse = !s.restir.spatial_reuse,
        ),
        (
            |s| s.restir_stochastic_reprojection ^= 1,
            |s| s.restir.stochastic_reprojection = !s.restir.stochastic_reprojection,
        ),
        (
            |s| s.restir_duplicate_map ^= 1,
            |s| s.restir.duplicate_map = !s.restir.duplicate_map,
        ),
        (
            |s| s.restir_decoupled_shading ^= 1,
            |s| s.restir.decoupled_shading = !s.restir.decoupled_shading,
        ),
        (
            |s| s.restir_rr_decorrelation ^= 1,
            |s| s.restir.rr_decorrelation = !s.restir.rr_decorrelation,
        ),
        (
            |s| s.restir_rr_bias_reduction ^= 1,
            |s| s.restir.rr_bias_reduction = !s.restir.rr_bias_reduction,
        ),
        (
            |s| s.restir_rr_firefly ^= 1,
            |s| s.restir.rr_firefly = !s.restir.rr_firefly,
        ),
    ];
    for (toggle_input, toggle_expected) in toggles {
        let mut changed_input = input;
        let mut changed_expected = expected;
        toggle_input(&mut changed_input);
        toggle_expected(&mut changed_expected);
        assert_eq!(
            RenderSettings::from_abi(&changed_input).unwrap(),
            changed_expected
        );
    }
}

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
        native_noisy_output: 0,
        reconstruction_quality: 3,
        terrain_batches_per_frame: 1,
        stars: 1.0,
        auto_exposure_compensation: 0.75,
        hdr: 0,
        hdr_reference_white: 0,
        frame_generation: 0,
        light_sampling: 0,
        integrator: 0,
        ignore_global_history_resets: 0,
        restir_spatial_only: 0,
        restir_history_length: 20,
        restir_spatial_reuse: 1,
        restir_spatial_iterations: 1,
        restir_spatial_neighbors: 3,
        restir_pairing_radius: 30,
        restir_stochastic_reprojection: 0,
        restir_duplicate_map: 1,
        restir_duplication_power: 0.1,
        restir_decoupled_shading: 0,
        restir_initial_samples: 1,
        restir_distance_threshold: 0.02,
        restir_distance_sigma: 0.2,
        restir_roughness_threshold: 0.2,
        restir_roughness_sigma: 0.0,
        restir_normal_threshold: 0.5,
        restir_depth_threshold: 0.1,
        restir_debug_view: 0,
        restir_rr_decorrelation: 1,
        restir_rr_mode: 2,
        restir_rr_factor: 0.4,
        restir_rr_stagnancy_exponent: 0.5,
        restir_rr_ema: 0.2,
        restir_rr_firefly_strength: 0.7,
        restir_rr_multiply_bound: 15.0,
        restir_rr_bias_reduction: 1,
        restir_rr_firefly: 1,
    };
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap(),
        RenderSettings::default()
    );
    settings.auto_exposure_compensation = 0.6;
    assert_eq!(
        RenderSettings::from_abi(&settings)
            .unwrap()
            .auto_exposure_compensation,
        0.6
    );
    settings.light_sampling = 1;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().light_sampling,
        prime_scene::settings::LightSampling::Tree
    );
    settings.light_sampling = 2;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().light_sampling,
        prime_scene::settings::LightSampling::Tree
    );
    settings.light_sampling = 3;
    assert!(RenderSettings::from_abi(&settings).is_err());
    settings.light_sampling = 0;
    settings.integrator = 1;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().integrator,
        prime_scene::settings::Integrator::RestirPt
    );
    for invalid in [2, u32::MAX] {
        settings.integrator = invalid;
        assert!(RenderSettings::from_abi(&settings).is_err());
    }
    settings.integrator = 0;
    settings.ignore_global_history_resets = 1;
    let diagnostic = RenderSettings::from_abi(&settings).unwrap();
    assert!(diagnostic.ignore_global_history_resets);
    assert!(diagnostic.transport_matches(RenderSettings::default()));
    settings.ignore_global_history_resets = 2;
    assert!(RenderSettings::from_abi(&settings).is_err());
    settings.ignore_global_history_resets = 0;
    for value in [0, 1] {
        settings.restir_spatial_only = value;
        let diagnostic = RenderSettings::from_abi(&settings).unwrap();
        assert_eq!(diagnostic.restir_spatial_only, value == 1);
        assert!(diagnostic.transport_matches(RenderSettings::default()));
    }
    for invalid in [2, u32::MAX] {
        settings.restir_spatial_only = invalid;
        assert!(RenderSettings::from_abi(&settings).is_err());
    }
    settings.restir_spatial_only = 0;
    settings.saturation = 0.08;
    assert_eq!(
        RenderSettings::from_abi(&settings).unwrap().saturation,
        0.08
    );
    settings.terrain_batches_per_frame = 128;
    let changed = RenderSettings::from_abi(&settings).unwrap();
    assert!(changed.transport_matches(RenderSettings::default()));
    settings.native_noisy_output = 1;
    assert!(
        RenderSettings::from_abi(&settings)
            .unwrap()
            .native_noisy_output
    );
    settings.native_noisy_output = 2;
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
