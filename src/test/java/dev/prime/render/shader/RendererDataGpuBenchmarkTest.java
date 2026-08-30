package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-benchmark")
@ExtendWith(ShaderComputeExtension.class)
final class RendererDataGpuBenchmarkTest {
    private static final int LOCAL_SIZE = 64;
    private static ShaderComputeRunner runner;

    @Test
    void recordsAFullFrameCoordinateAndColorKernelTimestampBaseline() throws IOException {
        int width = Integer.getInteger("prime.gpuBenchmark.width", 1280);
        int height = Integer.getInteger("prime.gpuBenchmark.height", 720);
        int pixels = Math.multiplyExact(width, height);
        ByteBuffer input = input(width, height);
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_renderer_data_benchmark.comp.spv");
        ShaderComputeRunner.Workgroups workgroups =
                new ShaderComputeRunner.Workgroups((pixels + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        int outputBytes = Math.multiplyExact(workgroups.x(), 4 * Float.BYTES);

        int warmupIterations = 10;
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            runner.dispatchTimed(shader, input, outputBytes, workgroups, null);
        }
        long[] samples = new long[30];
        for (int iteration = 0; iteration < samples.length; iteration++) {
            ShaderComputeRunner.TimedDispatch result =
                    runner.dispatchTimed(shader, input, outputBytes, workgroups, null);
            assertTrue(Float.isFinite(result.output().getFloat(0)));
            samples[iteration] = result.gpuNanoseconds();
        }
        Arrays.sort(samples);
        long median = samples[samples.length / 2];
        long p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];
        ShaderComputeRunner.DeviceInfo device = runner.deviceInfo();
        Path report = Path.of(System.getProperty("prime.gpuBenchmark.report"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                {
                  "benchmark": "renderer-data-coordinate-color-full-frame",
                  "gpu": "%s",
                  "vendorId": %d,
                  "deviceId": %d,
                  "driverVersion": %d,
                  "vulkanApiVersion": %d,
                  "timestampPeriodNanoseconds": %s,
                  "extent": [%d, %d],
                  "warmupIterations": %d,
                  "measurementIterations": %d,
                  "metric": "vulkan-timestamp-nanoseconds",
                  "minimum": %d,
                  "median": %d,
                  "p95": %d,
                  "samples": %s
                }
                """.formatted(
                        json(device.name()),
                        device.vendorId(),
                        device.deviceId(),
                        device.driverVersion(),
                        device.apiVersion(),
                        Float.toString(device.timestampPeriodNanoseconds()),
                        width,
                        height,
                        warmupIterations,
                        samples.length,
                        samples[0],
                        median,
                        p95,
                        Arrays.toString(samples)));
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ByteBuffer input(int width, int height) {
        return ByteBuffer.allocateDirect(9 * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(width)
                .putInt(height)
                .putFloat(0.25F)
                .putFloat(-0.375F)
                .putFloat(0.625F)
                .putFloat(0.25F)
                .putFloat(0.25F)
                .putFloat(0.5F)
                .putFloat(0.75F)
                .flip();
    }
}
