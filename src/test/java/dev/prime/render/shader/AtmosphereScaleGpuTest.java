package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class AtmosphereScaleGpuTest {
    private static final int CASE_FLOATS = 4;
    private static ShaderComputeRunner runner;

    @Test
    void coordinateScalePreservesAtmosphereAndMultipliesWorldOpticalDepth()
            throws IOException {
        float altitude = 10.0F;
        float horizonRadius = 6_360.0F + altitude - 0.01F;
        float tangent = (float) -Math.sqrt(
                1.0 - 6_360.0F * 6_360.0F
                        / (horizonRadius * horizonRadius));
        float[][] cases = {
            {0.0F, 0.0F, 1.0F, 1.0F},
            {0.5F, 1.0F, 0.2F, 16.0F},
            {1.5F, 0.0F, 0.0F, 64.0F},
            {10.0F, 1.0F, -0.2F, 256.0F},
            {altitude, 0.0F, tangent + 1.0e-4F, 256.0F},
            {altitude, 1.0F, tangent - 1.0e-4F, 256.0F},
            {50.0F, 0.0F, 0.7F, 1_024.0F},
            {99.0F, 1.0F, -0.7F, 2_048.0F}
        };
        ByteBuffer input = ByteBuffer.allocateDirect(
                        cases.length * CASE_FLOATS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float[] value : cases) {
            for (float component : value) {
                input.putFloat(component);
            }
        }
        input.flip();
        ByteBuffer push = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cases.length)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "atmosphere_scale_properties.comp.spv");

        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * CASE_FLOATS * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                push);
        for (int caseIndex = 0;
                caseIndex < cases.length;
                ++caseIndex) {
            int base = caseIndex * CASE_FLOATS * Float.BYTES;
            for (int component = 0;
                    component < CASE_FLOATS;
                    ++component) {
                float error = output.getFloat(
                        base + component * Float.BYTES);
                assertTrue(
                        error <= 5.0e-4F,
                        "coordinate-scale case " + caseIndex
                                + " component " + component
                                + ": " + error);
            }
        }
    }
}
