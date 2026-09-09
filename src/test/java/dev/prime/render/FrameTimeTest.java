// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class FrameTimeTest {
    @Test
    void initialNormalAndCappedIntervalsHaveOneContract() {
        assertEquals(
                1000.0F / 60.0F,
                FrameTime.deltaMilliseconds(false, Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(
                10.0F,
                FrameTime.deltaMilliseconds(true, 11_000_000L, 1_000_000L),
                1.0e-5F);
        assertEquals(
                0.0F,
                FrameTime.deltaMilliseconds(true, 1_000_000L, 1_000_000L));
        assertEquals(
                1000.0F,
                FrameTime.deltaMilliseconds(true, 2_000_000_001L, 1L));
    }

    @Test
    void backwardsOrOverflowingIntervalsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameTime.deltaMilliseconds(true, 1L, 2L));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameTime.deltaMilliseconds(
                        true, Long.MAX_VALUE, Long.MIN_VALUE));
        assertThrows(
                IllegalArgumentException.class,
                () -> FrameTime.deltaMilliseconds(
                        true, Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
