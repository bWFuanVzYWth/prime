//! Current primeDRT display policy: explicit transforms and artistic adjustments.
//! This is replaceable policy, not a stable math-library interface. Controls never
//! invalidate scene-linear accumulation.

/// Actual user-adjustable controls from legacy Prime. This is manual exposure;
/// the final display shader applies the device-local automatic multiplier once.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PrimeDrtSettings {
    pub exposure_multiplier: f32,
    pub hue_compensation: f32,
    pub saturation_compensation: f32,
}

impl Default for PrimeDrtSettings {
    fn default() -> Self {
        Self {
            exposure_multiplier: 1.0,
            hue_compensation: 0.75,
            saturation_compensation: 0.08,
        }
    }
}

impl PrimeDrtSettings {
    /// Prepare primeDRT's varying parameters once, outside the pixel loop. Headroom
    /// is a surface calibration input, not an independent artistic curve knob.
    /// SDR uses one; an active calibrated HDR surface passes peak/reference-white.
    pub fn prepare(self, headroom: f32) -> Result<PrimeDrtParameters, String> {
        if !self.exposure_multiplier.is_finite()
            || self.exposure_multiplier <= 0.0
            || !self.hue_compensation.is_finite()
            || !(0.0..=1.0).contains(&self.hue_compensation)
            || !self.saturation_compensation.is_finite()
            || !(0.0..=0.5).contains(&self.saturation_compensation)
            || !headroom.is_finite()
            || !(1.0..=10000.0).contains(&headroom)
        {
            return Err(
                "Invalid primeDRT exposure, hue/saturation compensation or output headroom".into(),
            );
        }
        // Legacy RgbReinhardOutput: unit tangent at 0.18, reaching headroom at +8 EV.
        let headroom64 = f64::from(headroom);
        let tangent_distance = 0.18 * 256.0 * headroom64 - 0.18;
        let output_distance = headroom64 - 0.18;
        let curve_peak = (0.18
            + output_distance * tangent_distance / (tangent_distance - output_distance))
            as f32;
        let encoded_peak = if headroom == 1.0 {
            1.0
        } else {
            (1.055 * headroom64.powf(1.0 / 2.4) - 0.055) as f32
        };
        Ok(PrimeDrtParameters {
            values: [
                self.exposure_multiplier,
                curve_peak,
                encoded_peak,
                self.hue_compensation,
                self.saturation_compensation,
                0.0,
                0.0,
                0.0,
            ],
        })
    }
}

#[derive(Clone, Copy)]
pub struct PrimeDrtParameters {
    pub(crate) values: [f32; 8],
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn curve_reaches_the_calibrated_peak_at_eight_ev_and_preserves_join() {
        for peak in [1.0, 2.0, 16.0, 10000.0] {
            let parameters = PrimeDrtSettings::default().prepare(peak).unwrap();
            let extent = f64::from(parameters.values[1]) - 0.18;
            let input = 0.18 * 256.0 * f64::from(peak);
            let curve = |x: f64| {
                if x <= 0.18 {
                    x
                } else {
                    0.18 + extent / (1.0 + extent / (x - 0.18))
                }
            };
            assert!((curve(input) - f64::from(peak)).abs() <= 1e-6 * f64::from(peak));
            assert!((curve(0.18 + 1e-6) - curve(0.18 - 1e-6) - 2e-6).abs() < 1e-10);
        }
        for headroom in [0.0, 10001.0, f32::NAN, f32::INFINITY] {
            assert!(PrimeDrtSettings::default().prepare(headroom).is_err());
        }
        assert!(
            PrimeDrtSettings {
                exposure_multiplier: f32::NAN,
                ..Default::default()
            }
            .prepare(1.0)
            .is_err()
        );
        assert!(
            PrimeDrtSettings {
                hue_compensation: 1.01,
                ..Default::default()
            }
            .prepare(1.0)
            .is_err()
        );
        assert!(
            PrimeDrtSettings {
                saturation_compensation: -0.01,
                ..Default::default()
            }
            .prepare(1.0)
            .is_err()
        );
    }
}
