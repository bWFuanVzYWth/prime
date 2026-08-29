package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class CompactOpenPbrOpaqueGpuTest {
    private static final long SEED = 0x0BE7_5B12_F0A4_0111L;
    private static final int INPUT_WORDS = 4;
    private static final int WITNESS_WORDS = 12;
    private static final int CASES_PER_KIND = 8_192;
    private static final int KIND_COUNT = 3;
    private static final int CASE_COUNT = CASES_PER_KIND * KIND_COUNT;
    private static final int MIXED_CASE_COUNT = 16_384;
    private static final int MIXED_WITNESS_WORDS = 10;

    private static final float[] ROUGHNESSES = {
        0.0F,
        Math.nextDown(0.01F),
        0.01F,
        Math.nextUp(0.01F),
        0.05F,
        0.25F,
        0.5F,
        1.0F
    };
    private static final float[] IORS = {
        1.0F, 1.0F / 1.5F, 1.0F / 1.333F, 1.333F, 1.5F, 2.4F
    };
    private static final float[] COSINES = {
        1.0e-6F, 1.0e-4F, 0.001F, 0.01F, 0.1F, 0.5F, 0.9F, 1.0F
    };
    private static final float[] RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] SUBSURFACE_WEIGHTS = {
        Math.nextUp(0.0F),
        0.25F,
        0.5F,
        0.75F,
        Math.nextDown(1.0F)
    };

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void bindTransmissionGgxEnergy() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void compactOpaqueSubsetPreservesOpenPbrProperties()
            throws IOException {
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "compact_openpbr_opaque_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                createCases(),
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    @Test
    void fractionalThinSubsurfacePreservesOpenPbrCompositionProperties() throws IOException {
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "compact_openpbr_mixed_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                createMixedCases(),
                MIXED_CASE_COUNT,
                INPUT_WORDS,
                MIXED_WITNESS_WORDS,
                SEED ^ 0x51B5_0AFC_E112_009DL);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        int caseIndex = 0;
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < CASES_PER_KIND; localCase++) {
                float viewCosine = COSINES[localCase % COSINES.length];
                float outgoingCosine = COSINES[(localCase * 5 + 3) % COSINES.length];
                if (kind >= 2 && (localCase & 1) != 0) {
                    outgoingCosine = -outgoingCosine;
                }
                float randomX = randomValue(localCase, 0, random);
                float randomY = randomValue(localCase, 1, random);
                float randomZ = randomValue(localCase, 2, random);
                float colorR = (float) random.nextDouble(0.001, 1.0);
                float colorG = (float) random.nextDouble(0.001, 1.0);
                float colorB = (float) random.nextDouble(0.001, 1.0);
                float anisotropy = (float) random.nextDouble(-0.95, 0.95);
                float inverseOutsideIor = IORS[(localCase * 7 + 1) % IORS.length];
                putCase(
                        input,
                        caseIndex,
                        kind,
                        ROUGHNESSES[localCase % ROUGHNESSES.length],
                        IORS[(localCase * 3 + kind) % IORS.length],
                        viewCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        outgoingCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        randomX,
                        randomY,
                        randomZ,
                        colorR,
                        colorG,
                        colorB,
                        anisotropy,
                        inverseOutsideIor,
                        (float) random.nextDouble(0.0, 1_000.0));
                caseIndex++;
            }
        }
        assertEquals(CASE_COUNT, caseIndex, "compact OpenPBR case count");
        return input;
    }

    private static ByteBuffer createMixedCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(MIXED_CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED ^ 0x51B5_0AFC_E112_009DL);
        for (int caseIndex = 0; caseIndex < MIXED_CASE_COUNT; caseIndex++) {
            float outgoingCosine = COSINES[(caseIndex * 5 + 3) % COSINES.length];
            if ((caseIndex & 1) != 0) {
                outgoingCosine = -outgoingCosine;
            }
            putCase(
                    input,
                    caseIndex,
                    1,
                    ROUGHNESSES[caseIndex % ROUGHNESSES.length],
                    IORS[(caseIndex * 3) % IORS.length],
                    COSINES[caseIndex % COSINES.length],
                    (float) (2.0 * Math.PI * random.nextDouble()),
                    outgoingCosine,
                    (float) (2.0 * Math.PI * random.nextDouble()),
                    randomValue(caseIndex, 0, random),
                    randomValue(caseIndex, 1, random),
                    randomValue(caseIndex, 2, random),
                    (float) random.nextDouble(0.001, 1.0),
                    (float) random.nextDouble(0.001, 1.0),
                    (float) random.nextDouble(0.001, 1.0),
                    SUBSURFACE_WEIGHTS[caseIndex % SUBSURFACE_WEIGHTS.length],
                    (float) random.nextDouble(-0.95, 0.95),
                    IORS[(caseIndex * 7 + 1) % IORS.length]);
        }
        return input;
    }

    private static float randomValue(
            int caseIndex, int dimension, SplittableRandom random) {
        if (caseIndex < 64) {
            int divisor = 1;
            for (int index = 0; index < dimension; index++) {
                divisor *= RANDOM_BOUNDARIES.length;
            }
            return RANDOM_BOUNDARIES[(caseIndex / divisor) % RANDOM_BOUNDARIES.length];
        }
        return (float) random.nextDouble();
    }

    private static void putCase(
            ByteBuffer input,
            int caseIndex,
            int kind,
            float... values) {
        ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 0, 0, kind);
        if (values.length != INPUT_WORDS * 4 - 1) {
            throw new IllegalArgumentException("Unexpected compact OpenPBR input size");
        }
        for (int parameter = 0; parameter < values.length; parameter++) {
            int component = parameter + 1;
            ShaderTestBuffer.putFloat(
                    input,
                    caseIndex,
                    INPUT_WORDS,
                    component / 4,
                    component % 4,
                    values[parameter]);
        }
    }
}
