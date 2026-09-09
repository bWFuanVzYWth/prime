// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

final class DiscreteStbnGpuTest extends GpuShaderTest {
    @Test
    void refinementPreservesRanksAndReachesSmallDiscreteProbabilities() throws Exception {
        byte[] table;
        try (var resource = getClass().getResourceAsStream("/prime/stbn/realtime_128x128x64x3.rg16ui")) {
            assertNotNull(resource);
            table = resource.readAllBytes();
        }
        int count = 128 * 128 * 64;
        ByteBuffer input = ByteBuffer.allocateDirect(16 + table.length).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(count).putInt(-32).putInt(-1).putInt(11).put(table).flip();
        ByteBuffer output = runner.dispatch("discrete_stbn.comp.spv", input, count * 48, count);
        BitSet leaves = new BitSet(131072);
        int[][] bins = new int[2][256];
        int[] changed = new int[6];
        int rareCell = 0;
        double cross = 0;
        boolean zero = false, last = false;
        for (int i = 0; i < count; ++i) {
            int offset = i * 48;
            int x = output.getInt(offset), y = output.getInt(offset + 4), packed = output.getInt(offset + 8);
            assertTrue(x >= 0 && x < 16777216 && y >= 0 && y < 16777216);
            assertEquals(packed & 65535, x >>> 8);
            assertEquals(packed >>> 16, y >>> 8);
            assertEquals(0, output.getInt(offset + 12) & 2, "Immediate and staged seed identity diverged");
            rareCell += output.getInt(offset + 12) & 1;
            leaves.set(x >>> 7); // Seventeen equal binary splits exceed the old 16-bit support.
            bins[0][x & 255]++;
            bins[1][y & 255]++;
            cross += (x & 255) * (y & 255);
            for (int j = 0; j < 6; ++j) {
                if (output.getInt(offset + 16 + j * 4) != (x & 255)) changed[j]++;
            }
            float high = output.getFloat(offset + 40), low = output.getFloat(offset + 44);
            assertTrue(high < 1 && high >= 65535.0f / 65536 && low >= 0 && low < 1.0f / 65536);
            zero |= low == 0;
            last |= high == Math.nextDown(1.0f);
        }
        assertTrue(zero && last, "Both extreme 24-bit codes must be representable");
        assertTrue(leaves.cardinality() > 131072 * 0.999, "Half the leaves were unreachable before refinement");
        assertEquals(count / 4096.0, rareCell, 6 * Math.sqrt(count / 4096.0));
        for (int[] channel : bins) {
            double chiSquare = 0;
            for (int bin : channel) chiSquare += Math.pow(bin - count / 256.0, 2) / (count / 256.0);
            assertTrue(chiSquare < 360, "Low-bit marginal distribution");
        }
        assertEquals(127.5 * 127.5, cross / count, 100);
        for (int value : changed) assertEquals(255.0 / 256, (double) value / count, 0.001);
    }
}
