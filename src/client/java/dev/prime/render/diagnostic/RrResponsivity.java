// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

/** Numeric contract for DLSS RR's session-only per-pixel responsivity bias. */
public final class RrResponsivity {
    public static final float MINIMUM = -1.0F;
    public static final float MAXIMUM = 1.0F;
    public static final float DEFAULT = -0.25F;

    private RrResponsivity() {}

    public static float requireValid(float value) {
        if (!Float.isFinite(value) || value < MINIMUM || value > MAXIMUM) {
            throw new IllegalArgumentException("DLSS RR responsivity must be within [-1, 1]");
        }
        return value;
    }

    public static float fromSlider(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("DLSS RR responsivity slider must be within [0, 1]");
        }
        return requireValid((float) (MINIMUM + value * (MAXIMUM - MINIMUM)));
    }

    public static double toSlider(float value) {
        return (requireValid(value) - MINIMUM) / (MAXIMUM - MINIMUM);
    }
}
