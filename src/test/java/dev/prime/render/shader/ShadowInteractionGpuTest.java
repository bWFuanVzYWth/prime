// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class ShadowInteractionGpuTest {
    private static final int COUNT = 16384;

    @Test void productionCompilerMatchesPerHitSamplingAndCanonicalExtinction() throws Exception {
        Path production = Path.of(System.getProperty("prime.test.shadowInteractionShader"));
        Path reference = Path.of(System.getProperty("prime.test.slangShaderDirectory"),
                "shadow_interaction_reference.comp.spv");
        // Distinct page contents model resource/animation changes, while geometry, tint and UV stay fixed.
        for (int frame = 0; frame < 2; frame++) {
            try (ShaderComputeRunner gpu = ShaderComputeRunner.openAddressed()) {
                gpu.bindSampledImage(ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES,
                        ShaderComputeRunner.ImageDimension.TWO_D,
                        ShaderComputeRunner.ImageFormat.R16G16B16A16_SFLOAT, pixels(frame), 64, 64, 1);
                gpu.repeatImageDescriptor(ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES, ShaderAbi.BASE_COLOR_PAGE_COUNT);
                gpu.bindStorageBuffer(ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS, textures());
                gpu.bindStorageBuffer(ShaderAbi.DESCRIPTOR_TINT_SAMPLES, tints());
                gpu.bindStorageBuffer(ShaderAbi.DESCRIPTOR_SURFACE_RECORDS, surfaces());
                ByteBuffer requests = data(COUNT * 8);
                for (int i = 0; i < COUNT; i++) requests.putInt(i * 8, i * 37 % COUNT).putInt(i * 8 + 4, 1 + i % 8);
                ByteBuffer actual = gpu.dispatchShadowCompiler(production, requests, COUNT * 16, COUNT);
                ByteBuffer expected = gpu.dispatchShadowCompiler(reference, requests, COUNT * 16, COUNT);
                int clear = 0, stained = 0;
                for (int i = 0; i < COUNT; i++) {
                    for (int channel = 0; channel < 3; channel++) {
                        int bits = actual.getInt(i * 16 + channel * 4);
                        assertEquals(expected.getInt(i * 16 + channel * 4), bits,
                                "frame=" + frame + " surface=" + i + " channel=" + channel);
                    }
                    int flag = actual.getInt(i * 16 + 12);
                    assertEquals(expected.getInt(i * 16 + 12), flag, "stained surface=" + i);
                    if (flag == 0) clear++; else stained++;
                }
                assertTrue(clear > 100 && stained > 100, "Both reference-opacity branches must execute");
            }
        }
    }

    private static ByteBuffer pixels(int frame) {
        ByteBuffer result = data(64 * 64 * 8);
        var random = new SplittableRandom(41263 + frame);
        for (int i = 0; i < 64 * 64; i++) {
            for (int c = 0; c < 3; c++) {
                float value = i % 7 == 0 ? 0 : i % 7 == 1 ? 1 : (float) random.nextDouble();
                result.putShort(i * 8 + c * 2, Float.floatToFloat16(value));
            }
            float alpha = switch (i / 64 % 4) { case 0 -> 0; case 1 -> 1; case 2 -> 128; default -> 255; };
            result.putShort(i * 8 + 6, Float.floatToFloat16(alpha));
        }
        return result;
    }

    private static ByteBuffer textures() {
        ByteBuffer result = data(9 * ShaderAbi.TEXTURE_RECORD_SIZE);
        for (int i = 1; i <= 8; i++) {
            int offset = i * ShaderAbi.TEXTURE_RECORD_SIZE;
            result.putInt(offset, (i - 1) * 8);
            result.putInt(offset + 4, 8 | 64 << 16);
            result.putInt(offset + 8, (i * 7 % 64) << 8);
        }
        return result;
    }

    private static ByteBuffer tints() {
        ByteBuffer result = data(256 * 8);
        var random = new SplittableRandom(781);
        for (int i = 0; i < 256; i++) {
            for (int c = 0; c < 3; c++) result.putShort(i * 8 + c * 2,
                    Float.floatToFloat16(i % 3 == 0 ? 1 : (float) random.nextDouble()));
            float alpha = switch (i % 4) { case 0 -> 0; case 1 -> 0.5f; case 2 -> Math.nextDown(0.5f); default -> 1; };
            result.putShort(i * 8 + 6, Float.floatToFloat16(alpha));
        }
        return result;
    }

    private static ByteBuffer surfaces() {
        ByteBuffer result = data(COUNT * ShaderAbi.PRIMITIVE_RECORD_SIZE);
        var random = new SplittableRandom(813);
        for (int i = 0; i < COUNT; i++) {
            int offset = i * ShaderAbi.PRIMITIVE_RECORD_SIZE;
            if (i % 3 == 0) {
                result.putFloat(offset, (float) random.nextDouble());
                result.putFloat(offset + 4, (float) random.nextDouble());
                result.putInt(offset + 24, 0x80000000);
            } else {
                for (int c = 0; c < 3; c++) result.putInt(offset + c * 4, random.nextInt());
            }
            result.putInt(offset + 12, i % 256 | 0x04000000);
        }
        return result;
    }

    private static ByteBuffer data(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
