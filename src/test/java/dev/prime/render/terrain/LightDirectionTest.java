// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class LightDirectionTest {
    private static final float BOUND_EPSILON = 2.0E-5F;
    private static final int LIGHT_NODE_WORDS = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
    private static final int LIGHT_NODE_DIRECTION_WORD =
            ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_LEAF_OFFSET / Integer.BYTES;
    private static final int LIGHT_NODE_CHILD_WORD = LIGHT_NODE_DIRECTION_WORD + 1;

    @Test
    void arbitrarySlantedLeafConesConservativelyBoundOneAndTwoSidedEmission() {
        SplittableRandom random = new SplittableRandom(0x6e6f_726d_616cL);
        for (int index = 0; index < 10_000; index++) {
            float[] normal = randomUnitVector(random);
            boolean twoSided = (index & 1) != 0;
            CpuLightTree.Bounds bounds = randomBounds(random);
            float lightX = mix(bounds.minX(), bounds.maxX(), random.nextFloat());
            float lightY = mix(bounds.minY(), bounds.maxY(), random.nextFloat());
            float lightZ = mix(bounds.minZ(), bounds.maxZ(), random.nextFloat());
            float pointX = bounds.minX() - 8.0F + random.nextFloat() * 20.0F;
            float pointY = bounds.minY() - 8.0F + random.nextFloat() * 20.0F;
            float pointZ = bounds.minZ() - 8.0F + random.nextFloat() * 20.0F;
            float actual = emissionCosine(
                    normal,
                    twoSided,
                    pointX - lightX,
                    pointY - lightY,
                    pointZ - lightZ);
            int packed = LightDirection.pack(LightDirection.fromNormal(
                    normal[0], normal[1], normal[2], twoSided));
            float bound = LightDirection.emissionCosineBound(
                    packed, bounds, pointX, pointY, pointZ);

            assertRangeAndContainment(bound, actual, index);
            assertEquals(
                    twoSided
                            ? LightDirection.MODE_TWO_SIDED_CONE
                            : LightDirection.MODE_ONE_SIDED_CONE,
                    LightDirection.mode(packed));
        }
    }

    @Test
    void mixedDirectionsFallBackToAConservativeSixAxisEnvelope() {
        SplittableRandom random = new SplittableRandom(0x6c6f_6265_7336L);
        for (int test = 0; test < 2_000; test++) {
            LightDirection.Bounds aggregate = null;
            float totalPower = 0.0F;
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float[][] normals = new float[6][];
            float[][] points = new float[6][];
            float[] powers = new float[6];
            boolean[] twoSided = new boolean[6];
            for (int emitter = 0; emitter < normals.length; emitter++) {
                normals[emitter] = randomUnitVector(random);
                points[emitter] = new float[] {
                    random.nextFloat() * 8.0F - 4.0F,
                    random.nextFloat() * 8.0F - 4.0F,
                    random.nextFloat() * 8.0F - 4.0F
                };
                powers[emitter] = 0.125F + random.nextFloat() * 8.0F;
                twoSided[emitter] = (emitter & 1) != 0;
                LightDirection.Bounds next = LightDirection.fromNormal(
                        normals[emitter][0],
                        normals[emitter][1],
                        normals[emitter][2],
                        twoSided[emitter]);
                aggregate = aggregate == null
                        ? next
                        : LightDirection.combine(aggregate, totalPower, next, powers[emitter]);
                totalPower += powers[emitter];
                minX = Math.min(minX, points[emitter][0]);
                minY = Math.min(minY, points[emitter][1]);
                minZ = Math.min(minZ, points[emitter][2]);
                maxX = Math.max(maxX, points[emitter][0]);
                maxY = Math.max(maxY, points[emitter][1]);
                maxZ = Math.max(maxZ, points[emitter][2]);
            }
            int packed = LightDirection.pack(aggregate);
            assertEquals(LightDirection.MODE_LOBES, LightDirection.mode(packed));
            float receiverX = random.nextFloat() * 24.0F - 12.0F;
            float receiverY = random.nextFloat() * 24.0F - 12.0F;
            float receiverZ = random.nextFloat() * 24.0F - 12.0F;
            float actual = 0.0F;
            for (int emitter = 0; emitter < normals.length; emitter++) {
                actual += powers[emitter] * emissionCosine(
                        normals[emitter],
                        twoSided[emitter],
                        receiverX - points[emitter][0],
                        receiverY - points[emitter][1],
                        receiverZ - points[emitter][2]);
            }
            actual /= totalPower;
            float bound = LightDirection.emissionCosineBound(
                    packed,
                    new CpuLightTree.Bounds(minX, minY, minZ, maxX, maxY, maxZ),
                    receiverX,
                    receiverY,
                    receiverZ);
            assertRangeAndContainment(bound, actual, test);
        }
    }

    @Test
    void coneSelectionHandlesCoherentSlopesAndOpposingAxialNormals() {
        LightDirection.Bounds x = LightDirection.fromNormal(1.0F, 0.0F, 0.0F, false);
        float sine = (float) Math.sin(Math.toRadians(10.0));
        float cosine = (float) Math.cos(Math.toRadians(10.0));
        LightDirection.Bounds nearX = LightDirection.fromNormal(cosine, sine, 0.0F, false);
        LightDirection.Bounds y = LightDirection.fromNormal(0.0F, 1.0F, 0.0F, false);
        LightDirection.Bounds negativeX = LightDirection.fromNormal(-1.0F, 0.0F, 0.0F, false);

        assertEquals(
                LightDirection.MODE_ONE_SIDED_CONE,
                LightDirection.mode(LightDirection.pack(
                        LightDirection.combine(x, 1.0F, nearX, 1.0F))));
        assertEquals(
                LightDirection.MODE_LOBES,
                LightDirection.mode(LightDirection.pack(
                        LightDirection.combine(x, 1.0F, y, 1.0F))));
        assertEquals(
                LightDirection.MODE_LOBES,
                LightDirection.mode(LightDirection.pack(
                        LightDirection.combine(x, 1.0F, negativeX, 1.0F))));

        LightDirection.Bounds twoSidedX =
                LightDirection.fromNormal(1.0F, 0.0F, 0.0F, true);
        LightDirection.Bounds twoSidedNegativeX =
                LightDirection.fromNormal(-1.0F, 0.0F, 0.0F, true);
        assertEquals(
                LightDirection.MODE_TWO_SIDED_CONE,
                LightDirection.mode(LightDirection.pack(LightDirection.combine(
                        twoSidedX, 1.0F, twoSidedNegativeX, 1.0F))));
    }

    @Test
    void lightTreePacksOneDirectionWordInEveryCompactNode() {
        CpuLightTree.Bounds firstBounds =
                new CpuLightTree.Bounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
        CpuLightTree.Bounds secondBounds =
                new CpuLightTree.Bounds(4.0F, 0.0F, 0.0F, 5.0F, 1.0F, 0.0F);
        CpuLightTree.Result tree = CpuLightTree.build(
                List.of(
                        new CpuLightTree.Leaf(
                                firstBounds,
                                0.5F,
                                0.5F,
                                0.0F,
                                1.0F,
                                0,
                                LightDirection.fromNormal(1.0F, 0.0F, 0.0F, false)),
                        new CpuLightTree.Leaf(
                                secondBounds,
                                4.5F,
                                0.5F,
                                0.0F,
                                1.0F,
                                1,
                                LightDirection.fromNormal(0.0F, 1.0F, 0.0F, false))),
                2);

        int[] nodes = tree.packNodes();
        assertEquals(tree.nodeCount() * LIGHT_NODE_WORDS, nodes.length);
        assertEquals(
                LightDirection.MODE_LOBES,
                LightDirection.mode(nodes[LIGHT_NODE_DIRECTION_WORD]));
        assertEquals(
                LightDirection.MODE_ONE_SIDED_CONE,
                LightDirection.mode(nodes[tree.leafNode(0) * LIGHT_NODE_WORDS
                        + LIGHT_NODE_DIRECTION_WORD]));
        assertEquals(
                LightDirection.MODE_ONE_SIDED_CONE,
                LightDirection.mode(nodes[tree.leafNode(1) * LIGHT_NODE_WORDS
                        + LIGHT_NODE_DIRECTION_WORD]));
    }

    @Test
    void spatialSahTiesKeepCoherentSlantedEmittersTogether() {
        float inverseRootTwo = 1.0F / (float) Math.sqrt(2.0);
        LightDirection.Bounds positive = LightDirection.fromNormal(
                inverseRootTwo, inverseRootTwo, 0.0F, false);
        LightDirection.Bounds negative = LightDirection.fromNormal(
                inverseRootTwo, -inverseRootTwo, 0.0F, false);
        CpuLightTree.Result tree = CpuLightTree.build(
                List.of(
                        directionalLeaf(0.0F, 0.0F, 0, positive),
                        directionalLeaf(0.0F, 4.0F, 1, negative),
                        directionalLeaf(4.0F, 0.0F, 2, positive),
                        directionalLeaf(4.0F, 4.0F, 3, negative)),
                4);

        int[] nodes = tree.packNodes();
        int firstChild = nodes[LIGHT_NODE_CHILD_WORD];
        for (int child = 0; child < 2; child++) {
            assertEquals(
                    LightDirection.MODE_ONE_SIDED_CONE,
                    LightDirection.mode(nodes[(firstChild + child) * LIGHT_NODE_WORDS
                            + LIGHT_NODE_DIRECTION_WORD]));
        }
    }

    @Test
    void directionalPreferenceCannotReplaceAClearlyBetterSpatialSplit() {
        float inverseRootTwo = 1.0F / (float) Math.sqrt(2.0);
        LightDirection.Bounds positive = LightDirection.fromNormal(
                inverseRootTwo, inverseRootTwo, 0.0F, false);
        LightDirection.Bounds negative = LightDirection.fromNormal(
                inverseRootTwo, -inverseRootTwo, 0.0F, false);
        CpuLightTree.Result tree = CpuLightTree.build(
                List.of(
                        directionalLeaf(0.0F, 0.0F, 0, positive),
                        directionalLeaf(0.0F, 1.0F, 1, negative),
                        directionalLeaf(100.0F, 0.0F, 2, positive),
                        directionalLeaf(100.0F, 1.0F, 3, negative)),
                4);

        int[] nodes = tree.packNodes();
        int firstChild = nodes[LIGHT_NODE_CHILD_WORD];
        for (int child = 0; child < 2; child++) {
            assertEquals(
                    LightDirection.MODE_LOBES,
                    LightDirection.mode(nodes[(firstChild + child) * LIGHT_NODE_WORDS
                            + LIGHT_NODE_DIRECTION_WORD]));
        }
    }

    private static CpuLightTree.Leaf directionalLeaf(
            float x, float y, int index, LightDirection.Bounds direction) {
        return new CpuLightTree.Leaf(
                new CpuLightTree.Bounds(x, y, 0.0F, x + 1.0F, y + 1.0F, 1.0F),
                x + 0.5F,
                y + 0.5F,
                0.5F,
                1.0F,
                index,
                direction);
    }

    private static CpuLightTree.Bounds randomBounds(SplittableRandom random) {
        float minX = random.nextFloat() * 16.0F - 8.0F;
        float minY = random.nextFloat() * 16.0F - 8.0F;
        float minZ = random.nextFloat() * 16.0F - 8.0F;
        return new CpuLightTree.Bounds(
                minX,
                minY,
                minZ,
                minX + random.nextFloat() * 4.0F,
                minY + random.nextFloat() * 4.0F,
                minZ + random.nextFloat() * 4.0F);
    }

    private static float emissionCosine(
            float[] normal, boolean twoSided, float x, float y, float z) {
        float squared = x * x + y * y + z * z;
        if (!(squared > 1.0E-12F)) {
            return 1.0F;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(squared);
        float cosine = (normal[0] * x + normal[1] * y + normal[2] * z)
                * inverseLength;
        return twoSided ? 0.5F * Math.abs(cosine) : Math.max(cosine, 0.0F);
    }

    private static float[] randomUnitVector(SplittableRandom random) {
        float x;
        float y;
        float z;
        float squared;
        do {
            x = random.nextFloat() * 2.0F - 1.0F;
            y = random.nextFloat() * 2.0F - 1.0F;
            z = random.nextFloat() * 2.0F - 1.0F;
            squared = x * x + y * y + z * z;
        } while (!(squared > 1.0E-6F) || squared > 1.0F);
        float inverseLength = 1.0F / (float) Math.sqrt(squared);
        return new float[] {x * inverseLength, y * inverseLength, z * inverseLength};
    }

    private static float mix(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static void assertRangeAndContainment(float bound, float actual, int index) {
        assertTrue(Float.isFinite(bound) && bound >= 0.0F && bound <= 1.0F);
        assertTrue(
                bound + BOUND_EPSILON >= actual,
                () -> "Directional emission bound missed case " + index
                        + ": bound=" + bound + ", actual=" + actual);
    }
}
