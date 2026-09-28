package dev.primept;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FrameTimingsTest {
    @Test
    void thresholdIncludesCurrentSourceWorkAndTheFirstFrame() {
        assertFalse(new FrameTimings(0, 0, 10_000_000, 20_000_000, 19_999_999).slow());
        var frame = new FrameTimings(0, 0, 10_000_000, 20_000_000, 20_000_000);
        assertTrue(frame.slow());
        assertEquals(-1, frame.outside());
        assertEquals(50_000_000, frame.currentWork());
    }
    @Test
    void cadenceUsesThePreviousHookAndKeepsUnknownHostTimeSeparate() {
        var frame = new FrameTimings(100_000_000, 60_000_000, 5_000_000, 10_000_000, 2_000_000);
        assertTrue(frame.slow());
        assertEquals(17_000_000, frame.currentWork());
        assertEquals(25_000_000, frame.outside());
        assertTrue(new FrameTimings(50_000_000, 0, 0, 0, 0).slow());
        assertFalse(new FrameTimings(0, 0, 0, 0, 1_000).slow());
    }
}
