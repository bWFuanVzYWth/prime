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
final class AtmosphereSegmentIntegralGpuTest {
    private static final int COMPONENTS = 4;
    private static ShaderComputeRunner runner;

    @Test
    void homogeneousSegmentIntegralMatchesDoubleOracleAcrossItsNumericalBranches()
            throws IOException {
        float[][] cases = {
            {0.0F, 1.0e-12F, 1.0e-6F, 37.0F},
            {1.0e-12F, 1.0e-8F, 1.0e-4F, 1.0F},
            {0.049999F, 0.05F, 0.050001F, 1.0F},
            {0.5F, 5.0F, 50.0F, 1.0F},
            {1.0e-4F, 0.01F, 2.0F, 25.0F},
            {100.0F, 1_000.0F, 10_000.0F, 0.5F}
        };
        ByteBuffer input = ByteBuffer.allocateDirect(
                        cases.length * COMPONENTS * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float[] value : cases) {
            for (float component : value) input.putFloat(component);
        }
        input.flip();
        ByteBuffer push = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cases.length)
                .flip();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "atmosphere_segment_integral.comp.spv");

        ByteBuffer output = runner.dispatch(
                shader,
                input,
                cases.length * COMPONENTS * Float.BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                push);
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            float previous = Float.POSITIVE_INFINITY;
            for (int component = 0; component < 3; component++) {
                float actual = output.getFloat(
                        (caseIndex * COMPONENTS + component) * Float.BYTES);
                double expected = oracle(cases[caseIndex][component], cases[caseIndex][3]);
                assertTrue(Float.isFinite(actual) && actual >= 0.0F);
                assertEquals(
                        expected,
                        actual,
                        Math.max(2.0e-6, Math.abs(expected) * 3.0e-6),
                        "case " + caseIndex + " component " + component);
                assertTrue(actual <= previous + 2.0e-6F);
                previous = actual;
            }
            assertEquals(
                    output.getFloat(caseIndex * COMPONENTS * Float.BYTES),
                    output.getFloat((caseIndex * COMPONENTS + 3) * Float.BYTES),
                    0.0F);
        }
        int thresholdOffset = 2 * COMPONENTS * Float.BYTES;
        float below = output.getFloat(thresholdOffset);
        float above = output.getFloat(thresholdOffset + 2 * Float.BYTES);
        assertTrue(Math.abs(below - above) < 3.0e-6F);
    }

    private static double oracle(double extinction, double length) {
        if (extinction == 0.0) return length;
        return -Math.expm1(-extinction * length) / extinction;
    }
}
