package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

@Tag("gpu-ray-tracing")
final class RayTracingLifecycleGpuTest {
    @Test
    void buildsAccelerationStructuresTracesHitAndMissAndReleasesTwice()
            throws Exception {
        RayTracingTestRunner runner;
        try {
            runner = RayTracingTestRunner.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.rayTracingTests.required")) {
                throw new AssertionError(
                        "A validated Vulkan ray-tracing device is required", exception);
            }
            throw new TestAbortedException(
                    "Vulkan ray tracing is unavailable: " + exception.getMessage(), exception);
        }
        try (runner) {
            String directory = System.getProperty("prime.test.slangShaderDirectory");
            if (directory == null || directory.isBlank()) {
                throw new IllegalStateException(
                        "prime.test.slangShaderDirectory is not configured");
            }
            ByteBuffer output = runner.trace(Path.of(directory));

            assertEquals(6, word(output, 0, 0), "hit must run any-hit and closest-hit");
            assertEquals(0, word(output, 0, 1));
            assertEquals(7, word(output, 0, 2));
            assertEquals(0.25F, value(output, 0, 3), 1.0e-6F);
            assertEquals(0.5F, value(output, 0, 4), 1.0e-6F);
            assertEquals(1.0F, value(output, 0, 5), 1.0e-6F);

            assertEquals(1, word(output, 1, 0), "miss shader must own the miss payload");
            assertEquals(-1, word(output, 1, 1));
            assertEquals(-1, word(output, 1, 2));
            assertEquals(-1.0F, value(output, 1, 3), 0.0F);
            assertEquals(-1.0F, value(output, 1, 4), 0.0F);
            assertEquals(-1.0F, value(output, 1, 5), 0.0F);

            runner.close();
            runner.close();
        }
    }

    private static int word(ByteBuffer output, int ray, int word) {
        return output.getInt((ray * RayTracingTestRunner.WORDS_PER_RAY + word)
                * Integer.BYTES);
    }

    private static float value(ByteBuffer output, int ray, int word) {
        return output.getFloat((ray * RayTracingTestRunner.WORDS_PER_RAY + word)
                * Integer.BYTES);
    }
}
