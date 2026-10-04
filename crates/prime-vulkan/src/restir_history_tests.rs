//! Actual cached-suffix temporal-update behavior with the production scene and transport code.
use super::*;
use prime_scene::workers::CpuWorkers;
use realtime_tests::camera;
use restir_adapter_tests::{fixtures, run_probe};

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
        ray_reconstruction: false,
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
        (8, LightSampling::TreeSphere),
        (1, LightSampling::Grid),
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
        ray_reconstruction: false,
        opacity_micromap: false,
        ..Default::default()
    };
    let mut renderer =
        Renderer::with_settings_and_workers(settings, Arc::new(CpuWorkers::configured().unwrap()))
            .unwrap();
    for method in [
        LightSampling::Grid,
        LightSampling::Tree,
        LightSampling::TreeSphere,
    ] {
        renderer
            .configure(RenderSettings {
                light_sampling: method,
                ..settings
            })
            .unwrap();
        let code = match method {
            LightSampling::Grid => prime_shader_tests::restir_history(),
            LightSampling::Tree => prime_shader_tests::restir_history_tree(),
            LightSampling::TreeSphere => prime_shader_tests::restir_history_tree_sphere(),
        };
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
