package dev.prime.render.terrain;

import java.util.Arrays;

/**
 * Fixed-capacity chord matching backend for {@link RectangleDecomposition64}.
 *
 * <p>The conflict graph and iterative Hopcroft-Karp path are derived from voxel_engine's
 * rectangle_decomposition crate at 3e13182214aa3bdf71d4769ca6b1078671a7842c.
 */
final class RectangleMatching64 {
    private static final int AXIS_LIMIT = 64;
    private static final int AXIS_LENGTH = AXIS_LIMIT + 1;
    private static final int GRID_POINTS = AXIS_LENGTH * AXIS_LENGTH;
    private static final int MAX_CHORDS = AXIS_LIMIT * (AXIS_LIMIT - 1);
    private static final int MAX_CONFLICT_EDGES =
            (AXIS_LIMIT - 1) * (AXIS_LIMIT - 1);
    private static final char UNMATCHED = Character.MAX_VALUE;

    private RectangleMatching64() {}

    static final class Scratch {
        private final int[] nextOffsets = new int[MAX_CHORDS];
        private final int[] edgeBuffer = new int[MAX_CONFLICT_EDGES];
        private final int[] adjacencyOffsets = new int[MAX_CHORDS + 1];
        private final int[] adjacencyEdges = new int[MAX_CONFLICT_EDGES];
        private final char[] horizontalGrid = new char[GRID_POINTS];
        private final char[] horizontalGridMarks = new char[GRID_POINTS];
        private final long[] horizontalYMasks = new long[AXIS_LENGTH];
        private final char[] horizontalXMarks = new char[AXIS_LENGTH];

        private final char[] pairLeft = new char[MAX_CHORDS];
        private final char[] pairRight = new char[MAX_CHORDS];
        private final char[] distance = new char[MAX_CHORDS];
        private final char[] queue = new char[MAX_CHORDS];
        private final int[] nextEdge = new int[MAX_CHORDS];
        private final char[] unmatchedLefts = new char[MAX_CHORDS];
        private final char[] touchedLefts = new char[MAX_CHORDS];
        private final boolean[] reachableLeft = new boolean[MAX_CHORDS];
        private final boolean[] reachableRight = new boolean[MAX_CHORDS];
        private final int[] dfsLeftStack = new int[MAX_CHORDS];
        private final int[] dfsEdgeStack = new int[MAX_CHORDS];

        private final char[] selectedHorizontal = new char[MAX_CHORDS];
        private final char[] selectedVertical = new char[MAX_CHORDS];

        private char gridMark;
        private int edgeCount;
        private int selectedHorizontalCount;
        private int selectedVerticalCount;

        Scratch() {
            Arrays.fill(this.horizontalGrid, UNMATCHED);
        }

        void selectMaximumIndependentSet(
                long[] horizontal,
                int horizontalStart,
                int horizontalEnd,
                long[] vertical,
                int verticalStart,
                int verticalEnd) {
            int leftSize = horizontalEnd - horizontalStart;
            int rightSize = verticalEnd - verticalStart;
            this.selectedHorizontalCount = 0;
            this.selectedVerticalCount = 0;
            if (leftSize == 0 || rightSize == 0) {
                for (int index = 0; index < leftSize; index++) {
                    this.selectedHorizontal[this.selectedHorizontalCount++] = (char) index;
                }
                for (int index = 0; index < rightSize; index++) {
                    this.selectedVertical[this.selectedVerticalCount++] = (char) index;
                }
                return;
            }

            this.buildConflictGraph(
                    horizontal,
                    horizontalStart,
                    leftSize,
                    vertical,
                    verticalStart,
                    rightSize);
            this.hopcroftKarp(leftSize, rightSize);
            for (int index = 0; index < leftSize; index++) {
                if (this.reachableLeft[index]) {
                    this.selectedHorizontal[this.selectedHorizontalCount++] = (char) index;
                }
            }
            for (int index = 0; index < rightSize; index++) {
                if (!this.reachableRight[index]) {
                    this.selectedVertical[this.selectedVerticalCount++] = (char) index;
                }
            }
        }

        int selectedHorizontalCount() {
            return this.selectedHorizontalCount;
        }

        int selectedHorizontal(int index) {
            return this.selectedHorizontal[index];
        }

        int selectedVerticalCount() {
            return this.selectedVerticalCount;
        }

        int selectedVertical(int index) {
            return this.selectedVertical[index];
        }

        private void buildConflictGraph(
                long[] horizontal,
                int horizontalStart,
                int leftSize,
                long[] vertical,
                int verticalStart,
                int rightSize) {
            this.resetGrid();
            this.edgeCount = 0;

            for (int index = 0; index < leftSize; index++) {
                int chord = RectangleDecomposition64.chord(
                        horizontal[horizontalStart + index]);
                int y = RectangleDecomposition64.chordY1(chord);
                int start = Math.max(RectangleDecomposition64.chordX1(chord), 1);
                int end = Math.min(RectangleDecomposition64.chordX2(chord), 63);
                for (int x = start; x <= end; x++) {
                    int slot = gridIndex(x, y);
                    if (this.horizontalGridMarks[slot] == this.gridMark) {
                        throw new IllegalStateException(
                                "Horizontal chords overlap at one internal grid point");
                    }
                    this.horizontalGrid[slot] = (char) index;
                    this.horizontalGridMarks[slot] = this.gridMark;
                    if (this.horizontalXMarks[x] != this.gridMark) {
                        this.horizontalXMarks[x] = this.gridMark;
                        this.horizontalYMasks[x] = 0L;
                    }
                    this.horizontalYMasks[x] |= 1L << y;
                }
            }

            for (int index = 0; index < rightSize; index++) {
                int chord = RectangleDecomposition64.chord(vertical[verticalStart + index]);
                int x = RectangleDecomposition64.chordX1(chord);
                long active = this.horizontalXMarks[x] == this.gridMark
                        ? this.horizontalYMasks[x]
                                & internalMask(
                                        RectangleDecomposition64.chordY1(chord),
                                        RectangleDecomposition64.chordY2(chord))
                        : 0L;
                while (active != 0L) {
                    int y = Long.numberOfTrailingZeros(active);
                    int slot = gridIndex(x, y);
                    if (this.horizontalGridMarks[slot] != this.gridMark) {
                        throw new IllegalStateException("Conflict grid mark is inconsistent");
                    }
                    if (this.edgeCount >= this.edgeBuffer.length) {
                        throw new IllegalStateException("Conflict edge capacity was exceeded");
                    }
                    int left = this.horizontalGrid[slot];
                    this.edgeBuffer[this.edgeCount++] = left << 16 | index;
                    active &= active - 1L;
                }
            }

            this.buildAdjacency(leftSize);
        }

        private void buildAdjacency(int leftSize) {
            Arrays.fill(this.adjacencyOffsets, 0, leftSize + 1, 0);
            for (int index = 0; index < this.edgeCount; index++) {
                int left = this.edgeBuffer[index] >>> 16;
                this.adjacencyOffsets[left + 1]++;
            }
            for (int index = 1; index <= leftSize; index++) {
                this.adjacencyOffsets[index] += this.adjacencyOffsets[index - 1];
            }

            System.arraycopy(this.adjacencyOffsets, 0, this.nextOffsets, 0, leftSize);
            for (int index = 0; index < this.edgeCount; index++) {
                int edge = this.edgeBuffer[index];
                int left = edge >>> 16;
                this.adjacencyEdges[this.nextOffsets[left]++] = edge & 0xffff;
            }

        }

        private void resetGrid() {
            this.gridMark++;
            if (this.gridMark != 0) {
                return;
            }
            this.gridMark = 1;
            Arrays.fill(this.horizontalGridMarks, (char) 0);
            Arrays.fill(this.horizontalXMarks, (char) 0);
        }

        private void hopcroftKarp(int leftSize, int rightSize) {
            Arrays.fill(this.pairLeft, 0, leftSize, UNMATCHED);
            Arrays.fill(this.pairRight, 0, rightSize, UNMATCHED);

            Arrays.fill(this.distance, 0, leftSize, UNMATCHED);
            Arrays.fill(this.reachableLeft, 0, leftSize, false);
            Arrays.fill(this.reachableRight, 0, rightSize, false);
            int unmatchedCount = 0;
            for (int left = 0; left < leftSize; left++) {
                if (this.pairLeft[left] == UNMATCHED) {
                    this.unmatchedLefts[unmatchedCount++] = (char) left;
                }
            }
            int touchedCount = 0;

            while (unmatchedCount != 0) {
                for (int index = 0; index < touchedCount; index++) {
                    this.distance[this.touchedLefts[index]] = UNMATCHED;
                }
                touchedCount = 0;
                int queueCount = 0;
                int head = 0;
                boolean foundAugmentingPath = false;

                for (int index = 0; index < unmatchedCount; index++) {
                    int left = this.unmatchedLefts[index];
                    this.distance[left] = 0;
                    this.nextEdge[left] = this.adjacencyOffsets[left];
                    this.touchedLefts[touchedCount++] = (char) left;
                    this.queue[queueCount++] = (char) left;
                }

                while (head < queueCount) {
                    int left = this.queue[head++];
                    int start = this.adjacencyOffsets[left];
                    int end = this.adjacencyOffsets[left + 1];
                    for (int edge = start; edge < end; edge++) {
                        int right = this.adjacencyEdges[edge];
                        char matched = this.pairRight[right];
                        if (matched == UNMATCHED) {
                            foundAugmentingPath = true;
                        } else {
                            int nextLeft = matched;
                            if (this.distance[nextLeft] == UNMATCHED) {
                                this.distance[nextLeft] =
                                        (char) (this.distance[left] + 1);
                                this.nextEdge[nextLeft] =
                                        this.adjacencyOffsets[nextLeft];
                                this.touchedLefts[touchedCount++] =
                                        (char) nextLeft;
                                this.queue[queueCount++] = (char) nextLeft;
                            }
                        }
                    }
                }

                if (!foundAugmentingPath) {
                    for (int index = 0; index < touchedCount; index++) {
                        this.reachableLeft[this.touchedLefts[index]] = true;
                    }
                    for (int index = 0; index < touchedCount; index++) {
                        int left = this.touchedLefts[index];
                        int start = this.adjacencyOffsets[left];
                        int end = this.adjacencyOffsets[left + 1];
                        for (int edge = start; edge < end; edge++) {
                            int right = this.adjacencyEdges[edge];
                            if (this.pairLeft[left] != right) {
                                this.reachableRight[right] = true;
                            }
                        }
                    }
                    break;
                }

                for (int index = 0; index < unmatchedCount; index++) {
                    int left = this.unmatchedLefts[index];
                    if (this.pairLeft[left] == UNMATCHED) {
                        this.depthFirstAugment(left);
                    }
                }

                int retained = 0;
                for (int index = 0; index < unmatchedCount; index++) {
                    char left = this.unmatchedLefts[index];
                    if (this.pairLeft[left] == UNMATCHED) {
                        this.unmatchedLefts[retained++] = left;
                    }
                }
                unmatchedCount = retained;
            }
        }

        private boolean depthFirstAugment(int startLeft) {
            int stackSize = 1;
            this.dfsLeftStack[0] = startLeft;
            this.dfsEdgeStack[0] = this.nextEdge[startLeft];

            while (true) {
                int top = stackSize - 1;
                int left = this.dfsLeftStack[top];
                int edge = this.dfsEdgeStack[top];
                int end = this.adjacencyOffsets[left + 1];
                boolean found = false;

                while (edge < end) {
                    int right = this.adjacencyEdges[edge++];
                    char matched = this.pairRight[right];
                    if (matched == UNMATCHED) {
                        this.pairLeft[left] = (char) right;
                        this.pairRight[right] = (char) left;
                        this.dfsEdgeStack[top] = edge;
                        for (int index = top - 1; index >= 0; index--) {
                            int previousLeft = this.dfsLeftStack[index];
                            int previousEdge = this.dfsEdgeStack[index];
                            int previousRight =
                                    this.adjacencyEdges[previousEdge - 1];
                            this.pairLeft[previousLeft] =
                                    (char) previousRight;
                            this.pairRight[previousRight] =
                                    (char) previousLeft;
                            this.nextEdge[previousLeft] = previousEdge;
                        }
                        return true;
                    }

                    int nextLeft = matched;
                    if (this.distance[nextLeft]
                            == this.distance[left] + 1) {
                        this.dfsEdgeStack[top] = edge;
                        this.dfsLeftStack[stackSize] = nextLeft;
                        this.dfsEdgeStack[stackSize] =
                                this.nextEdge[nextLeft];
                        stackSize++;
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    this.distance[left] = UNMATCHED;
                    this.nextEdge[left] = edge;
                    stackSize--;
                    if (stackSize == 0) {
                        return false;
                    }
                }
            }
        }
    }

    private static int gridIndex(int x, int y) {
        return y * AXIS_LENGTH + x;
    }

    private static long internalMask(int start, int end) {
        int clampedStart = Math.max(start, 1);
        int clampedEnd = Math.min(end, 63);
        if (clampedStart > clampedEnd) {
            return 0L;
        }
        long startMask = -1L << clampedStart;
        long endMask = clampedEnd >= 63
                ? -1L
                : (1L << (clampedEnd + 1)) - 1L;
        return startMask & endMask;
    }
}
