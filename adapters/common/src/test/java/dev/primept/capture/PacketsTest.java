package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketsTest {
    @Test
    void textureBudgetIncludesTheWholePacketAndRejectsDimensionsWithoutAllocation() {
        int largestPixels = (Packets.MAX_PACKET_BYTES - Packets.TEXTURE_HEADER_BYTES) / 4;
        assertEquals(Packets.MAX_PACKET_BYTES,
                     Packets.TEXTURE_HEADER_BYTES + Packets.texturePixelBytes(largestPixels, 1));
        assertThrows(IllegalArgumentException.class,
                     () -> Packets.texturePixelBytes(largestPixels + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(8192, 8192));
        assertThrows(IllegalArgumentException.class,
                     () -> Packets.texturePixelBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(1, -1));
    }

    @Test
    void nativeTextureWriterReusesStorageAndOverwritesEveryWireField() {
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(64, 8);
            var output = memory.asByteBuffer();
            byte[] pixels = {1, 2, 3, 4, 5, 6, 7, 8};
            Packets.writeTexture(output, 11, 19, 2, 1, pixels);
            assertEquals(48, output.position());
            assertEquals(Packets.MAGIC, output.getInt(0));
            assertEquals(Packets.ABI_VERSION, output.getInt(4));
            assertEquals(4, output.getInt(8));
            assertEquals(0, output.getInt(12));
            assertEquals(11, output.getLong(16));
            assertEquals(19, output.getInt(24));
            assertEquals(2, output.getInt(28));
            assertEquals(1, output.getInt(32));
            assertEquals(0, output.getInt(36));
            assertArrayEquals(pixels, memory.asSlice(40, 8).toArray(ValueLayout.JAVA_BYTE));
            pixels[0] = 99;
            assertEquals(1, output.get(40), "The wire owns its one copied pixel snapshot");

            output.order(ByteOrder.BIG_ENDIAN).putLong(0, -1).position(7).limit(13);
            byte[] replacement = {9, 10, 11, 12};
            Packets.writeTexture(output, 12, 27, 1, 1, replacement);
            assertEquals(44, output.position());
            assertEquals(ByteOrder.LITTLE_ENDIAN, output.order());
            assertEquals(Packets.MAGIC, output.getInt(0));
            assertEquals(12, output.getLong(16));
            assertEquals(27, output.getInt(24));
            assertEquals(1, output.getInt(28));
            assertEquals(1, output.getInt(32));
            assertEquals(0, output.getInt(36));
            assertArrayEquals(replacement, memory.asSlice(40, 4).toArray(ValueLayout.JAVA_BYTE));

            byte[] before = memory.toArray(ValueLayout.JAVA_BYTE);
            assertThrows(IllegalArgumentException.class,
                         () -> Packets.writeTexture(output, 13, 28, 2, 1, replacement));
            assertArrayEquals(before, memory.toArray(ValueLayout.JAVA_BYTE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Packets.writeTexture(ByteBuffer.allocate(43), 13, 28, 1, 1, replacement));
        }
    }

    @Test
    void completeSectionHasOneAtomicPacketAndOwnsEveryLayer() {
        var opaque = ByteBuffer.allocate(4 * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < opaque.capacity(); i++)
            opaque.put((byte)i);
        opaque.flip();
        var alpha = ByteBuffer.allocate(3 * 32).order(ByteOrder.LITTLE_ENDIAN);
        alpha.putFloat(0, 3.25f);
        byte[] packet = Packets.sectionReplace(
                17, -91, 12, -32, 64, 160,
                List.of(new Packets.SectionLayer(0, 1, 0, 4, 4, 24, 0, 12, 16, opaque),
                        new Packets.SectionLayer(2, 7, 2, 3, 3, 32, 0, 12, 16, alpha)));
        var wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(72 + 2 * 40 + 2 * 96, packet.length);
        assertEquals(Packets.MAGIC, wire.getInt(0));
        assertEquals(8, wire.getInt(8));
        assertEquals(17, wire.getLong(16));
        assertEquals(-91, wire.getLong(24));
        assertEquals(12, wire.getLong(32));
        assertEquals(-32, wire.getDouble(40));
        assertEquals(64, wire.getDouble(48));
        assertEquals(160, wire.getDouble(56));
        assertEquals(2, wire.getInt(64));
        assertEquals(0, wire.getInt(68));
        assertEquals(4, wire.getInt(88));
        assertEquals(24, wire.getInt(92));
        int next = 72 + 40 + 96;
        assertEquals(2, wire.getInt(next));
        assertEquals(7, wire.getInt(next + 4));
        assertEquals(2, wire.getInt(next + 8));
        assertEquals(3, wire.getInt(next + 12));
        assertEquals(3.25f, wire.getFloat(next + 40));
        assertEquals(0, opaque.position());
        opaque.put(0, (byte)99);
        assertEquals(0, wire.get(112));
        byte[] empty = Packets.sectionReplace(17, -91, 13, -32, 64, 160, List.of());
        assertEquals(72, empty.length);
        assertEquals(0, ByteBuffer.wrap(empty).order(ByteOrder.LITTLE_ENDIAN).getInt(64));
        assertThrows(IllegalArgumentException.class,
                     ()
                             -> Packets.sectionReplace(
                                     1, 1, 1, 0, 0, 0,
                                     List.of(new Packets.SectionLayer(0, 1, 0, 4, 4, 24, 0, 12, 16,
                                                                      ByteBuffer.allocate(95)))));
    }

    @Test
    void meshRetainsSourceBytesAndExplicitFormat() {
        ByteBuffer source = ByteBuffer.allocate(28 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < source.capacity(); i++)
            source.put((byte)i);
        source.flip();
        byte[] packet = Packets.sectionReplace(
                7, -91, 12, -32, 64, 160,
                List.of(new Packets.SectionLayer(1, 1, 1, 4, 4, 28, 0, 12, 16, source)));
        assertEquals(0, source.position(), "Capture must not mutate Minecraft's buffer cursor");
        var wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(224, packet.length);
        assertEquals(Packets.MAGIC, wire.getInt(0));
        assertEquals(8, wire.getInt(8));
        assertEquals(7, wire.getLong(16));
        assertEquals(-91, wire.getLong(24));
        assertEquals(-32, wire.getDouble(40));
        assertEquals(4, wire.getInt(88));
        assertEquals(28, wire.getInt(92));
        assertEquals(12, wire.getInt(100));
        assertEquals(16, wire.getInt(104));
        assertEquals(1, wire.getInt(72));
        byte[] captured = new byte[source.remaining()];
        wire.position(112).get(captured);
        source.put(0, (byte)99);
        assertEquals(0, captured[0],
                     "Packet owns bytes independently of vanilla MeshData lifetime");
        for (int i = 1; i < captured.length; i++)
            assertEquals((byte)i, captured[i]);
    }

    @Test
    void cameraPacketHasExactAbiOffsets() {
        byte[] frame =
                Packets.frame(17, 30_000_000.25, -64, -17, new float[] {0, 0, -1},
                              new float[] {1, 0, 0}, new float[] {0, 1, 0}, 1.2f, 1920, 1080, 39);
        var wire = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(104, frame.length);
        assertEquals(5, wire.getInt(8));
        assertEquals(30_000_000.25, wire.getDouble(24));
        assertEquals(-1, wire.getFloat(56));
        assertEquals(1, wire.getFloat(60));
        assertEquals(1, wire.getFloat(76));
        assertEquals(1.2f, wire.getFloat(84));
        assertEquals(1920, wire.getInt(88));
        assertEquals(1080, wire.getInt(92));
        assertEquals(39, wire.getInt(96));
    }

    @Test
    void reusedNativeFrameOverwritesEveryFieldAndRestoresWireOrder() {
        var wire = ByteBuffer.allocateDirect(104);
        float[] forward = {0, 0, -1}, right = {1, 0, 0}, up = {0, 1, 0};
        Packets.writeFrame(wire, 1, 10, 20, 30, forward, right, up, 1, 1920, 1080, 7, 1.3f);
        assertEquals(1.3f, wire.getFloat(100));
        wire.order(ByteOrder.BIG_ENDIAN).putLong(0, -1).position(17).limit(23);
        Packets.writeFrame(wire, 2, -10, -20, -30, forward, right, up, 1.2f, 1920, 1080, 0);
        assertEquals(104, wire.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, wire.order());
        assertEquals(Packets.MAGIC, wire.getInt(0));
        assertEquals(2, wire.getLong(16));
        assertEquals(-10, wire.getDouble(24));
        assertEquals(0, wire.getInt(96));
        assertEquals(0, wire.getInt(100));
        byte[] copied = new byte[104];
        wire.flip().get(copied);
        assertArrayEquals(Packets.frame(2, -10, -20, -30, forward, right, up, 1.2f, 1920, 1080, 0),
                          copied);
    }

    @Test
    void malformedCaptureLengthsAreRejectedBeforeFfm() {
        assertThrows(IllegalArgumentException.class, () -> Packets.texture(1, 2, 2, new byte[15]));
        assertThrows(IllegalArgumentException.class,
                     ()
                             -> Packets.sectionReplace(
                                     1, 1, 1, 0, 0, 0,
                                     List.of(new Packets.SectionLayer(0, 1, 0, 4, 4, 28, 0, 12, 16,
                                                                      ByteBuffer.allocate(111)))));
    }
}
