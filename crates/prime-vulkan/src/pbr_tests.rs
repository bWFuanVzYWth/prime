//! Checks material translation, Full and retained Lite closures, and sanitizers.
use super::{Context, shader_tests::run};

const INPUT_WORDS: usize = 32;
const OUTPUT_WORDS: usize = 64;

#[test]
#[ignore = "requires Vulkan; compares compact PT vertex against the retained source facade"]
fn gpu_compact_pbr_vertex_preserves_source_closure() {
    let views = [
        [0.0, 0.0, 1.0, 0.0],
        [0.6, 0.0, 0.8, 0.0],
        [0.99995, 0.0, 0.01, 0.0],
        [0.0, 0.8, -0.6, 0.0],
    ];
    let iors = [
        [1.0, 1.5],
        [1.5, 1.0],
        [1.0, 1.0],
        [1.5, 1.33],
        [1.33, 1.5],
        [1.5, 0.9],
    ];
    let colors = [[0.4, 0.6, 0.2], [0.0; 3], [1.0; 3], [1.25, 0.1, 0.7]];
    let proposal_z = [0.0, 0.03, 0.25, 0.499999, 0.5, 0.75, 0.97, 0.999999];
    let mut cases = Vec::new();
    for g in 0..=255u8 {
        for b in [0, 64, 65, 96, 190, 254] {
            for r in [0, 64, 128, 254, 255] {
                for a in [0, 128, 255] {
                    for flags in 0..4 {
                        for thin in [false, true] {
                            for dielectric in [false, true] {
                                let index = cases.len();
                                let control =
                                    flags | u32::from(thin) << 2 | u32::from(dielectric) << 3;
                                let mut case = source_case([r, g, b, 255], control, dielectric);
                                // Include reserved canonical G codes as well as all supported classes.
                                case[0] = rgba([128, 128, 191, a]);
                                case[1] = rgba([r, g, b, 255]);
                                let color = colors[(index >> 2) % 4];
                                case[4..8]
                                    .copy_from_slice(&bits([color[0], color[1], color[2], 0.0]));
                                case[8..12].copy_from_slice(&bits(views[(index >> 4) % 4]));
                                case[12..16].copy_from_slice(&bits([
                                    ((index * 17) % 1009) as f32 / 1009.0,
                                    ((index * 53 + 1) % 1013) as f32 / 1013.0,
                                    proposal_z[(index >> 5) % 8],
                                    0.0,
                                ]));
                                let ior = iors[(index >> 7) % 6];
                                case[16..20].copy_from_slice(&bits([ior[0], 0.01, 0.03, 0.02]));
                                case[20..24].copy_from_slice(&bits([ior[1], 0.1, 0.2, 0.3]));
                                case[24..28].copy_from_slice(&bits(views[(index >> 9) % 4]));
                                let sign = if index >> 3 & 1 == 0 { 1.0 } else { -1.0 };
                                case[28..32].copy_from_slice(&bits(if index >> 6 & 1 == 0 {
                                    [0.0, 0.0, sign, 0.0]
                                } else {
                                    [0.6 * sign, 0.0, 0.8 * sign, 0.0]
                                }));
                                cases.push(case);
                            }
                        }
                    }
                }
            }
        }
    }
    assert_eq!(cases.len(), 368_640);
    for (index, result) in execute(13, &cases).iter().enumerate() {
        for word in 0..24 {
            if (8..16).contains(&word) {
                assert_eq!(
                    result[word],
                    result[word + 24],
                    "compact event/medium case {index} word {word}"
                );
            } else {
                close(
                    f32::from_bits(result[word + 24]),
                    f32::from_bits(result[word]),
                    2.0e-5,
                    "compact sample/evaluation/PDF",
                );
            }
        }
    }
}

fn bits(values: [f32; 4]) -> [u32; 4] {
    values.map(f32::to_bits)
}

fn rgba(bytes: [u8; 4]) -> u32 {
    u32::from_le_bytes(bytes)
}

fn canonical([r, g, b, a]: [u8; 4]) -> u32 {
    let fresnel = if g <= 237 {
        g + 1
    } else if g == 255 {
        239
    } else {
        0
    };
    let scattering = if b <= 64 {
        b
    } else if b == 65 {
        0
    } else {
        b - 1
    };
    rgba([r, fresnel, scattering, a])
}

fn source_case(specular: [u8; 4], flags: u32, dielectric: bool) -> [u32; INPUT_WORDS] {
    let mut result = [0; INPUT_WORDS];
    result[..4].copy_from_slice(&[
        rgba([128, 128, 191, 0]),
        canonical(specular),
        flags,
        u32::from(dielectric),
    ]);
    result[4..8].copy_from_slice(&bits([0.4, 0.6, 0.2, 0.0]));
    result[8..12].copy_from_slice(&bits([0.0, 0.0, 1.0, 0.0]));
    result[12..16].copy_from_slice(&bits([0.3, 0.7, 0.5, 0.0]));
    result[16..20].copy_from_slice(&bits([1.0, 0.0, 0.0, 0.0]));
    result[20..24].copy_from_slice(&bits([1.5, 0.1, 0.2, 0.3]));
    result[24..28].copy_from_slice(&bits([0.0, 0.0, 1.0, 0.0]));
    result
}

fn execute(mode: u32, cases: &[[u32; INPUT_WORDS]]) -> Vec<[u32; OUTPUT_WORDS]> {
    let context = Context::new().unwrap();
    let input: Vec<_> = cases.iter().flatten().copied().collect();
    run(
        &context,
        prime_shader_tests::pbr(),
        &input,
        cases.len() * OUTPUT_WORDS,
        [mode, cases.len() as u32],
        None,
    )
    .as_chunks::<OUTPUT_WORDS>()
    .0
    .to_vec()
}

fn close(actual: f32, expected: f32, tolerance: f32, label: &str) {
    assert!(
        actual.is_finite() && (actual - expected).abs() <= tolerance * expected.abs().max(1.0),
        "{label}: {actual} != {expected}"
    );
}

fn direction(cosine: f32, azimuth: f32) -> [f32; 4] {
    let sine = (1.0 - cosine * cosine).sqrt();
    [sine * azimuth.cos(), sine * azimuth.sin(), cosine, 0.0]
}

fn random(index: u32) -> [f32; 4] {
    let radical = |value: u32| (value.reverse_bits() >> 8) as f32 / 16_777_216.0;
    [
        radical(index + 1),
        radical((index + 1).wrapping_mul(0x9e37_79b9)),
        radical((index + 1).wrapping_mul(0x85eb_ca6b)),
        0.0,
    ]
}

#[test]
#[ignore = "requires Vulkan; executes production LabPBR and OpenPBR material translation"]
fn gpu_labpbr_translation_preserves_codes_endpoints_and_defaults() {
    let mut cases = Vec::new();
    for channel in 0..=255u8 {
        cases.push(source_case([channel, channel, channel, channel], 6, false));
    }
    for flags in [0, 1, 2, 3, 4, 5, 6, 7] {
        for smoothness in [0, 255] {
            let mut case = source_case([smoothness, 4, 255, 254], flags, false);
            case[0] = rgba([0, 255, 128, 255]);
            cases.push(case);
        }
    }
    let results = execute(0, &cases);
    for (index, (case, words)) in cases.iter().zip(&results).enumerate() {
        let normal = case[0].to_le_bytes();
        let specular = case[1].to_le_bytes();
        let roughness = 1.0 - f32::from(specular[0]) / 255.0;
        let fresnel = specular[1];
        let f0 = if fresnel == 0 || fresnel >= 231 {
            0.04
        } else {
            (f32::from(fresnel - 1) / 255.0).clamp(0.02, 0.17)
        };
        let subsurface = specular[2].saturating_sub(64);
        let porosity = if specular[2] <= 64 { specular[2] } else { 0 };
        assert_eq!(
            words[4],
            u32::from(fresnel) | u32::from(subsurface) << 8 | u32::from(porosity) << 16,
            "canonical optical control case {index}"
        );
        close(
            f32::from_bits(words[0]),
            roughness,
            2e-7,
            "roughness endpoint",
        );
        close(f32::from_bits(words[1]), f0, 2e-7, "dielectric F0 codebook");
        let emission = if specular[3] == 255 {
            0.0
        } else {
            f32::from(specular[3]) / 254.0
        };
        close(
            f32::from_bits(words[3]),
            emission,
            2e-7,
            "emission sentinel",
        );
        let has_specular = case[2] & 2 != 0;
        let has_normal = case[2] & 1 != 0;
        let material_roughness: f32 = if has_specular { roughness } else { 0.9 };
        let distribution = if has_normal {
            f32::from(normal[3]) / 255.0
        } else {
            0.0
        };
        let combined = (material_roughness.powi(4) + distribution.powi(4))
            .min(1.0)
            .sqrt()
            .sqrt();
        close(
            f32::from_bits(words[8]),
            combined,
            3e-6,
            "normal mip roughness",
        );
        let metal = has_specular && (231..=239).contains(&fresnel);
        close(
            f32::from_bits(words[10]),
            if metal { 1.0 } else { 0.0 },
            0.0,
            "metal identity",
        );
        close(
            f32::from_bits(words[11]),
            if has_specular && !metal {
                f32::from(subsurface) / 190.0
            } else {
                0.0
            },
            2e-7,
            "authored subsurface weight",
        );
        for &word in &words[8..23] {
            assert!(f32::from_bits(word).is_finite(), "material case {index}");
        }
        let length_squared = words[20..23]
            .iter()
            .map(|&v| f32::from_bits(v).powi(2))
            .sum();
        close(length_squared, 1.0, 3e-6, "decoded tangent normal length");
    }
}

#[test]
#[ignore = "requires Vulkan; checks executable BSDF boundary sanitization"]
fn gpu_pbr_sanitization_rejects_invalid_payloads_without_clipping_valid_values() {
    let mut valid = [0; INPUT_WORDS];
    valid[..4].copy_from_slice(&bits([0.0, 0.0, 1.0, 1.5]));
    valid[4..8].copy_from_slice(&bits([0.2, 0.3, 4.0, 0.5]));
    valid[8] = 5;
    valid[12..16].copy_from_slice(&bits([0.4, 0.5, 0.6, 0.0]));
    valid[16..20].copy_from_slice(&bits([-1.0, 0.4, 2.0, 0.0]));
    let mut cases = vec![valid];
    for field in 0..8 {
        for value in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -1.0] {
            let mut case = valid;
            case[field] = value.to_bits();
            cases.push(case);
        }
    }
    for (field, value) in [(2, 0.0f32), (3, 0.0), (7, 0.0), (12, f32::NAN)] {
        let mut case = valid;
        case[field] = value.to_bits();
        cases.push(case);
    }
    let mut no_event = valid;
    no_event[8] = 0;
    no_event[0] = f32::NAN.to_bits();
    cases.push(no_event);
    let mut albedo = valid;
    albedo[16..20].copy_from_slice(&bits([f32::NAN, f32::INFINITY, 0.7, 0.0]));
    cases.push(albedo);
    let results = execute(1, &cases);
    for (case, words) in cases.iter().zip(results) {
        let input = case.map(f32::from_bits);
        let direction_squared = input[..3].iter().map(|v| v * v).sum::<f32>();
        let response_valid = input[4..7].iter().all(|v| v.is_finite() && *v >= 0.0);
        let pdf_valid = input[7].is_finite() && input[7] >= 0.0;
        let sample_valid = case[8] != 0
            && input[..3].iter().all(|v| v.is_finite())
            && direction_squared.is_finite()
            && direction_squared > 1e-12
            && (direction_squared - 1.0).abs() <= 1e-3
            && response_valid
            && pdf_valid
            && input[7] > 0.0
            && input[3].is_finite()
            && input[3] > 0.0;
        if sample_valid {
            assert_eq!(&words[..8], &case[..8], "valid sample must remain exact");
            assert_eq!(words[8], case[8]);
        } else {
            assert_eq!(&words[..3], &[0; 3]);
            assert_eq!(words[3], 1.0f32.to_bits());
            assert_eq!(&words[4..9], &[0; 5]);
        }
        if response_valid && pdf_valid {
            assert_eq!(&words[12..16], &case[4..8]);
        } else {
            assert_eq!(&words[12..16], &[0; 4]);
        }
        let components_valid =
            response_valid && pdf_valid && input[12..15].iter().all(|v| v.is_finite() && *v >= 0.0);
        if components_valid {
            assert_eq!(&words[16..20], &case[4..8]);
            assert_eq!(&words[20..23], &case[12..15]);
        } else {
            assert_eq!(&words[16..24], &[0; 8]);
        }
        for channel in 0..3 {
            let value = input[16 + channel];
            let expected = if value.is_finite() {
                value.clamp(0.0, 1.0)
            } else {
                0.0
            };
            close(
                f32::from_bits(words[24 + channel]),
                expected,
                0.0,
                "albedo channel",
            );
        }
    }
}

#[test]
#[ignore = "requires Vulkan; checks production opaque LitePBR sampling and evaluation"]
fn gpu_litepbr_opaque_events_and_conductor_pdf_match_evaluation() {
    let mut cases = Vec::new();
    for roughness_byte in [0, 128, 254, 255] {
        for fresnel in [0, 4, 229, 230, 237, 255] {
            for cosine in [0.01, 0.5, 1.0] {
                for sample in 0..128 {
                    let mut case = source_case([roughness_byte, fresnel, 0, 255], 2, false);
                    case[8..12].copy_from_slice(&bits(direction(cosine, 0.7)));
                    case[12..16].copy_from_slice(&bits(random(sample)));
                    cases.push(case);
                }
            }
        }
    }
    let results = execute(2, &cases);
    let mut accepted = 0;
    for words in results {
        let flags = words[8];
        if flags == 0 {
            assert_eq!(&words[..3], &[0; 3]);
            assert_eq!(&words[4..8], &[0; 4]);
            continue;
        }
        accepted += 1;
        assert_ne!(flags & 1, 0, "opaque event reflects");
        assert_eq!(flags & 2, 0, "opaque event has no volume transmission");
        let values = words.map(f32::from_bits);
        close(
            values[..3].iter().map(|v| v * v).sum(),
            1.0,
            3e-5,
            "sample direction",
        );
        assert!(values[2] > 0.0);
        assert!(values[4..8].iter().all(|v| v.is_finite() && *v >= 0.0));
        assert!(values[7] > 0.0);
        assert!(values[16..24].iter().all(|v| v.is_finite() && *v >= 0.0));
        if flags & 16 == 0 {
            for channel in 0..4 {
                close(
                    values[4 + channel],
                    values[16 + channel],
                    1e-4,
                    "complete continuous sample/eval",
                );
            }
        }
    }
    assert!(
        accepted > cases.len() / 2,
        "closure accepted {accepted} proposals"
    );
}

#[test]
#[ignore = "requires Vulkan; checks historical LitePBR thin and solid dielectric continuation"]
fn gpu_litepbr_dielectric_preserves_medium_handoff_and_thin_wall_measure() {
    let mut cases = Vec::new();
    for thin in [false, true] {
        for smoothness in [0, 128, 255] {
            for cosine in [0.02, 0.5, 1.0] {
                for sample in 0..128 {
                    let mut case =
                        source_case([smoothness, 4, 0, 255], 2 | (u32::from(thin) * 4), true);
                    case[8..12].copy_from_slice(&bits(direction(cosine, 0.4)));
                    case[12..16].copy_from_slice(&bits(random(sample)));
                    cases.push(case);
                }
            }
        }
    }
    let results = execute(2, &cases);
    let mut reflection = 0;
    let mut transmission = 0;
    for (case, words) in cases.iter().zip(results) {
        let flags = words[8];
        let thin = case[2] & 4 != 0;
        let values = words.map(f32::from_bits);
        if flags == 0 {
            assert_eq!(
                &words[12..16],
                &case[16..20],
                "rejected event preserves medium"
            );
            continue;
        }
        close(
            values[..3].iter().map(|v| v * v).sum(),
            1.0,
            3e-5,
            "dielectric direction",
        );
        assert!(values[3].is_finite() && values[3] > 0.0);
        assert!(values[4..8].iter().all(|v| v.is_finite() && *v >= 0.0));
        assert!(values[7] > 0.0);
        assert!(values[16..24].iter().all(|v| v.is_finite() && *v >= 0.0));
        assert_ne!(flags & 3, 3, "each event has one boundary side");
        let transmitted = flags & 2 != 0;
        assert_eq!(transmitted, values[2] < 0.0);
        if transmitted {
            transmission += 1;
        } else {
            reflection += 1;
        }
        let expected_medium = if transmitted && !thin {
            &case[20..24]
        } else {
            &case[16..20]
        };
        for channel in 0..4 {
            close(
                values[12 + channel],
                f32::from_bits(expected_medium[channel]),
                3e-6,
                "medium handoff",
            );
        }
        if thin {
            close(values[3], 1.0, 0.0, "thin wall eta");
        }
        if case[1].to_le_bytes()[0] == 255 {
            assert_ne!(flags & 16, 0, "zero roughness is a discrete event");
        }
    }
    assert!(
        reflection > 100 && transmission > 100,
        "both dielectric events are exercised"
    );
}

#[test]
#[ignore = "requires Vulkan; exercises LitePBR foliage energy, components and event properties"]
fn gpu_litepbr_foliage_components_and_energy_obey_closure_contract() {
    let mut cases = Vec::new();
    for fresnel in [4, 230, 255] {
        for scattering in [0, 160, 255] {
            for cosine in [0.02, 0.5, 1.0] {
                for sample in 0..128 {
                    let mut case = source_case([128, fresnel, scattering, 255], 6, false);
                    case[8..12].copy_from_slice(&bits(direction(cosine, 0.7)));
                    case[12..16].copy_from_slice(&bits(random(sample)));
                    case[24..28].copy_from_slice(&bits(direction(
                        if sample & 1 == 0 { 0.6 } else { -0.6 },
                        1.2,
                    )));
                    cases.push(case);
                }
            }
        }
    }
    let results = execute(3, &cases);
    let mut accepted = 0;
    for words in results {
        assert_eq!(
            words[9], 1,
            "declared LitePBR foliage topology is supported"
        );
        let values = words.map(f32::from_bits);
        for &value in &values[12..32] {
            assert!(
                value.is_finite() && value >= 0.0,
                "foliage finite nonnegative result"
            );
        }
        for channel in 0..3 {
            close(
                values[12 + channel],
                values[16 + channel] + values[20 + channel],
                5e-5,
                "foliage component sum",
            );
            assert!(
                values[24 + channel] + values[28 + channel] <= 1.03,
                "directional energy"
            );
        }
        close(values[15], values[19], 5e-5, "foliage components PDF");
        if words[8] != 0 {
            accepted += 1;
            close(
                values[..3].iter().map(|v| v * v).sum(),
                1.0,
                3e-5,
                "foliage sample direction",
            );
            assert!(values[4..8].iter().all(|v| v.is_finite() && *v >= 0.0));
            assert!(values[7] > 0.0);
        }
    }
    assert!(accepted > cases.len() / 2);
}

#[test]
#[ignore = "requires Vulkan; checks production valid-reflection correction at grazing angles"]
fn gpu_labpbr_normal_correction_retains_the_geometric_reflection_hemisphere() {
    let mut cases = Vec::new();
    for x in [-1.0f32, -0.7, -0.3, 0.0, 0.3, 0.7, 1.0] {
        for y in [-1.0f32, -0.7, -0.3, 0.0, 0.3, 0.7, 1.0] {
            let z = (1.0 - x * x - y * y).max(0.0).sqrt();
            let length = (x * x + y * y + z * z).sqrt();
            for cosine in [1e-7f32, 1e-5, 0.001, 0.02, 0.5, 1.0] {
                for azimuth in 0..8 {
                    for back in [false, true] {
                        let mut case = [0; INPUT_WORDS];
                        let mut view =
                            direction(cosine, azimuth as f32 * std::f32::consts::FRAC_PI_4);
                        if back {
                            view = view.map(|v| -v);
                        }
                        case[..4].copy_from_slice(&bits([0.0, 0.0, 1.0, 0.0]));
                        case[4..8].copy_from_slice(&bits(view));
                        case[8..12].copy_from_slice(&bits([
                            x / length,
                            y / length,
                            z / length,
                            0.0,
                        ]));
                        cases.push(case);
                    }
                }
            }
        }
    }
    let results = execute(5, &cases);
    for (case, words) in cases.iter().zip(results) {
        let values = words.map(f32::from_bits);
        assert!(values[..8].iter().all(|v| v.is_finite()));
        close(values[3], 1.0, 3e-5, "corrected normal length");
        assert!(
            values[2] >= 0.0,
            "outward normal faces the geometric hemisphere"
        );
        let view_cosine = f32::from_bits(case[6]).abs();
        assert!(
            values[7] >= (0.9 * view_cosine).min(0.01) - 2e-5,
            "corrected reflection cosine {} at view cosine {view_cosine}",
            values[7]
        );
    }
}

#[test]
#[ignore = "requires Vulkan; checks source transport mathematics with extreme finite factors"]
fn gpu_litepbr_transport_products_and_mis_avoid_intermediate_overflow() {
    let mut cases = Vec::new();
    for first in [0.0f32, 1e-30, 1e-10, 1.0, 1e10, 1e30] {
        for second in [1e-30f32, 1e-10, 1.0, 1e10, 1e30] {
            for denominator in [1e-30f32, 1e-10, 1.0, 1e10, 1e30] {
                let expected = f64::from(first) * f64::from(second) / f64::from(denominator);
                if expected > f64::from(f32::MAX) || (expected != 0.0 && expected < 1e-30) {
                    continue;
                }
                let mut case = [0; INPUT_WORDS];
                case[..4].copy_from_slice(&bits([first; 4]));
                case[4..8].copy_from_slice(&bits([second; 4]));
                case[8..12].copy_from_slice(&bits([
                    denominator,
                    1.0 / denominator,
                    denominator,
                    second,
                ]));
                cases.push(case);
            }
        }
    }
    let results = execute(6, &cases);
    for (case, words) in cases.iter().zip(results) {
        let input = case.map(f32::from_bits);
        let expected = f64::from(input[0]) * f64::from(input[4]) / f64::from(input[8]);
        let triple = f64::from(input[0]) * f64::from(input[4]) * f64::from(input[9]);
        for (channel, expected) in [(0, expected), (4, triple)] {
            let actual = f64::from(f32::from_bits(words[channel]));
            assert!(
                actual.is_finite() && (actual - expected).abs() <= 2e-6 * expected.abs().max(1e-30),
                "stable product {actual} != {expected}"
            );
        }
        let sampled = f64::from(input[10]);
        let other = f64::from(input[11]);
        let weight = sampled * sampled / (sampled * sampled + other * other);
        close(f32::from_bits(words[8]), weight as f32, 3e-6, "MIS weight");
        let inverse = f64::from(f32::from_bits(words[9]));
        let expected_inverse = weight / sampled;
        assert!(
            inverse.is_finite()
                && (inverse - expected_inverse).abs() <= 3e-6 * expected_inverse.abs().max(1e-30)
        );
    }
}

#[test]
#[ignore = "requires Vulkan; exercises authored cutout subsurface without a foliage preset"]
fn gpu_labpbr_cutout_subsurface_selects_thin_closure_and_preserves_path_medium() {
    let mut cases = Vec::new();
    for authored in [false, true] {
        for optical_thin in [false, true] {
            for coverage in [0, 1, 2] {
                for scattering in [0, 64, 65, 66, 255] {
                    for fresnel in [4, 230] {
                        for sample in 0..128 {
                            let flags = u32::from(authored) * 2 + u32::from(optical_thin) * 4;
                            let mut case =
                                source_case([128, fresnel, scattering, 255], flags, false);
                            case[12..16].copy_from_slice(&bits(random(sample)));
                            case[28] = coverage;
                            cases.push(case);
                        }
                    }
                }
            }
        }
    }
    let results = execute(7, &cases);
    let mut cutout_transmission = 0;
    let mut thick_transmission = 0;
    let mut thin_transmission = 0;
    for (case, words) in cases.iter().zip(results) {
        let authored = case[2] & 2 != 0;
        let optical_thin = case[2] & 4 != 0;
        let specular = case[1].to_le_bytes();
        let thin = optical_thin || authored && case[28] == 1 && specular[2] > 64;
        let conductor = authored && (231..=239).contains(&specular[1]);
        let subsurface = if authored && !conductor {
            f32::from(specular[2].saturating_sub(64)) / 190.0
        } else {
            0.0
        };
        assert_eq!(words[9], u32::from(thin), "source thin-surface declaration");
        assert_eq!(
            words[10],
            u32::from(thin && !conductor),
            "LitePBR subsurface geometry"
        );
        close(
            f32::from_bits(words[11]),
            subsurface,
            2e-7,
            "authored subsurface weight",
        );
        assert_eq!(words[20], 0, "no preset transmission is introduced");
        assert_eq!(
            &words[12..16],
            &case[16..20],
            "surface SSS keeps the path medium"
        );
        let flags = words[8];
        assert_eq!(
            words[21],
            if flags & (2 | 16) != 0 { 0 } else { words[7] },
            "opaque transmission has no competing direct-light estimator"
        );
        assert_eq!(
            words[22],
            if flags & 16 != 0 { 0 } else { words[7] },
            "continuous dielectric events keep both direct-light sides"
        );
        if flags & 2 != 0 {
            assert!(subsurface > 0.0 && !conductor);
            assert_ne!(flags & 4, 0, "subsurface transmission remains diffuse");
            assert_eq!(flags & 16, 0, "subsurface transmission is not discrete");
            let values = words.map(f32::from_bits);
            assert!(
                values[2] < 0.0,
                "transmission crosses the material boundary"
            );
            assert!(values[4..8].iter().all(|v| v.is_finite() && *v > 0.0));
            assert!(values[16..20].iter().all(|v| v.is_finite() && *v > 0.0));
            close(values[3], 1.0, 0.0, "subsurface relative eta");
            if specular[2] == 254 {
                close(
                    values[5] / values[4],
                    if thin { 1.5 } else { 1.0 },
                    3e-6,
                    "pure subsurface transmission color",
                );
            }
            if thin {
                thin_transmission += 1;
            } else {
                thick_transmission += 1;
            }
            if !optical_thin && case[28] == 1 {
                cutout_transmission += 1;
            }
        } else if subsurface == 0.0 {
            assert_eq!(flags & 2, 0, "absent authored SSS cannot add transmission");
        }
    }
    assert!(
        cutout_transmission > 40,
        "cutout authored SSS must exercise diffuse transmission"
    );
    assert!(thin_transmission > 40 && thick_transmission > 40);
}

#[test]
#[ignore = "requires Vulkan; checks direct-light geometric support against actual closure values"]
fn gpu_litepbr_normal_mapped_evaluation_rejects_the_wrong_physical_boundary_side() {
    let geometric = [0.0, 0.0, 1.0, 0.0];
    let shading = [0.5, 0.0, 3.0f32.sqrt() / 2.0, 0.0];
    let view = direction(0.2, 0.0);
    // A grazing view permits a real refractive half-vector even though both directions are
    // above the geometric surface. At normal incidence that wrong-side transmission is zero
    // before the geometric guard because it violates the compact microfacet hemisphere test.
    let mut cases = Vec::new();
    for normal_mapped in [false, true] {
        for dielectric in [false, true] {
            for scatter in [
                direction(0.02, std::f32::consts::PI), // Geometric reflection; shading transmission.
                direction(-0.02, 0.0), // Geometric transmission; shading reflection.
                direction(0.98, 0.0),
                direction(-0.2, std::f32::consts::PI),
                [1.0, 0.0, 0.0, 0.0], // No nonzero geometric side.
            ] {
                let mut case = [0; INPUT_WORDS];
                case[..4].copy_from_slice(&bits(geometric));
                case[4..8].copy_from_slice(&bits(shading));
                case[8..12].copy_from_slice(&bits(view));
                case[12..16].copy_from_slice(&bits(scatter));
                case[16] = u32::from(normal_mapped);
                case[17] = u32::from(dielectric);
                cases.push(case);
            }
        }
    }
    let results = execute(8, &cases);
    let mut rejected_nonzero_dielectric = [0; 2];
    let mut accepted_nonzero_dielectric = [0; 2];
    for (case, words) in cases.iter().zip(results) {
        let scatter = [case[12], case[13], case[14]].map(f32::from_bits);
        let geometric_side = view[2] * scatter[2];
        let shading_view = shading[0] * view[0] + shading[2] * view[2];
        let shading_side = shading_view * (shading[0] * scatter[0] + shading[2] * scatter[2]);
        let lobe = usize::from(shading_side < 0.0);
        let normal_mapped = case[16] != 0;
        let dielectric = case[17] != 0;
        let expected = !normal_mapped
            || geometric_side != 0.0
                && if dielectric {
                    shading_side != 0.0 && (shading_side >= 0.0) == (geometric_side > 0.0)
                } else {
                    geometric_side > 0.0
                };
        assert_eq!(words[0], u32::from(expected), "direct-light physical side");
        let raw = [words[4], words[5], words[6], words[7]].map(f32::from_bits);
        assert!(raw.iter().all(|v| v.is_finite() && *v >= 0.0));
        if expected {
            assert_eq!(
                &words[8..12],
                &words[4..8],
                "supported evaluation stays exact"
            );
            if normal_mapped && dielectric && raw[3] > 0.0 {
                accepted_nonzero_dielectric[lobe] += 1;
            }
        } else {
            assert_eq!(
                &words[8..12],
                &[0; 4],
                "unsupported evaluation is canonical zero"
            );
            if normal_mapped && dielectric && geometric_side != 0.0 && raw[3] > 0.0 {
                rejected_nonzero_dielectric[lobe] += 1;
            }
        }
    }
    assert_eq!(
        rejected_nonzero_dielectric,
        [1, 1],
        "both wrong-side dielectric lobes are exercised"
    );
    assert_eq!(
        accepted_nonzero_dielectric,
        [1, 1],
        "both supported dielectric lobes are exercised"
    );
}

#[test]
fn pbr_spirv_bindings_match_full_production_and_lite_reference() {
    fn bindings(code: &[u8], descriptor_set: u32) -> Vec<u32> {
        use std::collections::{BTreeMap, BTreeSet};
        let words: Vec<_> = code
            .as_chunks::<4>()
            .0
            .iter()
            .map(|&bytes| u32::from_le_bytes(bytes))
            .collect();
        assert_eq!(words[0], 0x0723_0203, "compiled SPIR-V magic");
        assert_eq!(code.len() % 4, 0);
        let mut decorations = BTreeMap::<u32, (Option<u32>, Option<u32>)>::new();
        let mut offset = 5;
        while offset < words.len() {
            let count = (words[offset] >> 16) as usize;
            let opcode = words[offset] & 0xffff;
            assert!(count > 0 && offset + count <= words.len());
            assert!(
                !(opcode == 17 && words[offset + 1] == 11),
                "production shader must not introduce the unnegotiated Int64 capability"
            );
            if opcode == 71 && count == 4 {
                let entry = decorations.entry(words[offset + 1]).or_default();
                match words[offset + 2] {
                    33 => entry.1 = Some(words[offset + 3]),
                    34 => entry.0 = Some(words[offset + 3]),
                    _ => {}
                }
            }
            offset += count;
        }
        decorations
            .values()
            .filter_map(|&(set, binding)| {
                if set == Some(descriptor_set) {
                    binding
                } else {
                    None
                }
            })
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
    fn specializations(code: &[u8]) -> Vec<(u32, u32)> {
        use std::collections::BTreeMap;
        let words: Vec<_> = code
            .as_chunks::<4>()
            .0
            .iter()
            .map(|&word| u32::from_le_bytes(word))
            .collect();
        let mut ids = BTreeMap::new();
        let mut defaults = BTreeMap::new();
        let mut offset = 5;
        while offset < words.len() {
            let count = (words[offset] >> 16) as usize;
            assert!(count > 0 && offset + count <= words.len());
            match words[offset] & 0xffff {
                71 if count == 4 && words[offset + 2] == 1 => {
                    ids.insert(words[offset + 1], words[offset + 3]);
                }
                50 if count == 4 => {
                    defaults.insert(words[offset + 2], words[offset + 3]);
                }
                _ => {}
            }
            offset += count;
        }
        let mut result: Vec<_> = ids
            .into_iter()
            .map(|(target, id)| (id, defaults[&target]))
            .collect();
        result.sort_unstable();
        result
    }
    // The complete scene/layout declarations remain present in unspecialized SPIR-V.
    // Raw K1/K2/Offline use motion ID3=0 (the default as well as the production selection),
    // so no previous-pose transform consumer is enabled. Actual compiled loadSurface already
    // loads only its current members; no driver/physical traffic reduction is asserted here.
    type StageContract = (
        &'static str,
        &'static [u8],
        &'static [u32],
        &'static [u32],
        &'static [(u32, u32)],
    );
    const PRIMARY_IDS: &[(u32, u32)] = &[(0, 2), (2, 1), (3, 0)];
    const TRANSPORT_IDS: &[(u32, u32)] = &[(0, 2), (1, 1), (2, 1), (3, 0)];
    let stages: [StageContract; 23] = [
        (
            "Offline",
            prime_shaders::path_trace(),
            &[0, 2, 3, 4, 5, 7, 8, 9],
            &[0, 1, 2, 3, 4, 5, 6],
            &[(0, 2), (1, 1), (2, 1), (3, 0), (4, 0)],
        ),
        (
            "K1 raw",
            prime_shaders::realtime_primary(),
            &[0, 2, 3, 7, 8],
            &[0, 1, 2, 5, 6],
            PRIMARY_IDS,
        ),
        (
            "K1 RR",
            prime_shaders::realtime_primary_rr(),
            &[0, 2, 3, 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22],
            &[0, 1, 2, 5, 6],
            PRIMARY_IDS,
        ),
        (
            "K2 raw",
            prime_shaders::realtime_transport(),
            &[0, 2, 3, 7, 8, 9],
            &[0, 1, 2, 5, 6],
            TRANSPORT_IDS,
        ),
        (
            "K2 RR",
            prime_shaders::realtime_transport_rr(),
            &[0, 2, 3, 7, 8, 9, 16],
            &[0, 1, 2, 5, 6],
            TRANSPORT_IDS,
        ),
        ("post raw", prime_shaders::realtime(), &[4], &[0, 3, 4], &[]),
        (
            "post RR input",
            prime_shaders::realtime_rr(),
            &[10, 11, 12, 16, 17, 19, 20],
            &[0, 3, 4],
            &[],
        ),
        (
            "RR display",
            prime_shaders::rr_display(),
            &[4, 10, 11, 13, 18, 19],
            &[],
            &[],
        ),
        (
            "post raw linear",
            prime_shaders::realtime_linear(),
            &[4],
            &[0, 3, 4],
            &[],
        ),
        (
            "RR linear",
            prime_shaders::rr_linear(),
            &[4, 10, 18, 19],
            &[],
            &[],
        ),
        (
            "linear display",
            prime_shaders::display_from_linear(),
            &[0, 1, 2, 3],
            &[],
            &[],
        ),
        ("stars", prime_shaders::stars(), &[0, 1, 2, 3], &[], &[]),
        (
            "exposure histogram",
            prime_shaders::exposure_histogram(),
            &[0, 1],
            &[],
            &[],
        ),
        (
            "exposure update",
            prime_shaders::exposure_update(),
            &[1, 2],
            &[],
            &[],
        ),
        (
            "HDR present",
            prime_shaders::hdr_present(),
            &[0, 1, 2, 3, 4, 5],
            &[],
            &[],
        ),
        (
            "FG present",
            prime_shaders::frame_generation_present(),
            &[0, 1, 2, 3],
            &[],
            &[],
        ),
        (
            "atmosphere prepare",
            prime_shaders::atmosphere_prepare(),
            &[],
            &[0],
            &[],
        ),
        (
            "atmosphere sky",
            prime_shaders::atmosphere_sky_update(),
            &[0, 1, 2, 3, 4, 5],
            &[0],
            &[],
        ),
        (
            "atmosphere transmittance",
            prime_shaders::atmosphere_transmittance_update(),
            &[0],
            &[0],
            &[],
        ),
        (
            "atmosphere aerial",
            prime_shaders::atmosphere_aerial_update(),
            &[0, 1, 2, 4, 5],
            &[0],
            &[],
        ),
        (
            "atmosphere aerial transmittance",
            prime_shaders::atmosphere_aerial_transmittance_update(),
            &[5],
            &[0],
            &[],
        ),
        (
            "atmosphere shadow demand",
            prime_shaders::atmosphere_shadow_demand(),
            &[],
            &[0],
            &[],
        ),
        (
            "atmosphere shadow resolve",
            prime_shaders::atmosphere_shadow_resolve(),
            &[],
            &[0],
            &[(0, 2), (2, 1)],
        ),
    ];
    for (stage, code, scene, atmosphere, ids) in stages {
        assert_eq!(bindings(code, 0), scene, "{stage}: scene/output contract");
        assert_eq!(
            bindings(code, 1),
            atmosphere,
            "{stage}: atmosphere contract"
        );
        assert_eq!(
            specializations(code),
            ids,
            "{stage}: specialization IDs/defaults contract"
        );
    }
    assert_eq!(
        bindings(prime_shader_tests::pbr(), 0),
        [0, 1],
        "the retained Lite behavior fixture needs no energy table"
    );
    assert_eq!(
        bindings(prime_shader_tests::full_openpbr(), 0),
        [0, 1, 9],
        "the Full constructor oracle binds the actual energy table"
    );
}

#[test]
#[ignore = "requires Vulkan; ports historical LitePBR properties for all six topologies"]
fn gpu_litepbr_six_topologies_preserve_historical_properties() {
    let roughnesses = [
        0.0f32,
        f32::from_bits(0.01f32.to_bits() - 1),
        0.01,
        f32::from_bits(0.01f32.to_bits() + 1),
        0.05,
        0.2,
        0.5,
        1.0,
    ];
    let iors = [1.0f32, 1.1, 1.333, 1.45, 1.5, 2.4];
    let cosines = [1e-4f32, 0.01, 0.1, 0.35, 0.65, 0.9, 1.0];
    let boundaries = [
        0.0f32,
        f32::from_bits(1),
        0.5,
        f32::from_bits(1.0f32.to_bits() - 1),
    ];
    let mut cases = Vec::new();
    for kind in 0..6u32 {
        for local in 0..4096usize {
            let alternate = local & 1 != 0;
            let wi =
                cosines[local % cosines.len()] * if kind == 4 && alternate { -1.0 } else { 1.0 };
            let magnitude = cosines[(local * 5 + 2) % cosines.len()];
            let transmission = if kind == 2 {
                !alternate || local & 2 != 0
            } else {
                kind >= 3 && local & 2 != 0
            };
            let wo = magnitude.copysign(wi) * if transmission { -1.0 } else { 1.0 };
            let rng = random(local as u32 + 4096 * kind);
            let proposal = if local < 16 {
                [
                    boundaries[local % 4],
                    boundaries[(local / 4) % 4],
                    boundaries[(local / 16) % 4],
                ]
            } else {
                let values = random(local as u32 + 8192 * kind + 300);
                [values[0], values[1], values[2]]
            };
            let mut case = [0; INPUT_WORDS];
            case[0] = kind;
            let params = [
                roughnesses[local % roughnesses.len()],
                iors[(local / roughnesses.len()) % iors.len()],
                wi,
                rng[0] * 2.0 * std::f32::consts::PI,
                wo,
                rng[1] * 2.0 * std::f32::consts::PI,
                proposal[0],
                proposal[1],
                proposal[2],
                if local % 31 == 0 {
                    0.0
                } else {
                    0.02 + 0.98 * rng[0]
                },
                if local % 37 == 0 {
                    0.0
                } else {
                    0.02 + 0.98 * rng[1]
                },
                if local % 41 == 0 {
                    0.0
                } else {
                    0.02 + 0.98 * rng[2]
                },
                1.0,
                0.001 + 16.0 * rng[2],
                if alternate { 1.0 } else { 0.0 },
            ];
            case[1..16].copy_from_slice(&params.map(f32::to_bits));
            cases.push(case);
        }
    }
    let results = execute(9, &cases);
    let mut accepted = [0; 6];
    let mut discrete = [0; 6];
    let mut transmitted = [0; 6];
    for (index, (case, words)) in cases.iter().zip(results).enumerate() {
        assert_eq!(
            words[0], 0,
            "historical property mask at case {index}: {case:?}; {words:?}"
        );
        assert_eq!(words[1], case[0]);
        let kind = case[0] as usize;
        let flags = words[28];
        if flags == 0 {
            continue;
        }
        accepted[kind] += 1;
        if flags & 48 != 0 {
            discrete[kind] += 1;
        }
        if flags & 42 != 0 {
            transmitted[kind] += 1;
        }
        let eta = f32::from_bits(words[27]);
        let expected = if kind == 4 && flags & 42 != 0 {
            let ior = f32::from_bits(case[2]);
            if f32::from_bits(case[3]) > 0.0 {
                ior
            } else {
                1.0 / ior
            }
        } else {
            1.0
        };
        close(eta, expected, 3e-6, "historical sample relative eta");
    }
    for kind in 0..6 {
        assert!(accepted[kind] > 128, "topology {kind} accepted proposals");
        assert!(
            discrete[kind] > 0,
            "topology {kind} discrete reflection/transmission exercised"
        );
    }
    for (kind, &count) in transmitted.iter().enumerate().skip(2) {
        assert!(count > 40, "topology {kind} transmission exercised");
    }
}

#[test]
#[ignore = "requires Vulkan; checks generic dispatch rejects unsupported closures"]
fn gpu_litepbr_generic_dispatch_has_explicit_supported_topologies() {
    let kinds: Vec<_> = (0..13).chain(100..106).collect();
    let cases: Vec<_> = kinds
        .iter()
        .map(|&kind| {
            let mut case = [0; INPUT_WORDS];
            case[0] = kind;
            case
        })
        .collect();
    for (&kind, words) in kinds.iter().zip(execute(10, &cases)) {
        if kind < 100 {
            assert_eq!(&words[..3], &[0; 3], "unsupported kind {kind}");
            assert_eq!(
                &words[4..11],
                &[0; 7],
                "canonical rejected evaluation/direction {kind}"
            );
            assert_eq!(
                &words[12..16],
                &[0; 4],
                "canonical rejected response/PDF {kind}"
            );
            assert_eq!(words[11], 1.0f32.to_bits(), "rejected relative eta");
            assert_eq!(&words[16..20], &[0; 4], "rejected event");
            assert_eq!(&words[20..24], &bits([1.0, 0.0, 0.0, 0.0]));
        } else {
            assert!(words[..3].iter().any(|&v| v != 0), "supported kind {kind}");
            assert_ne!(words[16], 0, "supported sample {kind}");
            assert!(f32::from_bits(words[15]) > 0.0, "supported PDF {kind}");
            assert!(words[4..16].iter().all(|&v| f32::from_bits(v).is_finite()));
        }
    }
}

#[test]
#[ignore = "requires Vulkan; verifies signed exit Fresnel/TIR and endpoint handoff"]
fn gpu_litepbr_exit_tir_pdf_and_relative_eta_match_the_proven_endpoints() {
    let mut cases = Vec::new();
    for (incident_ior, transmitted_ior) in [(1.0, 1.5), (1.5, 1.0), (1.6, 1.3)] {
        for smoothness in [128, 255] {
            for cosine in [0.2, 0.5, 0.9, 1.0] {
                for sample in 0..128 {
                    let mut case = source_case([smoothness, 4, 0, 255], 2, true);
                    case[8..12].copy_from_slice(&bits(direction(cosine, 0.7)));
                    case[12..16].copy_from_slice(&bits(random(sample)));
                    case[16..20].copy_from_slice(&bits([incident_ior, 0.2, 0.3, 0.4]));
                    case[20..24].copy_from_slice(&bits([transmitted_ior, 0.3, 0.5, 0.7]));
                    cases.push(case);
                }
            }
        }
    }
    let mut exiting_transmission = 0;
    let mut smooth_tir_reflection = 0;
    let mut rejected = [0; 2];
    for (case, words) in cases.iter().zip(execute(2, &cases)) {
        let flags = words[8];
        let incident = f32::from_bits(case[16]);
        let transmitted = f32::from_bits(case[20]);
        let eta = transmitted / incident;
        let cosine = f32::from_bits(case[10]);
        let smooth_tir = case[1].to_le_bytes()[0] == 255 && (1.0 - cosine * cosine) > eta * eta;
        if smooth_tir {
            // Check the event before handling rejected proposals: TIR must not disappear.
            assert_eq!(
                flags,
                1 | 16,
                "smooth TIR must produce discrete reflection for endpoints {incident}->{transmitted} at cosine {cosine}"
            );
        }
        if flags == 0 {
            rejected[usize::from(incident > transmitted)] += 1;
            continue;
        }
        let values = words.map(f32::from_bits);
        let transmits = flags & 2 != 0;
        close(
            values[3],
            if transmits { eta } else { 1.0 },
            3e-6,
            "physical relative eta",
        );
        assert_eq!(
            &words[12..16],
            if transmits {
                &case[20..24]
            } else {
                &case[16..20]
            }
        );
        if transmits && incident > transmitted {
            exiting_transmission += 1;
        }
        if flags & 16 == 0 {
            for channel in 0..4 {
                close(
                    values[4 + channel],
                    values[16 + channel],
                    3e-4,
                    "dielectric sample/eval and PDF",
                );
            }
        }
        if smooth_tir {
            smooth_tir_reflection += 1;
            assert_eq!(flags, 1 | 16, "smooth TIR is discrete reflection");
            close(values[7], 1.0, 0.0, "TIR selection probability");
            for &value in &values[4..7] {
                close(value, 1.0, 0.0, "TIR unit reflection");
            }
        }
    }
    eprintln!(
        "Lite exit endpoints: exiting transmission={exiting_transmission}, smooth TIR reflection={smooth_tir_reflection}, rejected entering/exiting={rejected:?}"
    );
    assert!(
        exiting_transmission > 100 && smooth_tir_reflection > 100,
        "exiting transmission={exiting_transmission}, smooth TIR reflection={smooth_tir_reflection}, rejected={rejected:?}"
    );
}

#[test]
#[ignore = "requires Vulkan; verifies lower-IOR thin-wall TIR has no transmission"]
fn gpu_litepbr_thin_lower_ior_tir_reflects_without_changing_medium() {
    let root = 0.02f32.sqrt();
    let slab_ior = (1.0 + root) / (1.0 - root);
    let mut cases = Vec::new();
    for smoothness in [250, 255] {
        for selector in [0.0, 0.5, f32::from_bits(1.0f32.to_bits() - 1)] {
            let mut case = source_case([smoothness, 4, 0, 255], 6, true);
            case[8..12].copy_from_slice(&bits(direction(0.05, 0.0)));
            case[12..16].copy_from_slice(&bits([0.0, 0.0, selector, 0.0]));
            case[16..20].copy_from_slice(&bits([1.333, 0.2, 0.3, 0.4]));
            case[20..24].copy_from_slice(&bits([slab_ior, 0.3, 0.5, 0.7]));
            cases.push(case);
        }
    }
    for (case, words) in cases.iter().zip(execute(11, &cases)) {
        let values = words.map(f32::from_bits);
        assert_ne!(words[8] & 1, 0, "TIR reflects");
        assert_eq!(words[8] & 2, 0, "TIR has no sampled transmission");
        close(values[3], 1.0, 0.0, "thin-wall eta");
        assert_eq!(&words[12..16], &case[16..20], "thin-wall path medium");
        for &value in &values[24..27] {
            close(value, 1.0, 0.0, "unit directional reflection");
        }
        assert_eq!(&words[20..24], &[0; 4], "zero transmitted evaluation/PDF");
        assert_eq!(&words[28..31], &[0; 3], "zero directional transmission");
        if case[1].to_le_bytes()[0] == 250 {
            assert!(values[19] > 0.0, "rough TIR evaluation is nonzero");
            for channel in 0..4 {
                close(
                    values[4 + channel],
                    values[16 + channel],
                    3e-4,
                    "rough TIR sample/eval/PDF",
                );
            }
        } else {
            assert_ne!(words[8] & 16, 0, "smooth TIR is discrete");
            close(values[7], 1.0, 0.0, "smooth TIR PDF");
            for &value in &values[4..7] {
                close(value, 1.0, 0.0, "smooth TIR response");
            }
        }
    }
}

#[test]
#[ignore = "requires Vulkan; checks marginal PDFs and complementary direct-light MIS"]
fn gpu_litepbr_continuous_mixtures_match_evaluation_and_two_strategy_mis() {
    let mut cases = Vec::new();
    for kind in 0..4 {
        for generic in [false, true] {
            if kind == 3 && !generic {
                continue;
            }
            for smoothness in [128, 255] {
                for cosine in [0.05, 0.5, 0.95] {
                    for proposal in 0..256 {
                        let thin = kind == 1 || kind == 3;
                        let mut case = source_case(
                            [smoothness, 4, if kind == 0 { 0 } else { 180 }, 255],
                            2 | (u32::from(thin) * 4),
                            false,
                        );
                        case[8..12].copy_from_slice(&bits(direction(cosine, 0.7)));
                        case[12..16].copy_from_slice(&bits(random(proposal)));
                        case[28] = kind;
                        case[29] = u32::from(generic);
                        cases.push(case);
                    }
                }
            }
        }
    }
    let mut reflected = [0; 4];
    let mut transmitted = [0; 4];
    let mut discrete = [0; 4];
    let mut corrected = [0; 4];
    let mut raw_mis_mismatch = [0; 4];
    for (case, words) in cases.iter().zip(execute(12, &cases)) {
        let kind = case[28] as usize;
        let flags = words[8];
        if flags == 0 {
            continue;
        }
        let values = words.map(f32::from_bits);
        assert_eq!(
            flags, words[28],
            "selected event classification is retained"
        );
        close(
            values[3],
            values[27],
            0.0,
            "selected relative eta is retained",
        );
        assert_eq!(
            &words[12..16],
            &case[16..20],
            "all surface mixtures keep their medium"
        );
        close(
            values[34],
            1.0,
            3e-6,
            "complementary two-strategy MIS weights",
        );
        for channel in 0..3 {
            close(
                values[36 + channel],
                values[40 + channel],
                5e-6,
                "density-weighted direct-light plus continuation integrand",
            );
        }
        if flags & 16 != 0 {
            discrete[kind] += 1;
            assert_eq!(
                &words[4..8],
                &words[20..24],
                "discrete response and joint mass stay unchanged"
            );
            assert_eq!(
                words[35], 0,
                "discrete sample has no direct-light competition"
            );
            close(values[32], 0.0, 0.0, "discrete NEE weight");
            close(values[33], 1.0, 0.0, "discrete continuation weight");
            continue;
        }
        for channel in 0..4 {
            close(
                values[4 + channel],
                values[16 + channel],
                1e-4,
                "complete continuous response and marginal PDF",
            );
        }
        if (values[7] - values[23]).abs() > 1e-5 * values[7].max(1e-3) {
            corrected[kind] += 1;
        }
        if flags & 1 != 0 {
            reflected[kind] += 1;
            assert_eq!(
                words[35], words[7],
                "reflection competes with its marginal BSDF density"
            );
            if (values[44] - 1.0).abs() > 1e-5 {
                raw_mis_mismatch[kind] += 1;
            }
        } else {
            transmitted[kind] += 1;
            assert_ne!(flags & 2, 0, "the second hemisphere is transmission");
            assert_eq!(
                words[35], 0,
                "opaque/foliage transmission has no reflection-only NEE competitor"
            );
            close(values[32], 0.0, 0.0, "transmission NEE weight");
            close(values[33], 1.0, 0.0, "transmission continuation weight");
        }
    }
    for kind in 0..4 {
        assert!(
            reflected[kind] > 40 && discrete[kind] > 0,
            "reflection and discrete topology {kind}"
        );
        assert!(
            corrected[kind] > 100,
            "marginal PDF replacement exercised for topology {kind}"
        );
        assert!(
            raw_mis_mismatch[kind] > 40,
            "the fixture must expose historical joint-PDF MIS mismatch for topology {kind}"
        );
    }
    for (kind, &count) in transmitted.iter().enumerate().skip(1) {
        assert!(count > 40, "SSS/foliage transmission for topology {kind}");
    }
}

#[test]
#[ignore = "requires Vulkan; actual HALF4 LUT plus retained full-model constructor oracle"]
fn gpu_full_openpbr_narrow_constructors_preserve_legacy_math() {
    let mut cases = Vec::new();
    for kind in 0..4u32 {
        for roughness in [0.0, 1e-8, 0.009999, 0.01, 0.010001, 0.2, 0.5, 1.0] {
            for cosine in [1.0f32, 0.8, 0.02, 0.0001, -0.8, -0.02] {
                for ior in [1.0f32, 1.0001, 1.33, 1.5, 2.5, 0.8, 1.0 / 1.5] {
                    for subsurface in [0.0, 1.0 / 190.0, 0.25, 0.5, 0.75, 1.0] {
                        if kind != 0 && subsurface != 0.0 {
                            continue;
                        }
                        for random_z in [0.0f32, 1.0 / 16777216.0, 0.5, 0.99999994, 1.0] {
                            let mut record = Vec::new();
                            record.extend(bits([0.4, 0.6, 0.2, roughness]));
                            record.extend(bits([(1.0 - cosine * cosine).sqrt(), 0.0, cosine, ior]));
                            record.extend(bits([0.0, 0.6, 0.8, subsurface]));
                            record.extend([
                                0.3f32.to_bits(),
                                0.7f32.to_bits(),
                                random_z.to_bits(),
                                kind,
                            ]);
                            record.extend(bits([0.4, 0.6, 0.8, 0.0]));
                            record.extend(bits([0.8, 1.0, 0.9, 0.0]));
                            cases.extend(record);
                        }
                    }
                }
            }
        }
    }
    let count = cases.len() / 24;
    let context = Context::new().unwrap();
    let values = super::shader_tests::run_full_openpbr(
        &context,
        prime_shader_tests::full_openpbr(),
        &cases,
        count * 32,
        count as u32,
    );
    for (case, output) in values.as_chunks::<32>().0.iter().enumerate() {
        for word in 0..16 {
            if (8..12).contains(&word) {
                assert_eq!(
                    output[word],
                    output[word + 16],
                    "full flags case {case} word {word}"
                );
            } else {
                let reference = f32::from_bits(output[word]);
                let actual = f32::from_bits(output[word + 16]);
                if reference.is_nan() && actual.is_nan() {
                    continue;
                }
                assert!(
                    reference == actual
                        || (reference - actual).abs()
                            <= 2e-6 + 2e-5 * reference.abs().max(actual.abs()),
                    "full math case {case} word {word}: {reference} vs {actual}"
                );
            }
        }
    }
}
