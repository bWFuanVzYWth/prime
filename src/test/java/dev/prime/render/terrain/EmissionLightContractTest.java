package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EmissionLightContractTest {
    private static final CapturedSprite TEXTURE = new CapturedSprite(
            new SpriteId("prime", "emission_fixture"),
            1,
            16,
            16,
            false,
            new int[] {0},
            null);
    private static final int LIGHT_NODE_WORDS = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
    private static final int LIGHT_NODE_POWER_WORD =
            ShaderAbi.LIGHT_NODE_CENTROID_POWER_OFFSET / Integer.BYTES + 3;
    private static final int LIGHT_NODE_CENTROID_WORD =
            ShaderAbi.LIGHT_NODE_CENTROID_POWER_OFFSET / Integer.BYTES;
    private static final int LIGHT_NODE_CONTROL_WORD =
            ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_RESERVED_OFFSET / Integer.BYTES;
    private static final int LIGHT_NODE_CHILD_WORD = LIGHT_NODE_CONTROL_WORD + 1;
    private static final int LIGHT_NODE_RESERVED_WORD = LIGHT_NODE_CONTROL_WORD + 2;

    @Test
    void minecraftLightLevelUsesSquaredRadiusCalibration() {
        assertEquals(0.0F, CpuSectionLights.emissionScale(0));
        assertEquals(3.0F / 50.0F, CpuSectionLights.emissionScale(3), 1.0E-6F);
        assertEquals(49.0F / 150.0F, CpuSectionLights.emissionScale(7), 1.0E-6F);
        assertEquals(1.5F, CpuSectionLights.emissionScale(15));
        assertEquals(1.5F, CpuSectionLights.emissionScale(100));
    }

    @Test
    void triangularSubdivisionHasExactlyEqualAreaCells() {
        boolean[][][] seen = new boolean[EmissionDistribution.SUBDIVISION]
                [EmissionDistribution.SUBDIVISION][2];
        float expectedTwiceArea = 1.0F
                / (EmissionDistribution.SUBDIVISION * EmissionDistribution.SUBDIVISION);
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(index);
            assertTrue(cell.column() + cell.row() < EmissionDistribution.SUBDIVISION);
            assertTrue(!cell.upper()
                    || cell.column() + cell.row() < EmissionDistribution.SUBDIVISION - 1);
            assertTrue(!seen[cell.column()][cell.row()][cell.upper() ? 1 : 0]);
            seen[cell.column()][cell.row()][cell.upper() ? 1 : 0] = true;
            float[][] vertices = cell.vertices();
            for (float[] vertex : vertices) {
                assertEquals(1.0F, vertex[0] + vertex[1] + vertex[2], 1.0E-6F);
                assertTrue(vertex[0] >= -1.0E-6F && vertex[1] >= -1.0E-6F && vertex[2] >= -1.0E-6F);
            }
            float twiceArea = Math.abs(
                    (vertices[1][1] - vertices[0][1]) * (vertices[2][2] - vertices[0][2])
                            - (vertices[1][2] - vertices[0][2]) * (vertices[2][1] - vertices[0][1]));
            assertEquals(expectedTwiceArea, twiceArea, 1.0E-6F);
        }
    }

    @Test
    void packedCellGeometryRoundTripsWithoutShaderSearch() {
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(index);
            int geometry = cell.packedGeometry();
            assertEquals(cell.column(), geometry & 0xf);
            assertEquals(cell.row(), geometry >>> 4 & 0xf);
            assertEquals(cell.upper(), (geometry & 0x100) != 0);
            assertEquals(0, geometry & ~0x1ff);
        }
    }

    @Test
    void aliasAndCellGeometryShareOneWordWithoutLosingEitherIndex() {
        for (int cellIndex = 0; cellIndex < EmissionDistribution.CELL_COUNT; cellIndex++) {
            int alias = EmissionDistribution.CELL_COUNT - 1 - cellIndex;
            int packed = EmissionDistribution.packAliasGeometry(alias, cellIndex);
            assertEquals(alias, packed & 0xff);
            assertEquals(
                    EmissionDistribution.cell(cellIndex).packedGeometry(),
                    packed >>> 8);
        }
    }

    @Test
    void emissionImportanceUsesTheLargestLinearRec2020Component() {
        assertEquals(1.0F, EmissionDistribution.linearSrgbToRec2020Maximum(1.0F, 1.0F, 1.0F), 1.0E-6F);
        assertEquals(0.6274039F, EmissionDistribution.linearSrgbToRec2020Maximum(1.0F, 0.0F, 0.0F), 1.0E-6F);
        assertEquals(0.9195404F, EmissionDistribution.linearSrgbToRec2020Maximum(0.0F, 1.0F, 0.0F), 1.0E-6F);
        assertEquals(0.8955953F, EmissionDistribution.linearSrgbToRec2020Maximum(0.0F, 0.0F, 1.0F), 1.0E-6F);
    }

    @Test
    void uniformFallbackKeepsEveryCellInTheSamplingSupport() {
        EmissionDistribution distribution = EmissionDistribution.uniform();
        float total = 0.0F;
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            assertTrue(distribution.aliasProbability(index) > 0.0F);
            assertTrue(distribution.probabilityMass(index) > 0.0F);
            total += distribution.probabilityMass(index);
        }
        assertEquals(1.0F, total, 1.0E-5F);
        assertEquals(1.0F, distribution.meanImportance(), 1.0E-6F);
    }

    @Test
    void uniformTriangleDistributionHasExactCentroid() {
        EmissionDistribution.SpatialMoments moments =
                EmissionDistribution.uniform().spatialMoments();

        assertEquals(1.0F / 3.0F, moments.meanU(), 1.0E-6F);
        assertEquals(1.0F / 3.0F, moments.meanV(), 1.0E-6F);
    }

    @Test
    void sectionRetainsMoreThan1024DistinctImportanceDistributions() {
        int count = 1026;
        CpuSectionLights.Builder builder = new CpuSectionLights.Builder();
        for (int index = 0; index < count; index++) {
            builder.addTriangle(
                    index,
                    0.0F,
                    0.0F,
                    index + 1.0F,
                    0.0F,
                    0.0F,
                    index,
                    1.0F,
                    0.0F,
                    0,
                    0,
                    0,
                    0xff000000 | index,
                    false,
                    15,
                    TEXTURE,
                    null);
        }

        CpuSectionLights lights = builder.build();
        int[] packed = lights.pack(0L);
        int emitterStart = packed[6] / Integer.BYTES;
        int lastEmitter = emitterStart
                + (count - 1) * (ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES);

        assertEquals(count, lights.emitterCount());
        assertEquals(
                (count - 1) * EmissionDistribution.CELL_COUNT,
                packed[lastEmitter + 20]);
    }

    @Test
    void emitterNormalFollowsTriangleWinding() {
        CpuSectionLights.Builder builder = new CpuSectionLights.Builder();
        builder.addTriangle(
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                0,
                0,
                0,
                -1,
                false,
                15,
                TEXTURE,
                null);

        CpuSectionLights lights = builder.build();
        int[] packed = lights.pack(0L);
        int emitterStart = packed[6] / Integer.BYTES;

        assertEquals(0.0F, Float.intBitsToFloat(packed[emitterStart + 12]), 0.0F);
        assertEquals(0.0F, Float.intBitsToFloat(packed[emitterStart + 13]), 0.0F);
        assertEquals(-1.0F, Float.intBitsToFloat(packed[emitterStart + 14]), 0.0F);
    }

    @Test
    void treePacksConsecutiveSiblingsSingletonLeavesAndBitTrails() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, 1.0F, 0),
                leaf(4.0F, 2.0F, 1),
                leaf(8.0F, 3.0F, 2));
        CpuLightTree.Result tree = CpuLightTree.build(leaves, leaves.size());
        assertEquals(6.0F, tree.power(), 1.0E-6F);
        int[] nodes = tree.packNodes();
        int[] terminals = tree.packLeaves();
        int[] combined = new int[nodes.length + terminals.length];
        tree.packInto(combined, 0, nodes.length);
        assertArrayEquals(
                nodes,
                java.util.Arrays.copyOfRange(combined, 0, nodes.length));
        assertArrayEquals(
                terminals,
                java.util.Arrays.copyOfRange(
                        combined, nodes.length, combined.length));
        assertEquals(tree.nodeCount() * LIGHT_NODE_WORDS, nodes.length);
        assertEquals(tree.leafCount() * 2, terminals.length);
        assertEquals(2 * leaves.size() - 1, tree.nodeCount());
        assertEquals(leaves.size(), tree.leafCount());
        for (int node = 0; node < tree.nodeCount(); node++) {
            int childOrLeaf = nodes[node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) == 0) {
                assertEquals(0, nodes[node * LIGHT_NODE_WORDS + LIGHT_NODE_RESERVED_WORD]);
                assertTrue(childOrLeaf + 1 < tree.nodeCount());
            }
        }
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            int node = tree.leafNode(leaf);
            assertEquals(node, terminalNode(nodes, tree.leafPath(leaf)));
            int descriptor = nodes[node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            assertLeafContains(terminals, descriptor & CpuLightTree.INDEX_MASK, leaf);
        }
    }

    @Test
    void treeMedianSplitsCoincidentLightsIntoSingletonLeaves() {
        ArrayList<CpuLightTree.Leaf> leaves = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            leaves.add(leaf(0.0F, index + 1.0F, index));
        }

        CpuLightTree.Result tree = CpuLightTree.build(leaves, leaves.size());
        int[] nodes = tree.packNodes();
        int[] terminals = tree.packLeaves();

        assertBinaryNodeCount(leaves.size(), tree.nodeCount());
        assertEquals(leaves.size(), tree.leafCount());
        for (int index = 0; index < leaves.size(); index++) {
            int node = terminalNode(nodes, tree.leafPath(index));
            int descriptor = nodes[node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            assertLeafContains(
                    terminals,
                    descriptor & CpuLightTree.INDEX_MASK,
                    index);
        }
    }

    @Test
    void treeKeepsPathologicalSahSplitsWithinPackedDepth() {
        ArrayList<CpuLightTree.Leaf> leaves = new ArrayList<>();
        float x = 1.0F;
        float power = 1.0F;
        for (int index = 0; index < 32; index++) {
            leaves.add(leaf(x, power, index));
            x *= 1.4F;
            power *= 4.0F;
        }

        CpuLightTree.Result tree = CpuLightTree.build(leaves, leaves.size());
        int[] nodes = tree.packNodes();
        int[] terminals = tree.packLeaves();

        for (int index = 0; index < leaves.size(); index++) {
            int path = tree.leafPath(index);
            assertTrue((path >>> CpuLightTree.PATH_DEPTH_SHIFT) <= CpuLightTree.MAX_PATH_DEPTH);
            int node = terminalNode(nodes, path);
            assertEquals(tree.leafNode(index), node);
            int descriptor = nodes[node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            assertLeafContains(
                    terminals,
                    descriptor & CpuLightTree.INDEX_MASK,
                    index);
        }
    }

    @Test
    void treePacksPowerWeightedCentroidAndZeroReservedWords() {
        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(2);
        leaves.add(
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                LightDirection.full());
        leaves.add(
                10.0F,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0.0F,
                3.0F,
                1,
                LightDirection.full());

        CpuLightTree.Result tree = CpuLightTree.buildOwned(leaves, 2);
        int[] nodes = tree.packNodes();
        float centerX = Float.intBitsToFloat(nodes[LIGHT_NODE_CENTROID_WORD]);

        assertEquals(0, nodes[LIGHT_NODE_CONTROL_WORD + 2]);
        assertEquals(0, nodes[LIGHT_NODE_CONTROL_WORD + 3]);
        assertEquals(7.5F, centerX, 10.0F / 1023.0F);
    }

    @Test
    void worldTreePacksNodesThenTerminalLeaves() {
        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                worldLightInput(List.of(cluster(0, 2.0F)), 0, 0, 0));
        int[] packed = tree.pack();

        assertEquals(1, tree.nodeCount());
        assertEquals(ShaderAbi.LIGHT_NODE_SIZE, tree.leafByteOffset());
        assertEquals(
                (ShaderAbi.LIGHT_NODE_SIZE + ShaderAbi.LIGHT_LEAF_SIZE)
                        / Integer.BYTES,
                packed.length);
    }

    @Test
    void compactionOnlyUpdateReusesExistingWorldLightUpload() {
        CpuWorldLightTree.Result existingTree = CpuWorldLightTree.build(
                worldLightInput(List.of(cluster(0, 1.0F)), 0, 0, 0));
        CpuWorldLightTree.Result emptyTree = CpuWorldLightTree.Result.empty(0);

        assertFalse(TerrainScene.requiresWorldLightUpload(false, existingTree));
        assertTrue(TerrainScene.requiresWorldLightUpload(true, existingTree));
        assertFalse(TerrainScene.requiresWorldLightUpload(true, emptyTree));
    }

    @Test
    void treeRejectsAggregatePowerOutsideThePackedF32Domain() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, Float.MAX_VALUE, 0),
                leaf(1.0F, Float.MAX_VALUE, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> CpuLightTree.build(leaves, leaves.size()));
    }

    @Test
    void worldTreeRebuildKeepsExactBitTrailLeafMapping() {
        ArrayList<WorldLightTreeInput.Entry> clusters = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            clusters.add(cluster(index, index + 1.0F));
        }
        CpuWorldLightTree.Result initial =
                CpuWorldLightTree.build(worldLightInput(clusters, 0, 0, 0));
        assertWorldLeafMapping(initial, clusters.size());
        assertBinaryNodeCount(clusters.size(), initial.nodeCount());
        assertEquals(clusters.size(), initial.leafCount());
        assertEquals(
                36.0F,
                Float.intBitsToFloat(initial.pack()[LIGHT_NODE_POWER_WORD]),
                1.0E-6F);

        clusters.remove(0);
        CpuWorldLightTree.Result rebuilt =
                CpuWorldLightTree.build(worldLightInput(clusters, 16, 0, 0));
        assertWorldLeafMapping(rebuilt, clusters.size());
        assertBinaryNodeCount(clusters.size(), rebuilt.nodeCount());
        assertEquals(clusters.size(), rebuilt.leafCount());
        assertEquals(
                35.0F,
                Float.intBitsToFloat(rebuilt.pack()[LIGHT_NODE_POWER_WORD]),
                1.0E-6F);
        for (int node = 0; node < rebuilt.nodeCount(); node++) {
            assertTrue(Float.intBitsToFloat(
                            rebuilt.pack()[node * LIGHT_NODE_WORDS
                                    + LIGHT_NODE_POWER_WORD])
                    > 0.0F);
        }
    }

    @Test
    void treeBuildsTwoNonemptySaohRootBranches() {
        float[] centers = {
            1.1087142F, 2.5938268F, 3.9133697F, 4.942278F,
            5.5759964F, 6.505394F, 7.0851145F, 8.035374F,
            9.438156F, 10.604509F, 11.7998085F, 13.37184F
        };
        float[] powers = {
            0.95967007F, 2.2348843F, 1.5441734F, 2.2040129F,
            2.0082793F, 2.018568F, 2.1100402F, 0.77936697F,
            2.0560603F, 1.3423656F, 2.408029F, 0.96765053F
        };
        ArrayList<CpuLightTree.Leaf> leaves = new ArrayList<>();
        for (int index = 0; index < centers.length; index++) {
            leaves.add(leaf(centers[index] - 0.5F, powers[index], index));
        }

        CpuLightTree.Result tree = CpuLightTree.build(leaves, leaves.size());
        int[] rootChildren = new int[2];
        for (int index = 0; index < leaves.size(); index++) {
            rootChildren[tree.leafPath(index) & 1]++;
        }
        for (int count : rootChildren) assertTrue(count > 0);
    }

    @Test
    void worldTreeRetainsF32CentroidsBeyondFiniteF16RangeAfterOriginRebase() {
        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                worldLightInput(List.of(cluster(0, 1.0F)), 0, 1_000_000, 0));
        int[] packed = tree.pack();

        float centroidY = Float.intBitsToFloat(packed[1]);
        assertTrue(Float.isFinite(centroidY));
        assertTrue(centroidY >= -1_000_000.0F && centroidY <= -999_999.0F);
    }

    @Test
    void worldTreeOriginRebaseDoesNotOverflowTheBlockCoordinateDomain() {
        WorldLightTreeInput.Entry source = cluster(0, 1.0F);
        WorldLightTreeInput.Entry distant = new WorldLightTreeInput.Entry(
                source.key(), Integer.MIN_VALUE >> 4, 0, 0, source.lights());
        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                worldLightInput(List.of(distant), Integer.MAX_VALUE, 0, 0));

        assertEquals(
                (float) ((long) Integer.MIN_VALUE - Integer.MAX_VALUE),
                Float.intBitsToFloat(tree.pack()[0]),
                0.0F);
    }

    @Test
    void worldWithoutEmittersMapsEveryResidentClusterToNoLight() {
        List<WorldLightTreeInput.Entry> clusters = List.of(
                emptyCluster(1),
                emptyCluster(2),
                emptyCluster(3));

        CpuWorldLightTree.Result result =
                CpuWorldLightTree.build(worldLightInput(clusters, 0, 0, 0));

        assertTrue(result.isEmpty());
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            assertEquals(CpuLightTree.NO_INDEX, result.lightPath(clusterIndex));
        }
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> result.lightPath(clusters.size()));
    }

    private static void assertWorldLeafMapping(CpuWorldLightTree.Result tree, int clusterCount) {
        int[] packed = tree.pack();
        int nodeOffset = 0;
        int leafOffset = Math.toIntExact(tree.leafByteOffset() / Integer.BYTES);
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            int path = tree.lightPath(clusterIndex);
            assertNotEquals(CpuLightTree.NO_INDEX, path);
            int node = terminalNode(packed, nodeOffset, path);
            int descriptor = packed[
                    nodeOffset + node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            int leaf = descriptor & CpuLightTree.INDEX_MASK;
            assertEquals(clusterIndex, packed[leafOffset + leaf * 2]);
        }
    }

    private static int terminalNode(int[] nodes, int packedPath) {
        return terminalNode(nodes, 0, packedPath);
    }

    private static int terminalNode(int[] nodes, int nodeOffset, int packedPath) {
        int depth = packedPath >>> CpuLightTree.PATH_DEPTH_SHIFT;
        int node = 0;
        for (int level = 0; level < depth; level++) {
            int firstChild = nodes[
                    nodeOffset + node * LIGHT_NODE_WORDS + LIGHT_NODE_CHILD_WORD];
            assertEquals(0, firstChild & CpuLightTree.LEAF_FLAG);
            int selected = packedPath >>> level & 1;
            node = firstChild + selected;
        }
        return node;
    }

    private static void assertBinaryNodeCount(int leafCount, int nodeCount) {
        assertEquals(2 * leafCount - 1, nodeCount);
    }

    private static void assertLeafContains(int[] leaves, int leaf, int expectedIndex) {
        assertEquals(expectedIndex, leaves[leaf * 2]);
        assertTrue(Float.intBitsToFloat(leaves[leaf * 2 + 1]) > 0.0F);
    }

    private static WorldLightTreeInput.Entry cluster(int index, float power) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(
                0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                new CompiledClusterLights.Summary(
                        1,
                        bounds.minX(),
                        bounds.minY(),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ(),
                        power));
    }

    private static WorldLightTreeInput worldLightInput(
            List<WorldLightTreeInput.Entry> clusters,
            int originX,
            int originY,
            int originZ) {
        return WorldLightTreeInput.capture(
                clusters, originX, originY, originZ);
    }

    private static WorldLightTreeInput.Entry emptyCluster(int index) {
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                CompiledClusterLights.EMPTY.summary());
    }

    private static CpuLightTree.Leaf leaf(float x, float power, int index) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(x, 0.0F, 0.0F, x + 1.0F, 1.0F, 1.0F);
        return new CpuLightTree.Leaf(bounds, x + 0.5F, 0.5F, 0.5F, power, index);
    }
}
