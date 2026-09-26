package dev.primept.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceQuadsTest {
    @Test void encodesSourceRgbaAndLocalVerticesWithoutLighting() {
        var source = new SourceQuads();
        int[] colors = {0xffabcdef, 0x7f123456, 0x01020304, 0xffffffff};
        for (int i = 0; i < 4; i++) source.vertex(SourceQuads.CUTOUT, i + .125f, -3, 9, colors[i], .2f, .8f);
        source.seal();
        var bytes = source.vertices(SourceQuads.CUTOUT);
        assertEquals(4 * SourceQuads.STRIDE, bytes.remaining());
        for (int i = 0; i < 4; i++) {
            assertEquals(i + .125f, bytes.getFloat());
            assertEquals(-3, bytes.getFloat());
            assertEquals(9, bytes.getFloat());
            assertEquals((colors[i] >>> 16) & 255, bytes.get() & 255);
            assertEquals((colors[i] >>> 8) & 255, bytes.get() & 255);
            assertEquals(colors[i] & 255, bytes.get() & 255);
            assertEquals(colors[i] >>> 24, bytes.get() & 255);
            assertEquals(.2f, bytes.getFloat());
            assertEquals(.8f, bytes.getFloat());
        }
        assertEquals(0, source.vertices(SourceQuads.OPAQUE).remaining());
        assertThrows(IllegalStateException.class, () -> source.vertex(0, 0, 0, 0, -1, 0, 0));
    }

    @Test void growthPreservesBytesAndEachLayerMustContainCompleteQuads() {
        var source = new SourceQuads();
        for (int i = 0; i < 2048; i++) source.vertex(i / 4 % 2, i, 0, 0, -1, 0, 0);
        source.seal();
        for (int layer = 0; layer < 2; layer++) {
            var bytes = source.vertices(layer);
            assertEquals(1024 * SourceQuads.STRIDE, bytes.remaining());
            for (int i = 0; i < 1024; i++) assertEquals((i / 4 * 2 + layer) * 4 + i % 4, bytes.getFloat(i * SourceQuads.STRIDE));
        }
        var partial = new SourceQuads();
        partial.vertex(0, 0, 0, 0, -1, 0, 0);
        assertThrows(IllegalStateException.class, partial::seal);
    }
}
