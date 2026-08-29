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
final class SunShadowHierarchyGpuTest {
    private static final int CASE_FLOATS = 6;
    private static ShaderComputeRunner runner;

    @Test
    void leafIntegrationResolvesBlockerCrossingsAnalytically() throws IOException {
        float[][] cases = {
            {10.0F, 0.0F, 0.0F, 4.0F, 5.0F, 0.02F},
            {0.0F, 0.0F, 0.0F, 4.0F, 5.0F, 0.02F},
            {0.0F, 2.0F, 0.0F, 4.0F, 4.0F, 0.0F},
            {8.0F, -2.0F, 0.0F, 4.0F, 4.0F, 0.0F},
            {0.0F, 1.0F, 2.0F, 6.0F, 4.0F, 0.0F},
            {0.0F, 0.0F, 1.0F, 3.0F, -1.0e20F, 0.02F},
            {0.0F, 0.0F, 1.0F, 3.0F, 1.0e20F, 0.02F},
            {4.99F, 0.0F, 2.0F, 5.0F, 5.0F, 0.02F}
        };
        float[] expected = {4.0F, 0.0F, 2.0F, 2.0F, 2.0F, 2.0F, 0.0F, 3.0F};
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
                "sun_shadow_hierarchy_properties.comp.spv");
        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                push);
        for (int index = 0; index < cases.length; index++) {
            assertEquals(
                    expected[index],
                    output.getFloat(index * Float.BYTES),
                    1.0e-5F,
                    shader.getFileName() + " case " + index);
        }
    }
}
