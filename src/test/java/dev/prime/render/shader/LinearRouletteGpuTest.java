// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class LinearRouletteGpuTest extends GpuShaderTest {
    @Test
    void linearMetricIncludesEtaAndReweightsSurvivorsWithoutClippingTransport() throws Exception {
        float[][] cases = {
                {0, 0, 0, 1}, {0.04f, 0.08f, 0, 1}, {0.25f, 0.125f, 0, 0.5f},
                {0.01f, 0.02f, 0, 4}, {4, 2, 0, 1}, {0.001f, 0.4f, 0, 1},
                {1e-30f, 0, 0, 1}, {1e20f, 0, 0, 1e-20f}, {1e-30f, 0, 0, 1e20f}
        };
        int count = cases.length;
        ByteBuffer input = ByteBuffer.allocateDirect(16 + count * 16).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(count).putInt(0).putLong(0);
        for (float[] row : cases) for (float value : row) input.putFloat(value);
        input.flip();
        ByteBuffer output = runner.dispatch("linear_roulette.comp.spv", input, count * 16, count);
        for (int i = 0; i < count; ++i) {
            float[] row = cases[i];
            double expected = Math.min(1, Math.max(row[0], Math.max(row[1], row[2])) * (double) row[3]);
            float p = output.getFloat(i * 16);
            assertTrue(Float.isFinite(p) && p >= 0 && p <= 1);
            assertEquals(expected, p, Math.max(1e-35, expected * 2e-6));
            for (int c = 0; c < 3; c++) {
                float weighted = output.getFloat(i * 16 + 4 + c * 4);
                assertTrue(Float.isFinite(weighted) && weighted >= 0);
                assertEquals(row[c], weighted * p, Math.max(1e-35, row[c] * 2e-6));
            }
        }
    }

    @Test
    void unsampledFirstDistanceAndLaterTerminationPreserveNrdSemantics() throws Exception {
        ByteBuffer input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(4).putInt(1).putLong(0).flip();
        ByteBuffer output = runner.dispatch("linear_roulette.comp.spv", input, 4 * 16, 4);
        float[] distances = {0, 0, 7, 65504}; // Black, first RR rejection, first hit, actual miss.
        for (int i = 0; i < 4; ++i) {
            assertEquals(0, output.getFloat(i * 16));
            assertEquals(distances[i], output.getFloat(i * 16 + 4));
            assertEquals(distances[i], output.getFloat(i * 16 + 8));
            assertEquals(144, output.getFloat(i * 16 + 12));
        }
    }
}
