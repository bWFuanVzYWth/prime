package dev.primept.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameSequenceTest {
    @Test
    void resizeAndExplicitResetStartNewSetsWhileStableTargetsProgress() {
        var frames = new FrameSequence();
        for (int[] extent :
             new int[][] {{1920, 1080}, {1919, 1079}, {1080, 1920}, {4097, 17}, {1920, 1080}}) {
            assertEquals(0, frames.next(extent[0], extent[1]));
            assertEquals(1, frames.next(extent[0], extent[1]));
            assertEquals(2, frames.next(extent[0], extent[1]));
        }
        frames.reset();
        assertThrows(IllegalArgumentException.class, () -> frames.next(0, 0));
        assertEquals(0, frames.next(1920, 1080));
        assertEquals(1, frames.next(1920, 1080));
    }

    @Test
    void suspendedTargetDoesNotDiscardTheAcceptedExtentSequence() {
        var frames = new FrameSequence();
        assertEquals(0, frames.next(1920, 1080));
        assertEquals(1, frames.next(1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> frames.next(0, 0));
        assertEquals(2, frames.next(1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> frames.next(1920, 0));
        assertEquals(3, frames.next(1920, 1080));
    }
}
