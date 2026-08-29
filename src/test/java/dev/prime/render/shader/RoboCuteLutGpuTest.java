package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class RoboCuteLutGpuTest {
    private static final int CASE_COUNT = RoboCuteTestResources.GGX_LUT_WIDTH
            * RoboCuteTestResources.GGX_LUT_HEIGHT
            * RoboCuteTestResources.GGX_LUT_DEPTH;
    private static ShaderComputeRunner runner;
    private static ByteBuffer lut;

    @BeforeAll
    static void bindTransmissionGgxEnergy() throws IOException {
        lut = RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void gpuSamplesEveryAuthoritativeTransmissionGgxTexel() throws IOException {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, 1);
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "robocute_lut_roundtrip.comp.spv");
        ByteBuffer output = runner.dispatch(
                shader,
                input,
                Math.multiplyExact(CASE_COUNT, ShaderTestBuffer.WORD_BYTES),
                CASE_COUNT);
        for (int texel = 0; texel < CASE_COUNT; texel++) {
            for (int channel = 0;
                    channel < RoboCuteTestResources.GGX_LUT_CHANNELS;
                    channel++) {
                int halfOffset = (texel * RoboCuteTestResources.GGX_LUT_CHANNELS
                        + channel) * Short.BYTES;
                float expected = Float.float16ToFloat(lut.getShort(halfOffset));
                float actual = ShaderTestBuffer.getFloat(
                        output, texel, 1, 0, channel);
                float tolerance = 2.0e-6F + 2.0e-6F
                        * Math.max(Math.abs(expected), Math.abs(actual));
                assertTrue(
                        Float.isFinite(actual)
                                && Math.abs(expected - actual) <= tolerance,
                        "GGX LUT texel "
                                + texel
                                + " channel "
                                + channel
                                + " differs: "
                                + expected
                                + " vs "
                                + actual);
            }
        }
    }
}
