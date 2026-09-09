// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RenderOriginTest {
    @Test
    void alignsPositiveAndNegativeCoordinatesToSectionBoundaries() {
        assertEquals(0, RenderOrigin.alignToSection(15.999));
        assertEquals(16, RenderOrigin.alignToSection(16.0));
        assertEquals(-16, RenderOrigin.alignToSection(-0.001));
        assertEquals(-16, RenderOrigin.alignToSection(-16.0));
        assertEquals(-32, RenderOrigin.alignToSection(-16.001));
    }

    @Test
    void rebasesOnlyAfterCrossingTheConfiguredDistance() {
        assertFalse(RenderOrigin.needsRebase(256.0, 0.0, 0.0, 0, 0, 0, 256));
        assertTrue(RenderOrigin.needsRebase(256.01, 0.0, 0.0, 0, 0, 0, 256));
        assertTrue(RenderOrigin.needsRebase(0.0, -257.0, 0.0, 0, 0, 0, 256));
    }
}
