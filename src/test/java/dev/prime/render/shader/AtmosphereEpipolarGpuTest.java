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
final class AtmosphereEpipolarGpuTest {
    private static final int CASE_FLOATS = 4;
    private static final int OUTPUT_FLOATS = 4;
    private static ShaderComputeRunner runner;

    @Test
    void screenPointsRoundTripForVisibleAndOffscreenEpipoles()
            throws IOException {
        float[][] cases = {
            {0.0F, 0.0F, 0.75F, 0.25F},
            {0.2F, -0.4F, -0.8F, 0.9F},
            {-3.0F, 0.1F, -0.5F, -0.7F},
            {4.0F, 3.0F, 0.9F, -0.9F},
            {0.3F, 5.0F, -0.9F, 0.9F},
            {-2000.0F, 0.0F, 0.4F, -0.6F},
            {-16_384.0F, 0.0F, 0.4F, 0.6F}
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
                "atmosphere_epipolar_properties.comp.spv");

        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * OUTPUT_FLOATS * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                push);
        for (int index = 0; index < cases.length; index++) {
            int offset = index * OUTPUT_FLOATS * Float.BYTES;
            assertEquals(
                    cases[index][2],
                    output.getFloat(offset),
                    2.0e-3F,
                    "x case " + index);
            assertEquals(
                    cases[index][3],
                    output.getFloat(offset + Float.BYTES),
                    2.0e-3F,
                    "y case " + index);
            assertEquals(
                    0.0F,
                    output.getFloat(offset + 2 * Float.BYTES),
                    2.0e-4F,
                    "boundary case " + index);
            assertEquals(
                    1.0F,
                    output.getFloat(offset + 3 * Float.BYTES),
                    0.0F,
                    "valid case " + index);
        }
    }
}
