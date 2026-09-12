// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class StarmapFilterGpuTest extends GpuShaderTest {
    @Test
    void movingSubpixelStarPreservesFluxAndCoverageRejectsForeground() throws Exception {
        int bytes = 0;
        for (int size = 64; size > 0; size >>= 1) bytes += size * size * 8;
        var texture = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] pixels = new float[64 * 64];
        pixels[36 * 64 + 36] = 64.0F;
        for (int size = 64; size > 0; size >>= 1) {
            for (float value : pixels) {
                short half = Float.floatToFloat16(value);
                texture.putShort(half).putShort(half).putShort(half).putShort(Float.floatToFloat16(1.0F));
            }
            if (size == 1) break;
            float[] next = new float[size * size / 4];
            for (int y = 0; y < size / 2; y++) for (int x = 0; x < size / 2; x++) {
                int p = y * 2 * size + x * 2;
                next[y * (size / 2) + x] = (pixels[p] + pixels[p + 1] + pixels[p + size] + pixels[p + size + 1]) * 0.25F;
            }
            pixels = next;
        }
        texture.flip();
        runner.bindMipmappedImage(2, ShaderComputeRunner.ImageDimension.TWO_D,
                ShaderComputeRunner.ImageFormat.R16G16B16A16_SFLOAT, texture, 64, 64, 1, 7, true);
        int count = 64 * 8 * 8;
        var input = ByteBuffer.allocateDirect((count + 5) * 32).order(ByteOrder.LITTLE_ENDIAN);
        float[] coverage = {0.0F, 0.25F, 1.0F, Float.NaN, Float.POSITIVE_INFINITY};
        for (int phase = 0; phase < 64; phase++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            input.putFloat((x + 0.5F + phase / 64.0F) / 8.0F + 0.5F / 64.0F)
                    .putFloat((y + 0.5F) / 8.0F + 0.5F / 64.0F)
                    .putFloat(1.0F / 8.0F).putFloat(0.0F)
                    .putFloat(0.0F).putFloat(1.0F / 8.0F).putFloat(coverage[phase % 5]).putFloat(0.0F);
        }
        // Degenerate, extreme anisotropic, and tiny footprints must stay finite.
        float[][] derivatives = {{0,0,0,0}, {0.5F,0,0,1e-6F}, {1e-12F,0,0,1e-12F},
                {0.25F,0.25F,-0.25F,0.25F}, {0,0.5F,0,0}};
        for (float[] d : derivatives) input.putFloat(0.5F).putFloat(0.5F)
                .putFloat(d[0]).putFloat(d[1]).putFloat(d[2]).putFloat(d[3]).putFloat(1).putFloat(0);
        input.flip();
        var output = runner.dispatch("starmap_filter.comp.spv", input, (count + 5) * 32,
                new ShaderComputeRunner.Workgroups(count + 5, 1, 1), null);
        double rawMin = Double.POSITIVE_INFINITY, rawMax = 0;
        for (int phase = 0; phase < 64; phase++) {
            double filtered = 0, raw = 0;
            for (int i = 0; i < 64; i++) {
                int offset = (phase * 64 + i) * 32;
                filtered += output.getFloat(offset);
                raw += output.getFloat(offset + 4);
                float c = coverage[phase % 5];
                float visible = Float.isFinite(c) ? 1 - c : 0;
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals((channel + 1) * (1 + 4 * visible), output.getFloat(offset + 16 + channel * 4), 1e-5F);
                }
                assertEquals(0.002F, output.getFloat(offset + 28), 1e-6F, "RA wrap");
            }
            assertEquals(1.0, filtered, 0.002, "integrated star flux at phase " + phase);
            rawMin = Math.min(rawMin, raw);
            rawMax = Math.max(rawMax, raw);
        }
        assertTrue(rawMax - rawMin > 32, "LOD 0 reference must expose undersampling");
        for (int i = count; i < count + 5; i++) {
            for (int c = 0; c < 8; c++) assertTrue(Float.isFinite(output.getFloat(i * 32 + c * 4)));
            assertTrue(output.getFloat(i * 32 + 12) >= 1 && output.getFloat(i * 32 + 12) <= 8);
        }
    }
}
