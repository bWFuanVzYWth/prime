// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import dev.prime.render.shader.ShaderAbi;

/**
 * Immutable observer latitude and seasonal position shared by the sun and celestial sphere.
 *
 * <p>The seasonal coordinate is apparent solar ecliptic longitude in integer degrees: zero is the
 * March equinox and 90, 180 and 270 are the June solstice, September equinox and December
 * solstice. It is deliberately independent of Minecraft's day count.
 */
public record AstronomySettings(
        int latitudeDegrees,
        int solarLongitudeDegrees) {
    public static final int MINIMUM_LATITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_MINIMUM_LATITUDE_DEGREES;
    public static final int MAXIMUM_LATITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_MAXIMUM_LATITUDE_DEGREES;
    public static final int DEFAULT_LATITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_DEFAULT_LATITUDE_DEGREES;
    public static final int MINIMUM_SOLAR_LONGITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_MINIMUM_SOLAR_LONGITUDE_DEGREES;
    public static final int MAXIMUM_SOLAR_LONGITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_MAXIMUM_SOLAR_LONGITUDE_DEGREES;
    public static final int DEFAULT_SOLAR_LONGITUDE_DEGREES =
            ShaderAbi.ASTRONOMY_DEFAULT_SOLAR_LONGITUDE_DEGREES;

    public AstronomySettings {
        if (latitudeDegrees < MINIMUM_LATITUDE_DEGREES
                || latitudeDegrees > MAXIMUM_LATITUDE_DEGREES) {
            throw new IllegalArgumentException(
                    "Observer latitude must be between -90 and 90 degrees");
        }
        if (solarLongitudeDegrees < MINIMUM_SOLAR_LONGITUDE_DEGREES
                || solarLongitudeDegrees > MAXIMUM_SOLAR_LONGITUDE_DEGREES) {
            throw new IllegalArgumentException(
                    "Solar longitude must be between 0 and 359 degrees");
        }
    }

    public static AstronomySettings defaults() {
        return new AstronomySettings(
                DEFAULT_LATITUDE_DEGREES,
                DEFAULT_SOLAR_LONGITUDE_DEGREES);
    }

    public AstronomySettings withLatitudeDegrees(int value) {
        return value == this.latitudeDegrees
                ? this
                : new AstronomySettings(value, this.solarLongitudeDegrees);
    }

    public AstronomySettings withSolarLongitudeDegrees(int value) {
        return value == this.solarLongitudeDegrees
                ? this
                : new AstronomySettings(this.latitudeDegrees, value);
    }
}
