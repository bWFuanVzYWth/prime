package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.data.RendererDataContracts;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class RendererDataContractGpuTest {
    private static ShaderComputeRunner runner;

    @Test
    void generatedSlangCoordinateAndColorLeavesMatchTheJavaOracle() throws IOException {
        int pixelX = 733;
        int pixelY = 419;
        int width = 1920;
        int height = 1080;
        float jitterX = 0.25F;
        float jitterY = -0.375F;
        float previousU = 0.625F;
        float previousV = 0.25F;
        float currentU = 0.5F;
        float currentV = 0.5F;
        float red = 0.25F;
        float green = 0.5F;
        float blue = 0.75F;
        ByteBuffer input = ByteBuffer.allocateDirect(13 * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(pixelX).putInt(pixelY).putInt(width).putInt(height)
                .putFloat(jitterX).putFloat(jitterY)
                .putFloat(previousU).putFloat(previousV)
                .putFloat(currentU).putFloat(currentV)
                .putFloat(red).putFloat(green).putFloat(blue)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_renderer_data_contract.comp.spv");

        ByteBuffer output = runner.dispatch(shader, input, 3 * 4 * Float.BYTES, 1);
        double[] uv = RendererDataContracts.sampleUv(pixelX, pixelY, width, height);
        double[] clip = RendererDataContracts.uvToClip(uv[0], uv[1]);
        double[] projectionJitter =
                RendererDataContracts.projectionJitterPixels(jitterX, jitterY);
        double[] motion = RendererDataContracts.visibleMotionUv(
                previousU, previousV, currentU, currentV);
        double[] rec2020 = RendererDataContracts.linearSrgbToLinearRec2020(
                RendererDataContracts.decodeSrgb(red),
                RendererDataContracts.decodeSrgb(green),
                RendererDataContracts.decodeSrgb(blue));

        assertEquals(uv[0], value(output, 0, 0), 3.0e-7);
        assertEquals(uv[1], value(output, 0, 1), 3.0e-7);
        assertEquals(clip[0], value(output, 0, 2), 3.0e-7);
        assertEquals(clip[1], value(output, 0, 3), 3.0e-7);
        assertEquals(projectionJitter[0], value(output, 1, 0), 0.0);
        assertEquals(projectionJitter[1], value(output, 1, 1), 0.0);
        assertEquals(motion[0], value(output, 1, 2), 0.0);
        assertEquals(motion[1], value(output, 1, 3), 0.0);
        assertEquals(rec2020[0], value(output, 2, 0), 2.0e-6);
        assertEquals(rec2020[1], value(output, 2, 1), 2.0e-6);
        assertEquals(rec2020[2], value(output, 2, 2), 2.0e-6);
    }

    private static float value(ByteBuffer output, int entry, int component) {
        return output.getFloat((entry * 4 + component) * Float.BYTES);
    }
}
