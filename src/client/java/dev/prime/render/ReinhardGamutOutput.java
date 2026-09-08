package dev.prime.render;

/** Exact derived output parameters for Prime's unified SDR/HDR Reinhard-Gamut curve. */
public final class ReinhardGamutOutput {
    public static final double MIDDLE_GRAY = 0.18;
    public static final double COMPRESSION_START = 0.5;
    public static final double HIGHLIGHT_REACH_EV = 10.0;

    private ReinhardGamutOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("Reinhard-Gamut headroom must be finite");
        }
        double outputPeak = Math.clamp(
                (double) requestedHeadroom,
                (double) HdrOutput.MINIMUM_HEADROOM,
                (double) HdrOutput.MAXIMUM_HEADROOM);
        // The bench's default input scale gives a unit linear slope.
        double reachInput = MIDDLE_GRAY * Math.pow(2.0, HIGHLIGHT_REACH_EV) * outputPeak;
        double tangentDistance = reachInput - COMPRESSION_START;
        double outputDistance = outputPeak - COMPRESSION_START;
        double shoulderExtent = outputDistance * tangentDistance
                / (tangentDistance - outputDistance);
        return new Parameters(
                (float) outputPeak,
                (float) (COMPRESSION_START + shoulderExtent));
    }

    public record Parameters(float outputPeak, float curvePeak) {
        public Parameters {
            if (!Float.isFinite(outputPeak)
                    || !Float.isFinite(curvePeak)
                    || outputPeak < HdrOutput.MINIMUM_HEADROOM
                    || outputPeak > HdrOutput.MAXIMUM_HEADROOM
                    || curvePeak <= outputPeak) {
                throw new IllegalArgumentException("Invalid derived Reinhard-Gamut parameters");
            }
        }
    }
}
