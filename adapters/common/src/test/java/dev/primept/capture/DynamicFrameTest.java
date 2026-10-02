package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;
import static dev.primept.abi.PrimeAbi.*;
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
            var batch = frame.seal();
            assertEquals(PrimeDynamicBatch.SIZE, batch.byteSize());
            assertEquals(PrimeDynamicBatch.SIZE,
                         PrimeHeader.struct_size(PrimeDynamicBatch.header(batch)));
            assertEquals(PRIME_ABI_VERSION,
                         PrimeHeader.abi_version(PrimeDynamicBatch.header(batch)));
            assertEquals(7, PrimeDynamicBatch.epoch(batch));
            assertEquals(19, PrimeDynamicBatch.sequence(batch));
            assertEquals(30_000_000.25, PrimeDynamicBatch.origin(batch, 0));
            assertEquals(-3, PrimeDynamicBatch.origin(batch, 1));
            assertEquals(77, PrimeDynamicBatch.origin(batch, 2));
            assertEquals(1, PrimeDynamicBatch.count(batch));
            var span = span(batch, 0);
            assertArrayEquals(
                    new int[] {5, 2, 4, 4, 36, 4, 16, 20},
                    new int[] {PrimeMeshSpan.texture_id(span), PrimeMeshSpan.flags(span),
                               PrimeMeshSpan.topology(span), PrimeMeshSpan.vertex_count(span),
                               PrimeMeshSpan.stride(span), PrimeMeshSpan.position_offset(span),
                               PrimeMeshSpan.color_offset(span), PrimeMeshSpan.uv_offset(span)});
            var wire = vertices(span).asByteBuffer();
            assertEquals(144, wire.remaining());
            for (int i = 0; i < 144; i++)
                assertEquals((byte)(i + 7), wire.get(i));
            assertEquals(7, source.position());
            assertEquals(ByteOrder.BIG_ENDIAN, source.order());
            source.put(7, (byte)0);
            assertEquals((byte)7, wire.get(0),
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
            var descriptor = span(packet, 0);
            var wire = vertices(descriptor).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, frame.spanCount());
            assertEquals(16_384, frame.vertexCount());
            assertEquals(64 + 48 + 16_384 * 24, frame.byteSize());
            assertEquals(16_384, PrimeMeshSpan.vertex_count(descriptor));
            assertEquals(4095, wire.getFloat(wire.limit() - 24));
            assertArrayEquals(new byte[] {0x40, 0x20, 0x10, (byte)0x80},
                              new byte[] {wire.get(12), wire.get(13), wire.get(14), wire.get(15)});
            long address = packet.address();
            int growths = frame.growthCount();
            frame.begin(1, 2, 0, 0, 0);
            var empty = frame.seal();
            assertEquals(64, empty.byteSize());
            assertEquals(0, PrimeDynamicBatch.count(empty));
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
            var batch = frame.seal();
            assertEquals(2, PrimeDynamicBatch.count(batch));
            assertEquals(8, PrimeMeshSpan.vertex_count(span(batch, 0)));
            var second = span(batch, 1);
            assertEquals(1, PrimeMeshSpan.texture_id(second));
            assertEquals(2, PrimeMeshSpan.flags(second));
            assertEquals(4, PrimeMeshSpan.vertex_count(second));
            assertEquals(64 + 2 * 48 + 12 * 24, frame.byteSize());
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
                var wire = vertices(span(packet, 0)).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(1, frame.spanCount());
                assertEquals(objects * vertices, frame.vertexCount());
                assertEquals(64L + 48L + (long)objects * vertices * stride, frame.byteSize());
                for (int object = 0; object < objects; object++)
                    assertEquals(object + iteration * objects,
                                 wire.getInt(object * vertices * stride + 32));
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

    private static MemorySegment span(MemorySegment batch, long index) {
        assertTrue(index < PrimeDynamicBatch.count(batch));
        return PrimeDynamicBatch.spans(batch)
                .reinterpret(Math.multiplyExact(PrimeDynamicBatch.count(batch), PrimeMeshSpan.SIZE))
                .asSlice(index * PrimeMeshSpan.SIZE, PrimeMeshSpan.SIZE);
    }
    private static MemorySegment vertices(MemorySegment span) {
        var bytes = PrimeMeshSpan.vertices(span);
        return PrimeByteSpan.data(bytes).reinterpret(PrimeByteSpan.count(bytes));
    }
    private static void quad(DynamicFrame frame, int texture, int flags) {
        frame.beginSpan(texture, flags, 4);
        for (int corner = 0; corner < 4; corner++)
            frame.vertex(corner, 0, 0, -1, 0, 0);
        frame.endSpan();
    }
}
