// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/**
 * Runtime lighting controls expressed as photographic exposure-value offsets.
 *
 * <p>The zero-EV defaults preserve Prime's calibrated sun, embedded night sky, and Minecraft
 * emitter models. A quarter-EV step is converted only at the rendering boundary as
 * {@code 2^(quarterSteps / 4)}; the stored values never masquerade as linear light.
 */
public final class LightingSettings {
    public static final int QUARTER_STEPS_PER_EV = 4;
    public static final int MINIMUM_QUARTER_STEPS = -32;
    public static final int MAXIMUM_QUARTER_STEPS = 32;
    public static final int DEFAULT_SUN_QUARTER_STEPS = 0;
    public static final int DEFAULT_STAR_QUARTER_STEPS = 0;
    public static final int DEFAULT_BLOCK_LIGHT_QUARTER_STEPS = 0;

    private LightingSettings() {
    }

    public static float linearMultiplier(int quarterSteps) {
        requireValid(quarterSteps);
        return (float) Math.pow(2.0, quarterSteps / (double) QUARTER_STEPS_PER_EV);
    }

    public static float exposureValue(int quarterSteps) {
        requireValid(quarterSteps);
        return quarterSteps / (float) QUARTER_STEPS_PER_EV;
    }

    private static void requireValid(int quarterSteps) {
        if (quarterSteps < MINIMUM_QUARTER_STEPS
                || quarterSteps > MAXIMUM_QUARTER_STEPS) {
            throw new IllegalArgumentException(
                    "Lighting EV must be between "
                            + exposureValueUnchecked(MINIMUM_QUARTER_STEPS)
                            + " and "
                            + exposureValueUnchecked(MAXIMUM_QUARTER_STEPS));
        }
    }

    private static float exposureValueUnchecked(int quarterSteps) {
        return quarterSteps / (float) QUARTER_STEPS_PER_EV;
    }

    public record Snapshot(
            int sunQuarterSteps,
            int starQuarterSteps,
            int blockLightQuarterSteps,
            TransparentNeeMode transparentNeeMode) {
        public Snapshot {
            requireValid(sunQuarterSteps);
            requireValid(starQuarterSteps);
            requireValid(blockLightQuarterSteps);
            java.util.Objects.requireNonNull(transparentNeeMode, "transparentNeeMode");
        }

        public Snapshot(
                int sunQuarterSteps,
                int starQuarterSteps,
                int blockLightQuarterSteps) {
            this(
                    sunQuarterSteps,
                    starQuarterSteps,
                    blockLightQuarterSteps,
                    TransparentNeeMode.DEFAULT);
        }

        public float sunMultiplier() {
            return linearMultiplier(this.sunQuarterSteps);
        }

        public float starMultiplier() {
            return linearMultiplier(this.starQuarterSteps);
        }

        public float blockLightMultiplier() {
            return linearMultiplier(this.blockLightQuarterSteps);
        }
    }
}
