// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainOwnershipTest {
    @Test
    void vanillaDistanceReflectsTheSingleOwnershipBit() {
        TerrainOwnership ownership = new TerrainOwnership();
        assertEquals(32, ownership.vanillaDistance(251));
        assertFalse(ownership.primeOwned());

        assertTrue(ownership.changeOwnership(true));
        assertTrue(ownership.primeOwned());
        assertEquals(2, ownership.vanillaDistance(251));
        assertFalse(ownership.changeOwnership(true));

        assertTrue(ownership.changeOwnership(false));
        assertFalse(ownership.primeOwned());
        assertEquals(32, ownership.vanillaDistance(251));
    }
}
