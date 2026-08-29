package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
final class AtmosphereEpipolarShadowProfileGpuTest {
    private static final int CASE_FLOATS = 12;
    private static final int OUTPUT_FLOATS = 7;
    private static ShaderComputeRunner runner;

    @Test
    void profilesEveryLeafAndKeepsInvalidDirectionsShadowed()
            throws IOException {
        float diagonal = (float) (1.0 / Math.sqrt(2.0));
        float[][] cases = {
            {
                0.25F, -0.25F, 1.0F, 0.0F,
                1.0F, 0.0F, 1.0F, 5.0F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                0.0F, 0.0F, diagonal, diagonal,
                diagonal, diagonal, 0.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                0.0F, 0.0F, 1.0F, 0.0F,
                1.0F, 0.001F, 1.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                0.0F, 0.0F, 1.0F, 0.0F,
                -1.0F, 0.0F, 1.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                0.0F, 0.0F, 1.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 5.0F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                4_096.0F, -4_096.0F, -1.0F, 0.0F,
                -1.0F, 0.0F, 1.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                -4_096.0F, 4_096.0F, -diagonal, -diagonal,
                -diagonal, -diagonal, 0.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            },
            {
                1_000_000.0F, -1_000_000.0F, diagonal, -diagonal,
                diagonal, -diagonal, 0.0F, -1.0e20F,
                0.0F, 10.0F, 0.0F, 1.0F
            }
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
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "atmosphere_epipolar_shadow_profile.comp.spv");

        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * OUTPUT_FLOATS * Float.BYTES,
                new ShaderComputeRunner.Workgroups(cases.length, 1, 1),
                null);
        for (int caseIndex = 0;
                caseIndex < cases.length;
                ++caseIndex) {
            int base = caseIndex * OUTPUT_FLOATS * Float.BYTES;
            for (int cascade = 0; cascade < 5; ++cascade) {
                assertTrue(
                        output.getFloat(base + cascade * Float.BYTES) > 0.0F,
                        "profile overflow case " + caseIndex
                                + " cascade " + cascade);
            }
            float directionMax = Math.max(
                    Math.abs(cases[caseIndex][2]),
                    Math.abs(cases[caseIndex][3]));
            assertEquals(
                    127.0F / directionMax,
                    output.getFloat(base + 5 * Float.BYTES),
                    2.0e-4F,
                    "near profile extent case " + caseIndex);
        }
        assertEquals(
                255.0F,
                output.getFloat(0),
                0.0F,
                "axis-aligned leaf count");
        assertEquals(
                254.0F,
                output.getFloat(OUTPUT_FLOATS * Float.BYTES),
                0.0F,
                "diagonal leaf count");
        assertEquals(
                0.502F,
                output.getFloat(6 * Float.BYTES),
                1.0e-5F,
                "analytic blocker crossing");
        for (int caseIndex = 1; caseIndex < 5; ++caseIndex) {
            float expected = caseIndex == 1 ? 1.0F : 0.0F;
            int offset = (caseIndex * OUTPUT_FLOATS + 6) * Float.BYTES;
            assertEquals(
                    expected,
                    output.getFloat(offset),
                    1.0e-6F,
                    "visibility case " + caseIndex);
        }
        for (int caseIndex = 5; caseIndex < cases.length; ++caseIndex) {
            assertEquals(
                    1.0F,
                    output.getFloat(
                            (caseIndex * OUTPUT_FLOATS + 6)
                                    * Float.BYTES),
                    1.0e-6F,
                    "large-coordinate traversal case " + caseIndex);
        }
    }
}
