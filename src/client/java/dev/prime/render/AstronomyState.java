// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/** One coherent observer, season and sun-direction snapshot captured at a frame boundary. */
public record AstronomyState(
        AstronomySettings settings,
        SunDirection sunDirection) {
    public AstronomyState {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sunDirection, "sunDirection");
        double latitude = Math.toRadians(settings.latitudeDegrees());
        double solarLongitude =
                Math.toRadians(settings.solarLongitudeDegrees());
        double axialTilt =
                Math.toRadians(ShaderAbi.ASTRONOMY_AXIAL_TILT_DEGREES);
        double expectedSineDeclination =
                Math.sin(axialTilt) * Math.sin(solarLongitude);
        double actualSineDeclination =
                sunDirection.y() * Math.sin(latitude)
                        - sunDirection.z() * Math.cos(latitude);
        if (Math.abs(
                        actualSineDeclination
                                - expectedSineDeclination)
                > 1.0e-5) {
            throw new IllegalArgumentException(
                    "Sun direction is inconsistent with its observer and season");
        }
    }

    /**
     * Computes the apparent sun in Prime's terrestrial frame.
     *
     * <p>Minecraft's sun angle is local apparent solar hour angle: zero is noon, positive angles
     * move toward the western horizon. World east is +X, up is +Y and south is +Z.
     */
    public static AstronomyState atSolarHourAngle(
            float solarHourAngleRadians,
            AstronomySettings settings) {
        if (!Float.isFinite(solarHourAngleRadians)) {
            throw new IllegalArgumentException("Solar hour angle must be finite");
        }
        Objects.requireNonNull(settings, "settings");
        double latitude = Math.toRadians(settings.latitudeDegrees());
        double solarLongitude =
                Math.toRadians(settings.solarLongitudeDegrees());
        double axialTilt =
                Math.toRadians(ShaderAbi.ASTRONOMY_AXIAL_TILT_DEGREES);
        double declination = Math.asin(
                Math.sin(axialTilt) * Math.sin(solarLongitude));
        double sineLatitude = Math.sin(latitude);
        double cosineLatitude = Math.cos(latitude);
        double sineDeclination = Math.sin(declination);
        double cosineDeclination = Math.cos(declination);
        double sineHourAngle = Math.sin(solarHourAngleRadians);
        double cosineHourAngle = Math.cos(solarHourAngleRadians);
        SunDirection sunDirection = new SunDirection(
                (float) (-cosineDeclination * sineHourAngle),
                (float) (sineLatitude * sineDeclination
                        + cosineLatitude * cosineDeclination * cosineHourAngle),
                (float) (-cosineLatitude * sineDeclination
                        + sineLatitude * cosineDeclination * cosineHourAngle));
        return new AstronomyState(settings, sunDirection);
    }

    public int latitudeDegrees() {
        return this.settings.latitudeDegrees();
    }

    public int solarLongitudeDegrees() {
        return this.settings.solarLongitudeDegrees();
    }
}
