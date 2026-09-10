// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RectangleDecomposition64Test {
    private static final int EDGE = RectangleDecomposition64.EDGE;

    @Test
    void validatesSquaresAndRejectsOverlap() {
        RectangleDecomposition64.LayerBuilder builder =
                new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch =
                new RectangleDecomposition64.Scratch();

        assertEquals(0, builder.finish(scratch).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.pushSquare(0, 0, 7, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.pushSquare(1, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.pushSquare(64, 0, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.pushSquare(0, 0, 0, 0));
        for (int coordinate : new int[] {Integer.MIN_VALUE, -1, 64, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.pushSquare(coordinate, 0, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.pushSquare(0, coordinate, 0, 1));
        }

        builder.pushSquare(0, 0, 6, 5);
        builder.pushSquare(0, 0, 5, 5);
        assertThrows(IllegalStateException.class, () -> builder.finish(scratch));
    }

    @Test
    void fullLayerAndMultipleLabelsPreserveHalfOpenRectangles() {
        RectangleDecomposition64.LayerBuilder builder =
                new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch =
                new RectangleDecomposition64.Scratch();
        builder.pushSquare(0, 0, 6, 5);

        RectangleDecomposition64.Result full = builder.finish(scratch);

        assertEquals(1, full.size());
        assertRectangle(full, 0, 5, 0, 64, 0, 64);

        builder.clear();
        builder.pushSquare(0, 0, 0, 1);
        builder.pushSquare(1, 0, 0, 0xffff);
        builder.pushSquare(0, 1, 0, 1);
        builder.pushSquare(1, 1, 0, 0xffff);
        RectangleDecomposition64.Result labels = builder.finish(scratch);
        int[] cells = rasterize(labels, 2, 2);
        assertArrayEquals(new int[] {1, 0xffff, 1, 0xffff}, cells);
        assertEquals(2, labels.size());
    }

    @Test
    void everyFourByFourImageMatchesBruteForceOptimalPartition() {
        int[] optimum = optimalCounts(4);
        RectangleDecomposition64.LayerBuilder builder =
                new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch =
                new RectangleDecomposition64.Scratch();
        for (int mask = 0; mask < 1 << 16; mask++) {
            builder.clear();
            for (int cell = 0; cell < 16; cell++) {
                if ((mask & 1 << cell) != 0) {
                    builder.pushSquare(cell % 4, cell / 4, 0, 1);
                }
            }

            RectangleDecomposition64.Result result = builder.finish(scratch);

            int covered = 0;
            for (int index = 0; index < result.size(); index++) {
                assertEquals(1, result.value(index));
                for (int y = result.yStart(index); y < result.yEnd(index); y++) {
                    for (int x = result.xStart(index);
                            x < result.xEnd(index);
                            x++) {
                        assertTrue(x < 4 && y < 4);
                        int bit = 1 << (y * 4 + x);
                        assertTrue((mask & bit) != 0);
                        assertEquals(0, covered & bit);
                        covered |= bit;
                    }
                }
            }
            assertEquals(mask, covered);
            assertEquals(optimum[mask], result.size(), Integer.toHexString(mask));
        }
    }

    @Test
    void lowDiscrepancyWorstCaseMatchesReferenceAndReusesScratch() {
        boolean[] holes = lowDiscrepancyHoles();
        RectangleDecomposition64.LayerBuilder builder =
                new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch =
                new RectangleDecomposition64.Scratch();
        for (int y = 0; y < EDGE; y++) {
            for (int x = 0; x < EDGE; x++) {
                if (!holes[y * EDGE + x]) {
                    builder.pushSquare(x, y, 0, 1);
                }
            }
        }

        RectangleDecomposition64.Result first = builder.finish(scratch);
        assertEquals(521, first.size());
        assertCoverage(first, holes);
        long[] expected = snapshot(first);

        RectangleDecomposition64.Result second = builder.finish(scratch);
        assertEquals(521, second.size());
        assertCoverage(second, holes);
        assertArrayEquals(expected, snapshot(second));
    }

    @Test
    void everyThreeByThreeTwoLabelImageIsOptimalAtBothGridEdges() {
        int[] optimum = optimalCounts(3);
        RectangleDecomposition64.LayerBuilder builder = new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch = new RectangleDecomposition64.Scratch();
        int[] expected = new int[EDGE * EDGE];
        int[] labels = {0x00ff, 0x0100, 0x8000, 0xffff};
        for (int pattern = 0; pattern < 19683; pattern++) {
            Arrays.fill(expected, 0);
            int firstMask = 0;
            int secondMask = 0;
            int code = pattern;
            int offset = (pattern & 1) == 0 ? 0 : EDGE - 3;
            for (int cell = 0; cell < 9; cell++, code /= 3) {
                int value = code % 3;
                if (value == 1) {
                    firstMask |= 1 << cell;
                } else if (value == 2) {
                    secondMask |= 1 << cell;
                }
                expected[(offset + cell / 3) * EDGE + offset + cell % 3] = value == 0
                        ? 0 : labels[(pattern + value) & 3];
            }
            RectangleDecompositionCorpus.fill(builder, expected);
            RectangleDecomposition64.Result result = builder.finish(scratch);
            RectangleDecompositionCorpus.verify(expected, result);
            assertEquals(optimum[firstMask] + optimum[secondMask], result.size());
        }
    }

    @Test
    void corpusPreservesCoverageAndScratchReuseAcrossSizesAndLabels() {
        RectangleDecomposition64.LayerBuilder builder = new RectangleDecomposition64.LayerBuilder();
        RectangleDecomposition64.Scratch scratch = new RectangleDecomposition64.Scratch();
        for (String scenario : RectangleDecompositionCorpus.SCENARIOS) {
            int[] cells = RectangleDecompositionCorpus.cells(scenario);
            RectangleDecompositionCorpus.fill(builder, cells);
            RectangleDecomposition64.Result result = builder.finish(scratch);
            RectangleDecompositionCorpus.verify(cells, result);
            assertEquals(RectangleDecompositionCorpus.expectedCount(scenario), result.size());
            long[] expected = snapshot(result);
            // Reversing input must retain canonical extraction order and preserve unsigned labels.
            builder.clear();
            for (int cell = cells.length - 1; cell >= 0; cell--) {
                if (cells[cell] != 0) {
                    builder.pushSquare(cell % EDGE, cell / EDGE, 0, cells[cell]);
                }
            }
            assertArrayEquals(expected, snapshot(builder.finish(scratch)));
            builder.clear();
            assertEquals(0, builder.finish(scratch).size());
        }
    }

    private static int[] optimalCounts(int edge) {
        int[] result = new int[1 << (edge * edge)];
        Arrays.fill(result, edge * edge + 1);
        result[0] = 0;
        for (int mask = 1; mask < result.length; mask++) {
            int first = Integer.numberOfTrailingZeros(mask);
            int x = first % edge;
            int y = first / edge;
            for (int height = 1; y + height <= edge; height++) {
                for (int width = 1; x + width <= edge; width++) {
                    int rectangle = rectangleMask(edge, x, y, width, height);
                    if ((mask & rectangle) == rectangle) {
                        result[mask] = Math.min(
                                result[mask], 1 + result[mask ^ rectangle]);
                    }
                }
            }
        }
        return result;
    }

    private static int rectangleMask(
            int edge, int x, int y, int width, int height) {
        int mask = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                mask |= 1 << (row * edge + column);
            }
        }
        return mask;
    }

    private static int[] rasterize(
            RectangleDecomposition64.Result result, int width, int height) {
        int[] cells = new int[width * height];
        for (int index = 0; index < result.size(); index++) {
            for (int y = result.yStart(index); y < result.yEnd(index); y++) {
                for (int x = result.xStart(index); x < result.xEnd(index); x++) {
                    assertEquals(0, cells[y * width + x]);
                    cells[y * width + x] = result.value(index);
                }
            }
        }
        return cells;
    }

    private static void assertCoverage(
            RectangleDecomposition64.Result result, boolean[] holes) {
        boolean[] covered = new boolean[EDGE * EDGE];
        for (int index = 0; index < result.size(); index++) {
            assertEquals(1, result.value(index));
            for (int y = result.yStart(index); y < result.yEnd(index); y++) {
                for (int x = result.xStart(index); x < result.xEnd(index); x++) {
                    int cell = y * EDGE + x;
                    assertFalse(holes[cell]);
                    assertFalse(covered[cell]);
                    covered[cell] = true;
                }
            }
        }
        for (int cell = 0; cell < covered.length; cell++) {
            assertEquals(!holes[cell], covered[cell]);
        }
    }

    private static long[] snapshot(RectangleDecomposition64.Result result) {
        long[] snapshot = new long[result.size()];
        for (int index = 0; index < result.size(); index++) {
            snapshot[index] = result.value(index)
                    | (long) result.xStart(index) << 16
                    | (long) result.xEnd(index) << 24
                    | (long) result.yStart(index) << 32
                    | (long) result.yEnd(index) << 40;
        }
        return snapshot;
    }

    private static boolean[] lowDiscrepancyHoles() {
        boolean[] holes = new boolean[EDGE * EDGE];
        int holeCount = 0;
        int sample = 0;
        while (holeCount < holes.length / 8) {
            int x = radicalInverseBaseTwo(sample);
            int y = radicalInverseBaseThree(sample);
            int index = y * EDGE + x;
            if (!holes[index]) {
                holes[index] = true;
                holeCount++;
            }
            sample++;
        }
        return holes;
    }

    private static int radicalInverseBaseTwo(int value) {
        int reversed = 0;
        for (int index = 0; index < 6; index++) {
            reversed = reversed << 1 | value & 1;
            value >>>= 1;
        }
        return reversed;
    }

    private static int radicalInverseBaseThree(int value) {
        int numerator = 0;
        int denominator = 1;
        while (denominator < 729) {
            numerator = numerator * 3 + value % 3;
            denominator *= 3;
            value /= 3;
        }
        return numerator * EDGE / denominator;
    }

    private static void assertRectangle(
            RectangleDecomposition64.Result result,
            int index,
            int value,
            int xStart,
            int xEnd,
            int yStart,
            int yEnd) {
        assertEquals(value, result.value(index));
        assertEquals(xStart, result.xStart(index));
        assertEquals(xEnd, result.xEnd(index));
        assertEquals(yStart, result.yStart(index));
        assertEquals(yEnd, result.yEnd(index));
    }
}
