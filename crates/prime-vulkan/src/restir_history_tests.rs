//! Actual cached-suffix temporal-update behavior with the production scene and transport code.
use super::*;
use prime_scene::workers::CpuWorkers;
use realtime_tests::camera;
use restir_adapter_tests::{fixtures, run_probe};

#[test]
#[ignore = "requires windowless Vulkan; actual borrowed source resource reload and accepted ReSTIR history"]
fn gpu_restir_source_resource_reload_retains_history_and_withdraws_old_geometry() {
    use prime_scene::{SourceScene, incremental::TranslatedScene};

    // Use the same source section publication and borrowed translation consumed by
    // the host path, rather than replacing resources on a diagnostic Scene copy.
    fn section(slot: u64) -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in [
            prime_scene::protocol::MAGIC,
            prime_scene::protocol::ABI_VERSION,
            8,
            0,
        ] {
            bytes.extend(value.to_le_bytes());
        }
        for value in [1_u64, slot + 1, 1] {
            bytes.extend(value.to_le_bytes());
        }
        for value in [slot / 16, slot / 4 % 4, slot % 4] {
            let value = (value * 16) as f64;
            bytes.extend(value.to_le_bytes());
        }
        for value in [u32::from(slot == 0), 0] {
            bytes.extend(value.to_le_bytes());
        }
        if slot == 0 {
            for value in [0_u32, 7, 0, 4, 4, 24, 0, 12, 16, 0] {
                bytes.extend(value.to_le_bytes());
            }
            for position in [
                [0_f32, 0., 0.],
                [16., 0., 0.],
                [16., 16., 0.],
                [0., 16., 0.],
            ] {
                for value in position {
                    bytes.extend(value.to_le_bytes());
                }
                bytes.extend([255_u8; 4]);
                bytes.extend([0_u8; 8]);
            }
        }
        bytes
    }
    fn uniform(host: &HostBenchmark) -> Vec<u8> {
        // Each enqueue below is drained before the next one: the renderer's first
        // retired descriptor slot is always zero, independently of the host ring.
        host.renderer_for_test()
            .restir
            .as_ref()
            .unwrap()
            .uniform_for_test(0)
            .read(restir::UNIFORM_BYTES as usize)
            .unwrap()
    }
    fn word(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    }
    fn identities(host: &HostBenchmark) -> [u64; 3] {
        host.renderer_for_test()
            .geometry
            .as_ref()
            .unwrap()
            .history_identity_buffers_for_test()
            .map(|buffer| buffer.address())
    }
    let mut source = SourceScene::default();
    source.reset_world(1).unwrap();
    source
        .publish_resource_textures(1, vec![(7, realtime_tests::texture(1, 1, vec![255; 4]))])
        .unwrap();
    // Terrain compiles complete 64-unit Cells. Publish every 16-unit section,
    // including empty sections, so this real source Cell becomes renderable.
    for slot in 0..64 {
        source.submit(&section(slot)).unwrap();
    }
    let mut translated = TranslatedScene::default();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert!(
        translated
            .input()
            .ready_terrain
            .contains(&prime_scene::spatial::Cell::containing([0.; 3]).unwrap())
    );
    assert_eq!(translated.input().meshes.len(), 1);
    let camera = camera();
    let mut host = HostBenchmark::new(17, 9).unwrap();
    host.configure(RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        bounces: 2,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        auto_exposure_compensation: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    })
    .unwrap();
    for frame in 0..2 {
        let sample = host
            .enqueue_with_instances(&translated, source.instance_input(), &camera, frame)
            .unwrap();
        assert_eq!(host.drain().unwrap()[0].serial, sample.serial);
        assert_eq!(word(&uniform(&host), 260), frame);
        assert!(
            host.renderer_for_test()
                .restir
                .as_ref()
                .unwrap()
                .accepted_history()
                .0
        );
    }
    assert_eq!(
        host.triangle_count(),
        2,
        "fixture did not compile its source quad"
    );
    let state = host.renderer_for_test().restir.as_ref().unwrap();
    let accepted = state.accepted_history();
    let watermark = state.accepted_revision();
    let scratch = state.history_for_test().0.address();
    let tables = identities(&host);

    source
        .publish_resource_textures(
            2,
            vec![(7, realtime_tests::texture(1, 1, vec![64, 180, 255, 255]))],
        )
        .unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    assert_eq!(translated.input().epoch, 1);
    assert_eq!(translated.input().resource_generation(), 2);
    let sample = host
        .enqueue_with_instances(&translated, source.instance_input(), &camera, 2)
        .unwrap();
    assert_eq!(host.drain().unwrap()[0].serial, sample.serial);
    let bytes = uniform(&host);
    assert_eq!(
        word(&bytes, 260),
        1,
        "resource reload reset the executed shader history"
    );
    assert_eq!(
        word(&bytes, 460),
        watermark,
        "accepted watermark domain was restarted"
    );
    assert_eq!(
        host.triangle_count(),
        0,
        "old resource-domain geometry survived reload"
    );
    assert_eq!(
        identities(&host),
        tables,
        "resource reload replaced identity buffers"
    );
    for (index, address) in tables.into_iter().enumerate() {
        assert_eq!(
            u64::from_le_bytes(bytes[416 + 8 * index..424 + 8 * index].try_into().unwrap()),
            address,
            "shader consumed a different geometry identity table"
        );
    }
    let state = host.renderer_for_test().restir.as_ref().unwrap();
    assert_eq!(state.accepted_history(), (true, accepted.1 ^ 1));
    assert_eq!(state.history_for_test().0.address(), scratch);
    assert!(state.accepted_revision() > watermark);
    assert!(state.dynamic_update_this_frame);

    // A real SourceScene world reset changes the source/epoch domain. The default
    // policy must still encode a cold temporal input for its completed submission.
    source.reset_world(2).unwrap();
    translated.update(&mut source, [0.; 3]).unwrap();
    let sample = host
        .enqueue_with_instances(&translated, source.instance_input(), &camera, 3)
        .unwrap();
    assert_eq!(host.drain().unwrap()[0].serial, sample.serial);
    assert_eq!(
        word(&uniform(&host), 260),
        0,
        "real world reset retained temporal input"
    );
    assert!(
        host.renderer_for_test()
            .restir
            .as_ref()
            .unwrap()
            .accepted_history()
            .0
    );
}

#[test]
#[ignore = "requires windowless Vulkan; accepted history across sampling/resource/configuration changes"]
fn gpu_restir_retains_history_without_a_proved_global_change() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        auto_exposure_compensation: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::new(1).unwrap()))
            .unwrap();
    let mut scene = frame::tests::plane();
    let camera = frame::tests::camera();
    renderer.render(&scene, &camera, 17, 9, 0).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert!(renderer.restir.as_ref().unwrap().accepted_history().0);
    let scratch = renderer
        .restir
        .as_ref()
        .unwrap()
        .history_for_test()
        .0
        .address();
    let table_handles = renderer
        .geometry
        .as_ref()
        .unwrap()
        .history_identity_buffers_for_test()
        .map(|buffer| buffer.buffer);
    // Zero may be the host's sampling restart after an atlas edit or a skipped
    // draw. The real accepted GPU history remains valid on repeated zero frames.
    renderer.render(&scene, &camera, 17, 9, 0).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    let context = renderer.context.clone();
    let previous_revision = renderer
        .geometry
        .as_mut()
        .unwrap()
        .shader_history_identity(&context, 0)
        .unwrap()
        .revision;
    let mut resource_source = prime_scene::SourceScene::default();
    resource_source
        .publish_resource_textures(1, Vec::new())
        .unwrap();
    scene.resources = resource_source.translate([0.; 3]).unwrap().resources;
    renderer.render(&scene, &camera, 17, 9, 0).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert!(renderer.restir.as_ref().unwrap().dynamic_update_this_frame);
    assert_eq!(
        renderer
            .geometry
            .as_ref()
            .unwrap()
            .history_identity_buffers_for_test()
            .map(|buffer| buffer.buffer),
        table_handles,
        "Texture ownership alone replaced monotonic geometry identity storage"
    );
    assert!(
        renderer
            .geometry
            .as_mut()
            .unwrap()
            .shader_history_identity(&context, 0)
            .unwrap()
            .revision
            > previous_revision
    );
    resource_source
        .publish_resource_textures(2, Vec::new())
        .unwrap();
    scene.resources = resource_source.translate([0.; 3]).unwrap().resources;
    // A real catalog replacement withdraws/rebuilds geometry via the existing
    // range journal while preserving the global history bank and watermark.
    scene.terrain_resource_generation += 1;
    renderer.render(&scene, &camera, 17, 9, 73).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    for (budget, method) in [
        (2, LightSampling::Tree),
        (8, LightSampling::Tree),
        (1, LightSampling::Tree),
    ] {
        let accepted = renderer.restir.as_ref().unwrap().accepted_history();
        renderer
            .configure(RenderSettings {
                bounces: budget,
                light_sampling: method,
                ..settings
            })
            .unwrap();
        assert_eq!(
            renderer.restir.as_ref().unwrap().accepted_history(),
            accepted
        );
        renderer.render(&scene, &camera, 17, 9, 0).unwrap();
        assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
        assert!(renderer.restir.as_ref().unwrap().dynamic_update_this_frame);
        assert_eq!(
            renderer
                .restir
                .as_ref()
                .unwrap()
                .history_for_test()
                .0
                .address(),
            scratch
        );
    }
    renderer.render(&scene, &camera, 19, 9, 0).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().temporal_this_frame);
    scene.epoch += 1;
    renderer.render(&scene, &camera, 19, 9, 11).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().temporal_this_frame);
    // Diagnostic policy keeps explicitly reset histories only while their physical storage stays.
    renderer
        .configure(RenderSettings {
            ignore_global_history_resets: true,
            ..renderer.settings
        })
        .unwrap();
    let accepted = renderer.restir.as_ref().unwrap().accepted_revision();
    let scratch = renderer
        .restir
        .as_ref()
        .unwrap()
        .history_for_test()
        .0
        .address();
    scene.epoch += 1;
    renderer.render(&scene, &camera, 19, 9, 0).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert_eq!(
        renderer
            .restir
            .as_ref()
            .unwrap()
            .history_for_test()
            .0
            .address(),
        scratch
    );
    let identity = renderer
        .geometry
        .as_mut()
        .unwrap()
        .shader_history_identity(&context, 0)
        .unwrap();
    assert!(
        identity.revision > accepted,
        "new numeric domain aliased accepted history"
    );
    renderer.reset_world().unwrap();
    renderer.render(&scene, &camera, 19, 9, 0).unwrap();
    assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
    assert_eq!(
        renderer
            .restir
            .as_ref()
            .unwrap()
            .history_for_test()
            .0
            .address(),
        scratch
    );
    renderer.render(&scene, &camera, 21, 9, 0).unwrap();
    assert!(
        !renderer.restir.as_ref().unwrap().temporal_this_frame,
        "diagnostic made new scratch falsely valid"
    );
    renderer
        .configure(RenderSettings {
            ignore_global_history_resets: false,
            ..renderer.settings
        })
        .unwrap();
    scene.epoch += 1;
    renderer.render(&scene, &camera, 21, 9, 0).unwrap();
    assert!(!renderer.restir.as_ref().unwrap().temporal_this_frame);
    eprintln!(
        "ReSTIR history retained across zero/gap sequence, resource owner/generation and sampler/budget changes; actual extent/world epoch reset"
    );
}

#[test]
#[ignore = "requires windowless Vulkan; production dynamic temporal cached-suffix update"]
fn gpu_restir_temporal_update_replays_all_cached_suffix_cases() {
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Offline,
        bounces: 6,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    {
        let method = LightSampling::Tree;
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = prime_shader_tests::restir_history_tree();
        let mut cases = [0u32; 5];
        let mut sky_changes = 0u32;
        let mut medium_changes = 0u32;
        let mut side_rejections = 0u32;
        let mut adjacent_colored = 0u32;
        let mut max_error = 0_f32;
        for (label, fixture) in fixtures() {
            renderer.render(&fixture, &camera(), 3, 3, 0).unwrap();
            for row in run_probe(&renderer, 4096, code, [4096, 0, 0, 0]) {
                let value = row.map(f32::from_bits);
                if value[3] == 0. || value[0..3].iter().all(|v| *v == 0.) {
                    continue;
                }
                assert!(
                    value[7] > 0.,
                    "{method:?} {label}: static update rejected a positive source {row:?}"
                );
                let branch = row[11] as usize;
                cases[branch] += 1;
                for channel in 0..3 {
                    let expected = value[channel];
                    let actual = value[4 + channel];
                    let restored = value[8 + channel];
                    assert!(
                        actual.is_finite()
                            && actual >= 0.
                            && restored.is_finite()
                            && restored >= 0.
                    );
                    let error =
                        (expected - actual).abs() / expected.abs().max(actual.abs()).max(1e-8);
                    max_error = max_error.max(error);
                    assert!(
                        error < 1e-3,
                        "{method:?} {label}: static suffix update mismatch {row:?}"
                    );
                    if branch != 0 {
                        let error =
                            (restored - actual).abs() / restored.abs().max(actual.abs()).max(1e-8);
                        assert!(
                            error < 1e-3,
                            "{method:?} {label}: poisoned cached radiance/PDF survived {row:?}"
                        );
                    }
                    if row[15] == 0 {
                        // Recorded sky endpoint: double sky exposure doubles the contribution.
                        let expected = actual * 2.;
                        let brighter = value[12 + channel];
                        let error = (expected - brighter).abs()
                            / expected.abs().max(brighter.abs()).max(1e-8);
                        assert!(
                            error < 1e-3,
                            "{method:?} {label}: current sky was not reevaluated {row:?}"
                        );
                    }
                }
                if row[15] == 0 {
                    sky_changes += 1;
                }
                let flags = row[19];
                let rc_length = (flags >> 8) & 255;
                let path_length = flags & 255;
                if label.contains("submerged")
                    && branch == 4
                    && rc_length == path_length
                    && flags & (1 << 16) == 0
                {
                    adjacent_colored += 1;
                }
                if row[23] & 1 != 0
                    && value[20..23]
                        .iter()
                        .zip(&value[16..19])
                        .any(|(a, b)| (a - b).abs() > 1e-8)
                {
                    medium_changes += 1;
                }
                if row[23] & 2 != 0 {
                    side_rejections += 1;
                }
            }
        }
        eprintln!(
            "ReSTIR dynamic suffix {method:?}: branches={cases:?}, sky_changes={sky_changes}, medium_changes={medium_changes}, side_rejections={side_rejections}, adjacent_colored={adjacent_colored}, max_relative_error={max_error}"
        );
        assert!(
            cases[1..].iter().all(|count| *count > 0),
            "{method:?}: a dynamic suffix branch was not executed"
        );
        assert!(sky_changes > 0 && medium_changes > 0 && side_rejections > 0);
        assert!(
            adjacent_colored > 0,
            "{method:?}: colored adjacent RC BSDF endpoint was not executed"
        );
    }
}

#[test]
#[ignore = "requires windowless Vulkan; stable physical quad slots, NEE endpoints and directory alias growth"]
fn gpu_restir_topology_edits_preserve_unmodified_quads_and_nee_endpoints() {
    stable_quad_fixture(false);
}

#[test]
#[ignore = "requires windowless Vulkan; independent sampled-point, physical-half and forward/inverse NEE oracle"]
fn gpu_restir_nee_sampled_points_match_physical_endpoints() {
    stable_quad_fixture(true);
}

fn stable_quad_fixture(endpoint_only: bool) {
    use prime_scene::surface::{Emission, LayerMode, Medium, Optics, SurfaceDetail, SurfaceLayer};
    use restir_support_tests::{Snapshot, snapshot};
    const EXTENT: [u32; 2] = [31, 17];
    fn lamp(x: f32, emission: [f32; 3]) -> prime_scene::surface::SurfaceFace {
        let mut value = realtime_tests::face(3.);
        value.geometry.positions = [
            [x, 8., 3.],
            [x + 2., 8., 3.],
            [x + 2., 10., 3.],
            [x, 10., 3.],
        ];
        value.emission = Emission {
            radiance: emission,
            two_sided: true,
            textured: false,
        };
        value
    }
    fn publish(scene: &mut Scene, faces: Vec<prime_scene::surface::SurfaceFace>) {
        scene.revision += 1;
        let replacement = realtime_tests::scene(scene.revision, faces);
        scene.meshes = replacement.meshes;
        scene.ready_terrain = replacement.ready_terrain;
    }
    fn record(snapshot: &Snapshot, hit: [u32; 2]) -> [u32; 2] {
        let page = (hit[0] & 0x00ff_ffff) as usize;
        let header = snapshot.identities[0][page];
        assert_ne!(header[1] & 0x8000_0000, 0, "actual static quad-mode page");
        assert!(hit[1] < header[1] & 0x7fff_ffff);
        snapshot.quads[header[0] as usize + (hit[1] >> 1) as usize]
    }
    fn saved(renderer: &Renderer, hit: [u32; 2], valid: bool) -> Vec<[u32; 32]> {
        let values = run_probe(
            renderer,
            2,
            prime_shader_tests::restir_history_tree(),
            [2, hit[0], 2, hit[1] & !1],
        );
        for row in &values {
            assert_eq!(
                f32::from_bits(row[7]),
                if valid { 1. } else { 0. },
                "saved endpoint guard {hit:?}"
            );
            if valid {
                assert!(
                    row[4..7]
                        .iter()
                        .copied()
                        .map(f32::from_bits)
                        .all(f32::is_finite)
                );
            }
        }
        values
    }
    fn same_surface(actual: &[[u32; 32]], expected: &[[u32; 32]]) {
        assert_eq!(actual.len(), expected.len());
        for (actual, expected) in actual.iter().zip(expected) {
            assert_eq!(
                &actual[4..11],
                &expected[4..11],
                "saved alias surface position/selected emission changed"
            );
            // loaded.light is a current compact sampling address, intentionally
            // rebuilt when an unrelated emitter is added or removed.
        }
    }
    fn probe(renderer: &Renderer, expected: Option<[[u32; 2]; 4]>) -> [[u32; 2]; 4] {
        let rows = run_probe(
            renderer,
            512,
            prime_shader_tests::restir_history_tree(),
            [512, 0, 1, 0],
        );
        if let Some(directory) = std::env::var_os("PRIME_RESTIR_ORACLE_ARTIFACT") {
            static PROBE_SEQUENCE: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            let sequence = PROBE_SEQUENCE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            let directory = std::path::PathBuf::from(directory);
            std::fs::create_dir_all(&directory).unwrap();
            let bytes: Vec<_> = rows
                .iter()
                .flatten()
                .flat_map(|word| word.to_le_bytes())
                .collect();
            std::fs::write(
                directory.join(format!("Tree-nee-points-{sequence}.bin")),
                bytes,
            )
            .unwrap();
        }
        let hits = std::array::from_fn(|index| [rows[index][0], rows[index][1]]);
        if let Some(expected) = expected {
            assert_eq!(hits, expected, "unchanged physical endpoint remapped");
        }
        for (index, row) in rows[..4].iter().enumerate() {
            assert_eq!(f32::from_bits(row[7]), 1., "actual emitter query missed");
            assert_eq!(row[1] & 1, index as u32 & 1, "both physical halves");
        }
        assert_eq!(
            hits[0][0] & 0x4000_0000,
            0,
            "terminal NEE must address the static quad guard"
        );
        assert_eq!(
            hits[2][0] >> 24 & 1,
            1,
            "bilateral back leaf must be selected"
        );
        let mut selected = [[0usize; 2]; 2];
        let mut max_pdf_error = 0_f32;
        for row in &rows {
            if row[8] == u32::MAX {
                continue;
            }
            assert_eq!(row[27], 1, "actual local emitter branch");
            let endpoint = [row[8], row[9]];
            let category = hits
                .iter()
                .position(|hit| *hit == endpoint)
                .expect("NEE physical page/quad/selected-leaf differs from ray query");
            selected[category / 2][category & 1] += 1;
            let forward = f32::from_bits(row[15]);
            let inverse = f32::from_bits(row[23]);
            assert!(forward.is_finite() && forward > 0. && inverse.is_finite() && inverse > 0.);
            let error = (forward - inverse).abs() / forward.max(inverse);
            max_pdf_error = max_pdf_error.max(error);
            assert!(
                error < 1e-4,
                "Tree forward/inverse NEE PDF mismatch: {forward}/{inverse}"
            );
            // The FP32 shader recovery adds a second round of division/multiply/add
            // rounding at world coordinates 12..14. Recover independently in FP64
            // from the actual proposal direction, retaining the original point bound.
            let direction: [f64; 3] =
                std::array::from_fn(|axis| f64::from(f32::from_bits(row[28 + axis])));
            assert!(direction.iter().all(|value| value.is_finite()) && direction[2] > 0.);
            let sampled = [
                8. + 3. * direction[0] / direction[2],
                8. + 3. * direction[1] / direction[2],
                3.,
            ];
            let loaded: [f64; 3] =
                std::array::from_fn(|axis| f64::from(f32::from_bits(row[16 + axis])));
            let distance = |point: [f64; 3]| {
                point
                    .into_iter()
                    .zip(loaded)
                    .map(|(a, b)| (a - b) * (a - b))
                    .sum::<f64>()
                    .sqrt()
            };
            assert!(
                distance(sampled) < 1e-6,
                "endpoint reconstruction moved the sampled point: f64_error={} shader_error={} raw={row:?}",
                distance(sampled),
                f32::from_bits(row[19])
            );
            // Also compare with the authored physical triangle, using the real
            // HitInfo barycentrics rather than a second production hit decoder.
            let x = if category < 2 { 2. } else { 12. };
            let b1 = f64::from(f32::from_bits(row[10]));
            let b2 = f64::from(f32::from_bits(row[11]));
            let authored = if row[9] & 1 == 0 {
                [x + 2. * (b1 + b2), 8. + 2. * b2, 3.]
            } else {
                [x + 2. * (1. - b1 - b2), 10. - 2. * b2, 3.]
            };
            assert!(
                distance(authored) < 1e-6,
                "endpoint barycentrics differ from the authored triangle: {} raw={row:?}",
                distance(authored)
            );
            for channel in 0..3 {
                let sampled = f32::from_bits(row[12 + channel]);
                let loaded = f32::from_bits(row[20 + channel]);
                assert!(sampled.is_finite() && loaded.is_finite());
                assert!(
                    (sampled - loaded).abs() <= sampled.abs().max(loaded.abs()).max(1.) * 1e-6,
                    "Tree NEE/load selected emission mismatch {sampled}/{loaded}"
                );
            }
        }
        assert!(
            selected.into_iter().flatten().all(|count| count > 0),
            "Tree missed an emitter half: {selected:?}"
        );
        eprintln!(
            "stable quad Tree: actual NEE halves={selected:?}, max_pdf_relative_error={max_pdf_error}"
        );
        hits
    }
    let settings = RenderSettings {
        integrator: Integrator::RestirPt,
        mode: RenderMode::Realtime,
        bounces: 1,
        sun: 1. / 256.,
        sky: 1. / 256.,
        stars: 0.,
        // Request the realtime linear intermediate read by snapshot().
        // The copied radiance is before display exposure adaptation.
        auto_exposure_compensation: 0.1,
        native_noisy_output: true,
        opacity_micromap: false,
        ..Default::default()
    };
    {
        let method = LightSampling::Tree;
        let mut renderer = Renderer::with_settings_and_workers(
            RenderSettings {
                light_sampling: method,
                ..settings
            },
            Arc::new(CpuWorkers::new(1).unwrap()),
        )
        .unwrap();
        renderer.set_diagnostics(false).unwrap();
        let mut floor = realtime_tests::face(0.);
        floor.emission.two_sided = true; // Non-emitting format1, physical row remains stable.
        let mut red = lamp(2., [20., 0., 0.]);
        red.optics = Some(Optics {
            negative: Medium::default(),
            positive: Medium::default(),
            ior_textures: [None; 2],
            transmit: false,
            thin: false,
        });
        let mut neighbor = red.clone();
        neighbor.emission.radiance = [0.; 3];
        for point in &mut neighbor.geometry.positions {
            point[0] += 4.;
        }
        let mut green = lamp(12., [0., 20., 0.]);
        green.detail = Some(Arc::new(SurfaceDetail {
            mode: LayerMode::Bilateral,
            layer: SurfaceLayer {
                colors: [[1.; 4]; 4],
                uvs: [[0.; 2]; 4],
                texture_id: 0,
                flags: 0,
                repeat: None,
                emission: Emission {
                    radiance: [0., 0., 20.],
                    two_sided: true,
                    textured: false,
                },
            },
        }));
        let mut scene = realtime_tests::scene(
            1,
            vec![floor.clone(), neighbor.clone(), red.clone(), green.clone()],
        );
        let camera = realtime_tests::camera();
        let mut sequence = 0;
        let render = |renderer: &mut Renderer, scene: &Scene, sequence: &mut u32| {
            renderer
                .render(scene, &camera, EXTENT[0], EXTENT[1], *sequence)
                .unwrap();
            *sequence += 1;
        };
        // The endpoint oracle needs current production resources and an accepted
        // history watermark, but does not need the topology fixture's M warmup.
        for _ in 0..if endpoint_only { 2 } else { 12 } {
            render(&mut renderer, &scene, &mut sequence);
        }
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 8);
        if endpoint_only {
            assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
            let original = probe(&renderer, None);
            assert_eq!(
                original[0][1] >> 1,
                1,
                "non-emitting slot precedes compact red emitter0"
            );
            eprintln!(
                "stable NEE independent-point oracle {method:?}: initial production resources, two submitted frames, 512 actual samples"
            );
            return;
        }
        let before = snapshot(&renderer);
        assert!(
            before.mean(true).0 > 4.,
            "fixture history did not accumulate"
        );
        let original = probe(&renderer, None);
        assert_eq!(
            original[0][1] >> 1,
            1,
            "non-emitting slot precedes compact red emitter0"
        );
        let saved_red = saved(&renderer, original[0], true);
        let saved_green = saved(&renderer, original[2], true);
        let deleted = run_probe(
            &renderer,
            2,
            prime_shader_tests::restir_history_tree(),
            [2, 0, 3, 0],
        );
        assert_eq!(
            f32::from_bits(deleted[0][7]),
            1.,
            "neighbor source must actually be queryable"
        );
        let deleted_hit = [deleted[0][0], deleted[0][1]];
        renderer
            .geometry
            .as_mut()
            .unwrap()
            .limit_history_capacity_for_test(
                prime_scene::spatial::Cell::containing([0.; 3]).unwrap(),
                3,
            );
        let mut appended = lamp(18., [0.; 3]);
        appended.geometry.flags = 1; // fourth valid (flags,format) row forces real migration.
        publish(
            &mut scene,
            vec![green.clone(), floor.clone(), red.clone(), appended.clone()],
        );
        render(&mut renderer, &scene, &mut sequence);
        assert!(renderer.restir.as_ref().unwrap().temporal_this_frame);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 8);
        let after = snapshot(&renderer);
        let current = probe(&renderer, None);
        assert_ne!(
            current[0][0] & 0x00ff_ffff,
            original[0][0] & 0x00ff_ffff,
            "fixture did not exercise row-base growth"
        );
        for index in 0..4 {
            assert_eq!(
                current[index][1], original[index][1],
                "physical quad ordinal moved on source reorder"
            );
            assert_eq!(
                record(&before, original[index]),
                record(&after, original[index]),
                "unchanged actual GPU quad stamp changed"
            );
            assert_eq!(
                record(&after, original[index]),
                record(&after, current[index]),
                "old alias must share current quad records"
            );
        }
        same_surface(&saved(&renderer, original[0], true), &saved_red);
        same_surface(&saved(&renderer, original[2], true), &saved_green);
        saved(&renderer, deleted_hit, false);
        let holes = run_probe(
            &renderer,
            2,
            prime_shader_tests::restir_history_tree(),
            [2, 0, 3, 0],
        );
        assert!(
            holes.iter().all(|row| f32::from_bits(row[7]) == 0.),
            "degenerate tombstone was hit"
        );
        assert!(
            after.mean(true).0 > 4.,
            "unchanged receiver lost temporal reuse on excavation/growth"
        );

        publish(
            &mut scene,
            vec![
                appended.clone(),
                neighbor.clone(),
                red.clone(),
                floor.clone(),
                green.clone(),
            ],
        );
        render(&mut renderer, &scene, &mut sequence);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 10);
        probe(&renderer, Some(current));
        saved(&renderer, deleted_hit, false); // dead slot reuse stamped this submission.
        let reused = snapshot(&renderer);
        assert!(record(&reused, deleted_hit)[0] > reused.watermark);
        assert!(reused.mean(true).0 > 4.);

        publish(
            &mut scene,
            vec![
                floor.clone(),
                neighbor.clone(),
                green.clone(),
                appended.clone(),
            ],
        );
        render(&mut renderer, &scene, &mut sequence);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 8);
        saved(&renderer, original[0], false);
        same_surface(&saved(&renderer, original[2], true), &saved_green);
        red.emission.radiance = [15., 1., 0.];
        publish(
            &mut scene,
            vec![
                green.clone(),
                red.clone(),
                floor.clone(),
                neighbor.clone(),
                appended,
            ],
        );
        render(&mut renderer, &scene, &mut sequence);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 10);
        probe(&renderer, Some(current));
        saved(&renderer, original[0], false);
        render(&mut renderer, &scene, &mut sequence);
        saved(&renderer, original[0], true);
        assert!(snapshot(&renderer).mean(true).0 > 4.);

        scene.revision += 1;
        scene.meshes.clear();
        scene.ready_terrain.clear();
        render(&mut renderer, &scene, &mut sequence);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 0);
        saved(&renderer, original[0], false);
        saved(&renderer, original[2], false);
        publish(&mut scene, vec![floor, red, green]);
        render(&mut renderer, &scene, &mut sequence);
        assert_eq!(renderer.geometry.as_ref().unwrap().triangle_count, 6);
        saved(&renderer, original[0], false);
        saved(&renderer, original[2], false);
        probe(&renderer, None);
        eprintln!(
            "stable quad {method:?}: excavation/reorder/dead reuse/row growth/emitter edit/Cell unload executed; unaffected receiver M continued"
        );
    }
}
