package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RendererSignalRangeDiagnosticsTest {
    @Test
    void snapshotHistogramsAreImmutableAndReportConservativeBounds() {
        long[] radiance = new long[128];
        long[] roughness = new long[64];
        long[] hit = new long[128];
        radiance[64] = 1L;
        radiance[66] = 3L;
        roughness[0] = 1L;
        roughness[31] = 3L;
        hit[68] = 4L;
        RendererSignalRangeDiagnostics.Snapshot snapshot = snapshot(
                radiance, roughness, hit);

        radiance[66] = 0L;
        snapshot.roughnessHistogram()[31] = 0L;

        assertEquals(Math.sqrt(8.0), snapshot.radiancePercentile(0.5), 1.0e-12);
        assertEquals(0.5, snapshot.roughnessPercentile(0.95), 0.0);
        assertEquals(Math.sqrt(32.0), snapshot.hitDistancePercentile(1.0), 1.0e-12);
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot.hitDistancePercentile(-0.01));
    }

    private static RendererSignalRangeDiagnostics.Snapshot snapshot(
            long[] radiance, long[] roughness, long[] hit) {
        return new RendererSignalRangeDiagnostics.Snapshot(
                1L,
                4L,
                4L,
                0L,
                0L,
                0L,
                2.0F,
                4L,
                0L,
                0.0F,
                0L,
                0.01F,
                0.5F,
                0L,
                0L,
                1.0F,
                8L,
                0L,
                0L,
                0L,
                0.25F,
                4.0F,
                radiance,
                roughness,
                hit);
    }
}
