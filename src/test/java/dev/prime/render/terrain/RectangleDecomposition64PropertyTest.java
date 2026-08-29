package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.test.PrimeProperties;
import java.util.List;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.Test;

final class RectangleDecomposition64PropertyTest {
    private static final int EDGE = RectangleDecomposition64.EDGE;

    @Test
    void arbitraryLegalLayersPreserveCoverageLabelsAndDeterminism() {
        Generator<List<Integer>> layers = Generator.listsOf(
                IntDistribution.uniform(0, 320), Generator.integers());

        PrimeProperties.check(
                "rectangle-decomposition-64",
                320,
                layers,
                RectangleDecomposition64PropertyTest::assertLayer);
    }

    private static void assertLayer(List<Integer> cells) {
        int[] expected = new int[EDGE * EDGE];
        for (int encoded : cells) {
            int cell = Math.floorMod(encoded, expected.length);
            int label = 1 + Math.floorMod(Integer.rotateRight(encoded, 12), 7);
            expected[cell] = label;
        }

        RectangleDecomposition64.LayerBuilder builder = build(expected);
        RectangleDecomposition64.Scratch firstScratch =
                new RectangleDecomposition64.Scratch();
        RectangleDecomposition64.Result first = builder.finish(firstScratch);
        int[] actual = rasterize(first);
        assertArrayEquals(expected, actual);

        RectangleDecomposition64.Scratch secondScratch =
                new RectangleDecomposition64.Scratch();
        RectangleDecomposition64.Result second = build(expected).finish(secondScratch);
        assertArrayEquals(snapshot(first), snapshot(second));
    }

    private static RectangleDecomposition64.LayerBuilder build(int[] cells) {
        RectangleDecomposition64.LayerBuilder builder =
                new RectangleDecomposition64.LayerBuilder();
        for (int cell = 0; cell < cells.length; cell++) {
            if (cells[cell] != 0) {
                builder.pushSquare(cell % EDGE, cell / EDGE, 0, cells[cell]);
            }
        }
        return builder;
    }

    private static int[] rasterize(RectangleDecomposition64.Result result) {
        int[] cells = new int[EDGE * EDGE];
        for (int index = 0; index < result.size(); index++) {
            int xStart = result.xStart(index);
            int xEnd = result.xEnd(index);
            int yStart = result.yStart(index);
            int yEnd = result.yEnd(index);
            int value = result.value(index);
            assertTrue(xStart >= 0 && xStart < xEnd && xEnd <= EDGE);
            assertTrue(yStart >= 0 && yStart < yEnd && yEnd <= EDGE);
            assertTrue(value > 0 && value <= 0xffff);
            for (int y = yStart; y < yEnd; y++) {
                for (int x = xStart; x < xEnd; x++) {
                    int cell = y * EDGE + x;
                    assertEquals(0, cells[cell], "overlap at " + x + "," + y);
                    cells[cell] = value;
                }
            }
        }
        return cells;
    }

    private static long[] snapshot(RectangleDecomposition64.Result result) {
        long[] rectangles = new long[result.size()];
        for (int index = 0; index < rectangles.length; index++) {
            rectangles[index] = result.value(index)
                    | (long) result.xStart(index) << 16
                    | (long) result.xEnd(index) << 23
                    | (long) result.yStart(index) << 30
                    | (long) result.yEnd(index) << 37;
        }
        return rectangles;
    }
}
