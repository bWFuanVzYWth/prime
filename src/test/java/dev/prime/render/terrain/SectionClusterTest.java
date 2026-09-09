// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class SectionClusterTest {
    @Test
    void alignsPositiveAndNegativeSectionCoordinates() {
        assertEquals(0, SectionCluster.origin(0));
        assertEquals(0, SectionCluster.origin(3));
        assertEquals(4, SectionCluster.origin(4));
        assertEquals(-4, SectionCluster.origin(-1));
        assertEquals(-4, SectionCluster.origin(-4));
        assertEquals(-8, SectionCluster.origin(-5));
    }

    @Test
    void keyUsesTheAlignedClusterOrigin() {
        long key = SectionCluster.keyForSection(-1, 6, 9);
        assertEquals(-4, SectionPos.x(key));
        assertEquals(4, SectionPos.y(key));
        assertEquals(8, SectionPos.z(key));
        assertTrue(SectionCluster.contains(key, -1, 7, 11));
        assertFalse(SectionCluster.contains(key, 0, 7, 11));
    }

    @Test
    void contractContainsSixtyFourSectionsAndOneSectionHalo() {
        assertEquals(64, SectionCluster.SECTION_COUNT);
        assertEquals(6, SectionCluster.SNAPSHOT_SIZE);
    }
}
