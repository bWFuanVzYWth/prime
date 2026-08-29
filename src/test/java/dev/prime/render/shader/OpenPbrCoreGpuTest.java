package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@ExtendWith(ShaderComputeExtension.class)
final class OpenPbrCoreGpuTest {
    private static final long SEED = 0x5985_E989_254B_4685L;
    private static final int INPUT_WORDS = 4;
    private static final int WITNESS_WORDS = 8;

    private static final int ONB_CASES = 65_536;
    private static final int COMMON_CASES = 4_096;
    private static final int FRESNEL_CASES = 8_192;
    private static final int REFLECTION_CASES = 32_768;
    private static final int CASE_COUNT = ONB_CASES
            + COMMON_CASES
            + FRESNEL_CASES
            + REFLECTION_CASES;

    private static final int ONB = 0;
    private static final int COMMON = 1;
    private static final int FRESNEL = 2;
    private static final int REFLECTION = 3;

    private static final float[] UNIT_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] VIEW_COSINES = {
        1.0e-6F, 1.0e-4F, 0.001F, 0.01F, 0.1F, 0.5F, 0.9F, 1.0F
    };
    private static final float[] ALPHAS = {
        0.0F,
        Math.nextDown(1.0e-4F),
        1.0e-4F,
        Math.nextUp(1.0e-4F),
        0.0005F,
        0.005F,
        0.01F,
        0.05F,
        0.25F,
        0.5F,
        1.0F
    };
    private static final float[] IORS = {
        1.0F / 2.4F,
        1.0F / 1.5F,
        1.0F / 1.333F,
        Math.nextDown(1.0F),
        1.0F,
        Math.nextUp(1.0F),
        1.333F,
        1.5F,
        2.4F
    };

    private static ShaderComputeRunner runner;

    @Test
    void commonFresnelAndMicrofacetPropertiesHoldAcrossGpuSweep() throws IOException {
        ByteBuffer input = createCases();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "openpbr_core_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        int caseIndex = 0;
        caseIndex = writeOnbCases(input, caseIndex, random);
        caseIndex = writeCommonCases(input, caseIndex, random);
        caseIndex = writeFresnelCases(input, caseIndex, random);
        caseIndex = writeReflectionCases(input, caseIndex, random);
        assertEquals(CASE_COUNT, caseIndex, "GPU property case count");
        return input;
    }

    private static int writeOnbCases(
            ByteBuffer input, int firstCase, SplittableRandom random) {
        int caseIndex = firstCase;
        for (int encodedY = 0; encodedY < 256; encodedY++) {
            for (int encodedX = 0; encodedX < 256; encodedX++) {
                float normalX = encodedX * (2.0F / 255.0F) - 1.0F;
                float normalY = encodedY * (2.0F / 255.0F) - 1.0F;
                float normalZ = (float) Math.sqrt(
                        Math.max(1.0F - normalX * normalX - normalY * normalY, 0.0F));
                float normalLength = (float) Math.sqrt(
                        normalX * normalX + normalY * normalY + normalZ * normalZ);
                normalX /= normalLength;
                normalY /= normalLength;
                normalZ /= normalLength;

                float localZ = (float) random.nextDouble(-1.0, 1.0);
                float localRadius = (float) Math.sqrt(
                        Math.max(0.0, 1.0 - localZ * localZ));
                float localPhi = (float) (2.0 * Math.PI * random.nextDouble());
                putKind(input, caseIndex, ONB);
                putParams(
                        input,
                        caseIndex,
                        normalX,
                        normalY,
                        normalZ,
                        localRadius * (float) Math.cos(localPhi),
                        localRadius * (float) Math.sin(localPhi),
                        localZ);
                caseIndex++;
            }
        }
        return caseIndex;
    }

    private static int writeCommonCases(
            ByteBuffer input, int firstCase, SplittableRandom random) {
        float[] f0Values = {
            0.0F,
            Math.nextUp(0.0F),
            0.02F,
            0.04F,
            0.17F,
            0.5F,
            0.9F,
            0.999F
        };
        float[] roughnessValues = {
            0.0F,
            Math.nextDown(0.01F),
            0.01F,
            Math.nextUp(0.01F),
            0.05F,
            0.5F,
            1.0F
        };
        float[] anisotropyValues = {0.0F, 0.25F, 0.5F, 0.9F, 1.0F};
        for (int localCase = 0; localCase < COMMON_CASES; localCase++) {
            int caseIndex = firstCase + localCase;
            float sampleX = localCase < 64
                    ? UNIT_BOUNDARIES[localCase % UNIT_BOUNDARIES.length]
                    : (float) random.nextDouble();
            float sampleY = localCase < 64
                    ? UNIT_BOUNDARIES[
                            (localCase / UNIT_BOUNDARIES.length) % UNIT_BOUNDARIES.length]
                    : (float) random.nextDouble();
            putKind(input, caseIndex, COMMON);
            putParams(
                    input,
                    caseIndex,
                    sampleX,
                    sampleY,
                    f0Values[localCase % f0Values.length],
                    roughnessValues[(localCase / f0Values.length) % roughnessValues.length],
                    anisotropyValues[
                            (localCase / (f0Values.length * roughnessValues.length))
                                    % anisotropyValues.length]);
        }
        return firstCase + COMMON_CASES;
    }

    private static int writeFresnelCases(
            ByteBuffer input, int firstCase, SplittableRandom random) {
        int criticalCases = IORS.length * 3;
        for (int localCase = 0; localCase < FRESNEL_CASES; localCase++) {
            int caseIndex = firstCase + localCase;
            float ior;
            float cosine;
            if (localCase < criticalCases) {
                ior = IORS[localCase / 3];
                if (ior == 1.0F) {
                    cosine = switch (localCase % 3) {
                        case 0 -> -1.0e-6F;
                        case 1 -> 1.0e-6F;
                        default -> 1.0e-4F;
                    };
                } else {
                    float effectiveEta = Math.min(ior, 1.0F / ior);
                    float critical = (float) Math.sqrt(
                            Math.max(0.0F, 1.0F - effectiveEta * effectiveEta));
                    float criticalNeighbor = switch (localCase % 3) {
                        case 0 -> Math.nextDown(critical);
                        case 1 -> critical;
                        default -> Math.nextUp(critical);
                    };
                    cosine = Math.copySign(criticalNeighbor, ior < 1.0F ? 1.0F : -1.0F);
                }
            } else {
                ior = IORS[localCase % IORS.length];
                float magnitude = localCase < criticalCases + VIEW_COSINES.length * 2
                        ? VIEW_COSINES[
                                (localCase - criticalCases) % VIEW_COSINES.length]
                        : (float) Math.max(1.0e-6, random.nextDouble());
                cosine = ((localCase & 1) == 0 ? 1.0F : -1.0F) * magnitude;
            }
            putKind(input, caseIndex, FRESNEL);
            putParams(
                    input,
                    caseIndex,
                    ior,
                    cosine,
                    (float) (2.0 * Math.PI * random.nextDouble()));
        }
        return firstCase + FRESNEL_CASES;
    }

    private static int writeReflectionCases(
            ByteBuffer input, int firstCase, SplittableRandom random) {
        for (int localCase = 0; localCase < REFLECTION_CASES; localCase++) {
            int caseIndex = firstCase + localCase;
            float[] alpha = alphaPair(localCase);
            float cosine = viewCosine(localCase, random, false);
            float phi = (float) (2.0 * Math.PI * random.nextDouble());
            float sine = (float) Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
            float sampleX = sampleValue(localCase, 0, random);
            float sampleY = sampleValue(localCase, 1, random);
            putKind(input, caseIndex, REFLECTION);
            putParams(
                    input,
                    caseIndex,
                    alpha[0],
                    alpha[1],
                    sine * (float) Math.cos(phi),
                    sine * (float) Math.sin(phi),
                    cosine,
                    sampleX,
                    sampleY);
        }
        return firstCase + REFLECTION_CASES;
    }

    private static float[] alphaPair(int caseIndex) {
        float first = ALPHAS[Math.floorMod(caseIndex, ALPHAS.length)];
        float second = ALPHAS[Math.floorMod(caseIndex * 7 + 2, ALPHAS.length)];
        if (Math.max(first, second) >= 1.0e-4F) {
            first = Math.max(first, 1.0e-4F);
            second = Math.max(second, 1.0e-4F);
        }
        return new float[] {first, second};
    }

    private static float viewCosine(
            int caseIndex, SplittableRandom random, boolean signed) {
        float magnitude = caseIndex < VIEW_COSINES.length * 8
                ? VIEW_COSINES[caseIndex % VIEW_COSINES.length]
                : (float) Math.max(1.0e-6, random.nextDouble());
        return signed && (caseIndex & 1) != 0 ? -magnitude : magnitude;
    }

    private static float sampleValue(
            int caseIndex, int dimension, SplittableRandom random) {
        int boundaryCases = UNIT_BOUNDARIES.length * UNIT_BOUNDARIES.length;
        if (caseIndex < boundaryCases) {
            int divisor = 1;
            for (int index = 0; index < dimension; index++) {
                divisor *= UNIT_BOUNDARIES.length;
            }
            return UNIT_BOUNDARIES[(caseIndex / divisor) % UNIT_BOUNDARIES.length];
        }
        return (float) random.nextDouble();
    }

    private static void putKind(ByteBuffer input, int caseIndex, int kind) {
        ShaderTestBuffer.putInt(
                input, caseIndex, INPUT_WORDS, 0, 0, kind);
    }

    private static void putParams(ByteBuffer input, int caseIndex, float... values) {
        if (values.length > INPUT_WORDS * 4 - 1) {
            throw new IllegalArgumentException("Too many shader property parameters");
        }
        for (int parameter = 0; parameter < values.length; parameter++) {
            int flatComponent = parameter + 1;
            ShaderTestBuffer.putFloat(
                    input,
                    caseIndex,
                    INPUT_WORDS,
                    flatComponent / 4,
                    flatComponent % 4,
                    values[parameter]);
        }
    }
}
