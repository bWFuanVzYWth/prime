// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/** Runtime controls for Prime's automatic exposure and final display-referred transform. */
public final class DisplaySettings {
    public static final int QUARTER_STEPS_PER_EV = 4;
    public static final int MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS =
            -8 * QUARTER_STEPS_PER_EV;
    public static final int MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS =
            8 * QUARTER_STEPS_PER_EV;
    public static final int DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS = 0;
    public static final int HUNDREDTH_STEPS_PER_UNIT = 100;
    public static final int MINIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS = 0;
    public static final int MAXIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS = HUNDREDTH_STEPS_PER_UNIT;
    public static final int DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS = 60;

    private DisplaySettings() {
    }

    public static float finalExposureMultiplier(int quarterSteps) {
        requireValidFinalExposure(quarterSteps);
        return Math.scalb(1.0F, Math.floorDiv(quarterSteps, QUARTER_STEPS_PER_EV))
                * (float) Math.pow(
                        2.0,
                        Math.floorMod(quarterSteps, QUARTER_STEPS_PER_EV)
                                / (double) QUARTER_STEPS_PER_EV);
    }

    public static float autoExposureCompensation(int steps) {
        requireRange(
                steps,
                MINIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                MAXIMUM_AUTO_EXPOSURE_COMPENSATION_STEPS,
                "Auto-exposure compensation must be between 0.0 and 1.0");
        return steps / (float) HUNDREDTH_STEPS_PER_UNIT;
    }

    private static void requireValidFinalExposure(int quarterSteps) {
        if (quarterSteps < MINIMUM_FINAL_EXPOSURE_QUARTER_STEPS
                || quarterSteps > MAXIMUM_FINAL_EXPOSURE_QUARTER_STEPS) {
            throw new IllegalArgumentException("Final exposure must be between -8 EV and +8 EV");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String message) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    public record Snapshot(
            int finalExposureQuarterSteps,
            int autoExposureCompensationSteps) {
        public Snapshot {
            DisplaySettings.finalExposureMultiplier(finalExposureQuarterSteps);
            DisplaySettings.autoExposureCompensation(autoExposureCompensationSteps);
        }

        public float finalExposureMultiplier() {
            return DisplaySettings.finalExposureMultiplier(this.finalExposureQuarterSteps);
        }

        public float autoExposureCompensation() {
            return DisplaySettings.autoExposureCompensation(
                    this.autoExposureCompensationSteps);
        }
    }
}
