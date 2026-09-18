// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/** Manual, immutable atmosphere controls; independent of weather and time. */
public record AtmosphereSettings(int aerosolDensitySteps, int altitudeOffsetMeters) {
    public static final int STEPS_PER_UNIT = 100;
    public static final int MINIMUM_STEPS = 0;
    public static final int MAXIMUM_STEPS = 16 * STEPS_PER_UNIT;
    public static final int DEFAULT_STEPS = 100;
    public static final int MINIMUM_ALTITUDE_OFFSET_METERS = 0;
    public static final int MAXIMUM_ALTITUDE_OFFSET_METERS = 10_000;
    public static final int DEFAULT_ALTITUDE_OFFSET_METERS = 300;

    public AtmosphereSettings {
        densityScale(aerosolDensitySteps);
        validateAltitudeOffsetMeters(altitudeOffsetMeters);
    }

    public static AtmosphereSettings defaults() {
        return new AtmosphereSettings(DEFAULT_STEPS, DEFAULT_ALTITUDE_OFFSET_METERS);
    }

    public static int validateAltitudeOffsetMeters(int meters) {
        if (meters < MINIMUM_ALTITUDE_OFFSET_METERS || meters > MAXIMUM_ALTITUDE_OFFSET_METERS) {
            throw new IllegalArgumentException("Atmosphere altitude offset must be between 0 and 10000 meters");
        }
        return meters;
    }

    public static float densityScale(int steps) {
        if (steps < MINIMUM_STEPS || steps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Aerosol density scale must be between 0 and 16");
        }
        return steps / (float) STEPS_PER_UNIT;
    }

    public float aerosolDensityScale() {
        return densityScale(this.aerosolDensitySteps);
    }
}
