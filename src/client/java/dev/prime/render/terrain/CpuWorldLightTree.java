package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Arrays;

/** Pure CPU builder for the packed top-level world light tree. */
public final class CpuWorldLightTree {
    private CpuWorldLightTree() {}

    public static Result build(WorldLightTreeInput input) {
        int lightCount = lightCount(input);
        if (lightCount == 0) {
            return Result.empty(input.clusterCount());
        }

        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(lightCount);
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            CompiledClusterLights.Summary lights = input.lights(clusterIndex);
            if (lights.isEmpty()) {
                continue;
            }
            CpuLightTree.Bounds bounds = lights.bounds();
            float translateX = (float) (((long) input.clusterX(clusterIndex) << 4)
                    - input.originX());
            float translateY = (float) (((long) input.clusterY(clusterIndex) << 4)
                    - input.originY());
            float translateZ = (float) (((long) input.clusterZ(clusterIndex) << 4)
                    - input.originZ());
            float minX = bounds.minX() + translateX;
            float minY = bounds.minY() + translateY;
            float minZ = bounds.minZ() + translateZ;
            float maxX = bounds.maxX() + translateX;
            float maxY = bounds.maxY() + translateY;
            float maxZ = bounds.maxZ() + translateZ;
            leaves.add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    (minX + maxX) * 0.5F,
                    (minY + maxY) * 0.5F,
                    (minZ + maxZ) * 0.5F,
                    lights.power(),
                    clusterIndex,
                    LightDirection.unpack(lights.packedDirection()));
        }

        CpuLightTree.Result tree = CpuLightTree.buildOwned(leaves, input.clusterCount());
        Result result = Result.forTree(tree, input.clusterCount());
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            result.setLightPath(clusterIndex, tree.leafPath(clusterIndex));
        }
        result.pack(tree);
        return result;
    }

    private static int lightCount(WorldLightTreeInput input) {
        int count = 0;
        for (int index = 0; index < input.clusterCount(); index++) {
            if (!input.lights(index).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public static final class Result {
        private final int[] packedWords;
        private final int nodeWordCount;
        private final int leafWordCount;
        private final int[] lightPaths;

        private Result(
                int[] packedWords,
                int nodeWordCount,
                int leafWordCount,
                int clusterCount) {
            this.packedWords = packedWords;
            this.nodeWordCount = nodeWordCount;
            this.leafWordCount = leafWordCount;
            this.lightPaths = new int[clusterCount];
            Arrays.fill(this.lightPaths, CpuLightTree.NO_INDEX);
        }

        public static Result empty(int clusterCount) {
            if (clusterCount < 0) {
                throw new IllegalArgumentException("Negative world light cluster count");
            }
            return new Result(new int[0], 0, 0, clusterCount);
        }

        private static Result forTree(CpuLightTree.Result tree, int clusterCount) {
            int nodeWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
            int leafWordCount = tree.leafCount()
                    * (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
            return new Result(
                    new int[nodeWordCount + leafWordCount],
                    nodeWordCount,
                    leafWordCount,
                    clusterCount);
        }

        private void setLightPath(int clusterIndex, int lightPath) {
            this.lightPaths[clusterIndex] = lightPath;
        }

        private void pack(CpuLightTree.Result tree) {
            tree.packInto(
                    this.packedWords,
                    0,
                    this.nodeWordCount);
        }

        public boolean isEmpty() {
            return this.nodeWordCount == 0;
        }

        public int[] pack() {
            return this.packedWords;
        }

        public long leafByteOffset() {
            return (long) this.nodeWordCount * Integer.BYTES;
        }

        public int nodeCount() {
            return this.nodeWordCount / (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        }

        public int leafCount() {
            return this.leafWordCount / (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
        }

        public int lightPath(int clusterIndex) {
            if (clusterIndex < 0 || clusterIndex >= this.lightPaths.length) {
                throw new IndexOutOfBoundsException(clusterIndex);
            }
            return this.lightPaths[clusterIndex];
        }
    }
}
