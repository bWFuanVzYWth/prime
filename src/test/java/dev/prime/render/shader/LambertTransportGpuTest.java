// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class LambertTransportGpuTest extends GpuShaderTest {
    @Test void shaderScratchAndQueueBoundariesMatchTheHostAllocation() throws Exception {
        int[] pixels = {1, 3, 17, 1920 * 1027, 3840 * 2160};
        ByteBuffer input = input(4 + pixels.length).putInt(pixels.length).putInt(5).putLong(0);
        for (int count : pixels) input.putInt(count);
        ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input.flip(), pixels.length * 16, pixels.length);
        for (int i = 0; i < pixels.length; ++i) {
            long scratchEnd = 48L * pixels[i];
            assertEquals(scratchEnd, 4L * output.getInt(i * 16));
            assertEquals(scratchEnd + 32, 4L * output.getInt(i * 16 + 4));
            assertEquals(scratchEnd + 48, 4L * output.getInt(i * 16 + 8));
            assertEquals(60L * pixels[i] + 48, 4L * output.getInt(i * 16 + 12));
        }
    }
    private static ByteBuffer input(int words) {
        return ByteBuffer.allocateDirect(words * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test void configuredRoundsOwnRouletteAndEmissionDomains() throws Exception {
        int[][] cases = {{1,1,0}, {1,1,1}, {2,16,1}, {2,16,2}, {2,16,15}, {2,16,16}, {8,1,7}, {8,1,8}, {2,64,63}, {2,64,64}};
        ByteBuffer input = input(4 + 4 * cases.length);
        input.putInt(cases.length).putInt(0).putLong(0);
        for (int[] c : cases) input.putInt(c[0]).putInt(c[1]).putInt(c[2]).putInt(0);
        input.flip();
        ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input, cases.length * 16, cases.length);
        for (int i = 0; i < cases.length; ++i) {
            int[] c = cases[i]; int limit = Math.max(c[0], c[1]);
            assertEquals(limit, output.getFloat(i * 16));
            assertEquals(c[2] >= c[0] && c[2] < limit ? 1 : 0, output.getFloat(i * 16 + 4));
            assertEquals(c[2] == 1 ? 0 : 1, output.getFloat(i * 16 + 8));
            assertEquals(1, output.getFloat(i * 16 + 12));
        }
    }

    @Test void absorptionTracksNestedAndAdjacentIdentitiesWithoutTriangleCountDependence() throws Exception {
        ByteBuffer input = input(4).putInt(1).putInt(1).putLong(0).flip();
        ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input, 80, 1);
        float[] a = {0.2f, 0.5f, 1}, b = {0.8f, 0.1f, 0.25f};
        for (int c = 0; c < 3; ++c) {
            assertEquals(Math.exp(-3 * a[c] - 3 * b[c]), output.getFloat(c * 4), 2e-7);
            assertEquals(b[c], output.getFloat(32 + c * 4));
            assertEquals(0, output.getFloat(48 + c * 4));
            assertEquals(Math.exp(-2 * a[c]), output.getFloat(64 + c * 4), 2e-7);
        }
        assertEquals(0, output.getFloat(12));
        assertEquals(1, output.getFloat(16));
        assertEquals(65535, output.getInt(20));
        assertEquals(128, output.getFloat(24));
        assertEquals(48, output.getFloat(28));
        for (int offset : new int[] {44, 60, 76}) assertEquals(1, output.getFloat(offset));
    }

    @Test void firstReflectionIsMonotoneConservesExpectedEnergyAndHasAnInvertibleGuide() throws Exception {
        int count = 1025;
        for (int water = 0; water < 2; ++water) {
            ByteBuffer input = input(4).putInt(count).putInt(2).putInt(water).putInt(0).flip();
            ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input, count * 16, count);
            float last = 1;
            for (int i = 0; i < count; ++i) {
                float f = output.getFloat(i * 16);
                assertTrue(Float.isFinite(f) && f <= last && f >= 0);
                assertEquals(1, output.getFloat(i * 16 + 4), 1e-6);
                assertTrue(output.getFloat(i * 16 + 8) < 1e-5);
                assertEquals(1, output.getFloat(i * 16 + 12));
                last = f;
            }
            assertEquals(water == 1 ? 0.02 : 0.04, last, 1e-7);
        }
    }

    @Test void delayedLinearRoulettePreservesFiniteOrderEnergyThroughConfiguredMaximum() throws Exception {
        int count = 1 << 17;
        for (int[] budget : new int[][] {{1,1}, {1,16}, {2,16}, {8,1}, {2,64}}) {
            float albedo = 0.75f;
            ByteBuffer input = input(5).putInt(count).putInt(3).putInt(budget[0]).putInt(budget[1]).putFloat(albedo).flip();
            ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input, count * 16, count);
            int limit = Math.max(budget[0], budget[1]);
            double sum = 0, squares = 0;
            for (int i = 0; i < count; ++i) {
                float value = output.getFloat(i * 16), length = output.getFloat(i * 16 + 4);
                assertTrue(Float.isFinite(value) && value >= 1);
                assertTrue(length >= budget[0] && length <= limit);
                sum += value; squares += (double) value * value;
            }
            double expected = (1 - Math.pow(albedo, limit + 1)) / (1 - albedo);
            double mean = sum / count;
            double error = Math.sqrt(Math.max(0, squares / count - mean * mean) / count);
            assertEquals(expected, mean, 6 * error + 1e-5);
        }
    }

    @Test void stbnDomainsDoNotAliasAtSixtyFourBouncesOrAcrossTheFrameCycle() throws Exception {
        int count = 2 * 130;
        for (int frame : new int[] {0, 1, 63, 64, 127, 128}) {
            ByteBuffer input = input(4).putInt(count).putInt(4).putInt(frame).putInt(72).flip();
            ByteBuffer output = runner.dispatch("lambert_transport.comp.spv", input, count * 16, count);
            var addresses = new HashSet<Integer>();
            for (int i = 0; i < count; ++i) {
                for (int bank = 0; bank < 3; ++bank) assertTrue(addresses.add(output.getInt(i * 16 + bank * 4)));
                assertEquals(frame & 63, output.getInt(i * 16 + 12));
            }
        }
    }
}
