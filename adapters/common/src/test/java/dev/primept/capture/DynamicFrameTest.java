package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicFrameTest {
    @Test
    void preservesRawLayoutAndProducerCursorAcrossGrowth() {
        ByteBuffer source = ByteBuffer.allocate(4 * 36 + 7).order(ByteOrder.BIG_ENDIAN);
        for (int i = 7; i < source.capacity(); i++)
            source.put(i, (byte)i);
        source.position(7);
        try (var frame = new DynamicFrame(64)) {
            frame.begin(7, 19, 30_000_000.25, -3, 77);
            frame.append(5, 2, 4, 4, 36, 4, 16, 20, source);
            var wire = frame.seal().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(240, wire.remaining());
            assertEquals(Packets.MAGIC, wire.getInt(0));
            assertEquals(1, wire.getInt(4));
            assertEquals(6, wire.getInt(8));
            assertEquals(7, wire.getLong(16));
            assertEquals(19, wire.getLong(24));
            assertEquals(30_000_000.25, wire.getDouble(32));
            assertEquals(-3, wire.getDouble(40));
            assertEquals(77, wire.getDouble(48));
            assertEquals(1, wire.getInt(56));
            assertEquals(0, wire.getInt(60));
            assertArrayEquals(new int[] {5, 2, 4, 4, 36, 4, 16, 20},
                              new int[] {wire.getInt(64), wire.getInt(68), wire.getInt(72),
                                         wire.getInt(76), wire.getInt(80), wire.getInt(84),
                                         wire.getInt(88), wire.getInt(92)});
            for (int i = 0; i < 144; i++)
                assertEquals((byte)(i + 7), wire.get(96 + i));
            assertEquals(7, source.position());
            assertEquals(ByteOrder.BIG_ENDIAN, source.order());
            source.put(7, (byte)0);
            assertEquals((byte)7, wire.get(96),
                         "Borrowed mesh was copied before its producer released it");
        }
    }

    @Test
    void batchesManySameMaterialObjectsWithoutObjectDescriptorsOrFrameAllocation() {
        try (var frame = new DynamicFrame(64)) {
            frame.begin(1, 1, 0, 0, 0);
            for (int object = 0; object < 4096; object++) {
                frame.beginSpan(3, 1, 4);
                for (int corner = 0; corner < 4; corner++)
                    frame.vertex(object, corner, 2, 0x80402010, .25f, .75f);
                frame.endSpan();
            }
            var packet = frame.seal();
            var wire = packet.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, frame.spanCount());
            assertEquals(16_384, frame.vertexCount());
            assertEquals(64 + 32 + 16_384 * 24, packet.byteSize());
            assertEquals(16_384, wire.getInt(76));
            assertEquals(4095, wire.getFloat(wire.limit() - 24));
            assertArrayEquals(
                    new byte[] {0x40, 0x20, 0x10, (byte)0x80},
                    new byte[] {wire.get(108), wire.get(109), wire.get(110), wire.get(111)});
            long address = packet.address();
            int growths = frame.growthCount();
            frame.begin(1, 2, 0, 0, 0);
            var empty = frame.seal();
            assertEquals(64, empty.byteSize());
            assertEquals(0, empty.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).getInt(56));
            assertEquals(address, empty.address());
            assertEquals(growths, frame.growthCount());
        }
    }

    @Test
    void materialChangeAndEmptySpanDoNotCorruptDescriptorBoundaries() {
        try (var frame = new DynamicFrame()) {
            frame.begin(1, 1, 0, 0, 0);
            quad(frame, 1, 0);
            frame.beginSpan(8, 2, 4);
            frame.endSpan();
            quad(frame, 1, 0);
            quad(frame, 1, 2);
            var wire = frame.seal().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, wire.getInt(56));
            assertEquals(8, wire.getInt(76));
            int second = 64 + 32 + 8 * 24;
            assertEquals(1, wire.getInt(second));
            assertEquals(2, wire.getInt(second + 4));
            assertEquals(4, wire.getInt(second + 12));
            assertEquals(second + 32 + 4 * 24, wire.limit());
        }
    }

    @Test
    void tenThousandDecorativeMeshesPreserveBytesAndReuseWarmedStorage() {
        int objects = 10_000, vertices = 96, stride = 36;
        ByteBuffer source =
                ByteBuffer.allocateDirect(vertices * stride).order(ByteOrder.LITTLE_ENDIAN);
        for (int vertex = 0; vertex < vertices; vertex++) {
            int offset = vertex * stride;
            source.putFloat(offset, vertex % 4)
                    .putFloat(offset + 4, vertex / 4)
                    .putFloat(offset + 8, 0);
            source.putInt(offset + 12, 0xff204080)
                    .putFloat(offset + 16, .25f)
                    .putFloat(offset + 20, .75f);
        }
        try (var frame = new DynamicFrame()) {
            long warmedAddress = 0;
            int warmedGrowths = 0;
            for (int iteration = 0; iteration < 2; iteration++) {
                frame.begin(1, iteration + 1, 0, 0, 0);
                for (int object = 0; object < objects; object++) {
                    // An unused source field distinguishes each mesh without inventing world positions.
                    source.putInt(32, object + iteration * objects);
                    frame.append(3, 1, 4, vertices, stride, 0, 12, 16, source);
                }
                var packet = frame.seal();
                var wire = packet.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(1, frame.spanCount());
                assertEquals(objects * vertices, frame.vertexCount());
                assertEquals(96L + (long)objects * vertices * stride, packet.byteSize());
                for (int object = 0; object < objects; object++)
                    assertEquals(object + iteration * objects,
                                 wire.getInt(96 + object * vertices * stride + 32));
                assertEquals(0, source.position());
                if (iteration == 0) {
                    warmedAddress = packet.address();
                    warmedGrowths = frame.growthCount();
                } else {
                    assertEquals(warmedAddress, packet.address());
                    assertEquals(warmedGrowths, frame.growthCount());
                }
            }
        }
    }

    @Test
    void incompleteOrSealedFrameCannotPublishAndNextBeginCanRecover() {
        try (var frame = new DynamicFrame()) {
            assertThrows(IllegalStateException.class, frame::seal);
            frame.begin(1, 1, 0, 0, 0);
            frame.beginSpan(0, 0, 4);
            frame.vertex(0, 0, 0, -1, 0, 0);
            assertThrows(IllegalStateException.class, frame::endSpan);
            assertThrows(IllegalStateException.class, frame::seal);
            frame.begin(1, 2, 0, 0, 0);
            quad(frame, 0, 0);
            frame.seal();
            assertThrows(IllegalStateException.class, () -> frame.beginSpan(0, 0, 4));
            assertThrows(IllegalStateException.class, frame::seal);
        }
    }

    @Test
    void storageHasExplicitThreadAndCloseLifetime() throws Exception {
        var frame = new DynamicFrame();
        frame.begin(1, 1, 0, 0, 0);
        var packet = frame.seal();
        var failure = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                frame.begin(1, 2, 0, 0, 0);
            } catch (Throwable expected) {
                failure.set(expected);
            }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        frame.close();
        assertThrows(IllegalStateException.class,
                     () -> packet.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        assertThrows(IllegalStateException.class, () -> frame.begin(1, 2, 0, 0, 0));
    }

    private static void quad(DynamicFrame frame, int texture, int flags) {
        frame.beginSpan(texture, flags, 4);
        for (int corner = 0; corner < 4; corner++)
            frame.vertex(corner, 0, 0, -1, 0, 0);
        frame.endSpan();
    }
}
