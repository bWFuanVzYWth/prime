//! Actual cached-suffix temporal-update behavior with the production scene and transport code.
use super::*;
use prime_scene::workers::CpuWorkers;
use realtime_tests::camera;
use restir_adapter_tests::{fixtures, run_probe};

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
