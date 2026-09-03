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
final class CompactOpenPbrFoliageGpuTest {
    private static final long SEED = 0x4F50_4252_464F_4C49L;
    private static final int CASES_PER_KIND = 4_096;
    private static final int KIND_COUNT = 3;
    private static final int CASE_COUNT = CASES_PER_KIND * KIND_COUNT;

    private static final float[] IORS = {
        1.0F, 1.0F / 1.5F, 1.333F, 1.45F, 1.5F, 2.4F
    };
    private static final float[] SUBSURFACE_WEIGHTS = {
        0.0F, Math.nextUp(0.0F), 0.25F, 0.5F, Math.nextDown(1.0F), 1.0F
    };
    private static final float[] RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.15F, 0.5F, Math.nextDown(1.0F)
    };

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void bindTransmissionGgxEnergy() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void compactFoliagePreservesDielectricSubsurfaceAndConductorProperties()
            throws IOException {
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_foliage_properties.comp.spv",
                createCases(),
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < CASES_PER_KIND; localCase++) {
                int caseIndex = kind * CASES_PER_KIND + localCase;
                float outgoingCosine = COSINES[(localCase * 5 + 3) % COSINES.length];
                if ((localCase & 1) != 0) {
                    outgoingCosine = -outgoingCosine;
                }
                CompactOpenPbrCases.put(
                        input,
                        caseIndex,
                        kind,
                        ROUGHNESSES[localCase % ROUGHNESSES.length],
                        IORS[(localCase * 3 + kind) % IORS.length],
                        SUBSURFACE_WEIGHTS[(localCase * 7 + 1) % SUBSURFACE_WEIGHTS.length],
                        COSINES[localCase % COSINES.length],
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        outgoingCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        randomValue(localCase, 0, random),
                        randomValue(localCase, 1, random),
                        randomValue(localCase, 2, random),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        IORS[(localCase * 11 + 2) % IORS.length],
                        (float) random.nextDouble(0.0, 1_000.0));
            }
        }
        return input;
    }

    private static float randomValue(
            int caseIndex, int dimension, SplittableRandom random) {
        return CompactOpenPbrCases.boundaryRandom(
                caseIndex, dimension, RANDOM_BOUNDARIES, random);
    }
}
