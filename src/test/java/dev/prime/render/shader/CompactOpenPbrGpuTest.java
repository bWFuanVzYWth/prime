package dev.prime.render.shader;

import static dev.prime.render.shader.CompactOpenPbrCases.COSINES;
import static dev.prime.render.shader.CompactOpenPbrCases.INPUT_WORDS;
import static dev.prime.render.shader.CompactOpenPbrCases.ROUGHNESSES;
import static dev.prime.render.shader.CompactOpenPbrCases.WITNESS_WORDS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class CompactOpenPbrGpuTest extends GpuShaderTest {
    private static final long OPAQUE_SEED = 0x0BE7_5B12_F0A4_0111L;
    private static final int OPAQUE_CASES_PER_KIND = 8_192;
    private static final int OPAQUE_KIND_COUNT = 3;
    private static final int OPAQUE_CASE_COUNT = OPAQUE_CASES_PER_KIND * OPAQUE_KIND_COUNT;
    private static final int MIXED_CASE_COUNT = 16_384;
    private static final int MIXED_WITNESS_WORDS = 10;

    private static final float[] OPAQUE_IORS = {
        1.0F, 1.0F / 1.5F, 1.0F / 1.333F, 1.333F, 1.5F, 2.4F
    };
    private static final float[] OPAQUE_RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] SUBSURFACE_WEIGHTS = {
        Math.nextUp(0.0F),
        0.25F,
        0.5F,
        0.75F,
        Math.nextDown(1.0F)
    };
    private static final long TRANSMISSION_SEED = 0x7A61_5A10_0E31_091DL;
    private static final int TRANSMISSION_CASES_PER_KIND = 4_096;
    private static final int TRANSMISSION_KIND_COUNT = 9;
    private static final int TRANSMISSION_CASE_COUNT =
            TRANSMISSION_CASES_PER_KIND * TRANSMISSION_KIND_COUNT;
    private static final float[] MATERIAL_IORS = {1.0F, 1.333F, 1.5F, 2.4F};
    private static final float[] INVERSE_OUTSIDE_IORS = {
        1.0F, 1.0F / 1.333F, 1.0F / 1.5F, 1.0F / 2.4F
    };
    private static final float[] TRANSMISSION_RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final long FOLIAGE_SEED = 0x4F50_4252_464F_4C49L;
    private static final int FOLIAGE_CASES_PER_KIND = 4_096;
    private static final int FOLIAGE_KIND_COUNT = 3;
    private static final int FOLIAGE_CASE_COUNT =
            FOLIAGE_CASES_PER_KIND * FOLIAGE_KIND_COUNT;
    private static final float[] FOLIAGE_IORS = {
        1.0F, 1.0F / 1.5F, 1.333F, 1.45F, 1.5F, 2.4F
    };
    private static final float[] FOLIAGE_SUBSURFACE_WEIGHTS = {
        0.0F, Math.nextUp(0.0F), 0.25F, 0.5F, Math.nextDown(1.0F), 1.0F
    };
    private static final float[] FOLIAGE_RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.15F, 0.5F, Math.nextDown(1.0F)
    };

    @BeforeAll
    void bindTransmissionGgxEnergy() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void compactOpaqueSubsetPreservesOpenPbrProperties()
            throws IOException {
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_opaque_properties.comp.spv",
                createOpaqueCases(),
                OPAQUE_CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                OPAQUE_SEED);
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
                OPAQUE_SEED ^ 0x51B5_0AFC_E112_009DL);
    }

    @Test
    void compactTransmissionPreservesPropertiesAcrossTopologyAndSamplingFlags()
            throws IOException {
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_transmission_properties.comp.spv",
                createTransmissionCases(),
                TRANSMISSION_CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                TRANSMISSION_SEED);
    }

    @Test
    void compactFoliagePreservesDielectricSubsurfaceAndConductorProperties()
            throws IOException {
        ShaderPropertyBatch.assertProperties(
                runner,
                "compact_openpbr_foliage_properties.comp.spv",
                createFoliageCases(),
                FOLIAGE_CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                FOLIAGE_SEED);
    }

    private static ByteBuffer createOpaqueCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(OPAQUE_CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(OPAQUE_SEED);
        for (int kind = 0; kind < OPAQUE_KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < OPAQUE_CASES_PER_KIND; localCase++) {
                int caseIndex = kind * OPAQUE_CASES_PER_KIND + localCase;
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
                float inverseOutsideIor = OPAQUE_IORS[
                        (localCase * 7 + 1) % OPAQUE_IORS.length];
                CompactOpenPbrCases.put(
                        input,
                        caseIndex,
                        kind,
                        ROUGHNESSES[localCase % ROUGHNESSES.length],
                        OPAQUE_IORS[(localCase * 3 + kind) % OPAQUE_IORS.length],
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
        SplittableRandom random =
                new SplittableRandom(OPAQUE_SEED ^ 0x51B5_0AFC_E112_009DL);
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
                    OPAQUE_IORS[(caseIndex * 3) % OPAQUE_IORS.length],
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
                    OPAQUE_IORS[(caseIndex * 7 + 1) % OPAQUE_IORS.length]);
        }
        return input;
    }

    private static ByteBuffer createTransmissionCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(TRANSMISSION_CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(TRANSMISSION_SEED);
        for (int kind = 0; kind < TRANSMISSION_KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < TRANSMISSION_CASES_PER_KIND; localCase++) {
                int caseIndex = kind * TRANSMISSION_CASES_PER_KIND + localCase;
                float outgoingCosine = COSINES[(localCase * 5 + 3) % COSINES.length];
                if ((localCase & 1) != 0) {
                    outgoingCosine = -outgoingCosine;
                }
                CompactOpenPbrCases.put(
                        input,
                        caseIndex,
                        kind,
                        ROUGHNESSES[localCase % ROUGHNESSES.length],
                        MATERIAL_IORS[(localCase * 3 + kind) % MATERIAL_IORS.length],
                        COSINES[localCase % COSINES.length],
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        outgoingCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        boundaryRandom(
                                localCase, 0, TRANSMISSION_RANDOM_BOUNDARIES, random),
                        boundaryRandom(
                                localCase, 1, TRANSMISSION_RANDOM_BOUNDARIES, random),
                        boundaryRandom(
                                localCase, 2, TRANSMISSION_RANDOM_BOUNDARIES, random),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        INVERSE_OUTSIDE_IORS[
                                (localCase * 7 + 1) % INVERSE_OUTSIDE_IORS.length],
                        (float) random.nextDouble(0.0, 1_000.0),
                        (localCase & 2) != 0 ? 1.0F : 0.0F);
            }
        }
        return input;
    }

    private static ByteBuffer createFoliageCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(FOLIAGE_CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(FOLIAGE_SEED);
        for (int kind = 0; kind < FOLIAGE_KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < FOLIAGE_CASES_PER_KIND; localCase++) {
                int caseIndex = kind * FOLIAGE_CASES_PER_KIND + localCase;
                float outgoingCosine = COSINES[(localCase * 5 + 3) % COSINES.length];
                if ((localCase & 1) != 0) {
                    outgoingCosine = -outgoingCosine;
                }
                CompactOpenPbrCases.put(
                        input,
                        caseIndex,
                        kind,
                        ROUGHNESSES[localCase % ROUGHNESSES.length],
                        FOLIAGE_IORS[(localCase * 3 + kind) % FOLIAGE_IORS.length],
                        FOLIAGE_SUBSURFACE_WEIGHTS[
                                (localCase * 7 + 1) % FOLIAGE_SUBSURFACE_WEIGHTS.length],
                        COSINES[localCase % COSINES.length],
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        outgoingCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()),
                        boundaryRandom(localCase, 0, FOLIAGE_RANDOM_BOUNDARIES, random),
                        boundaryRandom(localCase, 1, FOLIAGE_RANDOM_BOUNDARIES, random),
                        boundaryRandom(localCase, 2, FOLIAGE_RANDOM_BOUNDARIES, random),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        (float) random.nextDouble(0.001, 1.0),
                        FOLIAGE_IORS[(localCase * 11 + 2) % FOLIAGE_IORS.length],
                        (float) random.nextDouble(0.0, 1_000.0));
            }
        }
        return input;
    }

    private static float randomValue(
            int caseIndex, int dimension, SplittableRandom random) {
        return CompactOpenPbrCases.boundaryRandom(
                caseIndex, dimension, OPAQUE_RANDOM_BOUNDARIES, random);
    }

    private static float boundaryRandom(
            int caseIndex,
            int dimension,
            float[] boundaries,
            SplittableRandom random) {
        return CompactOpenPbrCases.boundaryRandom(caseIndex, dimension, boundaries, random);
    }
}
