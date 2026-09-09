// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LabPbrEmissionMapTest {
    @Test
    void alpha255IsAbsentAnd254IsFullEmission() {
        assertEquals(0.0F, LabPbrEmissionMap.decode(0));
        assertEquals(1.0F, LabPbrEmissionMap.decode(254));
        assertEquals(0.0F, LabPbrEmissionMap.decode(255));
    }

    @Test
    void onlyTheAll255SentinelMapIsDiscarded() {
        assertNull(LabPbrEmissionMap.fromSpecular(
                new int[] {0xff000000, 0xff000000},
                2,
                1,
                2,
                1,
                1,
                1));
        LabPbrEmissionMap authoredZero = LabPbrEmissionMap.fromSpecular(
                new int[] {0xff000000, 0x00000000},
                2,
                1,
                2,
                1,
                1,
                1);
        assertNotNull(authoredZero);
        assertFalse(authoredZero.hasPositiveEmission());
    }

    @Test
    void reportsWhetherAnyTexelActuallyEmits() {
        LabPbrEmissionMap map = LabPbrEmissionMap.fromSpecular(
                new int[] {0xff000000, 0xe7000000},
                2,
                1,
                2,
                1,
                1,
                1);

        assertNotNull(map);
        assertTrue(map.hasPositiveEmission());
    }

    @Test
    void animatedSamplingUsesTheRequestedFrameAndStaticMapsRemainStatic() {
        LabPbrEmissionMap animated = LabPbrEmissionMap.fromSpecular(
                new int[] {0x00000000, 0xfe000000},
                1,
                2,
                1,
                1,
                1,
                2);
        LabPbrEmissionMap single = LabPbrEmissionMap.fromSpecular(
                new int[] {0x7f000000},
                1,
                1,
                1,
                1,
                1,
                1);

        assertEquals(0.0F, animated.sample(0, 0.5F, 0.5F));
        assertEquals(1.0F, animated.sample(1, 0.5F, 0.5F));
        assertEquals(0.5F, single.sample(7, 0.5F, 0.5F), 1.0E-6F);
    }
}
