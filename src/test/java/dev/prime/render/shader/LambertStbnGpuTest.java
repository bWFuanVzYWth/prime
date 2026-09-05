package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class LambertStbnGpuTest extends GpuShaderTest {
    @Test
    void productionTablePreservesCosineDistributionEnergyAndIndependentStreams() throws Exception {
        byte[] table;
        try (var resource = getClass().getResourceAsStream("/prime/stbn/realtime_128x128x64x3.rg16ui")) {
            assertNotNull(resource);
            table = resource.readAllBytes();
        }
        int count = 128 * 128 * 64;
        ByteBuffer input = ByteBuffer.allocateDirect(16 + table.length).order(ByteOrder.LITTLE_ENDIAN);
        input.position(16).put(table).clear();
        // Includes a temporal-cycle boundary, late vertices, and unsigned index/epoch wrap.
        int[][] cases = {{0, 1, 0}, {63, 6, 17}, {-32, 11, -1}};
        for (int[] test : cases) {
            input.putInt(0, count).putInt(4, test[0]).putInt(8, test[1]).putInt(12, test[2]);
            ByteBuffer output = runner.dispatch("lambert_stbn.comp.spv", input, count * 64, count);
            double[] sums = new double[6];
            double xy = 0, crossBank = 0, crossBounce = 0, z = 0, z2 = 0;
            int[] histogram = new int[16];
            for (int i = 0; i < count; ++i) {
                int offset = i * 64;
                float u = output.getFloat(offset), v = output.getFloat(offset + 4);
                float su = output.getFloat(offset + 8), sv = output.getFloat(offset + 12);
                float nu = output.getFloat(offset + 48), nv = output.getFloat(offset + 52);
                float[] values = {u, v, su, sv, nu, nv};
                for (int c = 0; c < values.length; c++) {
                    assertTrue(values[c] > 0 && values[c] < 1, "STBN endpoint or non-finite");
                    sums[c] += values[c];
                }
                xy += u * v;
                crossBank += u * su;
                crossBounce += u * nu;
                float dx = output.getFloat(offset + 16), dy = output.getFloat(offset + 20);
                float dz = output.getFloat(offset + 24);
                assertEquals(1.0, dx * dx + dy * dy + dz * dz, 1e-5);
                assertEquals(1.0, output.getFloat(offset + 28), 1e-5);
                assertTrue(dz > 0 && output.getFloat(offset + 44) > 0);
                z += dz;
                z2 += dz * dz;
                histogram[Math.min(15, (int) (dz * dz * 16))]++;
                assertEquals(0.4, output.getFloat(offset + 32), 2e-6);
                assertEquals(1.0, output.getFloat(offset + 36), 2e-6);
                assertEquals(2.0, output.getFloat(offset + 40), 2e-6);
                int texel = output.getInt(offset + 56), frame = output.getInt(offset + 60);
                assertTrue(texel >= 0 && texel < 128 * 128);
                assertEquals((test[0] + (i >> 14)) & 63, frame);
                // One 64-frame column retains its spatial origin; only cycle boundaries may shift it.
                if (i >= 16384 && frame != 0) assertEquals(output.getInt(offset - 16384 * 64 + 56), texel);
            }
            for (double sum : sums) assertEquals(0.5, sum / count, 0.003);
            assertEquals(0.25, xy / count, 0.003);
            assertEquals(0.25, crossBank / count, 0.003);
            assertEquals(0.25, crossBounce / count, 0.003);
            assertEquals(2.0 / 3.0, z / count, 0.003);
            assertEquals(0.5, z2 / count, 0.003);
            for (int bin : histogram) assertEquals(1.0 / 16.0, (double) bin / count, 0.002);
        }
    }
}
