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
            saturation: 0.08,
            view: DiagnosticView::Output,
            sun: 1.0,
            sky: 1.0,
            depth_range: 128.0,
            seed: 0x1357_2468,
            opacity_micromap: true,
            ray_reconstruction: true,
            reconstruction_quality: ReconstructionQuality::Performance,
        }
    }
}
impl RenderSettings {
    pub const VERSION: u32 = 5;
    pub const BYTES: usize = 72;
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() != Self::BYTES {
            return Err("Settings require exactly 72 bytes".into());
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
            && self.seed == other.seed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn golden() -> Vec<u8> {
        [
            5_u32,
            1,
            12,
            1,
            4_f32.to_bits(),
            0.75_f32.to_bits(),
            0.08_f32.to_bits(),
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
            (0, 6),
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
        for offset in [16, 20, 24, 32, 36, 40] {
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
