//! MC supplies the apparent solar hour angle; Rust owns Prime's tuned astronomy.
use prime_scene::environment::Environment;

pub fn environment(
    world_y: f64,
    solar_hour_angle: f32,
    orbit: prime_scene::environment::SolarOrbit,
) -> Environment {
    Environment {
        world_y,
        sun_direction: orbit.direction(solar_hour_angle),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tuned_sun_matches_noon_horizon_and_night() {
        for angle in [0., std::f32::consts::FRAC_PI_2, std::f32::consts::PI, -1.2] {
            let e = environment(64., angle, Default::default())
                .validate()
                .unwrap();
            assert!(
                (e.sun_direction[1] * 0.5 - e.sun_direction[2] * 30f32.to_radians().cos()).abs()
                    < 1e-6
            );
        }
        assert_eq!(
            environment(0., 0., Default::default()).sun_direction,
            Environment::default().sun_direction
        );
        assert!(environment(0., std::f32::consts::PI, Default::default()).sun_direction[1] < 0.);
    }
}
