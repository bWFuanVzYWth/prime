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
final class AtmosphereAerialPrefixGpuTest {
    private static final int GROUP_COUNT = 2;
    private static final int VALUE_COUNT = 4;
    private static final int CHANNEL_COUNT = 4;
    private static final int VOXEL_BYTES =
            VALUE_COUNT * CHANNEL_COUNT * Float.BYTES;
    private static ShaderComputeRunner runner;

    @Test
    void parallelPrefixMatchesOrderedAerialComposition() throws IOException {
        int depth = ShaderAbi.ATMOSPHERE_AERIAL_DEPTH;
        ByteBuffer input = ByteBuffer.allocateDirect(depth * VOXEL_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        double[][][] radiance = new double[GROUP_COUNT][depth][CHANNEL_COUNT];
        double[][][] transmittance = new double[GROUP_COUNT][depth][CHANNEL_COUNT];
        for (int slice = 0; slice < depth; slice++) {
            for (int group = 0; group < GROUP_COUNT; group++) {
                for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                    double segmentRadiance = 0.00025
                            * (1 + ((slice * 17 + group * 31 + channel * 7) % 29));
                    double segmentTransmittance = 0.972
                            + 0.00035
                                    * ((slice * 11 + group * 13 + channel * 5) % 71);
                    radiance[group][slice][channel] = segmentRadiance;
                    transmittance[group][slice][channel] = segmentTransmittance;
                }
            }
            putGroup(input, radiance[0][slice], transmittance[0][slice]);
            putGroup(input, radiance[1][slice], transmittance[1][slice]);
        }
        input.flip();

        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "atmosphere_aerial_prefix.comp.spv");
        ByteBuffer output = runner.dispatch(
                shader,
                input,
                depth * VOXEL_BYTES,
                new ShaderComputeRunner.Workgroups(1, 1, 1),
                null);
        double[][] cumulativeRadiance = new double[GROUP_COUNT][CHANNEL_COUNT];
        double[][] cumulativeTransmittance = new double[GROUP_COUNT][CHANNEL_COUNT];
        for (int group = 0; group < GROUP_COUNT; group++) {
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                cumulativeTransmittance[group][channel] = 1.0;
            }
        }
        for (int slice = 0; slice < depth; slice++) {
            for (int group = 0; group < GROUP_COUNT; group++) {
                for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                    cumulativeRadiance[group][channel] +=
                            cumulativeTransmittance[group][channel]
                                    * radiance[group][slice][channel];
                    cumulativeTransmittance[group][channel] *=
                            transmittance[group][slice][channel];
                    int value = slice * VALUE_COUNT + group * 2;
                    int radianceOffset =
                            (value * CHANNEL_COUNT + channel) * Float.BYTES;
                    int transmittanceOffset =
                            ((value + 1) * CHANNEL_COUNT + channel) * Float.BYTES;
                    assertEquals(
                            cumulativeRadiance[group][channel],
                            output.getFloat(radianceOffset),
                            2.0e-5,
                            "radiance group " + group + ", slice " + slice
                                    + ", channel " + channel);
                    assertEquals(
                            cumulativeTransmittance[group][channel],
                            output.getFloat(transmittanceOffset),
                            2.0e-5,
                            "transmittance group " + group + ", slice " + slice
                                    + ", channel " + channel);
                }
            }
        }
    }

    private static void putGroup(
            ByteBuffer input, double[] radiance, double[] transmittance) {
        for (double value : radiance) {
            input.putFloat((float) value);
        }
        for (double value : transmittance) {
            input.putFloat((float) value);
        }
    }
}
