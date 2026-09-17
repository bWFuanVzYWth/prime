// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class RealtimeContinuationGpuTest extends GpuShaderTest {
    @BeforeAll
    void reserveFixtureBindings() {
        runner.useIoBindings(100, 101);
    }

    private static ByteBuffer input(int words) {
        return ByteBuffer.allocateDirect(words * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void misHistorySurvivesLocalBounceResetsAndPreservesFullSourceIdentity() throws Exception {
        int[][] sources = {{-1, -1}, {0, 0}, {1, 0}, {0, 1}, {0x80000000, 0},
                {0, 0x80000000}, {-1, 17}, {23, -1}};
        int count = sources.length * 4 * 256;
        ByteBuffer input = input(4 + 4 * count).putInt(count).putInt(2).putLong(0);
        for (int[] source : sources)
            for (int flags = 0; flags < 4; ++flags)
                for (int bounce = 0; bounce < 256; ++bounce)
                    input.putInt(source[0]).putInt(source[1]).putInt(bounce).putInt(flags);
        ByteBuffer output = runner.dispatch("realtime_continuation.comp.spv", input.flip(), count * 32, count);
        int index = 0;
        for (int[] source : sources) {
            for (int flags = 0; flags < 4; ++flags) {
                boolean excluded = (source[0] == -1 && source[1] == -1) || (flags & 1) != 0;
                for (int bounce = 0; bounce < 256; ++bounce, ++index) {
                    int offset = index * 32;
                    assertEquals(excluded ? 1 : 0, output.getFloat(offset));
                    assertEquals(excluded ? 1 : 0, output.getFloat(offset + 4));
                    assertEquals(excluded ? 1 : 0.2f, output.getFloat(offset + 8), 1e-6f);
                    if (!excluded) assertEquals(1, output.getFloat(offset + 8)
                            + output.getFloat(offset + 12), 1e-6f);
                    assertEquals(1, output.getFloat(offset + 16), "state at " + index);
                    assertEquals(1, output.getFloat(offset + 20), "no competing NEE at " + index);
                }
            }
        }
    }

    @Test
    void allBudgetsPreserveTheFirstSecondaryGuideAndStopAtTheConfiguredMaximum() throws Exception {
        int count = 8 * 64 * 65;
        ByteBuffer input = input(4 + 4 * count).putInt(count).putInt(0).putLong(0);
        for (int minimum = 1; minimum <= 8; ++minimum)
            for (int maximum = 1; maximum <= 64; ++maximum)
                for (int secondary = 0; secondary <= 64; ++secondary)
                    input.putInt(minimum).putInt(maximum).putInt(secondary).putInt(0);
        ByteBuffer output = runner.dispatch("realtime_continuation.comp.spv", input.flip(), count * 16, count);
        int index = 0;
        for (int minimum = 1; minimum <= 8; ++minimum) {
            for (int maximum = 1; maximum <= 64; ++maximum) {
                int limit = Math.max(minimum, maximum);
                for (int secondary = 0; secondary <= 64; ++secondary, ++index) {
                    int completed = secondary + 1;
                    assertEquals(limit, output.getFloat(index * 16));
                    assertEquals(completed < limit ? 1 : 0, output.getFloat(index * 16 + 4));
                    assertEquals(completed < limit && completed >= Math.max(2, minimum) ? 1 : 0,
                            output.getFloat(index * 16 + 8));
                    assertEquals(completed, output.getFloat(index * 16 + 12));
                }
            }
        }
    }

    @Test
    void linearRoulettePreservesFiniteOrderEnergyWithoutAnExtraTerminalTrace() throws Exception {
        int count = 1 << 17;
        float albedo = 0.7f;
        for (int[] budget : new int[][] {{1, 1}, {1, 12}, {2, 12}, {8, 1}, {2, 64}, {8, 64}}) {
            ByteBuffer input = input(5).putInt(count).putInt(1).putInt(budget[0]).putInt(budget[1])
                    .putFloat(albedo).flip();
            ByteBuffer output = runner.dispatch("realtime_continuation.comp.spv", input, count * 16, count);
            int limit = Math.max(budget[0], budget[1]);
            double sum = 0, squares = 0;
            for (int i = 0; i < count; ++i) {
                float value = output.getFloat(i * 16), length = output.getFloat(i * 16 + 4);
                float roulettes = output.getFloat(i * 16 + 8);
                assertTrue(Float.isFinite(value) && value >= 1);
                assertTrue(length >= budget[0] && length <= limit);
                assertTrue(roulettes >= 0 && roulettes <= Math.max(0, limit - Math.max(2, budget[0])));
                sum += value;
                squares += (double) value * value;
            }
            double expected = (1 - Math.pow(albedo, limit)) / (1 - albedo);
            double mean = sum / count;
            double error = Math.sqrt(Math.max(0, squares / count - mean * mean) / count);
            assertEquals(expected, mean, 6 * error + 1e-5);
        }
    }
}
