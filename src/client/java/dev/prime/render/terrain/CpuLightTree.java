package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.List;

/**
 * Pure CPU builder for both levels of Prime's light tree.
 *
 * <p>The builder emits compact hot nodes, singleton leaves and an exact bit trail for every input
 * light. Forward sampling and emissive-hit MIS therefore traverse the same records in the same
 * order; no reverse parent stream or higher-precision CPU reconstruction is required.
 */
public final class CpuLightTree {
    public static final int NO_INDEX = -1;
    static final int LEAF_FLAG = Integer.MIN_VALUE;
    static final int INDEX_MASK = Integer.MAX_VALUE;
    private static final int LIGHTS_PER_LEAF = 1;
    static final int MAX_PATH_DEPTH = 27;
    static final int PATH_DEPTH_SHIFT = 27;
    static final int PATH_TRAIL_MASK = (1 << PATH_DEPTH_SHIFT) - 1;
    private static final int SAH_BIN_COUNT = 12;
    private static final int SAH_CANDIDATE_COUNT = 3 * (SAH_BIN_COUNT - 1);
    // Bound the local SAOH concession before using estimated traversal depth as a tie-breaker.
    private static final double SAOH_DEPTH_QUALITY_BAND = 1.02;
    private static final int WORDS_PER_NODE = 8;
    private static final int WORDS_PER_LEAF = 2;
    private CpuLightTree() {
    }

    static Result build(List<Leaf> source, int indexCapacity) {
        Leaves leaves = new Leaves(source.size());
        for (Leaf leaf : source) {
            leaves.add(
                    leaf.bounds,
                    leaf.centerX,
                    leaf.centerY,
                    leaf.centerZ,
                    leaf.power,
                    leaf.index,
                    leaf.direction);
        }
        return buildOwned(leaves, indexCapacity);
    }

    static Result buildOwned(Leaves leaves, int indexCapacity) {
        if (leaves.size == 0) {
            throw new IllegalArgumentException("A light tree requires at least one leaf");
        }
        if (indexCapacity < 0) {
            throw new IllegalArgumentException("Negative light leaf index capacity");
        }
        Nodes nodes = new Nodes(leaves.size * 2 - 1);
        TerminalLeaves terminals = new TerminalLeaves(leaves.size);
        int[] leafNodes = new int[indexCapacity];
        int[] leafPaths = new int[indexCapacity];
        Arrays.fill(leafNodes, NO_INDEX);
        Arrays.fill(leafPaths, NO_INDEX);
        Workspace workspace = new Workspace();
        int rootNode = createNode(leaves, 0, leaves.size, nodes);
        populateNode(
                leaves,
                0,
                leaves.size,
                rootNode,
                0,
                0,
                nodes,
                terminals,
                leafNodes,
                leafPaths,
                workspace);
        return new Result(nodes, terminals, leafNodes, leafPaths);
    }

    /**
     * Populates a node whose aggregate data has already been allocated.
     *
     * <p>Both direct children are appended before either subtree is populated. Every sibling pair
     * is therefore consecutive in the packed arrays, so traversal reads remain spatially coherent
     * without changing the SAOH partition or any sampling probability.
     */
    private static void populateNode(
            Leaves leaves,
            int start,
            int end,
            int nodeIndex,
            int trail,
            int depth,
            Nodes nodes,
            TerminalLeaves terminals,
            int[] leafNodes,
            int[] leafPaths,
            Workspace workspace) {
        int count = end - start;
        if (count <= 0) {
            throw new IllegalStateException("Empty light tree range");
        }
        int remainingDepth = MAX_PATH_DEPTH - depth;
        int capacity = LIGHTS_PER_LEAF << remainingDepth;
        if (count > capacity) {
            throw new IllegalStateException(
                    "Light tree range of " + count + " exceeds packed path capacity " + capacity);
        }
        if (count == 1) {
            int leaf = terminals.add(leaves, start, end);
            nodes.firstChildOrLeaf[nodeIndex] = leaf;
            nodes.direction[nodeIndex] = aggregateDirection(leaves, start, end);
            int packedPath = packPath(trail, depth);
            for (int slot = start; slot < end; slot++) {
                int inputIndex = leaves.index[slot];
                if (inputIndex < 0
                        || inputIndex >= leafNodes.length
                        || leafNodes[inputIndex] != NO_INDEX) {
                    throw new IllegalStateException(
                            "Invalid or duplicate light leaf index " + inputIndex);
                }
                leafNodes[inputIndex] = nodeIndex;
                leafPaths[inputIndex] = packedPath;
            }
            return;
        }

        if (depth >= MAX_PATH_DEPTH) {
            throw new IllegalStateException("Light tree exceeds packed bit-trail depth");
        }
        Split split = chooseSplit(leaves, start, end, workspace);
        int middle = partition(leaves, start, end, split, workspace);
        int childCapacity = LIGHTS_PER_LEAF << (remainingDepth - 1);
        if (middle - start > childCapacity || end - middle > childCapacity) {
            // SAOH can repeatedly peel a small child. Fall back only when its split would exceed
            // the existing packed-path capacity.
            middle = partitionByMedian(leaves, start, end, workspace.longestCentroidAxis());
        }
        int left = createNode(leaves, start, middle, nodes);
        int right = createNode(leaves, middle, end, nodes);
        nodes.firstChildOrLeaf[nodeIndex] = left;
        nodes.secondChild[nodeIndex] = right;
        populateNode(
                leaves,
                start,
                middle,
                left,
                trail,
                depth + 1,
                nodes,
                terminals,
                leafNodes,
                leafPaths,
                workspace);
        populateNode(
                leaves,
                middle,
                end,
                right,
                trail | (1 << depth),
                depth + 1,
                nodes,
                terminals,
                leafNodes,
                leafPaths,
                workspace);
        nodes.refitDirection(nodeIndex);
    }

    private static int createNode(
            Leaves leaves,
            int start,
            int end,
            Nodes nodes) {
        return nodes.add(leaves, start, end);
    }

    private static Split chooseSplit(
            Leaves leaves,
            int start,
            int end,
            Workspace workspace) {
        workspace.findCentroidBounds(leaves, start, end);
        workspace.resetCandidates();
        float maximumCentroidExtent = workspace.maximumCentroidExtent();
        for (int axis = 0; axis < 3; axis++) {
            float minimum = workspace.centroidMinimum(axis);
            float extent = workspace.centroidMaximum(axis) - minimum;
            if (!(extent > 0.0F)) {
                continue;
            }
            Bin[] bins = workspace.bins;
            for (Bin bin : bins) {
                bin.reset();
            }
            for (int index = start; index < end; index++) {
                int binIndex = binIndex(leaves.center(index, axis), minimum, extent);
                bins[binIndex].include(leaves, index);
            }
            workspace.aggregate();
            for (int split = 0; split < SAH_BIN_COUNT - 1; split++) {
                int leftCount = workspace.prefixCount[split];
                int rightCount = workspace.suffixCount[split + 1];
                if (leftCount == 0 || rightCount == 0) {
                    continue;
                }
                float cost = saohCost(
                                workspace.prefixMinX[split],
                                workspace.prefixMinY[split],
                                workspace.prefixMinZ[split],
                                workspace.prefixMaxX[split],
                                workspace.prefixMaxY[split],
                                workspace.prefixMaxZ[split],
                                workspace.prefixPower[split],
                                workspace.prefixDirection[split])
                        + saohCost(
                                workspace.suffixMinX[split + 1],
                                workspace.suffixMinY[split + 1],
                                workspace.suffixMinZ[split + 1],
                                workspace.suffixMaxX[split + 1],
                                workspace.suffixMaxY[split + 1],
                                workspace.suffixMaxZ[split + 1],
                                workspace.suffixPower[split + 1],
                                workspace.suffixDirection[split + 1]);
                cost *= maximumCentroidExtent / extent;
                double expectedRemainingDepth =
                        workspace.prefixPower[split]
                                * balancedContinuationDepth(leftCount)
                                + workspace.suffixPower[split + 1]
                                        * balancedContinuationDepth(rightCount);
                workspace.addCandidate(axis, split, cost, expectedRemainingDepth);
            }
        }

        float unsplitCost = rangeSaohCost(leaves, start, end);
        int bestCandidate = workspace.bestCandidate(unsplitCost);
        if (bestCandidate < 0) {
            return new Split(-1, -1);
        }
        return new Split(
                workspace.candidateAxis[bestCandidate],
                workspace.candidateSplit[bestCandidate]);
    }

    private static int partition(
            Leaves leaves, int start, int end, Split split, Workspace workspace) {
        int bestAxis = split.axis;
        int bestSplit = split.bucket;
        if (bestAxis >= 0) {
            float minimum = workspace.centroidMinimum(bestAxis);
            float extent = workspace.centroidMaximum(bestAxis) - minimum;
            int left = start;
            int right = end - 1;
            while (left <= right) {
                if (binIndex(leaves.center(left, bestAxis), minimum, extent) <= bestSplit) {
                    left++;
                } else {
                    leaves.swap(left, right);
                    right--;
                }
            }
            if (left > start && left < end) {
                return left;
            }
        }

        return partitionByMedian(leaves, start, end, workspace.longestCentroidAxis());
    }

    private static int partitionByMedian(
            Leaves leaves, int start, int end, int axis) {
        sortByAxis(leaves, start, end, axis);
        return start + (end - start) / 2;
    }

    private static float saohCost(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float power,
            LightDirection.Bounds direction) {
        float x = Math.max(maxX - minX, 0.0F);
        float y = Math.max(maxY - minY, 0.0F);
        float z = Math.max(maxZ - minZ, 0.0F);
        float area = 2.0F * (x * y + y * z + z * x);
        float spatialMeasure = area > 0.0F
                ? area
                : (float) Math.sqrt(x * x + y * y + z * z);
        return power * spatialMeasure * (1.0F + LightDirection.spread(direction));
    }

    private static float rangeSaohCost(Leaves leaves, int start, int end) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        float power = 0.0F;
        LightDirection.Bounds direction = null;
        for (int index = start; index < end; index++) {
            minX = Math.min(minX, leaves.minX[index]);
            minY = Math.min(minY, leaves.minY[index]);
            minZ = Math.min(minZ, leaves.minZ[index]);
            maxX = Math.max(maxX, leaves.maxX[index]);
            maxY = Math.max(maxY, leaves.maxY[index]);
            maxZ = Math.max(maxZ, leaves.maxZ[index]);
            direction = combineDirections(
                    direction, power, leaves.direction[index], leaves.power[index]);
            power += leaves.power[index];
        }
        return saohCost(minX, minY, minZ, maxX, maxY, maxZ, power, direction);
    }

    /** Returns the balanced binary continuation depth used as a receiver-independent estimate. */
    private static double balancedContinuationDepth(int count) {
        if (count <= 1) {
            return 0.0;
        }
        return Math.log(count) / Math.log(2.0);
    }

    private static LightDirection.Bounds aggregateDirection(Leaves leaves, int start, int end) {
        float power = 0.0F;
        LightDirection.Bounds direction = null;
        for (int index = start; index < end; index++) {
            direction = combineDirections(
                    direction, power, leaves.direction[index], leaves.power[index]);
            power += leaves.power[index];
        }
        return direction != null ? direction : LightDirection.full();
    }

    private static int packPath(int trail, int depth) {
        if (depth < 0 || depth > MAX_PATH_DEPTH || (trail & ~PATH_TRAIL_MASK) != 0) {
            throw new IllegalArgumentException("Light tree bit trail is not packable");
        }
        return depth << PATH_DEPTH_SHIFT | trail;
    }

    private record Split(int axis, int bucket) {}

    private static void sortByAxis(Leaves leaves, int start, int end, int axis) {
        int count = end - start;
        for (int root = count / 2 - 1; root >= 0; root--) {
            siftDown(leaves, start, root, count, axis);
        }
        for (int last = count - 1; last > 0; last--) {
            leaves.swap(start, start + last);
            siftDown(leaves, start, 0, last, axis);
        }
    }

    private static void siftDown(
            Leaves leaves, int start, int root, int count, int axis) {
        while (true) {
            int child = root * 2 + 1;
            if (child >= count) {
                return;
            }
            if (child + 1 < count
                    && leaves.compare(start + child, start + child + 1, axis) < 0) {
                child++;
            }
            if (leaves.compare(start + root, start + child, axis) >= 0) {
                return;
            }
            leaves.swap(start + root, start + child);
            root = child;
        }
    }

    private static int binIndex(float center, float minimum, float extent) {
        float scaled = Math.max(0.0F, Math.min(Math.nextDown(1.0F), (center - minimum) / extent));
        return Math.min((int) (scaled * SAH_BIN_COUNT), SAH_BIN_COUNT - 1);
    }

    private static float surfaceArea(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        float x = Math.max(maxX - minX, 0.0F);
        float y = Math.max(maxY - minY, 0.0F);
        float z = Math.max(maxZ - minZ, 0.0F);
        return 2.0F * (x * y + y * z + z * x);
    }

    private static float quantizedCentroidAxis(
            float center, float minimum, float maximum) {
        if (!(maximum > minimum)) {
            return minimum;
        }
        float normalized = Math.max(0.0F, Math.min(1.0F, (center - minimum) / (maximum - minimum)));
        int quantized = Math.round(normalized * 1023.0F);
        return minimum + (maximum - minimum) * ((float) quantized / 1023.0F);
    }

    private static LightDirection.Bounds combineDirections(
            LightDirection.Bounds first,
            float firstPower,
            LightDirection.Bounds second,
            float secondPower) {
        if (!(firstPower > 0.0F)) {
            return secondPower > 0.0F ? second : null;
        }
        if (!(secondPower > 0.0F)) {
            return first;
        }
        return LightDirection.combine(first, firstPower, second, secondPower);
    }

    static record Leaf(
            Bounds bounds,
            float centerX,
            float centerY,
            float centerZ,
            float power,
            int index,
            LightDirection.Bounds direction) {
        Leaf(Bounds bounds, float centerX, float centerY, float centerZ, float power, int index) {
            this(bounds, centerX, centerY, centerZ, power, index, LightDirection.full());
        }

        Leaf {
            validateLeaf(bounds, centerX, centerY, centerZ, power);
            if (direction == null) {
                throw new IllegalArgumentException("Light direction must not be null");
            }
        }
    }

    static record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }

    static final class Result {
        private final Nodes nodes;
        private final TerminalLeaves terminals;
        private final int[] leafNodes;
        private final int[] leafPaths;

        private Result(
                Nodes nodes, TerminalLeaves terminals, int[] leafNodes, int[] leafPaths) {
            this.nodes = nodes;
            this.terminals = terminals;
            this.leafNodes = leafNodes;
            this.leafPaths = leafPaths;
        }

        int leafCapacity() {
            return this.leafNodes.length;
        }

        double treeCost() {
            double cost = 0.0;
            for (int node = 0; node < this.nodes.size; node++) {
                if (this.nodes.secondChild[node] != NO_INDEX) {
                    cost += (double) surfaceArea(
                                    this.nodes.minX[node],
                                    this.nodes.minY[node],
                                    this.nodes.minZ[node],
                                    this.nodes.maxX[node],
                                    this.nodes.maxY[node],
                                    this.nodes.maxZ[node])
                            * this.nodes.power[node];
                }
            }
            return cost;
        }

        int[] packNodes() {
            int[] result = new int[this.nodes.size * WORDS_PER_NODE];
            int cursor = 0;
            for (int node = 0; node < this.nodes.size; node++) {
                cursor = packNode(result, cursor, node);
            }
            return result;
        }

        int[] packLeaves() {
            return this.terminals.pack();
        }

        void packInto(int[] target, int nodeWordOffset, int leafWordOffset) {
            int nodeCursor = nodeWordOffset;
            for (int node = 0; node < this.nodes.size; node++) {
                nodeCursor = packNode(target, nodeCursor, node);
            }
            this.terminals.packInto(target, leafWordOffset);
        }

        private int packNode(int[] target, int cursor, int node) {
            // Preserve the former 10-bit proposal centroid exactly while dropping bounds that
            // traversal never consumed after unpacking it.
            target[cursor++] = Float.floatToRawIntBits(quantizedCentroidAxis(
                    this.nodes.centerX[node], this.nodes.minX[node], this.nodes.maxX[node]));
            target[cursor++] = Float.floatToRawIntBits(quantizedCentroidAxis(
                    this.nodes.centerY[node], this.nodes.minY[node], this.nodes.maxY[node]));
            target[cursor++] = Float.floatToRawIntBits(quantizedCentroidAxis(
                    this.nodes.centerZ[node], this.nodes.minZ[node], this.nodes.maxZ[node]));
            target[cursor++] = Float.floatToRawIntBits(this.nodes.power[node]);
            target[cursor++] = LightDirection.pack(this.nodes.direction[node]);
            int childOrLeaf = this.nodes.firstChildOrLeaf[node];
            int secondChild = this.nodes.secondChild[node];
            if (childOrLeaf < 0) {
                throw new IllegalStateException("Light tree node was not populated");
            }
            if (secondChild == NO_INDEX) {
                childOrLeaf |= LEAF_FLAG;
            } else if (secondChild != childOrLeaf + 1) {
                throw new IllegalStateException("Light tree siblings must be consecutive");
            }
            target[cursor++] = childOrLeaf;
            target[cursor++] = 0;
            target[cursor++] = 0;
            return cursor;
        }

        int leafNode(int leafIndex) {
            return this.leafNodes[leafIndex];
        }

        int leafPath(int leafIndex) {
            return this.leafPaths[leafIndex];
        }

        Bounds bounds() {
            return this.nodes.bounds(0);
        }

        float power() {
            return this.nodes.power[0];
        }

        int packedDirection() {
            return LightDirection.pack(this.nodes.direction[0]);
        }

        int nodeCount() {
            return this.nodes.size;
        }

        int leafCount() {
            return this.terminals.size;
        }
    }

    private static final class TerminalLeaves {
        private final int[] index;
        private final float[] power;
        private int size;

        private TerminalLeaves(int capacity) {
            this.index = new int[capacity];
            this.power = new float[capacity];
        }

        private int add(Leaves leaves, int start, int end) {
            int count = end - start;
            if (count != LIGHTS_PER_LEAF) {
                throw new IllegalStateException("Invalid singleton light leaf size " + count);
            }
            int leaf = this.size++;
            this.index[leaf] = leaves.index[start];
            this.power[leaf] = leaves.power[start];
            return leaf;
        }

        private int[] pack() {
            int[] result = new int[this.size * WORDS_PER_LEAF];
            packInto(result, 0);
            return result;
        }

        private void packInto(int[] target, int offset) {
            int cursor = offset;
            for (int leaf = 0; leaf < this.size; leaf++) {
                target[cursor++] = this.index[leaf];
                target[cursor++] = Float.floatToRawIntBits(this.power[leaf]);
            }
        }
    }

    static final class Leaves {
        private final float[] minX;
        private final float[] minY;
        private final float[] minZ;
        private final float[] maxX;
        private final float[] maxY;
        private final float[] maxZ;
        private final float[] centerX;
        private final float[] centerY;
        private final float[] centerZ;
        private final float[] power;
        private final int[] index;
        private final LightDirection.Bounds[] direction;
        int size;

        Leaves(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("Negative light leaf capacity");
            }
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
            this.centerX = new float[capacity];
            this.centerY = new float[capacity];
            this.centerZ = new float[capacity];
            this.power = new float[capacity];
            this.index = new int[capacity];
            this.direction = new LightDirection.Bounds[capacity];
        }

        void add(
                Bounds bounds,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index) {
            add(bounds, centerX, centerY, centerZ, power, index, LightDirection.full());
        }

        void add(
                Bounds bounds,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            if (bounds == null) {
                throw new IllegalArgumentException("Light bounds must not be null");
            }
            add(
                    bounds.minX,
                    bounds.minY,
                    bounds.minZ,
                    bounds.maxX,
                    bounds.maxY,
                    bounds.maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    direction);
        }

        void addInactive(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                int index) {
            validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
            append(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    (minX + maxX) * 0.5F,
                    (minY + maxY) * 0.5F,
                    (minZ + maxZ) * 0.5F,
                    0.0F,
                    index,
                    LightDirection.full());
        }

        void add(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index) {
            add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    LightDirection.full());
        }

        void add(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            validateLeaf(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power);
            append(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    direction);
        }

        private void append(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            if (direction == null) {
                throw new IllegalArgumentException("Light direction must not be null");
            }
            int slot = this.size++;
            this.minX[slot] = minX;
            this.minY[slot] = minY;
            this.minZ[slot] = minZ;
            this.maxX[slot] = maxX;
            this.maxY[slot] = maxY;
            this.maxZ[slot] = maxZ;
            this.centerX[slot] = centerX;
            this.centerY[slot] = centerY;
            this.centerZ[slot] = centerZ;
            this.power[slot] = power;
            this.index[slot] = index;
            this.direction[slot] = direction;
        }

        private float center(int slot, int axis) {
            return switch (axis) {
                case 0 -> this.centerX[slot];
                case 1 -> this.centerY[slot];
                case 2 -> this.centerZ[slot];
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private int compare(int first, int second, int axis) {
            int compared = Float.compare(center(first, axis), center(second, axis));
            return compared != 0 ? compared : Integer.compare(this.index[first], this.index[second]);
        }

        private void swap(int first, int second) {
            if (first == second) {
                return;
            }
            swap(this.minX, first, second);
            swap(this.minY, first, second);
            swap(this.minZ, first, second);
            swap(this.maxX, first, second);
            swap(this.maxY, first, second);
            swap(this.maxZ, first, second);
            swap(this.centerX, first, second);
            swap(this.centerY, first, second);
            swap(this.centerZ, first, second);
            swap(this.power, first, second);
            LightDirection.Bounds direction = this.direction[first];
            this.direction[first] = this.direction[second];
            this.direction[second] = direction;
            int index = this.index[first];
            this.index[first] = this.index[second];
            this.index[second] = index;
        }

        private static void swap(float[] values, int first, int second) {
            float value = values[first];
            values[first] = values[second];
            values[second] = value;
        }
    }

    private static void validateLeaf(
            Bounds bounds, float centerX, float centerY, float centerZ, float power) {
        if (bounds == null) {
            throw new IllegalArgumentException("Light bounds must not be null");
        }
        validateLeaf(
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ,
                centerX,
                centerY,
                centerZ,
                power);
    }

    private static void validateLeaf(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float centerX,
            float centerY,
            float centerZ,
            float power) {
        validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        if (!Float.isFinite(centerX)
                || !Float.isFinite(centerY)
                || !Float.isFinite(centerZ)) {
            throw new IllegalArgumentException("Light bounds and center must be finite and ordered");
        }
        if (!(power > 0.0F) || !Float.isFinite(power)) {
            throw new IllegalArgumentException("Light power must be finite and positive");
        }
    }

    private static void validateBounds(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        if (!Float.isFinite(minX)
                || !Float.isFinite(minY)
                || !Float.isFinite(minZ)
                || !Float.isFinite(maxX)
                || !Float.isFinite(maxY)
                || !Float.isFinite(maxZ)
                || minX > maxX
                || minY > maxY
                || minZ > maxZ) {
            throw new IllegalArgumentException("Light bounds must be finite and ordered");
        }
    }

    private static final class Nodes {
        private final float[] minX;
        private final float[] minY;
        private final float[] minZ;
        private final float[] maxX;
        private final float[] maxY;
        private final float[] maxZ;
        private final float[] centerX;
        private final float[] centerY;
        private final float[] centerZ;
        private final float[] power;
        private final int[] firstChildOrLeaf;
        private final int[] secondChild;
        private final LightDirection.Bounds[] direction;
        private int size;

        private Nodes(int capacity) {
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
            this.centerX = new float[capacity];
            this.centerY = new float[capacity];
            this.centerZ = new float[capacity];
            this.power = new float[capacity];
            this.firstChildOrLeaf = new int[capacity];
            this.secondChild = new int[capacity];
            this.direction = new LightDirection.Bounds[capacity];
            Arrays.fill(this.firstChildOrLeaf, NO_INDEX);
            Arrays.fill(this.secondChild, NO_INDEX);
            Arrays.fill(this.direction, LightDirection.full());
        }

        private int add(
                Leaves leaves,
                int start,
                int end) {
            int index = this.size++;
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float power = 0.0F;
            double momentPower = 0.0;
            double meanX = 0.0;
            double meanY = 0.0;
            double meanZ = 0.0;
            for (int leaf = start; leaf < end; leaf++) {
                float leafPower = leaves.power[leaf];
                if (leafPower > 0.0F) {
                    minX = Math.min(minX, leaves.minX[leaf]);
                    minY = Math.min(minY, leaves.minY[leaf]);
                    minZ = Math.min(minZ, leaves.minZ[leaf]);
                    maxX = Math.max(maxX, leaves.maxX[leaf]);
                    maxY = Math.max(maxY, leaves.maxY[leaf]);
                    maxZ = Math.max(maxZ, leaves.maxZ[leaf]);
                    power += leafPower;
                    double nextPower = momentPower + leafPower;
                    double deltaX = leaves.centerX[leaf] - meanX;
                    double deltaY = leaves.centerY[leaf] - meanY;
                    double deltaZ = leaves.centerZ[leaf] - meanZ;
                    double centerWeight = leafPower / nextPower;
                    meanX += centerWeight * deltaX;
                    meanY += centerWeight * deltaY;
                    meanZ += centerWeight * deltaZ;
                    momentPower = nextPower;
                }
            }
            float centerX;
            float centerY;
            float centerZ;
            if (power == 0.0F) {
                minX = leaves.minX[start];
                minY = leaves.minY[start];
                minZ = leaves.minZ[start];
                maxX = leaves.maxX[start];
                maxY = leaves.maxY[start];
                maxZ = leaves.maxZ[start];
                centerX = leaves.centerX[start];
                centerY = leaves.centerY[start];
                centerZ = leaves.centerZ[start];
            } else if (!Float.isFinite(power)) {
                throw new IllegalArgumentException("Aggregate light power exceeds f32 range");
            } else {
                centerX = (float) meanX;
                centerY = (float) meanY;
                centerZ = (float) meanZ;
            }
            setBounds(
                    index,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power);
            return index;
        }

        private void refitDirection(int index) {
            int first = this.firstChildOrLeaf[index];
            int second = this.secondChild[index];
            this.direction[index] = LightDirection.combine(
                    this.direction[first],
                    this.power[first],
                    this.direction[second],
                    this.power[second]);
        }

        private void setBounds(
                int index,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power) {
            this.minX[index] = minX;
            this.minY[index] = minY;
            this.minZ[index] = minZ;
            this.maxX[index] = maxX;
            this.maxY[index] = maxY;
            this.maxZ[index] = maxZ;
            this.centerX[index] = centerX;
            this.centerY[index] = centerY;
            this.centerZ[index] = centerZ;
            this.power[index] = power;
        }

        private Bounds bounds(int index) {
            return new Bounds(
                    this.minX[index],
                    this.minY[index],
                    this.minZ[index],
                    this.maxX[index],
                    this.maxY[index],
                    this.maxZ[index]);
        }
    }

    private static final class Bin {
        private float minX;
        private float minY;
        private float minZ;
        private float maxX;
        private float maxY;
        private float maxZ;
        private float power;
        private LightDirection.Bounds direction;
        private int count;

        private void reset() {
            this.minX = Float.POSITIVE_INFINITY;
            this.minY = Float.POSITIVE_INFINITY;
            this.minZ = Float.POSITIVE_INFINITY;
            this.maxX = Float.NEGATIVE_INFINITY;
            this.maxY = Float.NEGATIVE_INFINITY;
            this.maxZ = Float.NEGATIVE_INFINITY;
            this.power = 0.0F;
            this.direction = null;
            this.count = 0;
        }

        private void include(Leaves leaves, int index) {
            this.minX = Math.min(this.minX, leaves.minX[index]);
            this.minY = Math.min(this.minY, leaves.minY[index]);
            this.minZ = Math.min(this.minZ, leaves.minZ[index]);
            this.maxX = Math.max(this.maxX, leaves.maxX[index]);
            this.maxY = Math.max(this.maxY, leaves.maxY[index]);
            this.maxZ = Math.max(this.maxZ, leaves.maxZ[index]);
            float leafPower = leaves.power[index];
            this.direction = combineDirections(
                    this.direction, this.power, leaves.direction[index], leafPower);
            this.power += leafPower;
            this.count++;
        }
    }

    private static final class Workspace {
        private final Bin[] bins = new Bin[SAH_BIN_COUNT];
        private final float[] prefixMinX = new float[SAH_BIN_COUNT];
        private final float[] prefixMinY = new float[SAH_BIN_COUNT];
        private final float[] prefixMinZ = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxX = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxY = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxZ = new float[SAH_BIN_COUNT];
        private final float[] prefixPower = new float[SAH_BIN_COUNT];
        private final LightDirection.Bounds[] prefixDirection =
                new LightDirection.Bounds[SAH_BIN_COUNT];
        private final int[] prefixCount = new int[SAH_BIN_COUNT];
        private final float[] suffixMinX = new float[SAH_BIN_COUNT];
        private final float[] suffixMinY = new float[SAH_BIN_COUNT];
        private final float[] suffixMinZ = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxX = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxY = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxZ = new float[SAH_BIN_COUNT];
        private final float[] suffixPower = new float[SAH_BIN_COUNT];
        private final LightDirection.Bounds[] suffixDirection =
                new LightDirection.Bounds[SAH_BIN_COUNT];
        private final int[] suffixCount = new int[SAH_BIN_COUNT];
        private final int[] candidateAxis = new int[SAH_CANDIDATE_COUNT];
        private final int[] candidateSplit = new int[SAH_CANDIDATE_COUNT];
        private final float[] candidateCost = new float[SAH_CANDIDATE_COUNT];
        private final double[] candidateExpectedDepth = new double[SAH_CANDIDATE_COUNT];
        private int candidateCount;
        private float centroidMinX;
        private float centroidMinY;
        private float centroidMinZ;
        private float centroidMaxX;
        private float centroidMaxY;
        private float centroidMaxZ;

        private Workspace() {
            for (int index = 0; index < this.bins.length; index++) {
                this.bins[index] = new Bin();
            }
        }

        private void resetCandidates() {
            this.candidateCount = 0;
        }

        private void addCandidate(int axis, int split, float cost, double expectedDepth) {
            if (!Float.isFinite(cost)) {
                return;
            }
            int index = this.candidateCount++;
            this.candidateAxis[index] = axis;
            this.candidateSplit[index] = split;
            this.candidateCost[index] = cost;
            this.candidateExpectedDepth[index] = expectedDepth;
        }

        private int bestCandidate(float unsplitCost) {
            if (this.candidateCount == 0) {
                return -1;
            }
            int minimumCostCandidate = 0;
            for (int index = 1; index < this.candidateCount; index++) {
                if (this.candidateCost[index] < this.candidateCost[minimumCostCandidate]) {
                    minimumCostCandidate = index;
                }
            }
            double qualityLimit = this.candidateCost[minimumCostCandidate]
                    * SAOH_DEPTH_QUALITY_BAND;
            boolean minimumImproves = this.candidateCost[minimumCostCandidate] < unsplitCost;
            int best = minimumCostCandidate;
            for (int index = 0; index < this.candidateCount; index++) {
                if (this.candidateCost[index] > qualityLimit
                        || (minimumImproves && !(this.candidateCost[index] < unsplitCost))) {
                    continue;
                }
                int comparedDepth = Double.compare(
                        this.candidateExpectedDepth[index],
                        this.candidateExpectedDepth[best]);
                if (comparedDepth < 0
                        || (comparedDepth == 0
                                && this.candidateCost[index] < this.candidateCost[best])) {
                    best = index;
                }
            }
            return best;
        }

        private void aggregate() {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float power = 0.0F;
            LightDirection.Bounds direction = null;
            int count = 0;
            for (int index = 0; index < SAH_BIN_COUNT; index++) {
                Bin bin = this.bins[index];
                if (bin.count != 0) {
                    minX = Math.min(minX, bin.minX);
                    minY = Math.min(minY, bin.minY);
                    minZ = Math.min(minZ, bin.minZ);
                    maxX = Math.max(maxX, bin.maxX);
                    maxY = Math.max(maxY, bin.maxY);
                    maxZ = Math.max(maxZ, bin.maxZ);
                    direction = combineDirections(direction, power, bin.direction, bin.power);
                    power += bin.power;
                    count += bin.count;
                }
                this.prefixMinX[index] = minX;
                this.prefixMinY[index] = minY;
                this.prefixMinZ[index] = minZ;
                this.prefixMaxX[index] = maxX;
                this.prefixMaxY[index] = maxY;
                this.prefixMaxZ[index] = maxZ;
                this.prefixPower[index] = power;
                this.prefixDirection[index] = direction;
                this.prefixCount[index] = count;
            }

            minX = Float.POSITIVE_INFINITY;
            minY = Float.POSITIVE_INFINITY;
            minZ = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY;
            maxY = Float.NEGATIVE_INFINITY;
            maxZ = Float.NEGATIVE_INFINITY;
            power = 0.0F;
            direction = null;
            count = 0;
            for (int index = SAH_BIN_COUNT - 1; index >= 0; index--) {
                Bin bin = this.bins[index];
                if (bin.count != 0) {
                    minX = Math.min(minX, bin.minX);
                    minY = Math.min(minY, bin.minY);
                    minZ = Math.min(minZ, bin.minZ);
                    maxX = Math.max(maxX, bin.maxX);
                    maxY = Math.max(maxY, bin.maxY);
                    maxZ = Math.max(maxZ, bin.maxZ);
                    direction = combineDirections(direction, power, bin.direction, bin.power);
                    power += bin.power;
                    count += bin.count;
                }
                this.suffixMinX[index] = minX;
                this.suffixMinY[index] = minY;
                this.suffixMinZ[index] = minZ;
                this.suffixMaxX[index] = maxX;
                this.suffixMaxY[index] = maxY;
                this.suffixMaxZ[index] = maxZ;
                this.suffixPower[index] = power;
                this.suffixDirection[index] = direction;
                this.suffixCount[index] = count;
            }
        }

        private void findCentroidBounds(Leaves leaves, int start, int end) {
            this.centroidMinX = Float.POSITIVE_INFINITY;
            this.centroidMinY = Float.POSITIVE_INFINITY;
            this.centroidMinZ = Float.POSITIVE_INFINITY;
            this.centroidMaxX = Float.NEGATIVE_INFINITY;
            this.centroidMaxY = Float.NEGATIVE_INFINITY;
            this.centroidMaxZ = Float.NEGATIVE_INFINITY;
            for (int index = start; index < end; index++) {
                this.centroidMinX = Math.min(this.centroidMinX, leaves.centerX[index]);
                this.centroidMinY = Math.min(this.centroidMinY, leaves.centerY[index]);
                this.centroidMinZ = Math.min(this.centroidMinZ, leaves.centerZ[index]);
                this.centroidMaxX = Math.max(this.centroidMaxX, leaves.centerX[index]);
                this.centroidMaxY = Math.max(this.centroidMaxY, leaves.centerY[index]);
                this.centroidMaxZ = Math.max(this.centroidMaxZ, leaves.centerZ[index]);
            }
        }

        private float centroidMinimum(int axis) {
            return switch (axis) {
                case 0 -> this.centroidMinX;
                case 1 -> this.centroidMinY;
                case 2 -> this.centroidMinZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private float centroidMaximum(int axis) {
            return switch (axis) {
                case 0 -> this.centroidMaxX;
                case 1 -> this.centroidMaxY;
                case 2 -> this.centroidMaxZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private int longestCentroidAxis() {
            float x = this.centroidMaxX - this.centroidMinX;
            float y = this.centroidMaxY - this.centroidMinY;
            float z = this.centroidMaxZ - this.centroidMinZ;
            if (x >= y && x >= z) {
                return 0;
            }
            return y >= z ? 1 : 2;
        }

        private float maximumCentroidExtent() {
            return Math.max(
                    this.centroidMaxX - this.centroidMinX,
                    Math.max(
                            this.centroidMaxY - this.centroidMinY,
                            this.centroidMaxZ - this.centroidMinZ));
        }
    }
}
