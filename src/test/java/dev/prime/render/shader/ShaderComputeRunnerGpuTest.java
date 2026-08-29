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
final class ShaderComputeRunnerGpuTest {
    private static ShaderComputeRunner runner;

    @Test
    void completeLifecycleUploadsBindsDispatchesReadsBackAndReleasesTwice()
            throws IOException {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel = 0; pixel < 4; pixel++) {
            for (int channel = 0; channel < 4; channel++) {
                pixels.putFloat(pixel * 10.0F + channel);
            }
        }
        pixels.flip();
        runner.bindSampledImage(
                2,
                ShaderComputeRunner.ImageDimension.TWO_D,
                ShaderComputeRunner.ImageFormat.R32G32B32A32_SFLOAT,
                pixels,
                2,
                2,
                1);
        runner.bindStorageImage(
                3,
                ShaderComputeRunner.ImageFormat.R32G32B32A32_SFLOAT,
                2,
                2);

        ByteBuffer input = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0);
        input.flip();
        ByteBuffer push = ByteBuffer.allocateDirect(4 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(2.0F)
                .putFloat(0.5F)
                .putFloat(0.0F)
                .putFloat(0.0F);
        push.flip();
        ByteBuffer output = runner.dispatch(
                Path.of(
                        System.getProperty("prime.test.slangShaderDirectory"),
                        "shader_compute_runner_contract.comp.spv"),
                input,
                4 * 4 * Float.BYTES,
                new ShaderComputeRunner.Workgroups(2, 2, 1),
                push);
        for (int pixel = 0; pixel < 4; pixel++) {
            for (int channel = 0; channel < 4; channel++) {
                assertEquals(
                        (pixel * 10.0F + channel) * 2.0F + 0.5F,
                        output.getFloat((pixel * 4 + channel) * Float.BYTES),
                        0.0F);
            }
        }
        runner.close();
        runner.close();
    }
}
