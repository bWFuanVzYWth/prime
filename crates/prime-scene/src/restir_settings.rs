//! Point-reservoir Hybrid controls. Distance uses Falcor's UI units; the GPU divides by 100.
//! Counts select real paired-neighbor assets and work; debug views never change transport.

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum RestirRrMode {
    None = 0,
    Uniform = 1,
    #[default]
    Stagnancy = 2,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RestirSettings {
    pub history_length: u32,
    pub spatial_reuse: bool,
    pub spatial_iterations: u32,
    pub spatial_neighbors: u32,
    pub pairing_radius: u32,
    pub stochastic_reprojection: bool,
    pub duplicate_map: bool,
    pub duplication_power: f32,
    pub decoupled_shading: bool,
    pub initial_samples: u32,
    pub distance_threshold: f32,
    pub distance_sigma: f32,
    pub roughness_threshold: f32,
    pub roughness_sigma: f32,
    pub normal_threshold: f32,
    pub depth_threshold: f32,
    pub debug_view: u32,
    pub rr_decorrelation: bool,
    pub rr_mode: RestirRrMode,
    pub rr_factor: f32,
    pub rr_stagnancy_exponent: f32,
    pub rr_ema: f32,
    pub rr_firefly_strength: f32,
    pub rr_multiply_bound: f32,
    pub rr_bias_reduction: bool,
    pub rr_firefly: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_enable_accepted_decorrelation_and_preserve_point_hybrid_parameters() {
        let s = RestirSettings::default();
        assert!(s.validate().is_ok());
        assert_eq!(
            (s.history_length, s.spatial_neighbors, s.spatial_iterations),
            (20, 3, 1)
        );
        assert_eq!((s.initial_samples, s.pairing_radius), (1, 30));
        assert!(s.spatial_reuse);
        assert!(!s.stochastic_reprojection && !s.decoupled_shading);
        assert!(s.duplicate_map);
        assert_eq!(s.distance_threshold / 100.0, 0.0002);
        assert_eq!(
            (s.distance_sigma, s.roughness_threshold, s.roughness_sigma),
            (0.2, 0.2, 0.0)
        );
        assert!(s.rr_decorrelation);
        assert_eq!(s.rr_mode, RestirRrMode::Stagnancy);
        assert_eq!(
            (s.rr_factor, s.rr_stagnancy_exponent, s.rr_ema),
            (0.4, 0.5, 0.2)
        );
        assert_eq!((s.rr_firefly_strength, s.rr_multiply_bound), (0.7, 15.0));
        assert!(s.rr_bias_reduction && s.rr_firefly);
    }

    #[test]
    fn boundary_validation_rejects_unknown_booleans_and_non_finite_values() {
        let mut words = [
            20,
            1,
            1,
            3,
            30,
            0,
            0,
            0.1_f32.to_bits(),
            0,
            1,
            0.02_f32.to_bits(),
            0.2_f32.to_bits(),
            0.2_f32.to_bits(),
            0,
            0.5_f32.to_bits(),
            0.1_f32.to_bits(),
            0,
            0,
            2,
            0.4_f32.to_bits(),
            0.5_f32.to_bits(),
            0.2_f32.to_bits(),
            0.7_f32.to_bits(),
            15.0_f32.to_bits(),
            1,
            1,
        ];
        assert!(
            RestirSettings::from_words(words)
                .unwrap()
                .validate()
                .is_ok()
        );
        for index in [1, 5, 6, 8, 17, 24, 25] {
            let original = words[index];
            words[index] = 2;
            assert!(RestirSettings::from_words(words).is_err());
            words[index] = original;
        }
        words[18] = 3;
        assert!(RestirSettings::from_words(words).is_err());
        words[18] = 2;
        for index in [7, 10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 23] {
            let original = words[index];
            for value in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
                words[index] = value.to_bits();
                assert!(
                    RestirSettings::from_words(words)
                        .unwrap()
                        .validate()
                        .is_err()
                );
            }
            words[index] = original;
        }
        for (index, invalid) in [
            (0, 101),
            (2, 9),
            (3, 0),
            (3, 6),
            (4, 31),
            (9, 0),
            (9, 17),
            (16, 3),
        ] {
            let original = words[index];
            words[index] = invalid;
            assert!(
                RestirSettings::from_words(words)
                    .unwrap()
                    .validate()
                    .is_err()
            );
            words[index] = original;
        }
        for (index, low, high) in [
            (0, 0, 100),
            (2, 0, 8),
            (3, 1, 5),
            (4, 5, 50),
            (9, 1, 16),
            (16, 0, 2),
        ] {
            let original = words[index];
            for value in [low, high] {
                words[index] = value;
                assert!(
                    RestirSettings::from_words(words)
                        .unwrap()
                        .validate()
                        .is_ok()
                );
            }
            words[index] = original;
        }
        for (index, low, high) in [
            (7, 0.0_f32, 10.0),
            (10, 0.0, 10000.0),
            (11, 0.0, 1.0),
            (12, 0.0, 1.0),
            (13, 0.0, 1.0),
            (14, -1.0, 1.0),
            (15, 0.0, 1.0),
            (19, 0.0, 1.0),
            (20, 0.0, 10.0),
            (21, 0.0, 1.0),
            (22, 0.0, 1.0),
            (23, 1.0, 100.0),
        ] {
            let original = words[index];
            for value in [low, high] {
                words[index] = value.to_bits();
                assert!(
                    RestirSettings::from_words(words)
                        .unwrap()
                        .validate()
                        .is_ok()
                );
            }
            for value in [low - 0.125, high + 0.125] {
                words[index] = value.to_bits();
                assert!(
                    RestirSettings::from_words(words)
                        .unwrap()
                        .validate()
                        .is_err()
                );
            }
            words[index] = original;
        }
    }
}

impl Default for RestirSettings {
    fn default() -> Self {
        Self {
            history_length: 20,
            spatial_reuse: true,
            spatial_iterations: 1,
            spatial_neighbors: 3,
            pairing_radius: 30,
            stochastic_reprojection: false,
            // RA-015: promote the user-accepted M reduction without tuning its exponent.
            duplicate_map: true,
            duplication_power: 0.1,
            decoupled_shading: false,
            initial_samples: 1,
            distance_threshold: 0.02,
            distance_sigma: 0.2,
            roughness_threshold: 0.2,
            roughness_sigma: 0.0,
            normal_threshold: 0.5,
            depth_threshold: 0.1,
            debug_view: 0,
            // RA-016: default-enable the user-accepted output mixture, preserving its curve.
            rr_decorrelation: true,
            rr_mode: RestirRrMode::Stagnancy,
            rr_factor: 0.4,
            rr_stagnancy_exponent: 0.5,
            rr_ema: 0.2,
            rr_firefly_strength: 0.7,
            rr_multiply_bound: 15.0,
            rr_bias_reduction: true,
            rr_firefly: true,
        }
    }
}

impl RestirSettings {
    pub const WIRE_WORDS: usize = 26;

    pub fn validate(self) -> Result<(), String> {
        // Falcor GUI ranges, with Prime's existing symmetric normal/depth gates exposed.
        if self.history_length > 100
            || self.spatial_iterations > 8
            || !(1..=5).contains(&self.spatial_neighbors)
            || !(5..=50).contains(&self.pairing_radius)
            || !self.pairing_radius.is_multiple_of(5)
            || !(1..=16).contains(&self.initial_samples)
            || !(0.0..=10.0).contains(&self.duplication_power)
            || !(0.0..=10000.0).contains(&self.distance_threshold)
            || !(0.0..=1.0).contains(&self.distance_sigma)
            || !(0.0..=1.0).contains(&self.roughness_threshold)
            || !(0.0..=1.0).contains(&self.roughness_sigma)
            || !(-1.0..=1.0).contains(&self.normal_threshold)
            || !(0.0..=1.0).contains(&self.depth_threshold)
            || self.debug_view > 2
            || !(0.0..=1.0).contains(&self.rr_factor)
            || !(0.0..=10.0).contains(&self.rr_stagnancy_exponent)
            || !(0.0..=1.0).contains(&self.rr_ema)
            || !(0.0..=1.0).contains(&self.rr_firefly_strength)
            || !(1.0..=100.0).contains(&self.rr_multiply_bound)
        {
            return Err("ReSTIR settings out of range or non-finite".into());
        }
        Ok(())
    }

    pub(crate) fn from_words(words: [u32; Self::WIRE_WORDS]) -> Result<Self, String> {
        let boolean = |index: usize| match words[index] {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("Unknown ReSTIR boolean setting".to_string()),
        };
        Ok(Self {
            history_length: words[0],
            spatial_reuse: boolean(1)?,
            spatial_iterations: words[2],
            spatial_neighbors: words[3],
            pairing_radius: words[4],
            stochastic_reprojection: boolean(5)?,
            duplicate_map: boolean(6)?,
            duplication_power: f32::from_bits(words[7]),
            decoupled_shading: boolean(8)?,
            initial_samples: words[9],
            distance_threshold: f32::from_bits(words[10]),
            distance_sigma: f32::from_bits(words[11]),
            roughness_threshold: f32::from_bits(words[12]),
            roughness_sigma: f32::from_bits(words[13]),
            normal_threshold: f32::from_bits(words[14]),
            depth_threshold: f32::from_bits(words[15]),
            debug_view: words[16],
            rr_decorrelation: boolean(17)?,
            rr_mode: match words[18] {
                0 => RestirRrMode::None,
                1 => RestirRrMode::Uniform,
                2 => RestirRrMode::Stagnancy,
                _ => return Err("Unknown ReSTIR RR decorrelation mode".into()),
            },
            rr_factor: f32::from_bits(words[19]),
            rr_stagnancy_exponent: f32::from_bits(words[20]),
            rr_ema: f32::from_bits(words[21]),
            rr_firefly_strength: f32::from_bits(words[22]),
            rr_multiply_bound: f32::from_bits(words[23]),
            rr_bias_reduction: boolean(24)?,
            rr_firefly: boolean(25)?,
        })
    }

    pub(crate) fn from_abi(s: &prime_abi::PrimeSettings) -> Result<Self, String> {
        Self::from_words([
            s.restir_history_length,
            s.restir_spatial_reuse,
            s.restir_spatial_iterations,
            s.restir_spatial_neighbors,
            s.restir_pairing_radius,
            s.restir_stochastic_reprojection,
            s.restir_duplicate_map,
            s.restir_duplication_power.to_bits(),
            s.restir_decoupled_shading,
            s.restir_initial_samples,
            s.restir_distance_threshold.to_bits(),
            s.restir_distance_sigma.to_bits(),
            s.restir_roughness_threshold.to_bits(),
            s.restir_roughness_sigma.to_bits(),
            s.restir_normal_threshold.to_bits(),
            s.restir_depth_threshold.to_bits(),
            s.restir_debug_view,
            s.restir_rr_decorrelation,
            s.restir_rr_mode,
            s.restir_rr_factor.to_bits(),
            s.restir_rr_stagnancy_exponent.to_bits(),
            s.restir_rr_ema.to_bits(),
            s.restir_rr_firefly_strength.to_bits(),
            s.restir_rr_multiply_bound.to_bits(),
            s.restir_rr_bias_reduction,
            s.restir_rr_firefly,
        ])
    }
}
