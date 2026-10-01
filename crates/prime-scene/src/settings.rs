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

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RenderSettings {
    pub astronomy: crate::environment::Astronomy,
    pub mode: RenderMode,
    pub bounces: u32,
    pub offline_samples: u32,
    pub exposure: f32,
    pub hue: f32,
    pub saturation: f32,
    pub view: DiagnosticView,
    pub sun: f32,
    pub sky: f32,
    pub depth_range: f32,
    pub seed: u32,
}
impl Default for RenderSettings {
    fn default() -> Self {
        Self {
            astronomy: Default::default(),
            mode: RenderMode::Realtime,
            bounces: 12,
            offline_samples: 1,
            exposure: 1.0,
            hue: 0.75,
            saturation: 0.08,
            view: DiagnosticView::Output,
            sun: 1.0,
            sky: 1.0,
            depth_range: 128.0,
            seed: 0x1357_2468,
        }
    }
}
impl RenderSettings {
    pub const VERSION: u32 = 2;
    pub const BYTES: usize = 56;
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() != Self::BYTES {
            return Err("Settings require exactly 56 bytes".into());
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
        };
        result.validate()?;
        Ok(result)
    }
    pub fn validate(self) -> Result<(), String> {
        self.astronomy.validate()?;
        if !(1..=64).contains(&self.bounces)
            || !(1..=64).contains(&self.offline_samples)
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
            2_u32,
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
            (0, 3),
            (48, 91),
            (48, (-91i32) as u32),
            (52, 360),
            (4, 2),
            (8, 0),
            (8, 65),
            (12, 65),
            (28, 4),
        ] {
            let mut invalid = bytes.clone();
            invalid[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            assert!(RenderSettings::parse(&invalid).is_err());
        }
        assert!(RenderSettings::parse(&bytes[..47]).is_err());
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
