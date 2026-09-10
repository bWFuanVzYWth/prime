// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/** Derived linear output parameters for Prime's SDR/HDR RGB Reinhard curve. */
public final class RgbReinhardOutput {
    public static final double COMPRESSION_START = 0.18;
    public static final double HIGHLIGHT_REACH_EV = 8.0;

    private RgbReinhardOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("RGB Reinhard headroom must be finite");
        }
        double headroom = Math.clamp((double) requestedHeadroom,
                (double) HdrOutput.MINIMUM_HEADROOM, (double) HdrOutput.MAXIMUM_HEADROOM);
        // drt src/gpu.rs curve_for_headroom, with unit slope and the requested +8 EV reach.
        // Derive in double before GPU submission; HDR preserves the SDR join and tangent.
        double reachInput = 0.18 * Math.pow(2.0, HIGHLIGHT_REACH_EV) * headroom;
        double tangentDistance = reachInput - COMPRESSION_START;
        double outputDistance = headroom - COMPRESSION_START;
        double extent = outputDistance * tangentDistance / (tangentDistance - outputDistance);
        return new Parameters((float) headroom, (float) (COMPRESSION_START + extent));
    }

    public record Parameters(float outputPeak, float curvePeak) {
        public Parameters {
            if (!Float.isFinite(outputPeak) || !Float.isFinite(curvePeak)
                    || outputPeak < HdrOutput.MINIMUM_HEADROOM || outputPeak > HdrOutput.MAXIMUM_HEADROOM
                    || curvePeak <= outputPeak) {
                throw new IllegalArgumentException("Invalid derived RGB Reinhard parameters");
            }
        }
    }
}
