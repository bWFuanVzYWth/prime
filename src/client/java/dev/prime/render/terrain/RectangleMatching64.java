// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;

/**
 * Fixed-capacity chord matching for {@link RectangleDecomposition64}, derived from
 * rectangle_decomposition at 334d078e6db2f0aa85898437714161190c4d50f2 (graph.rs, greedy.rs, hk.rs).
 * Each decomposition owner reuses its own scratch; no state is shared between workers.
 */
final class RectangleMatching64 {
    private static final int EDGE = 64;
    // Each internal line has 63 possible endpoints, consumed in disjoint pairs.
    static final int MAX_CHORDS = (EDGE - 1) * ((EDGE - 1) / 2);
    // Same-axis chords share no grid point; each conflict occupies one internal point.
    private static final int MAX_CONFLICT_EDGES = (EDGE - 1) * (EDGE - 1);
    private static final char UNMATCHED = Character.MAX_VALUE;

    private RectangleMatching64() {}

    static final class Scratch {
        private final int[] nextOffsets = new int[MAX_CHORDS];
        private final int[] adjacencyOffsets = new int[MAX_CHORDS + 1];
        private final char[] adjacencyEdges = new char[MAX_CONFLICT_EDGES];
        private final int[] transposeOffsets = new int[MAX_CHORDS + 1];
        private final char[] transposeEdges = new char[MAX_CONFLICT_EDGES];
        private final char[] rightOrder = new char[MAX_CHORDS];
        private final int[] degreeOffsets = new int[EDGE];
        private final char[] horizontalGrid = new char[EDGE * EDGE];
        private final long[] horizontalYMasks = new long[EDGE];
        private final char[] horizontalXMarks = new char[EDGE];

        private final char[] pairLeft = new char[MAX_CHORDS];
        private final char[] pairRight = new char[MAX_CHORDS];
        private final char[] rightDistance = new char[MAX_CHORDS];
        private final char[] queue = new char[MAX_CHORDS];
        private final char[] unmatchedLefts = new char[MAX_CHORDS];
        private final char[] shortestRoots = new char[MAX_CONFLICT_EDGES];
        private final boolean[] reachableLeft = new boolean[MAX_CHORDS];
        private final boolean[] reachableRight = new boolean[MAX_CHORDS];
        private final char[] dfsLeftStack = new char[MAX_CHORDS];
        private final char[] dfsEdgeStack = new char[MAX_CHORDS];

        private final char[] selectedHorizontal = new char[MAX_CHORDS];
        private final char[] selectedVertical = new char[MAX_CHORDS];

        private char gridMark;
        private int shortestRootCount;
        private int selectedHorizontalCount;
        private int selectedVerticalCount;

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
                    horizontal, horizontalStart, leftSize, vertical, verticalStart, rightSize);
            this.match(leftSize, rightSize);
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
            Arrays.fill(this.adjacencyOffsets, 0, leftSize + 1, 0);
            for (int index = 0; index < leftSize; index++) {
                int chord = RectangleDecomposition64.chord(horizontal[horizontalStart + index]);
                int y = RectangleDecomposition64.chordY1(chord);
                int start = RectangleDecomposition64.chordX1(chord);
                int end = RectangleDecomposition64.chordX2(chord);
                long bit = 1L << y;
                // Chord endpoints are internal concave corners. Include both in conflicts.
                for (int x = start; x <= end; x++) {
                    if (this.horizontalXMarks[x] != this.gridMark) {
                        this.horizontalXMarks[x] = this.gridMark;
                        this.horizontalYMasks[x] = 0L;
                    }
                    assert (this.horizontalYMasks[x] & bit) == 0L;
                    this.horizontalGrid[y * EDGE + x] = (char) index;
                    this.horizontalYMasks[x] |= bit;
                }
            }

            int edgeCount = 0;
            for (int right = 0; right < rightSize; right++) {
                this.transposeOffsets[right] = edgeCount;
                int chord = RectangleDecomposition64.chord(vertical[verticalStart + right]);
                int x = RectangleDecomposition64.chordX1(chord);
                long active = this.horizontalXMarks[x] == this.gridMark
                        ? this.horizontalYMasks[x] & internalMask(
                                RectangleDecomposition64.chordY1(chord),
                                RectangleDecomposition64.chordY2(chord))
                        : 0L;
                while (active != 0L) {
                    int y = Long.numberOfTrailingZeros(active);
                    // The column stamp and active bit jointly validate the otherwise stale slot.
                    int left = this.horizontalGrid[y * EDGE + x];
                    this.transposeEdges[edgeCount++] = (char) left;
                    this.adjacencyOffsets[left + 1]++;
                    active &= active - 1L;
                }
            }
            this.transposeOffsets[rightSize] = edgeCount;
            this.buildAdjacency(leftSize, rightSize);
        }

        private void buildAdjacency(int leftSize, int rightSize) {
            for (int left = 1; left <= leftSize; left++) {
                this.adjacencyOffsets[left] += this.adjacencyOffsets[left - 1];
            }
            System.arraycopy(this.adjacencyOffsets, 0, this.nextOffsets, 0, leftSize);
            Arrays.fill(this.degreeOffsets, 0);
            for (int right = 0; right < rightSize; right++) {
                int degree = this.transposeOffsets[right + 1] - this.transposeOffsets[right];
                this.degreeOffsets[degree]++;
            }
            int offset = 0;
            for (int degree = 0; degree < EDGE; degree++) {
                int count = this.degreeOffsets[degree];
                this.degreeOffsets[degree] = offset;
                offset += count;
            }
            for (int right = 0; right < rightSize; right++) {
                int degree = this.transposeOffsets[right + 1] - this.transposeOffsets[right];
                this.rightOrder[this.degreeOffsets[degree]++] = (char) right;
            }
            // Horizontal IDs follow boundary order, so each column lists increasing left IDs.
            // Enumerating vertical chords already built the transpose CSR. Reuse it and its
            // degree order for both stable (right degree, right id) scatter and greedy matching.
            for (int order = 0; order < rightSize; order++) {
                int right = this.rightOrder[order];
                int end = this.transposeOffsets[right + 1];
                for (int edge = this.transposeOffsets[right]; edge < end; edge++) {
                    int left = this.transposeEdges[edge];
                    this.adjacencyEdges[this.nextOffsets[left]++] = (char) right;
                }
            }
        }

        private void resetGrid() {
            this.gridMark++;
            if (this.gridMark == 0) {
                this.gridMark = 1;
                Arrays.fill(this.horizontalXMarks, (char) 0);
            }
        }

        private void initializeMatching(int leftSize, int rightSize) {
            Arrays.fill(this.pairLeft, 0, leftSize, UNMATCHED);
            Arrays.fill(this.pairRight, 0, rightSize, UNMATCHED);
            for (int left = 0; left < leftSize; left++) {
                int start = this.adjacencyOffsets[left];
                if (this.adjacencyOffsets[left + 1] - start == 1) {
                    int right = this.adjacencyEdges[start];
                    if (this.pairRight[right] == UNMATCHED) {
                        this.pairLeft[left] = (char) right;
                        this.pairRight[right] = (char) left;
                    }
                }
            }
            for (int order = 0; order < rightSize; order++) {
                int right = this.rightOrder[order];
                if (this.pairRight[right] != UNMATCHED) {
                    continue;
                }
                int end = this.transposeOffsets[right + 1];
                for (int edge = this.transposeOffsets[right]; edge < end; edge++) {
                    int left = this.transposeEdges[edge];
                    if (this.pairLeft[left] == UNMATCHED) {
                        this.pairLeft[left] = (char) right;
                        this.pairRight[right] = (char) left;
                        break;
                    }
                }
            }
            lefts: for (int left = 0; left < leftSize; left++) {
                if (this.pairLeft[left] != UNMATCHED) {
                    continue;
                }
                int end = this.adjacencyOffsets[left + 1];
                for (int edge = this.adjacencyOffsets[left]; edge < end; edge++) {
                    int right = this.adjacencyEdges[edge];
                    int matchedLeft = this.pairRight[right];
                    if (matchedLeft == UNMATCHED) {
                        this.pairLeft[left] = (char) right;
                        this.pairRight[right] = (char) left;
                        break;
                    }
                    int alternateEnd = this.adjacencyOffsets[matchedLeft + 1];
                    for (int alternate = this.adjacencyOffsets[matchedLeft];
                            alternate < alternateEnd; alternate++) {
                        int alternateRight = this.adjacencyEdges[alternate];
                        if (this.pairRight[alternateRight] == UNMATCHED) {
                            this.pairLeft[matchedLeft] = (char) alternateRight;
                            this.pairRight[alternateRight] = (char) matchedLeft;
                            this.pairLeft[left] = (char) right;
                            this.pairRight[right] = (char) left;
                            continue lefts;
                        }
                    }
                }
            }
        }

        private void match(int leftSize, int rightSize) {
            this.initializeMatching(leftSize, rightSize);
            int unmatchedCount = 0;
            for (int left = 0; left < leftSize; left++) {
                if (this.pairLeft[left] == UNMATCHED) {
                    this.unmatchedLefts[unmatchedCount++] = (char) left;
                }
            }
            while (unmatchedCount != 0) {
                int shortestDepth = this.buildReverseLevels(leftSize, rightSize);
                if (shortestDepth == UNMATCHED) {
                    break;
                }
                for (int index = 0; index < this.shortestRootCount; index++) {
                    int left = this.shortestRoots[index];
                    if (!this.reachableLeft[left] && this.pairLeft[left] == UNMATCHED) {
                        this.augment(left, shortestDepth);
                    }
                }
                // HKDW's additional DFS shares this phase's visited set, including failed
                // searches. Each left vertex is expanded at most once across both passes.
                int retained = 0;
                for (int index = 0; index < unmatchedCount; index++) {
                    int left = this.unmatchedLefts[index];
                    if (!this.reachableLeft[left] && this.pairLeft[left] == UNMATCHED) {
                        this.augment(left, UNMATCHED);
                    }
                    if (this.pairLeft[left] == UNMATCHED) {
                        this.unmatchedLefts[retained++] = (char) left;
                    }
                }
                unmatchedCount = retained;
            }
            this.collectReachable(leftSize, rightSize);
        }

        private int buildReverseLevels(int leftSize, int rightSize) {
            Arrays.fill(this.rightDistance, 0, rightSize, UNMATCHED);
            Arrays.fill(this.reachableLeft, 0, leftSize, false);
            this.shortestRootCount = 0;
            int queueCount = 0;
            for (int right = 0; right < rightSize; right++) {
                if (this.pairRight[right] == UNMATCHED) {
                    this.rightDistance[right] = 0;
                    this.queue[queueCount++] = (char) right;
                }
            }
            int shortestDepth = UNMATCHED;
            for (int head = 0; head < queueCount; head++) {
                int right = this.queue[head];
                int depth = this.rightDistance[right];
                if (depth > shortestDepth) {
                    break;
                }
                int end = this.transposeOffsets[right + 1];
                for (int edge = this.transposeOffsets[right]; edge < end; edge++) {
                    int left = this.transposeEdges[edge];
                    int matched = this.pairLeft[left];
                    if (matched == UNMATCHED) {
                        shortestDepth = depth;
                        this.shortestRoots[this.shortestRootCount++] = (char) left;
                    } else if (depth < shortestDepth && this.rightDistance[matched] == UNMATCHED) {
                        this.rightDistance[matched] = (char) (depth + 1);
                        this.queue[queueCount++] = (char) matched;
                    }
                }
            }
            return shortestDepth;
        }

        private void augment(int startLeft, int shortestDepth) {
            // UNMATCHED disables the depth restriction for this phase's additional paths.
            int stackSize = 1;
            this.dfsLeftStack[0] = (char) startLeft;
            this.dfsEdgeStack[0] = (char) this.adjacencyOffsets[startLeft];
            this.reachableLeft[startLeft] = true;
            while (stackSize != 0) {
                int top = stackSize - 1;
                int left = this.dfsLeftStack[top];
                int edge = this.dfsEdgeStack[top];
                int end = this.adjacencyOffsets[left + 1];
                boolean descended = false;
                while (edge < end) {
                    int right = this.adjacencyEdges[edge++];
                    if (shortestDepth != UNMATCHED
                            && this.rightDistance[right] + stackSize != shortestDepth + 1) {
                        continue;
                    }
                    int matched = this.pairRight[right];
                    if (matched == UNMATCHED) {
                        this.pairLeft[left] = (char) right;
                        this.pairRight[right] = (char) left;
                        for (int index = top - 1; index >= 0; index--) {
                            int previousLeft = this.dfsLeftStack[index];
                            int previousRight = this.adjacencyEdges[this.dfsEdgeStack[index] - 1];
                            this.pairLeft[previousLeft] = (char) previousRight;
                            this.pairRight[previousRight] = (char) previousLeft;
                        }
                        return;
                    }
                    if (!this.reachableLeft[matched]) {
                        this.dfsEdgeStack[top] = (char) edge;
                        this.reachableLeft[matched] = true;
                        this.dfsLeftStack[stackSize] = (char) matched;
                        this.dfsEdgeStack[stackSize++] = (char) this.adjacencyOffsets[matched];
                        descended = true;
                        break;
                    }
                }
                if (!descended) {
                    stackSize--;
                }
            }
        }

        private void collectReachable(int leftSize, int rightSize) {
            // Reverse BFS starts on the right. The independent-set formula instead needs
            // alternating reachability from unmatched left vertices after maximum matching.
            Arrays.fill(this.reachableLeft, 0, leftSize, false);
            Arrays.fill(this.reachableRight, 0, rightSize, false);
            int queueCount = 0;
            for (int left = 0; left < leftSize; left++) {
                if (this.pairLeft[left] == UNMATCHED) {
                    this.reachableLeft[left] = true;
                    this.queue[queueCount++] = (char) left;
                }
            }
            for (int head = 0; head < queueCount; head++) {
                int left = this.queue[head];
                int end = this.adjacencyOffsets[left + 1];
                for (int edge = this.adjacencyOffsets[left]; edge < end; edge++) {
                    int right = this.adjacencyEdges[edge];
                    if (this.pairLeft[left] == right) {
                        continue;
                    }
                    this.reachableRight[right] = true;
                    int matched = this.pairRight[right];
                    assert matched != UNMATCHED : "Unprocessed augmenting path";
                    if (!this.reachableLeft[matched]) {
                        this.reachableLeft[matched] = true;
                        this.queue[queueCount++] = (char) matched;
                    }
                }
            }
        }
    }

    private static long internalMask(int start, int end) {
        return (-1L << start) & (-1L >>> (63 - end));
    }
}
