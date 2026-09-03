package dev.prime.render.shader;

import static dev.prime.render.shader.CompactOpenPbrCases.COSINES;
import static dev.prime.render.shader.CompactOpenPbrCases.INPUT_WORDS;
import static dev.prime.render.shader.CompactOpenPbrCases.ROUGHNESSES;
import static dev.prime.render.shader.CompactOpenPbrCases.WITNESS_WORDS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class CompactOpenPbrOpaqueGpuTest {
    private static final long SEED = 0x0BE7_5B12_F0A4_0111L;
    private static final int CASES_PER_KIND = 8_192;
    private static final int KIND_COUNT = 3;
    private static final int CASE_COUNT = CASES_PER_KIND * KIND_COUNT;
    private static final int MIXED_CASE_COUNT = 16_384;
    private static final int MIXED_WITNESS_WORDS = 10;

    private static final float[] IORS = {
        1.0F, 1.0F / 1.5F, 1.0F / 1.333F, 1.333F, 1.5F, 2.4F
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
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_opaque_properties.comp.spv",
                createCases(),
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    @Test
    void fractionalThinSubsurfacePreservesOpenPbrCompositionProperties() throws IOException {
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_mixed_properties.comp.spv",
                createMixedCases(),
                MIXED_CASE_COUNT,
                INPUT_WORDS,
                MIXED_WITNESS_WORDS,
                SEED ^ 0x51B5_0AFC_E112_009DL);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < CASES_PER_KIND; localCase++) {
                int caseIndex = kind * CASES_PER_KIND + localCase;
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
                CompactOpenPbrCases.put(
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
            }
        }
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
            CompactOpenPbrCases.put(
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
        return CompactOpenPbrCases.boundaryRandom(
                caseIndex, dimension, RANDOM_BOUNDARIES, random);
    }
}
