package dev.prime.render;

/** Derived output parameters for Prime's unified SDR/HDR Reinhard AgX curve. */
public final class ReinhardAgxOutput {
    public static final double MIDDLE_GRAY = 0.18;
    public static final double COMPRESSION_START = 0.18;
    public static final double HIGHLIGHT_REACH_EV = 8.0;
    public static final double SHOULDER_POWER = 5.0;

    private ReinhardAgxOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("Reinhard AgX headroom must be finite");
        }
        double outputPeak = Math.clamp(
                (double) requestedHeadroom,
                (double) HdrOutput.MINIMUM_HEADROOM,
                (double) HdrOutput.MAXIMUM_HEADROOM);
        // The bench's default input scale gives a unit linear slope.
        double reachInput = MIDDLE_GRAY * Math.pow(2.0, HIGHLIGHT_REACH_EV) * outputPeak;
        double outputDistance = outputPeak - COMPRESSION_START;
        double logReach = Math.log1p((reachInput - COMPRESSION_START) / outputDistance);
        double shoulderCoefficient = 1.0 - Math.pow(logReach, -SHOULDER_POWER);
        return new Parameters(
                (float) outputPeak,
                (float) shoulderCoefficient);
    }

    public record Parameters(float outputPeak, float shoulderCoefficient) {
        public Parameters {
            if (!Float.isFinite(outputPeak)
                    || !Float.isFinite(shoulderCoefficient)
                    || outputPeak < HdrOutput.MINIMUM_HEADROOM
                    || outputPeak > HdrOutput.MAXIMUM_HEADROOM
                    || shoulderCoefficient <= 0.0F
                    || shoulderCoefficient >= 1.0F) {
                throw new IllegalArgumentException("Invalid derived Reinhard AgX parameters");
            }
        }
    }
}
