// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class RectangleMatching64Test {
    @Test
    void segmentGraphsMatchIndependentAugmentingPathOracle() {
        Random random = new Random(0x484b4457L);
        RectangleMatching64.Scratch scratch = new RectangleMatching64.Scratch();
        for (int sample = 0; sample < 2000; sample++) {
            long[] horizontal = chords(random, true, sample % 3);
            long[] vertical = chords(random, false, sample % 3);
            assertOptimal(scratch, horizontal, vertical);
        }
    }

    @Test
    void includesSharedEndpointsAndMaximumDegree() {
        RectangleMatching64.Scratch scratch = new RectangleMatching64.Scratch();
        long[] horizontal = new long[63];
        long[] vertical = new long[63];
        for (int line = 1; line < 64; line++) {
            horizontal[line - 1] = RectangleDecomposition64.packChord(1, line, 63, line);
            vertical[line - 1] = RectangleDecomposition64.packChord(line, 1, line, 63);
        }
        assertOptimal(scratch, horizontal, vertical);
        assertOptimal(scratch,
                new long[] {RectangleDecomposition64.packChord(2, 3, 5, 3)},
                new long[] {
                    RectangleDecomposition64.packChord(2, 1, 2, 3),
                    RectangleDecomposition64.packChord(5, 3, 5, 6)
                });
        assertOptimal(scratch, new long[0], vertical);
        assertOptimal(scratch, horizontal, new long[0]);
    }

    @Test
    void columnGenerationWrapDoesNotExposeStaleGridSlots() {
        RectangleMatching64.Scratch scratch = new RectangleMatching64.Scratch();
        long[] horizontal = {RectangleDecomposition64.packChord(1, 2, 6, 2)};
        long[] vertical = {RectangleDecomposition64.packChord(3, 1, 3, 5)};
        scratch.selectMaximumIndependentSet(horizontal, 0, 1, vertical, 0, 1);
        assertEquals(1, scratch.selectedHorizontalCount() + scratch.selectedVerticalCount());
        // Leave column 3 untouched until and beyond the u16 generation wrap.
        horizontal[0] = RectangleDecomposition64.packChord(4, 6, 6, 6);
        for (int call = 0; call < 65536; call++) {
            scratch.selectMaximumIndependentSet(horizontal, 0, 1, vertical, 0, 1);
            assertEquals(2, scratch.selectedHorizontalCount() + scratch.selectedVerticalCount());
        }
        horizontal[0] = RectangleDecomposition64.packChord(1, 4, 6, 4);
        assertOptimal(scratch, horizontal, vertical);
    }

    private static long[] chords(Random random, boolean horizontal, int shape) {
        long[] chords = new long[RectangleMatching64.MAX_CHORDS];
        int count = 0;
        for (int line = 1; line < 64; line++) {
            if (random.nextInt(3) == 0) {
                continue;
            }
            int start = 1 + random.nextInt(8);
            while (start < 63) {
                int end = Math.min(63, start + 1 + random.nextInt(shape == 0 ? 62 : 8));
                chords[count++] = horizontal
                        ? RectangleDecomposition64.packChord(start, line, end, line)
                        : RectangleDecomposition64.packChord(line, start, line, end);
                start = end + 1 + random.nextInt(shape == 2 ? 16 : 4);
            }
        }
        return Arrays.copyOf(chords, count);
    }

    private static void assertOptimal(
            RectangleMatching64.Scratch scratch, long[] horizontal, long[] vertical) {
        boolean[][] conflicts = new boolean[horizontal.length][vertical.length];
        for (int left = 0; left < horizontal.length; left++) {
            int h = (int) horizontal[left];
            for (int right = 0; right < vertical.length; right++) {
                int v = (int) vertical[right];
                int x = RectangleDecomposition64.chordX1(v);
                int y = RectangleDecomposition64.chordY1(h);
                conflicts[left][right] = RectangleDecomposition64.chordX1(h) <= x
                        && x <= RectangleDecomposition64.chordX2(h)
                        && RectangleDecomposition64.chordY1(v) <= y
                        && y <= RectangleDecomposition64.chordY2(v);
            }
        }
        int[] pairRight = new int[vertical.length];
        Arrays.fill(pairRight, -1);
        int matching = 0;
        for (int left = 0; left < horizontal.length; left++) {
            if (augment(conflicts, left, pairRight, new boolean[vertical.length])) {
                matching++;
            }
        }
        // Nonzero subrange offsets must not change relative selected chord identities.
        long[] shiftedHorizontal = new long[horizontal.length + 3];
        long[] shiftedVertical = new long[vertical.length + 4];
        System.arraycopy(horizontal, 0, shiftedHorizontal, 2, horizontal.length);
        System.arraycopy(vertical, 0, shiftedVertical, 3, vertical.length);
        scratch.selectMaximumIndependentSet(shiftedHorizontal, 2, 2 + horizontal.length,
                shiftedVertical, 3, 3 + vertical.length);
        assertEquals(horizontal.length + vertical.length - matching,
                scratch.selectedHorizontalCount() + scratch.selectedVerticalCount());
        boolean[] selectedLeft = new boolean[horizontal.length];
        boolean[] selectedRight = new boolean[vertical.length];
        for (int index = 0; index < scratch.selectedHorizontalCount(); index++) {
            int left = scratch.selectedHorizontal(index);
            assertFalse(selectedLeft[left]);
            selectedLeft[left] = true;
        }
        for (int index = 0; index < scratch.selectedVerticalCount(); index++) {
            int right = scratch.selectedVertical(index);
            assertFalse(selectedRight[right]);
            selectedRight[right] = true;
            for (int left = 0; left < horizontal.length; left++) {
                assertFalse(selectedLeft[left] && conflicts[left][right]);
            }
        }
    }

    // Deliberately uses one forward search per root, with no greedy initialization or HK levels.
    private static boolean augment(boolean[][] conflicts, int left, int[] pairRight, boolean[] seen) {
        for (int right = 0; right < pairRight.length; right++) {
            if (conflicts[left][right] && !seen[right]) {
                seen[right] = true;
                if (pairRight[right] == -1 || augment(conflicts, pairRight[right], pairRight, seen)) {
                    pairRight[right] = left;
                    return true;
                }
            }
        }
        return false;
    }
}
