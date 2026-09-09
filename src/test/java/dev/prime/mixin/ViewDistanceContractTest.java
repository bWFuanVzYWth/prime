// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.terrain.ViewDistanceLimits;
import org.junit.jupiter.api.Test;

final class ViewDistanceContractTest {
    @Test
    void playerDistanceAndReservedGraphLevelsFitTheUnsignedStorageContract() {
        int sentinelLevel = ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE + 2;

        assertEquals(253, sentinelLevel);
        assertEquals(sentinelLevel, Byte.toUnsignedInt((byte) sentinelLevel));
    }

    @Test
    void networkDecodeRestoresTheCompleteExtendedRange() {
        assertEquals(127, ViewDistanceLimits.decodeRequestedDistance(127));
        assertEquals(128, ViewDistanceLimits.decodeRequestedDistance(-128));
        assertEquals(251, ViewDistanceLimits.decodeRequestedDistance(-5));
    }

    @Test
    void onlyPrimeOnTheIntegratedServerRequestsExtendedDistance() {
        assertEquals(251, ViewDistanceLimits.requestedDistance(251, true, true));
        assertEquals(32, ViewDistanceLimits.requestedDistance(251, false, true));
        assertEquals(32, ViewDistanceLimits.requestedDistance(251, true, false));
        assertEquals(2, ViewDistanceLimits.requestedDistance(1, true, true));
        assertEquals(251, ViewDistanceLimits.primeDistance(251, true));
        assertEquals(32, ViewDistanceLimits.primeDistance(251, false));
    }

    @Test
    void vanillaTerrainUsesOnlyItsNativeOrDormantWindow() {
        assertEquals(32, ViewDistanceLimits.vanillaTerrainDistance(251, false));
        assertEquals(2, ViewDistanceLimits.vanillaTerrainDistance(251, true));
    }
}
