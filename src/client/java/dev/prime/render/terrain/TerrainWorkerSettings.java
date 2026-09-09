// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** User-facing admission limit for Prime jobs submitted to Minecraft's shared worker pool. */
public final class TerrainWorkerSettings {
    public static final int MINIMUM_PERCENTAGE = 1;
    public static final int MAXIMUM_PERCENTAGE = 100;
    public static final int DEFAULT_PERCENTAGE = 50;

    private TerrainWorkerSettings() {}

    public static int validatePercentage(int percentage) {
        if (percentage < MINIMUM_PERCENTAGE || percentage > MAXIMUM_PERCENTAGE) {
            throw new IllegalArgumentException(
                    "Terrain worker percentage must be between "
                            + MINIMUM_PERCENTAGE + " and " + MAXIMUM_PERCENTAGE);
        }
        return percentage;
    }

    public static int workerLimit(int maximumThreads, int percentage) {
        if (maximumThreads <= 0) {
            throw new IllegalArgumentException("Maximum terrain worker count must be positive");
        }
        validatePercentage(percentage);
        return Math.max(1, Math.toIntExact((long) maximumThreads * percentage / 100L));
    }

    /** Keeps one admitted job available for resident terrain invalidations. */
    public static int admissionLimit(int maximumInFlight, boolean interactivePending) {
        if (maximumInFlight <= 0) {
            throw new IllegalArgumentException("Maximum in-flight terrain work must be positive");
        }
        return interactivePending ? maximumInFlight : Math.max(1, maximumInFlight - 1);
    }
}
