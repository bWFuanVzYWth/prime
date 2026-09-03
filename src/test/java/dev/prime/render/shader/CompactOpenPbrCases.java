package dev.prime.render.shader;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;

final class CompactOpenPbrCases {
    static final int INPUT_WORDS = 4;
    static final int WITNESS_WORDS = 12;
    static final float[] ROUGHNESSES = {
        0.0F,
        Math.nextDown(0.01F),
        0.01F,
        Math.nextUp(0.01F),
        0.05F,
        0.25F,
        0.5F,
        1.0F
    };
    static final float[] COSINES = {
        1.0e-6F, 1.0e-4F, 0.001F, 0.01F, 0.1F, 0.5F, 0.9F, 1.0F
    };

    private CompactOpenPbrCases() {
    }

    static float boundaryRandom(
            int caseIndex,
            int dimension,
            float[] boundaries,
            SplittableRandom random) {
        int boundaryCases = boundaries.length * boundaries.length * boundaries.length;
        if (caseIndex >= boundaryCases) {
            return (float) random.nextDouble();
        }
        int divisor = 1;
        for (int index = 0; index < dimension; index++) {
            divisor *= boundaries.length;
        }
        return boundaries[(caseIndex / divisor) % boundaries.length];
    }

    static void put(ByteBuffer input, int caseIndex, int kind, float... values) {
        ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 0, 0, kind);
        if (values.length != INPUT_WORDS * 4 - 1) {
            throw new IllegalArgumentException("Unexpected compact OpenPBR input size");
        }
        for (int parameter = 0; parameter < values.length; parameter++) {
            int component = parameter + 1;
            ShaderTestBuffer.putFloat(input, caseIndex, INPUT_WORDS,
                    component / 4, component % 4, values[parameter]);
        }
    }
}
