package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketsTest {
    @Test void meshRetainsSourceBytesAndExplicitFormat() {
        ByteBuffer source = ByteBuffer.allocate(28 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < source.capacity(); i++) source.put((byte) i);
        source.flip();
        byte[] packet = Packets.mesh(7, -91, 12, -32, 64, 160, 4, 28, 0, 12, 16, 4, 1, 1, source);
        assertEquals(0, source.position(), "Capture must not mutate Minecraft's buffer cursor");
        var wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(216, packet.length);
        assertEquals(Packets.MAGIC, wire.getInt(0));
        assertEquals(2, wire.getInt(8));
        assertEquals(7, wire.getLong(16));
        assertEquals(-91, wire.getLong(24));
        assertEquals(-32, wire.getDouble(40));
        assertEquals(4, wire.getInt(64));
        assertEquals(28, wire.getInt(68));
        assertEquals(12, wire.getInt(76));
        assertEquals(16, wire.getInt(80));
        assertEquals(1, wire.getInt(96));
        byte[] captured = new byte[source.remaining()];
        wire.position(104).get(captured);
        source.put(0, (byte) 99);
        assertEquals(0, captured[0], "Packet owns bytes independently of vanilla MeshData lifetime");
        for (int i = 1; i < captured.length; i++) assertEquals((byte) i, captured[i]);
    }

    @Test void cameraPacketHasExactAbiOffsets() {
        byte[] frame = Packets.frame(17, 30_000_000.25, -64, -17,
                new float[] { 0, 0, -1 }, new float[] { 1, 0, 0 }, new float[] { 0, 1, 0 },
                1.2f, 1920, 1080, 39);
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

    @Test void reusedNativeFrameOverwritesEveryFieldAndRestoresWireOrder() {
        var wire = ByteBuffer.allocateDirect(104);
        float[] forward = { 0, 0, -1 }, right = { 1, 0, 0 }, up = { 0, 1, 0 };
        Packets.writeFrame(wire, 1, 10, 20, 30, forward, right, up, 1, 1920, 1080, 7);
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
        assertArrayEquals(Packets.frame(2, -10, -20, -30, forward, right, up, 1.2f, 1920, 1080, 0), copied);
    }

    @Test void malformedCaptureLengthsAreRejectedBeforeFfm() {
        assertThrows(IllegalArgumentException.class, () -> Packets.texture(1, 2, 2, new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> Packets.mesh(1, 1, 1, 0, 0, 0,
                4, 28, 0, 12, 16, 4, 0, 0, ByteBuffer.allocate(111)));
    }
}
