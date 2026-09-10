// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;

/** Fixed inputs shared by the decomposition behavior tests and allocation/time benchmarks. */
final class RectangleDecompositionCorpus {
    static final String[] SCENARIOS = {
        "solid", "stripes", "checkerboard", "sparse", "dense", "holes", "max-chords", "many-labels"
    };
    private static final int EDGE = RectangleDecomposition64.EDGE;

    private RectangleDecompositionCorpus() {}

    // Independently exported from rectangle_decomposition 334d078 using unit leaves.
    static int expectedCount(String scenario) {
        return switch (scenario) {
            case "solid" -> 1;
            case "stripes" -> 16;
            case "checkerboard" -> 4096;
            case "sparse" -> 213;
            case "dense" -> 448;
            case "holes" -> 521;
            case "max-chords" -> 1025;
            case "many-labels" -> 768;
            default -> throw new IllegalArgumentException("Unknown rectangle corpus: " + scenario);
        };
    }

    static int[] cells(String scenario) {
        int[] cells = new int[EDGE * EDGE];
        for (int y = 0; y < EDGE; y++) {
            for (int x = 0; x < EDGE; x++) {
                int hash = (y * EDGE + x + 1) * 0x9e3779b9;
                hash = (hash ^ hash >>> 16) * 0x21f0aaad;
                hash ^= hash >>> 15;
                cells[y * EDGE + x] = switch (scenario) {
                    case "solid", "holes" -> 1;
                    case "stripes" -> 1 + x / 4;
                    case "checkerboard" -> 1 + ((x + y) & 1);
                    case "sparse" -> (hash & 15) == 0 ? 1 : 0;
                    case "dense" -> (hash & 7) == 0 ? 0 : 1;
                    case "max-chords" -> (x & 1) == 0 && (y & 1) == 0 ? 0 : 1;
                    case "many-labels" -> (x & 3) == 0 && (y & 3) == 0
                            || (x & 3) == 3 && (y & 3) == 3 ? 0 : 0x8000 + (y / 4 * 16 + x / 4);
                    default -> throw new IllegalArgumentException("Unknown rectangle corpus: " + scenario);
                };
            }
        }
        if (scenario.equals("holes")) {
            int holes = 0;
            for (int sample = 0; holes < cells.length / 8; sample++) {
                int x = Integer.reverse(sample) >>> 26;
                int numerator = 0;
                int denominator = 1;
                int value = sample;
                while (denominator < 729) {
                    numerator = numerator * 3 + value % 3;
                    denominator *= 3;
                    value /= 3;
                }
                int cell = numerator * EDGE / denominator * EDGE + x;
                if (cells[cell] != 0) {
                    cells[cell] = 0;
                    holes++;
                }
            }
        }
        return cells;
    }

    static void fill(RectangleDecomposition64.LayerBuilder builder, int[] cells) {
        builder.clear();
        for (int cell = 0; cell < cells.length; cell++) {
            if (cells[cell] != 0) {
                builder.pushSquare(cell % EDGE, cell / EDGE, 0, cells[cell]);
            }
        }
    }

    static void verify(int[] expected, RectangleDecomposition64.Result result) {
        int[] actual = new int[EDGE * EDGE];
        for (int index = 0; index < result.size(); index++) {
            int xStart = result.xStart(index);
            int xEnd = result.xEnd(index);
            int yStart = result.yStart(index);
            int yEnd = result.yEnd(index);
            int value = result.value(index);
            if (xStart < 0 || xStart >= xEnd || xEnd > EDGE
                    || yStart < 0 || yStart >= yEnd || yEnd > EDGE || value == 0) {
                throw new AssertionError("Invalid rectangle bounds or label");
            }
            for (int y = yStart; y < yEnd; y++) {
                for (int x = xStart; x < xEnd; x++) {
                    int cell = y * EDGE + x;
                    if (actual[cell] != 0) {
                        throw new AssertionError("Rectangle overlap at " + x + "," + y);
                    }
                    actual[cell] = value;
                }
            }
        }
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Rectangle coverage or labels changed");
        }
    }
}
