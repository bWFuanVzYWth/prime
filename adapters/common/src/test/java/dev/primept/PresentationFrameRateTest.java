package dev.primept;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class PresentationFrameRateTest {
    @Test
    void usesActualTotalsAndElapsedTimeIncludingDroppedAndZeroFrames() {
        var rate = new PresentationFrameRate();
        rate.observe(0, 1, 100, 1, true, true);
        rate.observe(500_000_000, 1, 160, 2, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(1_000_000_000, 1, 220, 3, true, true);
        assertEquals(120, rate.framesPerSecond());
        rate.observe(2_000_000_000, 1, 310, 4, true, true);
        assertEquals(90, rate.framesPerSecond());
        rate.observe(3_000_000_000L, 1, 310, 5, true, true);
        assertEquals(0, rate.framesPerSecond());
        rate.observe(4_200_000_000L, 1, 430, 6, true, true);
        assertEquals(100, rate.framesPerSecond());
    }

    @Test
    void repeatedReadsDoNotCountAgainAndStaleSamplesRewarm() {
        var rate = ready();
        rate.observe(1_250_000_000, 1, 120, 2, true, true);
        assertEquals(120, rate.framesPerSecond());
        rate.observe(2_600_000_000L, 1, 120, 2, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(2_750_000_000L, 1, 120, 2, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(3_000_000_000L, 1, 150, 3, true, true);
        rate.observe(4_000_000_000L, 1, 210, 4, true, true);
        assertEquals(60, rate.framesPerSecond());
        rate.observe(8_000_000_000L, 1, 900, 5, true, true);
        assertEquals(173, rate.framesPerSecond());
    }

    @Test
    void continuouslyFreshSlowSamplesUseTheirFullElapsedWindow() {
        var rate = new PresentationFrameRate();
        rate.observe(0, 1, 0, 1, true, true);
        rate.observe(2_000_000_000L, 1, 2, 2, true, true);
        assertEquals(1, rate.framesPerSecond());
        rate.observe(4_000_000_000L, 1, 6, 3, true, true);
        assertEquals(2, rate.framesPerSecond());
        rate.observe(6_000_000_000L, 1, 6, 4, true, true);
        assertEquals(0, rate.framesPerSecond());
    }

    @Test
    void epochOffAndInvalidStateDiscardPreviousRates() {
        var rate = ready();
        rate.observe(1_250_000_000, 2, 0, 1, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(2_250_000_000L, 2, 60, 2, true, true);
        assertEquals(60, rate.framesPerSecond());
        rate.observe(2_500_000_000L, 2, 60, 3, false, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(2_750_000_000L, 2, 60, 4, true, false);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(3_000_000_000L, 0, 60, 5, true, true);
        assertEquals(-1, rate.framesPerSecond());
    }

    @Test
    void rejectsRegressingOrInconsistentCountersAndClock() {
        var rate = ready();
        rate.observe(1_250_000_000, 1, 119, 3, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate = ready();
        rate.observe(1_250_000_000, 1, 121, 2, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate = ready();
        rate.observe(1_250_000_000, 1, 121, 1, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate = ready();
        rate.observe(500_000_000, 1, 150, 3, true, true);
        assertEquals(-1, rate.framesPerSecond());
    }

    @Test
    void handlesUnsignedCounterCrossingAndBoundsHugeRates() {
        var rate = new PresentationFrameRate();
        rate.observe(0, 1, Long.MAX_VALUE - 59, 1, true, true);
        rate.observe(1_000_000_000, 1, Long.MIN_VALUE + 60, 2, true, true);
        assertEquals(120, rate.framesPerSecond());
        rate.observe(2_000_000_000L, 1, -1, 3, true, true);
        assertEquals(Integer.MAX_VALUE, rate.framesPerSecond());
    }

    private static PresentationFrameRate ready() {
        var rate = new PresentationFrameRate();
        rate.observe(0, 1, 0, 1, true, true);
        rate.observe(1_000_000_000, 1, 120, 2, true, true);
        assertEquals(120, rate.framesPerSecond());
        return rate;
    }

    @Test
    void busySnapshotsExpireWithoutBeingCountedAsNewSamples() {
        var rate = ready();
        rate.advance(1_250_000_000);
        rate.advance(2_000_000_000L);
        assertEquals(120, rate.framesPerSecond());
        rate.advance(2_600_000_000L);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(2_750_000_000L, 1, 240, 3, true, true);
        assertEquals(-1, rate.framesPerSecond());
        rate.observe(3_750_000_000L, 1, 300, 4, true, true);
        assertEquals(60, rate.framesPerSecond());
    }
}
