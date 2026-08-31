package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.data.RendererDataContracts;
import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.terrain.MaterialIdResolver;
import dev.prime.render.terrain.PrimitivePacking;
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
        int tintRgb = PrimitivePacking.packTint(0xff23_a7e1) & 0x00ff_ffff;
        int sourceArgb = 0xff91_37c4;
        CanonicalColorEncoding.TintOperator tint =
                CanonicalColorEncoding.tintOperator(tintRgb);
        CanonicalColorEncoding.Color base = CanonicalColorEncoding.decodeRgba16f(
                CanonicalColorEncoding.encodeRgba16f(sourceArgb));
        CanonicalColorEncoding.Color tinted = tint.apply(base);
        int mediumId = 0x1234;
        int materialId = 0xabcd;
        int textureId = 0x3456;
        int recipeControl = 0x7123;
        int materialCore = textureId
                | recipeControl << ShaderAbi.MATERIAL_CORE_RECIPE_CONTROL_SHIFT;
        int continuousTintArgb = 0xa037_b9e1;
        long continuousTintEncoding =
                CanonicalColorEncoding.encodeLinearSrgbTintRgba16f(continuousTintArgb);
        CanonicalColorEncoding.Color continuouslyTinted =
                CanonicalColorEncoding.decodeLinearSrgbTintRgba16f(continuousTintEncoding)
                        .apply(new CanonicalColorEncoding.Color(
                                base.red(), base.green(), base.blue(), 0.75F));
        ByteBuffer input = ByteBuffer.allocateDirect(32 * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(pixelX).putInt(pixelY).putInt(width).putInt(height)
                .putFloat(jitterX).putFloat(jitterY)
                .putFloat(previousU).putFloat(previousV)
                .putFloat(currentU).putFloat(currentV)
                .putFloat(red).putFloat(green).putFloat(blue)
                .putFloat(tint.m00()).putFloat(tint.m01()).putFloat(tint.m02()).putFloat(0.0F)
                .putFloat(tint.m10()).putFloat(tint.m11()).putFloat(tint.m12()).putFloat(0.0F)
                .putFloat(tint.m20()).putFloat(tint.m21()).putFloat(tint.m22()).putFloat(0.0F)
                .putFloat(base.red()).putFloat(base.green()).putFloat(base.blue())
                .putInt(MaterialIdResolver.pack(mediumId, materialId))
                .putInt(materialCore)
                .putInt((int) continuousTintEncoding)
                .putInt((int) (continuousTintEncoding >>> 32))
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_renderer_data_contract.comp.spv");

        ByteBuffer output = runner.dispatch(shader, input, 6 * 4 * Float.BYTES, 1);
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
        assertEquals(tinted.red(), value(output, 3, 0), 2.0e-7);
        assertEquals(tinted.green(), value(output, 3, 1), 2.0e-7);
        assertEquals(tinted.blue(), value(output, 3, 2), 2.0e-7);
        assertEquals(mediumId, value(output, 4, 0), 0.0);
        assertEquals(materialId, value(output, 4, 1), 0.0);
        assertEquals(textureId, value(output, 4, 2), 0.0);
        assertEquals(recipeControl, value(output, 4, 3), 0.0);
        assertEquals(continuouslyTinted.red(), value(output, 5, 0), 3.0e-7);
        assertEquals(continuouslyTinted.green(), value(output, 5, 1), 3.0e-7);
        assertEquals(continuouslyTinted.blue(), value(output, 5, 2), 3.0e-7);
        assertEquals(continuouslyTinted.alpha(), value(output, 5, 3), 0.0);
    }

    private static float value(ByteBuffer output, int entry, int component) {
        return output.getFloat((entry * 4 + component) * Float.BYTES);
    }
}
