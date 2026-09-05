package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class LambertRouletteGpuTest extends GpuShaderTest {
    @Test
    void truncatedTransportPreservesExpectationAndLegacyRandomMapping() throws Exception {
        int count = 1 << 18;
        float[][] colors = {{0.2f, 0.4f, 0.5f}, {0.9f, 0.8f, 0.6f}, {0.002f, 0.01f, 0}};
        for (float[] color : colors) {
            ByteBuffer input = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
            input.putInt(count).putInt(0).putInt(-8).putInt(-1);
            for (float c : color) input.putFloat(c);
            input.putInt(0);
            input.clear();
            ByteBuffer output = runner.dispatch("lambert_roulette.comp.spv", input, count * 32, count);
            double[] sums = new double[3], squares = new double[3];
            double length = 0, sample = 0, next = 0, product = 0;
            for (int i = 0; i < count; ++i) {
                int offset = i * 32;
                for (int c = 0; c < 3; ++c) {
                    float value = output.getFloat(offset + c * 4);
                    assertTrue(Float.isFinite(value) && value >= 0);
                    sums[c] += value;
                    squares[c] += (double) value * value;
                }
                float vertices = output.getFloat(offset + 12);
                assertTrue(vertices >= 1 && vertices <= 12, "RR must preserve its start and bounce limit");
                length += vertices;
                assertEquals(0.0f, output.getFloat(offset + 16), "Legacy hash mapping changed");
                float u = output.getFloat(offset + 20), v = output.getFloat(offset + 24);
                assertTrue(u >= 0 && u < 1 && v >= 0 && v < 1);
                sample += u; next += v; product += u * v;
            }
            for (int c = 0; c < 3; ++c) {
                double a = color[c];
                double expected = a * (1.0 - Math.pow(a, 12)) / (1.0 - a) + Math.pow(a, 12);
                double mean = sums[c] / count;
                double standardError = Math.sqrt(Math.max(0, squares[c] / count - mean * mean) / count);
                assertEquals(expected, mean, 6 * standardError + 1e-5);
            }
            assertTrue(length / count < (color[2] == 0.5f ? 5 : 10), "Roulette must shorten the simulated paths");
            assertEquals(0.5, sample / count, 0.003);
            assertEquals(0.5, next / count, 0.003);
            assertEquals(0.25, product / count, 0.003);
        }
    }

    @Test
    void survivalBoundaryZeroAndUnitProbabilityRemainFinite() throws Exception {
        // Beta RGB, completed scatters, random value, survives, expected scale.
        float[][] cases = {
            {0, 0, 0, 1, 0, 0, 1}, {0.25f, 0.125f, 0, 0, 0.9f, 1, 1},
            {0.25f, 0.125f, 0, 1, 0.25f, 0, 1},
            {0.25f, 0.125f, 0, 1, Math.nextDown(0.25f), 1, 4},
            {1, 0.5f, 0, 1, Math.nextDown(1.0f), 1, 1},
            {4, 2, 0, 12, Math.nextDown(1.0f), 1, 1},
            {1e-30f, 0, 0, 1, 0, 1, 1e30f},
            {1e-30f, 0, 0, 1, Math.nextDown(1.0f), 0, 1},
            {0.001f, 0.4f, 0, 1, Math.nextDown(0.4f), 1, 2.5f},
            {0.001f, 0.4f, 0, 1, 0.4f, 0, 1}
        };
        ByteBuffer input = ByteBuffer.allocateDirect(16 + cases.length * 32).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(cases.length).putInt(1).putLong(0);
        for (float[] test : cases) {
            input.putFloat(test[0]).putFloat(test[1]).putFloat(test[2]).putInt((int) test[3]);
            input.putFloat(test[4]).putFloat(0).putLong(0);
        }
        input.flip();
        ByteBuffer output = runner.dispatch("lambert_roulette.comp.spv", input, cases.length * 32, cases.length);
        for (int i = 0; i < cases.length; ++i) {
            float[] test = cases[i];
            assertEquals(test[5], output.getFloat(i * 32 + 12));
            for (int c = 0; c < 3; ++c) {
                float value = output.getFloat(i * 32 + c * 4);
                assertTrue(Float.isFinite(value) && value >= 0);
                double expected = (double) test[c] * (test[5] == 1 ? test[6] : 1);
                assertEquals(expected, value, Math.max(1e-35, expected * 1e-6));
            }
        }
    }
}
