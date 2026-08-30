package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RendererDataRangeDiagnosticsTest {
    @Test
    void histogramSnapshotIsImmutableAndReportsConservativeUpperBounds() {
        long[] motion = new long[128];
        long[] depth = new long[128];
        motion[64] = 1L;
        motion[66] = 3L;
        depth[68] = 4L;
        RendererDataRangeDiagnostics.Snapshot snapshot = snapshot(motion, depth);

        motion[66] = 0L;
        snapshot.motionPixelHistogram()[66] = 0L;

        assertEquals(Math.sqrt(8.0), snapshot.motionPixelsPercentile(0.5), 1.0e-12);
        assertEquals(Math.sqrt(8.0), snapshot.motionPixelsPercentile(1.0), 1.0e-12);
        assertEquals(Math.sqrt(32.0), snapshot.surfaceViewZPercentile(0.95), 1.0e-12);
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot.motionPixelsPercentile(1.01));
    }

    private static RendererDataRangeDiagnostics.Snapshot snapshot(
            long[] motion, long[] depth) {
        return new RendererDataRangeDiagnostics.Snapshot(
                1L,
                0L,
                4L,
                4L,
                0L,
                0L,
                4L,
                1.0F,
                1.0F,
                2.0F,
                4L,
                0L,
                0L,
                1.0F,
                4.0F,
                motion,
                depth);
    }
}
