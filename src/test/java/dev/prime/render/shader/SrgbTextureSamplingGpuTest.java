package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class SrgbTextureSamplingGpuTest {
    private static ShaderComputeRunner runner;

    @Test
    void srgbViewFiltersRgbInLinearLightWhileUnormKeepsDataAndAlphaRaw()
            throws IOException {
        ByteBuffer pixels = ByteBuffer.allocateDirect(3 * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        pixels.put(new byte[] {
            0, 0, 0, 32,
            (byte) 255, (byte) 255, (byte) 255, (byte) 224,
            (byte) 128, 0, 0, (byte) 128
        }).flip();
        ByteBuffer input = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "srgb_texture_sampling.comp.spv");

        runner.bindSampledImage(
                2,
                ShaderComputeRunner.ImageDimension.TWO_D,
                ShaderComputeRunner.ImageFormat.R8G8B8A8_SRGB,
                pixels,
                3,
                1,
                1);
        runner.bindSampledImage(
                3,
                ShaderComputeRunner.ImageDimension.TWO_D,
                ShaderComputeRunner.ImageFormat.R8G8B8A8_UNORM,
                pixels,
                3,
                1,
                1);
        ByteBuffer output = runner.dispatch(shader, input, 4 * 4 * Float.BYTES, 1);

        assertEquals(0.5F, value(output, 0, 0), 2.0e-6F);
        assertEquals(0.5F, value(output, 0, 1), 2.0e-6F);
        assertEquals(0.5F, value(output, 0, 2), 2.0e-6F);
        assertEquals(value(output, 1, 3), value(output, 0, 3), 0.0F);

        float encodedMidpoint = 128.0F / 255.0F;
        float decodedMidpoint = encodedMidpoint <= 0.04045F
                ? encodedMidpoint / 12.92F
                : (float) Math.pow((encodedMidpoint + 0.055F) / 1.055F, 2.4F);
        assertEquals(decodedMidpoint, value(output, 2, 0), 1.0e-4F);
        assertEquals(encodedMidpoint, value(output, 3, 0), 2.0e-6F);
        assertEquals(value(output, 3, 3), value(output, 2, 3), 0.0F);
        assertEquals(encodedMidpoint, value(output, 3, 3), 2.0e-6F);
    }

    private static float value(ByteBuffer output, int pixel, int channel) {
        return output.getFloat((pixel * 4 + channel) * Float.BYTES);
    }
}
