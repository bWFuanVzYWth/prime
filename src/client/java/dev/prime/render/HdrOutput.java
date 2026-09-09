// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/**
 * Crosses Minecraft's option, surface-configuration and render boundaries.
 *
 * <p>The client thread owns mutations. Volatile whole-value publication lets display recording
 * observe one immutable capability without a lock or independently changing its fields.
 */
public final class HdrOutput {
    public static final int AUTOMATIC_REFERENCE_WHITE_NITS = 0;
    public static final int MAXIMUM_REFERENCE_WHITE_NITS = 10_000;
    public static final float MINIMUM_HEADROOM = 1.0F;
    // Covers a 1-nit manual reference white through the HDR absolute luminance ceiling.
    public static final float MAXIMUM_HEADROOM = 10_000.0F;
    // Windows advanced-color scRGB defines linear 1.0 as exactly 80 nits. This is a unit
    // conversion, not an artistic gain, and must cover the world and every composited overlay.
    public static final float SCRGB_NITS_PER_UNIT = 80.0F;

    private static volatile boolean requested;
    private static volatile int referenceWhiteNits = AUTOMATIC_REFERENCE_WHITE_NITS;
    private static volatile Capability capability = Capability.UNSUPPORTED;

    private HdrOutput() {
    }

    public static boolean requested() {
        return requested;
    }

    public static void setRequested(boolean value) {
        requested = value;
    }

    public static Capability capability() {
        return capability;
    }

    public static int referenceWhiteNits() {
        return referenceWhiteNits;
    }

    public static void setReferenceWhiteNits(int value) {
        referenceWhiteNits = validateReferenceWhiteNits(value);
    }

    public static int validateReferenceWhiteNits(int value) {
        if (value < AUTOMATIC_REFERENCE_WHITE_NITS
                || value > MAXIMUM_REFERENCE_WHITE_NITS) {
            throw new IllegalArgumentException(
                    "HDR reference white must be automatic or between 1 and 10000 nits");
        }
        return value;
    }

    public static void updateCapability(
            boolean supported,
            float maximumNits,
            float systemReferenceWhiteNits) {
        capability = supported
                ? Capability.supported(maximumNits, systemReferenceWhiteNits)
                : Capability.UNSUPPORTED;
    }

    public static float activeHeadroom() {
        return activeCalibration().headroom();
    }

    public static Calibration activeCalibration() {
        Capability current = capability;
        if (!requested || !current.supported()) {
            return Calibration.SDR;
        }
        int configuredWhite = referenceWhiteNits;
        float white = configuredWhite == AUTOMATIC_REFERENCE_WHITE_NITS
                ? Math.min(current.systemReferenceWhiteNits(), current.maximumNits())
                : Math.min((float) configuredWhite, current.maximumNits());
        float headroom = Math.clamp(
                current.maximumNits() / white,
                MINIMUM_HEADROOM,
                MAXIMUM_HEADROOM);
        return new Calibration(
                true,
                white,
                current.maximumNits(),
                headroom,
                white / SCRGB_NITS_PER_UNIT);
    }

    public record Capability(
            boolean supported,
            float maximumNits,
            float systemReferenceWhiteNits) {
        private static final Capability UNSUPPORTED =
                new Capability(false, 0.0F, 0.0F);

        public Capability {
            if (!Float.isFinite(maximumNits)
                    || !Float.isFinite(systemReferenceWhiteNits)
                    || supported && (maximumNits <= 0.0F
                            || systemReferenceWhiteNits <= 0.0F)
                    || !supported && (maximumNits != 0.0F
                            || systemReferenceWhiteNits != 0.0F)) {
                throw new IllegalArgumentException("Invalid HDR output capability");
            }
        }

        public static Capability supported(
                float maximumNits,
                float systemReferenceWhiteNits) {
            if (!Float.isFinite(maximumNits)
                    || maximumNits <= 0.0F
                    || !Float.isFinite(systemReferenceWhiteNits)
                    || systemReferenceWhiteNits <= 0.0F) {
                throw new IllegalArgumentException("Invalid HDR luminance measurement");
            }
            return new Capability(true, maximumNits, systemReferenceWhiteNits);
        }

        public int maximumSelectableReferenceWhiteNits() {
            return supported
                    ? Math.max(
                            1,
                            (int) Math.min(
                                    MAXIMUM_REFERENCE_WHITE_NITS,
                                    Math.floor(maximumNits)))
                    : AUTOMATIC_REFERENCE_WHITE_NITS;
        }
    }

    public record Calibration(
            boolean active,
            float referenceWhiteNits,
            float maximumNits,
            float headroom,
            float scRgbScale) {
        private static final Calibration SDR = new Calibration(
                false,
                SCRGB_NITS_PER_UNIT,
                SCRGB_NITS_PER_UNIT,
                MINIMUM_HEADROOM,
                1.0F);

        public Calibration {
            if (!Float.isFinite(referenceWhiteNits)
                    || !Float.isFinite(maximumNits)
                    || !Float.isFinite(headroom)
                    || !Float.isFinite(scRgbScale)
                    || referenceWhiteNits <= 0.0F
                    || maximumNits < referenceWhiteNits
                    || headroom < MINIMUM_HEADROOM
                    || headroom > MAXIMUM_HEADROOM
                    || scRgbScale <= 0.0F) {
                throw new IllegalArgumentException("Invalid HDR output calibration");
            }
        }
    }
}
