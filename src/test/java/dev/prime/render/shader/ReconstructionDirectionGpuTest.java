package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class ReconstructionDirectionGpuTest extends GpuShaderTest {
    @Test
    void interfaceScratchCannotOverwriteAdjacentPixelOrFollowingQueue() throws Exception {
        ByteBuffer input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(1).putInt(1).putLong(0).flip();
        ByteBuffer output = runner.dispatch("reconstruction_direction.comp.spv", input, 80, 1);
        for (int pixel = 0; pixel < 2; pixel++) {
            for (int component = 0; component < 8; component++) {
                assertEquals(pixel * 100f + component, output.getFloat((pixel * 8 + component) * 4));
            }
        }
        for (int word = 16; word < 20; word++) assertEquals(-1f, output.getFloat(word * 4));
    }

    @Test
    void areaAndContinuationMomentsRespectEachSourcesDemodulatedEnergy() throws Exception {
        int count = 2048;
        float[][][] values = new float[count][5][3];
        SplittableRandom random = new SplittableRandom(0x53554e4755494445L);
        ByteBuffer input = ByteBuffer.allocateDirect(16 + count * 80).order(ByteOrder.LITTLE_ENDIAN);
        input.putInt(count).putInt(0).putLong(0);
        for (int i = 0; i < count; i++) {
            for (int source = 0; source < 3; source++) {
                for (int channel = 0; channel < 3; channel++) {
                    values[i][source][channel] = source == 2
                            ? (float) random.nextDouble(0.02, 1)
                            : (i < 3 && source != i ? 0 : (float) random.nextDouble(0, 20));
                }
            }
            for (int direction = 3; direction < 5; direction++) {
                double length = 0;
                for (int channel = 0; channel < 3; channel++) {
                    float v = (float) random.nextDouble(-1, 1);
                    values[i][direction][channel] = v;
                    length += v * v;
                }
                for (int channel = 0; channel < 3; channel++) values[i][direction][channel] /= (float) Math.sqrt(length);
            }
            for (float[] vector : values[i]) {
                for (float value : vector) input.putFloat(value);
                input.putFloat(0);
            }
        }
        input.flip();
        ByteBuffer output = runner.dispatch("reconstruction_direction.comp.spv", input, count * 16, count);
        for (int i = 0; i < count; i++) {
            double[] energy = new double[2];
            for (int source = 0; source < 2; source++) {
                for (int c = 0; c < 3; c++) energy[source] += values[i][source][c] / values[i][2][c] * (c == 1 ? .5 : .25);
            }
            double total = energy[0] + energy[1];
            for (int c = 0; c < 3; c++) {
                double expected = 0;
                for (int source = 0; source < 2; source++) expected += energy[source] * values[i][3 + source][c];
                expected = total == 0 ? 0 : expected / total;
                assertEquals(expected, output.getFloat(i * 16 + c * 4), 0.00001);
            }
        }
    }
}
