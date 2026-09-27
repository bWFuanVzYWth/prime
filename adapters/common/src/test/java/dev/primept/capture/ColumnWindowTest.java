package dev.primept.capture;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnWindowTest {
    private static Set<Long> cells(ColumnWindow current, ColumnWindow previous) {
        var cells = new HashSet<Long>();
        current.difference(
                previous,
                (x, z) -> assertTrue(cells.add(((long)x << 32) | Integer.toUnsignedLong(z))));
        return cells;
    }
    @Test
    void exactDifferenceAcrossMovesGrowthShrinkAndEmptyIntersections() {
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                for (int radius = 0; radius <= 4; radius++) {
                    var current = ColumnWindow.centered(x, z, radius);
                    var previous = ColumnWindow.centered(-1, 1, 2);
                    var expected = cells(current, null);
                    expected.removeAll(cells(previous, null));
                    assertEquals(expected, cells(current, previous));
                    var empty = current.intersect(ColumnWindow.centered(100, 100, 1));
                    assertTrue(cells(empty, previous).isEmpty());
                    assertEquals(cells(current, null), cells(current, empty));
                }
    }
    @Test
    void oneColumnMotionVisitsOnlyTheEnteringStripAndHandlesIntBoundaries() {
        assertEquals(
                201,
                cells(ColumnWindow.centered(1, 0, 100), ColumnWindow.centered(0, 0, 100)).size());
        for (int edge : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var single = new ColumnWindow(edge, edge, edge, edge);
            assertEquals(1, cells(single, null).size());
            assertTrue(cells(single, single).isEmpty());
        }
    }
}
