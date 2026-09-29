//! Version-independent frame environment supplied to the GPU owner.
/// Old Prime observer and apparent solar ecliptic longitude. Season is deliberately
/// independent of Minecraft's day count: 0/90/180/270 are the equinoxes/solstices.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Astronomy {
    pub latitude_degrees: i32,
    pub solar_longitude_degrees: u32,
}
impl Default for Astronomy {
    fn default() -> Self {
        Self {
            latitude_degrees: 30,
            solar_longitude_degrees: 0,
        }
    }
}
impl Astronomy {
    pub fn validate(self) -> Result<(), String> {
        if !(-90..=90).contains(&self.latitude_degrees) || self.solar_longitude_degrees > 359 {
            return Err(
                "Astronomy requires latitude -90..90 and solar longitude 0..359 degrees".into(),
            );
        }
        Ok(())
    }
    /// Settings are validated at their input boundary; prepare only when they change.
    pub fn prepare(self) -> SolarOrbit {
        let latitude = f64::from(self.latitude_degrees).to_radians();
        let longitude = f64::from(self.solar_longitude_degrees).to_radians();
        let declination = (23.43928f64.to_radians().sin() * longitude.sin()).asin();
        SolarOrbit {
            latitude_sin: latitude.sin(),
            latitude_cos: latitude.cos(),
            declination_sin: declination.sin(),
            declination_cos: declination.cos(),
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct SolarOrbit {
    latitude_sin: f64,
    latitude_cos: f64,
    declination_sin: f64,
    declination_cos: f64,
}
impl Default for SolarOrbit {
    fn default() -> Self {
        Astronomy::default().prepare()
    }
}
impl SolarOrbit {
    /// Apparent local hour angle, radians: zero is noon, positive moves west.
    /// World east +X, up +Y, south +Z. Preserve original f64 operation order.
    pub fn direction(self, hour_angle: f32) -> [f32; 3] {
        let angle = f64::from(hour_angle);
        let sine = angle.sin();
        let cosine = angle.cos();
        [
            (-self.declination_cos * sine) as f32,
            (self.latitude_sin * self.declination_sin
                + self.latitude_cos * self.declination_cos * cosine) as f32,
            (-self.latitude_cos * self.declination_sin
                + self.latitude_sin * self.declination_cos * cosine) as f32,
        ]
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Environment {
    pub world_y: f64,
    pub sun_direction: [f32; 3],
}
impl Default for Environment {
    fn default() -> Self {
        // Prime's tuned default: latitude 30 degrees, March equinox, solar noon.
        Self {
            world_y: 0.0,
            sun_direction: [0.0, 0.8660254, 0.5],
        }
    }
}
impl Environment {
    pub fn validate(self) -> Result<Self, String> {
        let length: f32 = self.sun_direction.into_iter().map(|v| v * v).sum();
        if !self.world_y.is_finite() || !length.is_finite() || (length - 1.0).abs() > 1e-4 {
            return Err("Environment requires finite altitude and a unit sun direction".into());
        }
        Ok(self)
    }
    pub fn eye_radius_km(self) -> f32 {
        // Preserve the old Java double-to-float point and physical 300 m offset.
        let altitude = ((self.world_y + 64.0) * f64::from(0.001f32) + 0.3) as f32;
        (6360.0f32 + altitude).clamp(6360.0, 6480.0 - 0.001)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn orbit(latitude: i32, longitude: u32) -> SolarOrbit {
        Astronomy {
            latitude_degrees: latitude,
            solar_longitude_degrees: longitude,
        }
        .prepare()
    }
    #[test]
    fn legacy_astronomy_cardinals_solstices_and_polar_day() {
        assert_eq!(
            SolarOrbit::default().direction(0.),
            Environment::default().sun_direction
        );
        let east = orbit(30, 0).direction(-std::f32::consts::FRAC_PI_2);
        let west = orbit(30, 0).direction(std::f32::consts::FRAC_PI_2);
        assert!((east[0] - 1.).abs() < 1e-6 && east[1].abs() < 1e-6 && east[2].abs() < 1e-6);
        assert!((west[0] + 1.).abs() < 1e-6 && west[1].abs() < 1e-6 && west[2].abs() < 1e-6);
        for (season, altitude) in [(90, 83.43928f64), (270, 36.56072)] {
            let y = f64::from(orbit(30, season).direction(0.)[1]);
            assert!((y.asin().to_degrees() - altitude).abs() < 3e-5);
        }
        for season in [0, 90, 180, 270] {
            let north = orbit(47, season).direction(0.83);
            let south = orbit(-47, (season + 180) % 360).direction(0.83);
            for lane in 0..3 {
                assert!(
                    (north[lane] - if lane == 2 { -south[lane] } else { south[lane] }).abs() < 1e-6
                );
            }
        }
        for hour in 0..24 {
            let angle = hour as f32 * std::f32::consts::TAU / 24.;
            assert!(orbit(90, 90).direction(angle)[1] > 0.39);
            assert!(orbit(90, 270).direction(angle)[1] < -0.39);
        }
    }
    #[test]
    fn supported_observers_and_seasons_keep_unit_direction_and_declination() {
        for latitude in (-90..=90).step_by(15) {
            for longitude in (0..360).step_by(15) {
                let parameters = Astronomy {
                    latitude_degrees: latitude,
                    solar_longitude_degrees: longitude,
                };
                parameters.validate().unwrap();
                for hour in 0..96 {
                    let d = parameters
                        .prepare()
                        .direction(hour as f32 * std::f32::consts::TAU / 96.);
                    let norm: f32 = d.into_iter().map(|v| v * v).sum();
                    assert!((norm - 1.).abs() < 2e-7);
                    let phi = f64::from(latitude).to_radians();
                    let observed = f64::from(d[1]) * phi.sin() - f64::from(d[2]) * phi.cos();
                    let expected =
                        23.43928f64.to_radians().sin() * f64::from(longitude).to_radians().sin();
                    assert!((observed - expected).abs() < 1e-7);
                }
            }
        }
        for (latitude, longitude) in [(-91, 0), (91, 0), (30, 360), (30, u32::MAX)] {
            assert!(
                Astronomy {
                    latitude_degrees: latitude,
                    solar_longitude_degrees: longitude
                }
                .validate()
                .is_err()
            );
        }
    }
}
