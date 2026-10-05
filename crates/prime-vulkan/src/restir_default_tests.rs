//! Optional replay of a frozen previous production SPIR-V bank.
use super::*;
use restir_tests::{multiple_light_scene, renderer, settings, temporal_snapshot_words};

#[test]
#[ignore = "requires explicit PRIME_RESTIR_BASELINE_SPV and windowless Vulkan"]
fn gpu_restir_default_matches_previous_production_bank_bit_for_bit() {
    let folder = std::env::var_os("PRIME_RESTIR_BASELINE_SPV")
        .map(std::path::PathBuf::from)
        .expect("freeze the previous production shader bank first");
    let extent = [1920, 1080];
    let config = RenderSettings {
        auto_exposure_compensation: 0.1,
        ..settings(RenderMode::Realtime)
    };
    let mut current = renderer(config);
    let mut baseline = renderer(config);
    let fixture = multiple_light_scene(1);
    let mut camera = realtime_tests::camera();
    // Prepare actual scene specialization and both independent devices/descriptor sets.
    current
        .render(&fixture, &camera, extent[0], extent[1], 10)
        .unwrap();
    baseline
        .render(&fixture, &camera, extent[0], extent[1], 10)
        .unwrap();
    let variant = baseline.geometry.as_ref().unwrap().shader_variant();
    let pipeline = baseline.pipeline.as_mut().unwrap();
    pipeline
        .restir
        .as_mut()
        .unwrap()
        .install_baseline_for_test(pipeline.layout, variant, &folder);
    for frame in 11..=14 {
        camera.position[0] += 0.0075;
        current
            .render(&fixture, &camera, extent[0], extent[1], frame)
            .unwrap();
        baseline
            .render(&fixture, &camera, extent[0], extent[1], frame)
            .unwrap();
        let (actual_paths, actual) = temporal_snapshot_words(&current, extent[0], extent[1]);
        let (expected_paths, expected) = temporal_snapshot_words(&baseline, extent[0], extent[1]);
        assert_eq!(
            actual_paths, expected_paths,
            "reservoir words frame {frame}"
        );
        assert!(
            actual
                .as_chunks::<4>()
                .0
                .iter()
                .all(|pixel| pixel[3] == 1.0)
        );
        assert!(
            actual
                .as_chunks::<4>()
                .0
                .iter()
                .any(|pixel| pixel[..3].iter().any(|value| *value > 0.0))
        );
        assert!(
            actual
                .iter()
                .zip(&expected)
                .all(|(a, b)| a.to_bits() == b.to_bits()),
            "physical linear frame {frame}"
        );
        for render in [&current, &baseline] {
            assert!(render.restir.as_ref().unwrap().temporal_this_frame);
        }
        let auxiliary = current.restir.as_ref().unwrap().auxiliary_for_test();
        assert!(
            auxiliary.sample_ids.is_none()
                && auxiliary.ages.is_none()
                && auxiliary.shading.is_none()
        );
        assert!(
            auxiliary.rr_initial.is_none()
                && auxiliary.rr_smoothed.is_none()
                && auxiliary.rr_fireflies.is_none()
        );
        assert!(
            current
                .restir
                .as_ref()
                .unwrap()
                .profiles_for_test()
                .planes_for_test()
                .is_none()
        );
    }
}
