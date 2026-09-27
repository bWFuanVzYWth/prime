package dev.primept.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceQuadsTest {
    @Test
    void encodesSourceRgbaAndLocalVerticesWithoutLighting() {
        var source = new SourceQuads();
        int[] colors = {0xffabcdef, 0x7f123456, 0x01020304, 0xffffffff};
        for (int i = 0; i < 4; i++)
            source.vertex(SourceQuads.CUTOUT, i + .125f, -3, 9, colors[i], .2f, .8f);
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

    @Test
    void reusedWorkspaceClearsEveryLayerAndPreservesEncodedPublication() {
        var source = new SourceQuads();
        for (int layer = 0; layer < 3; layer++)
            for (int i = 0; i < 1024; i++)
                source.vertex(layer, i, layer, 0, -1, 0, 1);
        source.seal();
        byte[] publication = new byte[source.vertices(1).remaining()];
        source.vertices(1).get(publication);
        for (int repeat = 0; repeat < 32; repeat++) {
            source.clear();
            int layer = repeat % 3;
            for (int i = 0; i < 4; i++)
                source.vertex(layer, repeat, i, 0, 0xff123456, 1, 0);
            source.seal();
            for (int other = 0; other < 3; other++)
                assertEquals(other == layer ? 96 : 0, source.vertices(other).remaining());
            assertEquals(repeat, source.vertices(layer).getFloat());
        }
        assertEquals(1, java.nio.ByteBuffer.wrap(publication)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .getFloat(24));
        source.clear();
        source.vertex(0, 1, 2, 3, -1, 0, 0);
        source.clear(); // Failed/partial compiles must not contaminate the next result either.
        source.seal();
        assertEquals(0, source.vertices(0).remaining());
    }

    @Test
    void growthPreservesBytesAndEachLayerMustContainCompleteQuads() {
        var source = new SourceQuads();
        for (int i = 0; i < 2048; i++)
            source.vertex(i / 4 % 2, i, 0, 0, -1, 0, 0);
        source.seal();
        for (int layer = 0; layer < 2; layer++) {
            var bytes = source.vertices(layer);
            assertEquals(1024 * SourceQuads.STRIDE, bytes.remaining());
            for (int i = 0; i < 1024; i++)
                assertEquals((i / 4 * 2 + layer) * 4 + i % 4,
                             bytes.getFloat(i * SourceQuads.STRIDE));
        }
        var partial = new SourceQuads();
        partial.vertex(0, 0, 0, 0, -1, 0, 0);
        assertThrows(IllegalStateException.class, partial::seal);
    }
}
