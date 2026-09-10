// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;

/**
 * Optimal rectangle decomposition for one fixed 64x64 sparse layer.
 *
 * <p>Ports rectangle_decomposition at 334d078. Prime writes unit faces, so a fixed grid
 * and row masks avoid sorting sparse input intervals. Scratch is reused by one owner.
 */
final class RectangleDecomposition64 {
    static final int EDGE = 64;

    private static final int MAX_LOD = 6;
    private static final int MAX_CHORDS = RectangleMatching64.MAX_CHORDS;
    private static final int MAX_RECTANGLES = EDGE * EDGE;

    private RectangleDecomposition64() {}

    static final class LayerBuilder {
        private final char[] cells = new char[EDGE * EDGE];
        private final char[] occupied = new char[EDGE * EDGE];
        private final long[] rowMasks = new long[EDGE];
        private final long[] columnMasks = new long[EDGE];
        private int occupiedCount;
        private int uniformValue;
        private boolean overlapping;

        void clear() {
            for (int index = 0; index < this.occupiedCount; index++) {
                this.cells[this.occupied[index]] = 0;
            }
            Arrays.fill(this.rowMasks, 0L);
            Arrays.fill(this.columnMasks, 0L);
            this.occupiedCount = 0;
            this.uniformValue = 0;
            this.overlapping = false;
        }

        void pushSquare(int u, int v, int lod, int value) {
            if (lod < 0 || lod > MAX_LOD) {
                throw new IllegalArgumentException("Rectangle square lod is out of range");
            }
            if (value <= 0 || value > 0xffff) {
                throw new IllegalArgumentException(
                        "Rectangle square label must be a nonzero u16");
            }
            int size = 1 << lod;
            int mask = size - 1;
            if ((u & mask) != 0 || (v & mask) != 0) {
                throw new IllegalArgumentException(
                        "Rectangle square is not aligned to its lod");
            }
            if (u < 0 || v < 0 || u > EDGE - size || v > EDGE - size) {
                throw new IllegalArgumentException(
                        "Rectangle square lies outside the 64x64 layer");
            }
            int uEnd = u + size;
            int vEnd = v + size;
            if (this.uniformValue != value) {
                this.uniformValue = this.occupiedCount == 0 ? value : 0;
            }
            // Production supplies unit faces; keep that path free of the two LOD loops.
            if (size == 1) {
                this.pushCell(u, v, value);
                return;
            }
            for (int y = v; y < vEnd; y++) {
                for (int x = u; x < uEnd; x++) {
                    this.pushCell(x, y, value);
                }
            }
        }

        private void pushCell(int x, int y, int value) {
            int index = y * EDGE + x;
            if (this.cells[index] != 0) {
                this.overlapping = true;
            } else {
                this.cells[index] = (char) value;
                this.occupied[this.occupiedCount++] = (char) index;
                this.rowMasks[y] |= 1L << x;
                this.columnMasks[x] |= 1L << y;
            }
        }

        Result finish(Scratch scratch) {
            return scratch.decompose(this);
        }
    }

    /** Borrowed view invalidated by the next decomposition on its owner. */
    static final class Result {
        private final Scratch owner;

        private Result(Scratch owner) {
            this.owner = owner;
        }

        int size() {
            return this.owner.rectangleCount;
        }

        int value(int index) {
            return rectangleValue(this.owner.rectangle(index));
        }

        int xStart(int index) {
            return rectangleXStart(this.owner.rectangle(index));
        }

        int xEnd(int index) {
            return rectangleXEnd(this.owner.rectangle(index));
        }

        int yStart(int index) {
            return rectangleYStart(this.owner.rectangle(index));
        }

        int yEnd(int index) {
            return rectangleYEnd(this.owner.rectangle(index));
        }
    }

    static final class Scratch {
        private final long[] horizontalChords = new long[MAX_CHORDS];
        private final long[] verticalChords = new long[MAX_CHORDS];
        private int horizontalChordCount;
        private int verticalChordCount;

        private final long[] sortedChords = new long[MAX_CHORDS];
        private final int[] valueOffsets = new int[256];

        private final RectangleMatching64.Scratch matching =
                new RectangleMatching64.Scratch();

        private final long[] horizontalCutMasks = new long[EDGE];
        private final long[] verticalCutMasks = new long[EDGE];
        private final int[] runs = new int[EDGE];
        private int runCount;
        private final long[] activeRectangles = new long[EDGE];
        private final long[] nextActiveRectangles = new long[EDGE];
        private final long[] rectangles = new long[MAX_RECTANGLES];
        private int rectangleCount;
        private final Result result = new Result(this);

        private Result decompose(LayerBuilder builder) {
            this.rectangleCount = 0;
            if (builder.overlapping) {
                throw new IllegalStateException("Rectangle layer squares overlap");
            }
            if (builder.occupiedCount == 0) {
                return this.result;
            }
            this.extractChords(builder);
            this.selectCuts(builder.uniformValue != 0);
            this.partition(builder);
            return this.result;
        }

        private long rectangle(int index) {
            if (index < 0 || index >= this.rectangleCount) {
                throw new IndexOutOfBoundsException(index);
            }
            return this.rectangles[index];
        }

        private void extractChords(LayerBuilder builder) {
            this.horizontalChordCount = 0;
            this.verticalChordCount = 0;
            if (builder.uniformValue != 0) {
                this.extractUniformChords(builder.rowMasks, builder.uniformValue, true);
                this.extractUniformChords(builder.columnMasks, builder.uniformValue, false);
            } else {
                this.extractChords(builder.cells, builder.rowMasks, true);
                this.extractChords(builder.cells, builder.columnMasks, false);
            }
        }

        private void extractUniformChords(long[] masks, int value, boolean horizontal) {
            for (int line = 1; line < EDGE; line++) {
                long shared = masks[line - 1] & masks[line];
                long different = masks[line - 1] ^ masks[line];
                // A shared run starts at a concave corner iff exactly one row extends left.
                // Bit zero is excluded by the shift; endpoints must be internal grid points.
                long starts = shared & ~(shared << 1) & (different << 1);
                while (starts != 0L) {
                    int start = Long.numberOfTrailingZeros(starts);
                    int end = start + Long.numberOfTrailingZeros(~(shared >>> start));
                    if (end < EDGE && (different & 1L << end) != 0L) {
                        if (horizontal) {
                            this.addHorizontalChord(value, packChord(start, line, end, line));
                        } else {
                            this.addVerticalChord(value, packChord(line, start, line, end));
                        }
                    }
                    starts &= starts - 1L;
                }
            }
        }

        private void extractChords(
                char[] cells, long[] masks, boolean horizontal) {
            for (int line = 1; line < EDGE; line++) {
                long active = masks[line - 1] & masks[line];
                while (active != 0L) {
                    int start = Long.numberOfTrailingZeros(active);
                    int value = commonValue(cells, horizontal, line, start);
                    int end = start + 1;
                    while (end < EDGE
                            && (active & 1L << end) != 0L
                            && commonValue(cells, horizontal, line, end) == value) {
                        end++;
                    }
                    active &= ~cellRangeMask(start, end);
                    if (value == 0) {
                        continue;
                    }
                    boolean firstBefore = start > 0
                            && axisValue(cells, horizontal, line - 1, start - 1) == value;
                    boolean secondBefore = start > 0
                            && axisValue(cells, horizontal, line, start - 1) == value;
                    boolean firstAfter = end < EDGE
                            && axisValue(cells, horizontal, line - 1, end) == value;
                    boolean secondAfter = end < EDGE
                            && axisValue(cells, horizontal, line, end) == value;
                    if (firstBefore != secondBefore && firstAfter != secondAfter) {
                        int chord = horizontal
                                ? packChord(start, line, end, line)
                                : packChord(line, start, line, end);
                        if (horizontal) {
                            this.addHorizontalChord(value, chord);
                        } else {
                            this.addVerticalChord(value, chord);
                        }
                    }
                }
            }
        }

        private static int commonValue(
                char[] cells, boolean horizontal, int line, int coordinate) {
            int first = axisValue(cells, horizontal, line - 1, coordinate);
            int second = axisValue(cells, horizontal, line, coordinate);
            return first == second ? first : 0;
        }

        private static int axisValue(
                char[] cells, boolean horizontal, int line, int coordinate) {
            return horizontal
                    ? cells[line * EDGE + coordinate]
                    : cells[coordinate * EDGE + line];
        }

        private void addHorizontalChord(int value, int chord) {
            if (this.horizontalChordCount >= this.horizontalChords.length) {
                throw new IllegalStateException(
                        "Horizontal chord capacity was exceeded");
            }
            this.horizontalChords[this.horizontalChordCount] =
                    packValuedChord(value, chord);
            this.horizontalChordCount++;
        }

        private void addVerticalChord(int value, int chord) {
            if (this.verticalChordCount >= this.verticalChords.length) {
                throw new IllegalStateException(
                        "Vertical chord capacity was exceeded");
            }
            this.verticalChords[this.verticalChordCount] =
                    packValuedChord(value, chord);
            this.verticalChordCount++;
        }

        private void selectCuts(boolean uniform) {
            Arrays.fill(this.horizontalCutMasks, 0L);
            Arrays.fill(this.verticalCutMasks, 0L);
            if (uniform) {
                this.selectGroupCuts(0, this.horizontalChordCount, 0, this.verticalChordCount);
                return;
            }
            this.sortValuedChords(this.horizontalChords, this.horizontalChordCount);
            this.sortValuedChords(this.verticalChords, this.verticalChordCount);
            int horizontalIndex = 0;
            int verticalIndex = 0;
            while (horizontalIndex < this.horizontalChordCount
                    || verticalIndex < this.verticalChordCount) {
                int horizontalValue = horizontalIndex < this.horizontalChordCount
                        ? valuedChordValue(this.horizontalChords[horizontalIndex]) : 0x10000;
                int verticalValue = verticalIndex < this.verticalChordCount
                        ? valuedChordValue(this.verticalChords[verticalIndex]) : 0x10000;
                int value = Math.min(horizontalValue, verticalValue);
                int horizontalStart = horizontalIndex;
                int verticalStart = verticalIndex;
                while (horizontalIndex < this.horizontalChordCount
                        && valuedChordValue(this.horizontalChords[horizontalIndex]) == value) {
                    horizontalIndex++;
                }
                while (verticalIndex < this.verticalChordCount
                        && valuedChordValue(this.verticalChords[verticalIndex]) == value) {
                    verticalIndex++;
                }
                this.selectGroupCuts(horizontalStart, horizontalIndex, verticalStart, verticalIndex);
            }
        }

        private void selectGroupCuts(
                int horizontalStart, int horizontalEnd, int verticalStart, int verticalEnd) {
            this.matching.selectMaximumIndependentSet(
                    this.horizontalChords, horizontalStart, horizontalEnd,
                    this.verticalChords, verticalStart, verticalEnd);
            // Cut masks combine by OR; materializing and sorting cut lists adds no information.
            for (int index = 0; index < this.matching.selectedHorizontalCount(); index++) {
                int selected = chord(this.horizontalChords[
                        horizontalStart + this.matching.selectedHorizontal(index)]);
                this.horizontalCutMasks[chordY1(selected)] |=
                        cellRangeMask(chordX1(selected), chordX2(selected));
            }
            for (int index = 0; index < this.matching.selectedVerticalCount(); index++) {
                int selected = chord(this.verticalChords[
                        verticalStart + this.matching.selectedVertical(index)]);
                long bit = 1L << chordX1(selected);
                for (int y = chordY1(selected); y < chordY2(selected); y++) {
                    this.verticalCutMasks[y] |= bit;
                }
            }
        }

        private void sortValuedChords(long[] chords, int count) {
            for (int index = 1; index < count; index++) {
                if (valuedChordValue(chords[index - 1]) > valuedChordValue(chords[index])) {
                    // Stable label radix sort preserves extraction order without the temporary
                    // allocations made by Arrays.sort on some long runs of primitive values.
                    this.sortValueByte(chords, this.sortedChords, count, 0);
                    this.sortValueByte(this.sortedChords, chords, count, 8);
                    return;
                }
            }
        }

        private void sortValueByte(long[] source, long[] target, int count, int shift) {
            Arrays.fill(this.valueOffsets, 0);
            for (int index = 0; index < count; index++) {
                this.valueOffsets[valuedChordValue(source[index]) >>> shift & 0xff]++;
            }
            int offset = 0;
            for (int value = 0; value < this.valueOffsets.length; value++) {
                int size = this.valueOffsets[value];
                this.valueOffsets[value] = offset;
                offset += size;
            }
            for (int index = 0; index < count; index++) {
                long chord = source[index];
                target[this.valueOffsets[valuedChordValue(chord) >>> shift & 0xff]++] = chord;
            }
        }

        private void partition(LayerBuilder builder) {
            this.rectangleCount = 0;
            long[] active = this.activeRectangles;
            long[] nextActive = this.nextActiveRectangles;

            this.buildRunsForRow(builder, 0);
            int activeCount = this.runCount;
            for (int index = 0; index < this.runCount; index++) {
                active[index] = packActiveRectangle(this.runs[index], 0);
            }

            for (int y = 1; y < EDGE; y++) {
                this.buildRunsForRow(builder, y);
                int nextCount = this.mergeSparseRuns(
                        active,
                        activeCount,
                        y,
                        this.horizontalCutMasks[y],
                        nextActive);
                long[] swap = active;
                active = nextActive;
                nextActive = swap;
                activeCount = nextCount;
            }
            for (int index = 0; index < activeCount; index++) {
                this.emit(active[index], EDGE);
            }
        }

        private void buildRunsForRow(LayerBuilder builder, int y) {
            this.runCount = 0;
            long active = builder.rowMasks[y];
            while (active != 0L) {
                int start = Long.numberOfTrailingZeros(active);
                int value = builder.uniformValue;
                int end;
                if (value != 0) {
                    end = start + Long.numberOfTrailingZeros(~(active >>> start));
                } else {
                    value = builder.cells[y * EDGE + start];
                    end = start + 1;
                    while (end < EDGE && builder.cells[y * EDGE + end] == value) {
                        end++;
                    }
                }
                long mask = cellRangeMask(start, end);
                active &= ~mask;
                long splitMask = this.verticalCutMasks[y] & mask & ~(1L << start);
                while (splitMask != 0L) {
                    int split = Long.numberOfTrailingZeros(splitMask);
                    this.pushRun(value, start, split);
                    start = split;
                    splitMask &= splitMask - 1L;
                }
                this.pushRun(value, start, end);
            }
        }

        private void pushRun(int value, int start, int end) {
            assert start < end;
            if (this.runCount >= this.runs.length) {
                throw new IllegalStateException("Row run capacity was exceeded");
            }
            this.runs[this.runCount++] = packInterval(start, end, value);
        }

        private int mergeSparseRuns(
                long[] active,
                int activeCount,
                int y,
                long horizontalCutMask,
                long[] nextActive) {
            int nextCount = 0;
            int activeIndex = 0;
            int runIndex = 0;
            while (activeIndex < activeCount && runIndex < this.runCount) {
                long activeRectangle = active[activeIndex];
                int activeInterval = activeInterval(activeRectangle);
                int run = this.runs[runIndex];
                if (activeInterval == run
                        && !horizontalCutOverlaps(
                                horizontalCutMask,
                                intervalStart(activeInterval),
                                intervalEnd(activeInterval))) {
                    nextActive[nextCount++] = activeRectangle;
                    activeIndex++;
                    runIndex++;
                } else if (comesBeforeOrEqual(activeInterval, run)) {
                    this.emit(activeRectangle, y);
                    activeIndex++;
                } else {
                    nextActive[nextCount++] =
                            packActiveRectangle(run, y);
                    runIndex++;
                }
            }
            while (activeIndex < activeCount) {
                this.emit(active[activeIndex++], y);
            }
            while (runIndex < this.runCount) {
                nextActive[nextCount++] =
                        packActiveRectangle(this.runs[runIndex++], y);
            }
            return nextCount;
        }

        private void emit(long active, int yEnd) {
            if (this.rectangleCount >= this.rectangles.length) {
                throw new IllegalStateException(
                        "Rectangle result capacity was exceeded");
            }
            int interval = activeInterval(active);
            this.rectangles[this.rectangleCount++] = packRectangle(
                    intervalValue(interval),
                    intervalStart(interval),
                    intervalEnd(interval),
                    activeYStart(active),
                    yEnd);
        }
    }

    static int packChord(int x1, int y1, int x2, int y2) {
        return x1 | y1 << 7 | x2 << 14 | y2 << 21;
    }

    static int chord(long valuedChord) {
        return (int) valuedChord;
    }

    static int chordX1(int chord) {
        return chord & 0x7f;
    }

    static int chordY1(int chord) {
        return chord >>> 7 & 0x7f;
    }

    static int chordX2(int chord) {
        return chord >>> 14 & 0x7f;
    }

    static int chordY2(int chord) {
        return chord >>> 21 & 0x7f;
    }

    private static long packValuedChord(int value, int chord) {
        return (long) value << 32 | Integer.toUnsignedLong(chord);
    }

    private static int valuedChordValue(long valuedChord) {
        return (int) (valuedChord >>> 32);
    }

    private static int packInterval(int start, int end, int value) {
        return value | start << 16 | end << 24;
    }

    private static int intervalValue(int interval) {
        return interval & 0xffff;
    }

    private static int intervalStart(int interval) {
        return interval >>> 16 & 0xff;
    }

    private static int intervalEnd(int interval) {
        return interval >>> 24 & 0xff;
    }

    private static long packActiveRectangle(int interval, int yStart) {
        return (long) yStart << 32 | Integer.toUnsignedLong(interval);
    }

    private static int activeInterval(long active) {
        return (int) active;
    }

    private static int activeYStart(long active) {
        return (int) (active >>> 32) & 0xff;
    }

    private static boolean comesBeforeOrEqual(int active, int run) {
        int activeStart = intervalStart(active);
        int runStart = intervalStart(run);
        return activeStart < runStart
                || activeStart == runStart
                        && intervalValue(active) <= intervalValue(run);
    }

    private static long packRectangle(
            int value, int xStart, int xEnd, int yStart, int yEnd) {
        return value
                | (long) xStart << 16
                | (long) xEnd << 24
                | (long) yStart << 32
                | (long) yEnd << 40;
    }

    private static int rectangleValue(long rectangle) {
        return (int) rectangle & 0xffff;
    }

    private static int rectangleXStart(long rectangle) {
        return (int) (rectangle >>> 16) & 0xff;
    }

    private static int rectangleXEnd(long rectangle) {
        return (int) (rectangle >>> 24) & 0xff;
    }

    private static int rectangleYStart(long rectangle) {
        return (int) (rectangle >>> 32) & 0xff;
    }

    private static int rectangleYEnd(long rectangle) {
        return (int) (rectangle >>> 40) & 0xff;
    }

    private static boolean horizontalCutOverlaps(
            long mask, int start, int end) {
        return (mask & cellRangeMask(start, end)) != 0L;
    }

    private static long cellRangeMask(int start, int end) {
        // All callers provide a nonempty half-open cell interval within [0, 64].
        return (-1L << start) & (-1L >>> (EDGE - end));
    }
}
