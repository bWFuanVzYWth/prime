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
final class PrimeBsdfGpuTest {
    private static final long SEED = 0xA11D_4A7A_26B5_DF31L;
    private static final int INPUT_WORDS = 7;
    private static final int WITNESS_WORDS = 12;
    private static final int CASES_PER_KIND = 16_384;
    private static final int KIND_COUNT = 5;
    private static final int CASE_COUNT = CASES_PER_KIND * KIND_COUNT;
    private static final int MATERIAL_FAMILY_DIELECTRIC = 1;
    private static final int MATERIAL_FAMILY_FOLIAGE = 2;
    private static final int MATERIAL_MEDIUM_COLORLESS_GLASS = 1 << 2;
    private static final int MATERIAL_MEDIUM_STAINED_GLASS = 2 << 2;
    private static final int MATERIAL_MEDIUM_WATER = 3 << 2;
    private static final int MATERIAL_THIN_WALLED = 1 << 4;
    private static final int MATERIAL_DECORATIVE_INTERFACE = 1 << 7;

    private static final float[] COSINES = {
        1.0e-6F, 1.0e-4F, 0.001F, 0.01F, 0.1F, 0.5F, 0.9F, 1.0F
    };
    private static final float[] UNIT_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] COLORS = {
        0.0F, Math.nextUp(0.0F), 0.01F, 0.25F, 0.5F, 0.9F, 1.0F
    };
    private static final float[] DISTANCES = {
        0.0F, Math.nextUp(0.0F), 0.001F, 1.0F, 64.0F, 1_000.0F
    };
    private static final int[] SMOOTHNESS = {0, 1, 127, 252, 253, 254, 255};
    private static final int[] MATERIAL = {
        0, 4, 10, 43, 128, 229, 230, 231, 232, 233, 234, 235, 236, 237,
        238, 254, 255
    };
    private static final int[] SUBSURFACE = {0, 1, 64, 65, 128, 254, 255};

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void bindTransmissionGgxEnergy() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void publicPrimeAdaptersCanonicalizeRejectionsAndKeepAcceptedPayloadsFinite()
            throws IOException {
        ByteBuffer input = createCases(0, KIND_COUNT);
        assertProperties(input, CASE_COUNT);
    }

    @Test
    void bilateralMediumBoundariesReplaceOnlyTheCurrentMedium() throws IOException {
        ByteBuffer input = createCases(KIND_COUNT, 1);
        assertProperties(input, CASES_PER_KIND);
    }

    @Test
    void matchedSolidMediaUseStraightThroughAirGapAndSwapOnlyTheCurrentMedium()
            throws IOException {
        ByteBuffer input = createCases(KIND_COUNT + 1, 1);
        assertProperties(input, CASES_PER_KIND);
    }

    private static void assertProperties(ByteBuffer input, int caseCount) throws IOException {
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "prime_bsdf_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                caseCount,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer createCases(int firstKind, int kindCount) {
        int caseCount = Math.multiplyExact(CASES_PER_KIND, kindCount);
        ByteBuffer input = ShaderTestBuffer.inputs(caseCount, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        int caseIndex = 0;
        for (int kind = firstKind; kind < firstKind + kindCount; kind++) {
            for (int localCase = 0; localCase < CASES_PER_KIND; localCase++) {
                int flags = flags(kind, localCase);
                int smoothness = SMOOTHNESS[localCase % SMOOTHNESS.length];
                int material = MATERIAL[(localCase / SMOOTHNESS.length) % MATERIAL.length];
                int subsurface = SUBSURFACE[
                        (localCase / (SMOOTHNESS.length * MATERIAL.length))
                                % SUBSURFACE.length];
                int roughness = Float.floatToRawIntBits(1.0F - smoothness / 255.0F);
                int fresnelCode = material < 230
                        ? material + 1
                        : material <= 237 ? material + 1 : material == 255 ? 239 : 0;
                int subsurfaceCode = subsurface >= 66 ? subsurface - 65 : 0;
                int porosityCode = subsurface <= 64 ? subsurface : 0;
                int opticalControl = fresnelCode
                        | subsurfaceCode << 8
                        | porosityCode << 16;

                Vec3 outward = randomUnit(random);
                float cosine = COSINES[localCase % COSINES.length];
                boolean backFace = kind != 0 && ((localCase / COSINES.length) & 1) != 0;
                Vec3 view = around(
                        outward,
                        backFace ? -cosine : cosine,
                        (float) (2.0 * Math.PI * random.nextDouble()));
                float probeCosine = COSINES[(localCase * 5 + 3) % COSINES.length];
                if ((localCase & 2) != 0) probeCosine = -probeCosine;
                Vec3 probe = around(
                        outward,
                        probeCosine,
                        (float) (2.0 * Math.PI * random.nextDouble()));
                float[] firstSample = sample(localCase, random);
                float[] secondSample = sample(localCase * 13 + 5, random);
                float[] base = {
                    COLORS[localCase % COLORS.length],
                    COLORS[(localCase / COLORS.length) % COLORS.length],
                    COLORS[(localCase / (COLORS.length * COLORS.length)) % COLORS.length]
                };
                float opacity = UNIT_BOUNDARIES[(localCase / 31) % UNIT_BOUNDARIES.length];
                int stackCount = backFace
                        ? 1 + ((localCase / 19) & 1)
                        : (localCase / 19) % 3;

                putInt(input, caseIndex, 0, 0, kind);
                putInt(input, caseIndex, 0, 1, flags);
                putInt(input, caseIndex, 0, 2, roughness);
                putInt(input, caseIndex, 0, 3, opticalControl);
                putVec3(input, caseIndex, 1, base[0], base[1], base[2]);
                putFloat(input, caseIndex, 1, 3, opacity);
                putVec3(input, caseIndex, 2, outward.x(), outward.y(), outward.z());
                putFloat(
                        input,
                        caseIndex,
                        2,
                        3,
                        DISTANCES[localCase % DISTANCES.length]);
                putVec3(input, caseIndex, 3, view.x(), view.y(), view.z());
                putInt(input, caseIndex, 3, 3, stackCount);
                putVec3(
                        input,
                        caseIndex,
                        4,
                        firstSample[0],
                        firstSample[1],
                        firstSample[2]);
                putFloat(input, caseIndex, 4, 3, secondSample[0]);
                putFloat(input, caseIndex, 5, 0, secondSample[1]);
                putFloat(input, caseIndex, 5, 1, secondSample[2]);
                putFloat(input, caseIndex, 5, 2, probe.x());
                putFloat(input, caseIndex, 5, 3, probe.y());
                putFloat(input, caseIndex, 6, 0, probe.z());
                caseIndex++;
            }
        }
        assertEquals(caseCount, caseIndex, "Prime BSDF property case count");
        return input;
    }

    private static int flags(int kind, int localCase) {
        int flags = 0;
        if (kind == 0) {
            if ((localCase & 8) != 0) {
                flags |= MATERIAL_THIN_WALLED;
            }
        } else if (kind == 1 || kind == 3 || kind == 4 || kind == 5 || kind == 6) {
            flags |= MATERIAL_FAMILY_DIELECTRIC;
            if (kind != 6 && (localCase & 8) != 0) {
                flags |= MATERIAL_MEDIUM_WATER;
            } else {
                flags |= (localCase & 64) != 0
                        ? MATERIAL_MEDIUM_COLORLESS_GLASS
                        : MATERIAL_MEDIUM_STAINED_GLASS;
                if (kind != 6 && (localCase & 16) != 0) {
                    flags |= MATERIAL_THIN_WALLED;
                }
                if (kind != 6 && (localCase & 32) != 0) {
                    flags |= MATERIAL_DECORATIVE_INTERFACE;
                }
            }
        } else if (kind == 2) {
            flags |= MATERIAL_FAMILY_FOLIAGE | MATERIAL_THIN_WALLED;
        }
        return flags;
    }

    private static float[] sample(int caseIndex, SplittableRandom random) {
        int boundaryCount = UNIT_BOUNDARIES.length
                * UNIT_BOUNDARIES.length
                * UNIT_BOUNDARIES.length;
        if (Math.floorMod(caseIndex, CASES_PER_KIND) < boundaryCount) {
            int index = Math.floorMod(caseIndex, boundaryCount);
            return new float[] {
                UNIT_BOUNDARIES[index % UNIT_BOUNDARIES.length],
                UNIT_BOUNDARIES[
                        (index / UNIT_BOUNDARIES.length) % UNIT_BOUNDARIES.length],
                UNIT_BOUNDARIES[
                        (index / (UNIT_BOUNDARIES.length * UNIT_BOUNDARIES.length))
                                % UNIT_BOUNDARIES.length]
            };
        }
        return new float[] {
            (float) random.nextDouble(),
            (float) random.nextDouble(),
            (float) random.nextDouble()
        };
    }

    private static Vec3 randomUnit(SplittableRandom random) {
        float z = (float) random.nextDouble(-1.0, 1.0);
        float radius = (float) Math.sqrt(Math.max(0.0, 1.0 - z * z));
        float phi = (float) (2.0 * Math.PI * random.nextDouble());
        return new Vec3(
                radius * (float) Math.cos(phi),
                radius * (float) Math.sin(phi),
                z);
    }

    private static Vec3 around(Vec3 normal, float cosine, float phi) {
        Vec3 helper = Math.abs(normal.z()) < 0.999F
                ? new Vec3(0.0F, 0.0F, 1.0F)
                : new Vec3(1.0F, 0.0F, 0.0F);
        Vec3 tangent = helper.cross(normal).normalized();
        Vec3 bitangent = normal.cross(tangent);
        float sine = (float) Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        return normal.scale(cosine)
                .add(tangent.scale(sine * (float) Math.cos(phi)))
                .add(bitangent.scale(sine * (float) Math.sin(phi)))
                .normalized();
    }

    private static void putVec3(
            ByteBuffer input,
            int caseIndex,
            int word,
            float x,
            float y,
            float z) {
        putFloat(input, caseIndex, word, 0, x);
        putFloat(input, caseIndex, word, 1, y);
        putFloat(input, caseIndex, word, 2, z);
    }

    private static void putFloat(
            ByteBuffer input, int caseIndex, int word, int component, float value) {
        ShaderTestBuffer.putFloat(
                input, caseIndex, INPUT_WORDS, word, component, value);
    }

    private static void putInt(
            ByteBuffer input, int caseIndex, int word, int component, int value) {
        ShaderTestBuffer.putInt(
                input, caseIndex, INPUT_WORDS, word, component, value);
    }

    private record Vec3(float x, float y, float z) {
        Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        Vec3 scale(float value) {
            return new Vec3(x * value, y * value, z * value);
        }

        Vec3 cross(Vec3 other) {
            return new Vec3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        Vec3 normalized() {
            float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z);
            return scale(inverseLength);
        }
    }
}
