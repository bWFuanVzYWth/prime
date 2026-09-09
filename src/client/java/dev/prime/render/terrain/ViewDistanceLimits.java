// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** One source of truth shared by the client control and its integrated-server producer. */
public final class ViewDistanceLimits {
    public static final int MINIMUM_RENDER_DISTANCE = 2;
    public static final int VANILLA_MAXIMUM_RENDER_DISTANCE = 32;
    // Minecraft's distance graph accepts fewer than 254 levels. The player tracker reserves two
    // levels above the configured distance, making 251 its representable maximum.
    public static final int MAXIMUM_RENDER_DISTANCE = 251;

    private ViewDistanceLimits() {
    }

    public static int requestedDistance(
            int configuredDistance,
            boolean pathTracingEnabled,
            boolean integratedServer) {
        if (pathTracingEnabled) {
            return primeDistance(configuredDistance, integratedServer);
        }
        return clamp(
                configuredDistance,
                MINIMUM_RENDER_DISTANCE,
                VANILLA_MAXIMUM_RENDER_DISTANCE);
    }

    public static int primeDistance(int configuredDistance, boolean integratedServer) {
        int maximum = integratedServer
                ? MAXIMUM_RENDER_DISTANCE
                : VANILLA_MAXIMUM_RENDER_DISTANCE;
        return clamp(configuredDistance, MINIMUM_RENDER_DISTANCE, maximum);
    }

    public static int vanillaTerrainDistance(
            int configuredDistance, boolean primeOwnsTerrain) {
        int maximum = primeOwnsTerrain
                ? MINIMUM_RENDER_DISTANCE
                : VANILLA_MAXIMUM_RENDER_DISTANCE;
        return clamp(configuredDistance, MINIMUM_RENDER_DISTANCE, maximum);
    }

    public static int decodeRequestedDistance(int signedDistance) {
        return Byte.toUnsignedInt((byte) signedDistance);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
