package dev.prime.render;

/** Shared transport-count domain plus the narrower fixed no-roulette realtime prefix. */
public final class BounceSettings {
    public static final int MINIMUM_COUNT = 1;
    public static final int MAXIMUM_COUNT = 64;
    public static final int DEFAULT_COUNT = 16;
    public static final int MAXIMUM_FIXED_COUNT = 8;
    public static final int DEFAULT_FIXED_COUNT = 2;

    private BounceSettings() {}

    public static int validateCount(int count) {
        return validate(count, MAXIMUM_COUNT);
    }

    public static int validateFixedCount(int count) {
        return validate(count, MAXIMUM_FIXED_COUNT);
    }

    private static int validate(int count, int maximum) {
        if (count < MINIMUM_COUNT || count > maximum) {
            throw new IllegalArgumentException(
                    "Bounce count must be between 1 and " + maximum);
        }
        return count;
    }
}
