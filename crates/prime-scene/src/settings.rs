//! Current renderer controls. Settings packets are independent of scene command lifetimes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum RenderMode {
    #[default]
    Realtime = 0,
    Offline = 1,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum DiagnosticView {
    #[default]
    Output = 0,
    NoisyColor = 1,
    LinearDepth = 2,
    Normal = 3,
}

/// DLSS input resolution modes. Performance renders half the output width and height.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum ReconstructionQuality {
    Native = 0,
    Quality = 1,
    Balanced = 2,
    #[default]
    Performance = 3,
    UltraPerformance = 4,
}

/// Frame-boundary renderer choice; each method has its own CPU data and shader variant.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum LightSampling {
    #[default]
    Grid = 0,
    Tree = 1,
    TreeSphere = 2,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RenderSettings {
    pub astronomy: crate::environment::Astronomy,
    pub mode: RenderMode,
    pub bounces: u32,
    pub offline_samples: u32,
    pub terrain_batches_per_frame: u32,
    pub exposure: f32,
    pub hue: f32,
    pub saturation: f32,
    pub view: DiagnosticView,
    pub sun: f32,
    pub sky: f32,
    pub depth_range: f32,
    pub seed: u32,
    pub opacity_micromap: bool,
    pub ray_reconstruction: bool,
    pub reconstruction_quality: ReconstructionQuality,
    pub stars: f32,
    pub auto_exposure_compensation: f32,
    pub hdr: bool,
    pub hdr_reference_white: u32,
    pub frame_generation: bool,
    pub light_sampling: LightSampling,
}
impl Default for RenderSettings {
    fn default() -> Self {
        Self {
            astronomy: Default::default(),
            mode: RenderMode::Realtime,
            bounces: 12,
            offline_samples: 1,
            terrain_batches_per_frame: 8,
            exposure: 1.0,
            hue: 0.75,
            saturation: 0.20,
            view: DiagnosticView::Output,
            sun: 1.0,
            sky: 1.0,
            depth_range: 128.0,
            seed: 0x1357_2468,
            opacity_micromap: true,
            ray_reconstruction: true,
            reconstruction_quality: ReconstructionQuality::Performance,
            stars: 1.0,
            auto_exposure_compensation: 0.6,
            hdr: false,
            hdr_reference_white: 0,
            frame_generation: false,
            light_sampling: LightSampling::Grid,
        }
    }
}
impl RenderSettings {
    pub const VERSION: u32 = 7;
    pub const BYTES: usize = 96;
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() != Self::BYTES {
            return Err("Settings require exactly 96 bytes".into());
        }
        let word = |offset| u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
        if word(0) != Self::VERSION {
            return Err("Unsupported settings version".into());
        }
        let result = Self {
            astronomy: crate::environment::Astronomy {
                latitude_degrees: word(48) as i32,
                solar_longitude_degrees: word(52),
            },
            mode: match word(4) {
                0 => RenderMode::Realtime,
                1 => RenderMode::Offline,
                _ => return Err("Unknown renderer mode".into()),
            },
            bounces: word(8),
            offline_samples: word(12),
            terrain_batches_per_frame: word(68),
            exposure: f32::from_bits(word(16)),
            hue: f32::from_bits(word(20)),
            saturation: f32::from_bits(word(24)),
            view: match word(28) {
                0 => DiagnosticView::Output,
                1 => DiagnosticView::NoisyColor,
                2 => DiagnosticView::LinearDepth,
                3 => DiagnosticView::Normal,
                _ => return Err("Unknown diagnostic view".into()),
            },
            sun: f32::from_bits(word(32)),
            sky: f32::from_bits(word(36)),
            depth_range: f32::from_bits(word(40)),
            seed: word(44),
            opacity_micromap: match word(56) {
                0 => false,
                1 => true,
                _ => return Err("Unknown opacity micromap setting".into()),
            },
            ray_reconstruction: match word(60) {
                0 => false,
                1 => true,
                _ => return Err("Unknown ray reconstruction setting".into()),
            },
            reconstruction_quality: match word(64) {
                0 => ReconstructionQuality::Native,
                1 => ReconstructionQuality::Quality,
                2 => ReconstructionQuality::Balanced,
                3 => ReconstructionQuality::Performance,
                4 => ReconstructionQuality::UltraPerformance,
                _ => return Err("Unknown reconstruction quality".into()),
            },
            stars: f32::from_bits(word(72)),
            auto_exposure_compensation: f32::from_bits(word(76)),
            hdr: match word(80) {
                0 => false,
                1 => true,
                _ => return Err("Unknown HDR setting".into()),
            },
            hdr_reference_white: word(84),
            frame_generation: match word(88) {
                0 => false,
                1 => true,
                _ => return Err("Unknown frame generation setting".into()),
            },
            light_sampling: match word(92) {
                0 => LightSampling::Grid,
                1 => LightSampling::Tree,
                2 => LightSampling::TreeSphere,
                _ => return Err("Unknown light sampling method".into()),
            },
        };
        result.validate()?;
        Ok(result)
    }
    pub fn from_abi(s: &prime_abi::PrimeSettings) -> Result<Self, String> {
        let result = Self {
            astronomy: crate::environment::Astronomy {
                latitude_degrees: s.latitude_degrees,
                solar_longitude_degrees: s.solar_longitude_degrees,
            },
            mode: match s.mode {
                0 => RenderMode::Realtime,
                1 => RenderMode::Offline,
                _ => return Err("Unknown renderer mode".into()),
            },
            bounces: s.bounces,
            offline_samples: s.offline_samples,
            terrain_batches_per_frame: s.terrain_batches_per_frame,
            exposure: s.exposure,
            hue: s.hue,
            saturation: s.saturation,
            view: match s.view {
                0 => DiagnosticView::Output,
                1 => DiagnosticView::NoisyColor,
                2 => DiagnosticView::LinearDepth,
                3 => DiagnosticView::Normal,
                _ => return Err("Unknown diagnostic view".into()),
            },
            sun: s.sun,
            sky: s.sky,
            depth_range: s.depth_range,
            seed: s.seed,
            opacity_micromap: match s.opacity_micromap {
                0 => false,
                1 => true,
                _ => return Err("Unknown opacity micromap setting".into()),
            },
            ray_reconstruction: match s.ray_reconstruction {
                0 => false,
                1 => true,
                _ => return Err("Unknown ray reconstruction setting".into()),
            },
            reconstruction_quality: match s.reconstruction_quality {
                0 => ReconstructionQuality::Native,
                1 => ReconstructionQuality::Quality,
                2 => ReconstructionQuality::Balanced,
                3 => ReconstructionQuality::Performance,
                4 => ReconstructionQuality::UltraPerformance,
                _ => return Err("Unknown reconstruction quality".into()),
            },
            stars: s.stars,
            auto_exposure_compensation: s.auto_exposure_compensation,
            hdr: match s.hdr {
                0 => false,
                1 => true,
                _ => return Err("Unknown HDR setting".into()),
            },
            hdr_reference_white: s.hdr_reference_white,
            frame_generation: match s.frame_generation {
                0 => false,
                1 => true,
                _ => return Err("Unknown frame generation setting".into()),
            },
            light_sampling: match s.light_sampling {
                0 => LightSampling::Grid,
                1 => LightSampling::Tree,
                2 => LightSampling::TreeSphere,
                _ => return Err("Unknown light sampling method".into()),
            },
        };
        result.validate()?;
        Ok(result)
    }
    pub fn validate(self) -> Result<(), String> {
        self.astronomy.validate()?;
        if !(1..=64).contains(&self.bounces)
            || !(1..=64).contains(&self.offline_samples)
            || !(1..=128).contains(&self.terrain_batches_per_frame)
            || !(1.0 / 4096.0..=4096.0).contains(&self.exposure)
            || !(0.0..=1.0).contains(&self.hue)
            || !(0.0..=0.5).contains(&self.saturation)
            || !(1.0 / 256.0..=256.0).contains(&self.sun)
            || !(1.0 / 256.0..=256.0).contains(&self.sky)
            || !(1.0..=4096.0).contains(&self.depth_range)
            || !(0.0..=4.0).contains(&self.stars)
            || !(0.0..=1.0).contains(&self.auto_exposure_compensation)
            || self.hdr_reference_white > 10000
        {
            return Err("Renderer settings out of range or non-finite".into());
        }
        Ok(())
    }
    pub fn transport_matches(self, other: Self) -> bool {
        self.bounces == other.bounces
            && self.astronomy == other.astronomy
            && self.sun == other.sun
            && self.sky == other.sky
            && self.stars == other.stars
            && self.seed == other.seed
            && self.light_sampling == other.light_sampling
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn golden() -> Vec<u8> {
        [
            7_u32,
            1,
            12,
            1,
            4_f32.to_bits(),
            0.75_f32.to_bits(),
            0.20_f32.to_bits(),
            3,
            0.5_f32.to_bits(),
            1_f32.to_bits(),
            128_f32.to_bits(),
            0x13572468,
            30,
            0,
            1,
            1,
            3,
            8,
            1_f32.to_bits(),
            0.6_f32.to_bits(),
            0,
            0,
            0,
            0,
        ]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect()
    }
    #[test]
    fn java_wire_layout_and_exact_version() {
        let bytes = golden();
        let settings = RenderSettings::parse(&bytes).unwrap();
        assert_eq!(
            settings,
            RenderSettings {
                mode: RenderMode::Offline,
                exposure: 4.0,
                sun: 0.5,
                view: DiagnosticView::Normal,
                ..Default::default()
            }
        );
        for (offset, value) in [
            (0, 0_u32),
            (0, 1),
            (0, 2),
            (0, 3),
            (0, 4),
            (0, 5),
            (0, 6),
            (0, 8),
            (48, 91),
            (48, (-91i32) as u32),
            (52, 360),
            (4, 2),
            (8, 0),
            (8, 65),
            (12, 65),
            (28, 4),
            (56, 2),
            (60, 2),
            (64, 5),
            (68, 0),
            (68, 129),
            (68, u32::MAX),
            (80, 2),
            (84, 10001),
            (88, 2),
            (92, 3),
            (92, u32::MAX),
        ] {
            let mut invalid = bytes.clone();
            invalid[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            assert!(RenderSettings::parse(&invalid).is_err());
        }
        assert!(RenderSettings::parse(&bytes[..47]).is_err());
        assert!(RenderSettings::parse(&bytes[..68]).is_err());
        let mut longer = bytes;
        longer.push(0);
        assert!(RenderSettings::parse(&longer).is_err());
    }
    #[test]
    fn vertex_budget_defaults_and_all_explicit_wire_values() {
        assert_eq!(RenderSettings::default().bounces, 12);
        for budget in 1..=64_u32 {
            let mut bytes = golden();
            bytes[8..12].copy_from_slice(&budget.to_le_bytes());
            assert_eq!(RenderSettings::parse(&bytes).unwrap().bounces, budget);
        }
        let old = RenderSettings {
            bounces: 4,
            ..Default::default()
        };
        assert!(old.validate().is_ok());
        assert!(!old.transport_matches(RenderSettings::default()));
    }
    #[test]
    fn light_sampling_defaults_and_exact_wire_and_abi_values() {
        let defaults = RenderSettings::default();
        assert_eq!(defaults.light_sampling, LightSampling::Grid);
        for (word, method) in [
            (0_u32, LightSampling::Grid),
            (1, LightSampling::Tree),
            (2, LightSampling::TreeSphere),
        ] {
            let mut bytes = golden();
            bytes[92..96].copy_from_slice(&word.to_le_bytes());
            let parsed = RenderSettings::parse(&bytes).unwrap();
            assert_eq!(parsed.light_sampling, method);
            assert_eq!(
                parsed,
                RenderSettings {
                    light_sampling: method,
                    mode: RenderMode::Offline,
                    exposure: 4.0,
                    sun: 0.5,
                    view: DiagnosticView::Normal,
                    ..defaults
                }
            );
            assert_eq!(
                defaults.transport_matches(RenderSettings {
                    light_sampling: method,
                    ..defaults
                }),
                word == 0
            );
        }
        let abi = prime_abi::PrimeSettings {
            header: Default::default(),
            mode: defaults.mode as u32,
            bounces: defaults.bounces,
            offline_samples: defaults.offline_samples,
            exposure: defaults.exposure,
            hue: defaults.hue,
            saturation: defaults.saturation,
            view: defaults.view as u32,
            sun: defaults.sun,
            sky: defaults.sky,
            depth_range: defaults.depth_range,
            seed: defaults.seed,
            latitude_degrees: defaults.astronomy.latitude_degrees,
            solar_longitude_degrees: defaults.astronomy.solar_longitude_degrees,
            opacity_micromap: defaults.opacity_micromap as u32,
            ray_reconstruction: defaults.ray_reconstruction as u32,
            reconstruction_quality: defaults.reconstruction_quality as u32,
            terrain_batches_per_frame: defaults.terrain_batches_per_frame,
            stars: defaults.stars,
            auto_exposure_compensation: defaults.auto_exposure_compensation,
            hdr: defaults.hdr as u32,
            hdr_reference_white: defaults.hdr_reference_white,
            frame_generation: defaults.frame_generation as u32,
            light_sampling: 1,
        };
        for method in [
            LightSampling::Grid,
            LightSampling::Tree,
            LightSampling::TreeSphere,
        ] {
            assert_eq!(
                RenderSettings::from_abi(&prime_abi::PrimeSettings {
                    light_sampling: method as u32,
                    ..abi
                })
                .unwrap(),
                RenderSettings {
                    light_sampling: method,
                    ..defaults
                }
            );
        }
        for invalid in [3, u32::MAX] {
            assert!(
                RenderSettings::from_abi(&prime_abi::PrimeSettings {
                    light_sampling: invalid,
                    ..abi
                })
                .is_err()
            );
        }
    }
    #[test]
    fn saturation_defaults_and_explicit_legacy_wire_values() {
        let defaults = RenderSettings::default();
        assert_eq!(defaults.saturation, 0.20);
        for value in [0.0_f32, 0.08, 0.20, 0.5] {
            let mut bytes = golden();
            bytes[24..28].copy_from_slice(&value.to_bits().to_le_bytes());
            let accepted = RenderSettings::parse(&bytes).unwrap();
            assert_eq!(accepted.saturation, value);
            assert!(accepted.transport_matches(RenderSettings {
                saturation: defaults.saturation,
                ..accepted
            }));
        }
    }
    #[test]
    fn opacity_micromap_defaults_enabled_and_does_not_change_transport() {
        let enabled = RenderSettings::default();
        assert!(enabled.opacity_micromap);
        let mut bytes = golden();
        bytes[56..60].copy_from_slice(&0_u32.to_le_bytes());
        assert!(!RenderSettings::parse(&bytes).unwrap().opacity_micromap);
        let disabled = RenderSettings {
            opacity_micromap: false,
            ..enabled
        };
        assert!(enabled.transport_matches(disabled));
    }
    #[test]
    fn terrain_batch_budget_defaults_range_and_transport_independence() {
        let defaults = RenderSettings::default();
        assert_eq!(defaults.terrain_batches_per_frame, 8);
        for budget in 1..=128_u32 {
            let mut bytes = golden();
            bytes[68..72].copy_from_slice(&budget.to_le_bytes());
            assert_eq!(
                RenderSettings::parse(&bytes)
                    .unwrap()
                    .terrain_batches_per_frame,
                budget
            );
            for mode in [RenderMode::Realtime, RenderMode::Offline] {
                let before = RenderSettings { mode, ..defaults };
                let after = RenderSettings {
                    terrain_batches_per_frame: budget,
                    ..before
                };
                assert!(after.validate().is_ok());
                assert!(before.transport_matches(after));
                assert!(after.transport_matches(before));
            }
        }
        for budget in [0, 129, u32::MAX] {
            assert!(
                RenderSettings {
                    terrain_batches_per_frame: budget,
                    ..defaults
                }
                .validate()
                .is_err()
            );
        }
    }
    #[test]
    fn ray_reconstruction_is_enabled_by_default_and_is_not_a_transport_change() {
        let enabled = RenderSettings::default();
        assert!(enabled.ray_reconstruction);
        assert_eq!(
            enabled.reconstruction_quality,
            ReconstructionQuality::Performance
        );
        let mut bytes = golden();
        bytes[60..64].copy_from_slice(&0_u32.to_le_bytes());
        assert!(!RenderSettings::parse(&bytes).unwrap().ray_reconstruction);
        assert!(enabled.transport_matches(RenderSettings {
            ray_reconstruction: false,
            ..enabled
        }));
        for quality in 0..=4_u32 {
            bytes[64..68].copy_from_slice(&quality.to_le_bytes());
            let settings = RenderSettings::parse(&bytes).unwrap();
            assert_eq!(settings.reconstruction_quality as u32, quality);
            assert!(enabled.transport_matches(RenderSettings {
                reconstruction_quality: settings.reconstruction_quality,
                ..enabled
            }));
        }
    }
    #[test]
    fn rejects_nonfinite_or_out_of_range_floats() {
        for offset in [16, 20, 24, 32, 36, 40, 72, 76] {
            for value in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, -1.0, 8192.0] {
                let mut invalid = golden();
                invalid[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
                assert!(
                    RenderSettings::parse(&invalid).is_err(),
                    "offset={offset}, value={value}"
                );
            }
        }
    }
}
