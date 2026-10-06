//! Checks canonical source inputs, production Full closures and numeric boundaries.
use super::Context;

const INPUT_WORDS: usize = 32;
const OUTPUT_WORDS: usize = 64;

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
    let scattering = b.saturating_sub(65);
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
    super::shader_tests::run_full_openpbr_config(
        &context,
        prime_shader_tests::pbr(),
        &input,
        cases.len() * OUTPUT_WORDS,
        [mode, cases.len() as u32],
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
        let subsurface = specular[2];
        assert_eq!(
            words[4],
            u32::from(fresnel) | u32::from(subsurface) << 8,
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
            if has_specular && !metal && case[2] & 4 != 0 {
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
fn gpu_transport_products_and_mis_avoid_intermediate_overflow() {
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
#[ignore = "requires Vulkan; exercises Full thin SSS and rejects unsupported thick/cutout assumptions"]
fn gpu_full_sss_requires_cpu_thin_proof_and_preserves_path_medium() {
    let mut cases = Vec::new();
    for authored in [false, true] {
        for proven_thin in [false, true] {
            for coverage in [0, 1, 2] {
                for scattering in [0, 64, 65, 66, 255] {
                    for fresnel in [4, 230] {
                        for sample in 0..128 {
                            let flags = u32::from(authored) * 2 + u32::from(proven_thin) * 4;
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
        let proven_thin = case[2] & 4 != 0;
        let specular = case[1].to_le_bytes();
        let thin = proven_thin;
        let conductor = authored && (231..=239).contains(&specular[1]);
        let subsurface = if authored && !conductor && thin {
            f32::from(specular[2]) / 190.0
        } else {
            0.0
        };
        assert_eq!(words[9], u32::from(thin), "source thin-surface declaration");
        assert_eq!(
            words[10],
            u32::from(thin && !conductor),
            "CPU proven thin SSS geometry"
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
            if specular[2] == 190 {
                close(
                    values[5] / values[4],
                    1.5,
                    3e-6,
                    "pure subsurface transmission color",
                );
            }
            if thin {
                thin_transmission += 1;
            } else {
                thick_transmission += 1;
            }
            if !proven_thin && case[28] == 1 {
                cutout_transmission += 1;
            }
        } else if subsurface == 0.0 {
            assert_eq!(flags & 2, 0, "absent authored SSS cannot add transmission");
        }
    }
    assert!(
        cutout_transmission == 0,
        "coverage alone cannot authorize SSS transmission"
    );
    assert!(thin_transmission > 40);
    assert_eq!(
        thick_transmission, 0,
        "unsupported thick SSS never transmits"
    );
}

#[test]
#[ignore = "requires Vulkan; checks direct-light geometric support against actual closure values"]
fn gpu_full_normal_mapped_evaluation_rejects_the_wrong_physical_boundary_side() {
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
fn pbr_spirv_bindings_match_full_production_and_fixtures() {
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
            prime_shaders::path_trace_tree(),
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
            prime_shaders::realtime_transport_tree(),
            &[0, 2, 3, 7, 8, 9],
            &[0, 1, 2, 5, 6],
            TRANSPORT_IDS,
        ),
        (
            "K2 RR",
            prime_shaders::realtime_transport_rr_tree(),
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
            &[4, 10, 11, 13, 14, 15, 18, 19],
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
            &[4, 10, 11, 13, 14, 15, 18, 19],
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
        [0, 1, 9],
        "production Full behavior fixture binds the actual energy table"
    );
    assert_eq!(
        bindings(prime_shader_tests::full_openpbr(), 0),
        [0, 1, 9],
        "the Full constructor oracle binds the actual energy table"
    );
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

#[test]
#[ignore = "requires Vulkan; actual Full LUT, complete mixture PDFs, MIS and physical media"]
fn gpu_full_production_samples_match_evaluation_mis_and_medium_handoff() {
    let mut cases = Vec::new();
    for (g, b, thin, dielectric) in [
        (4, 0, false, false),
        (230, 0, false, false),
        (4, 255, true, false),
        (4, 128, true, false),
        (4, 0, false, true),
        (4, 0, true, true),
    ] {
        for sample in 0..256 {
            let mut case = source_case([128, g, b, 255], 2 | u32::from(thin) * 4, dielectric);
            case[12..16].copy_from_slice(&bits(random(sample)));
            case[8..12].copy_from_slice(&bits(direction(
                [1.0, 0.8, 0.01, -0.8][sample as usize % 4],
                0.3,
            )));
            if dielectric {
                let ior = [[1.0, 1.5], [1.5, 1.0], [1.0, 1.0], [1.33, 1.5], [1.5, 0.9]]
                    [sample as usize % 5];
                case[16..20].copy_from_slice(&bits([ior[0], 0.01, 0.03, 0.02]));
                case[20..24].copy_from_slice(&bits([ior[1], 0.1, 0.2, 0.3]));
            }
            cases.push(case);
        }
    }
    let results = execute(12, &cases);
    let mut valid = 0;
    let mut diffuse_transmission = 0;
    let mut solid_transmission = 0;
    for (case, words) in cases.iter().zip(results) {
        let flags = words[8];
        if flags == 0 {
            continue;
        }
        valid += 1;
        let v = words.map(f32::from_bits);
        close(
            v[..3].iter().map(|x| x * x).sum(),
            1.0,
            1e-3,
            "Full unit sample",
        );
        assert!(v[3] > 0.0 && v[3].is_finite());
        assert!(v[4..8].iter().all(|x| x.is_finite() && *x >= 0.0));
        assert!(v[7] > 0.0);
        let thin = case[2] & 4 != 0;
        let dielectric = case[3] != 0;
        let transmission = flags & 2 != 0;
        let expected_medium = if dielectric && !thin && transmission {
            &case[20..24]
        } else {
            &case[16..20]
        };
        assert_eq!(&words[12..16], expected_medium, "Full endpoint medium");
        if !dielectric && transmission {
            assert!(thin && case[1].to_le_bytes()[2] > 0);
            assert_ne!(flags & 4, 0, "thin SSS is diffuse");
            close(v[3], 1.0, 0.0, "thin SSS eta");
            diffuse_transmission += 1;
        }
        if dielectric && !thin && transmission {
            solid_transmission += 1;
        }
        if flags & 16 != 0 {
            continue;
        }
        for axis in 0..4 {
            close(
                v[16 + axis],
                v[4 + axis],
                3e-5,
                "Full response/marginal PDF",
            );
        }
        close(v[34], 1.0, 3e-6, "two-strategy MIS partition");
        for axis in 0..3 {
            close(
                v[36 + axis],
                v[40 + axis],
                4e-5,
                "MIS reconstructs complete integrand",
            );
        }
    }
    assert!(valid > 1_000 && diffuse_transmission > 40 && solid_transmission > 40);
}

#[test]
#[ignore = "requires Vulkan; unsupported thick SSS equals ordinary Full opaque for identical samples"]
fn gpu_full_thick_sss_fallback_matches_ordinary_opaque_exactly() {
    let mut cases = Vec::new();
    for sample in 0..128 {
        for b in [0, 66, 128, 255] {
            let mut case = source_case([128, 4, b, 255], 2, false);
            case[12..16].copy_from_slice(&bits(random(sample)));
            cases.push(case);
        }
    }
    let results = execute(2, &cases);
    for group in results.chunks_exact(4) {
        for value in &group[1..] {
            assert_eq!(value, &group[0], "thick source never enters SSS math");
        }
        assert_eq!(group[0][8] & 2, 0, "ordinary opaque has no transmission");
    }
}

#[test]
#[ignore = "requires Vulkan; Full thin SSS lobes against independent colored cosine and PDF oracle"]
fn gpu_full_thin_sss_lobes_match_colored_two_hemisphere_cosine_oracle() {
    let mut cases = Vec::new();
    for thin in [false, true] {
        for sample in 0..128 {
            let mut case = source_case([128, 4, 255, 255], 2 | u32::from(thin) * 4, false);
            case[12..16].copy_from_slice(&bits(random(sample)));
            case[24..28].copy_from_slice(&bits(direction(
                if sample % 2 == 0 { 0.6 } else { -0.8 },
                0.7,
            )));
            cases.push(case);
        }
    }
    let results = execute(14, &cases);
    let mut reflection = 0;
    let mut transmission = 0;
    for (case, words) in cases.iter().zip(results) {
        let thin = case[2] & 4 != 0;
        assert_eq!(
            words[28],
            u32::from(thin),
            "Full rejects thick subsurface topology"
        );
        if !thin {
            assert!(words.iter().all(|w| *w == 0));
            continue;
        }
        let v = words.map(f32::from_bits);
        let cosine = f64::from(f32::from_bits(case[26])).abs();
        let pdf = (cosine / (2.0 * std::f64::consts::PI)) as f32;
        close(v[3], pdf, 3e-7, "Full two-hemisphere PDF");
        for axis in 0..3 {
            close(
                v[axis],
                pdf * f32::from_bits(case[4 + axis]),
                3e-7,
                "colored thin SSS response",
            );
        }
        close(v[7], 1.0, 0.0, "thin lobe eta");
        let sample_pdf = (f64::from(v[6]).abs() / (2.0 * std::f64::consts::PI)) as f32;
        close(v[11], sample_pdf, 3e-7, "sampled hemisphere PDF");
        for axis in 0..3 {
            close(
                v[8 + axis],
                sample_pdf * f32::from_bits(case[4 + axis]),
                3e-7,
                "sampled colored response",
            );
        }
        match words[12] {
            1 => reflection += 1,
            2 => transmission += 1,
            flags => panic!("wrong thin lobe flags {flags}"),
        }
    }
    assert!(
        reflection > 40 && transmission > 40,
        "both actual hemispheres sampled"
    );
}
