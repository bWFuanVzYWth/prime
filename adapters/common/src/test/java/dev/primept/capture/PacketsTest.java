package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;
import static dev.primept.abi.PrimeAbi.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketsTest {
    @Test
    void textureBudgetIncludesTypedDescriptorsAndRejectsDimensionsWithoutAllocation() {
        long descriptors = PrimeTextureBatch.SIZE + PrimeTextureSource.SIZE;
        int largestPixels = (int)((Packets.MAX_PACKET_BYTES - descriptors) / 4);
        assertEquals(Packets.MAX_PACKET_BYTES,
                     descriptors + Packets.texturePixelBytes(largestPixels, 1));
        assertThrows(IllegalArgumentException.class,
                     () -> Packets.texturePixelBytes(largestPixels + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(8192, 8192));
        assertThrows(IllegalArgumentException.class,
                     () -> Packets.texturePixelBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Packets.texturePixelBytes(1, -1));
    }
    @Test
    void cameraFieldsPreserveWorldPrecisionAndBasis() {
        byte[] bytes =
                Packets.frame(17, 30_000_000.25, -64, -17, new float[] {0, 0, -1},
                              new float[] {1, 0, 0}, new float[] {0, 1, 0}, 1.2f, 1920, 1080, 39);
        try (var arena = Arena.ofConfined()) {
            var frame = arena.allocate(PrimeFrame.LAYOUT);
            frame.copyFrom(MemorySegment.ofArray(bytes));
            assertEquals(PrimeFrame.SIZE, bytes.length);
            assertEquals(PrimeFrame.SIZE, PrimeHeader.struct_size(PrimeFrame.header(frame)));
            assertEquals(PRIME_ABI_VERSION, PrimeHeader.abi_version(PrimeFrame.header(frame)));
            assertEquals(17, PrimeFrame.epoch(frame));
            assertEquals(30_000_000.25, PrimeFrame.position(frame, 0));
            assertEquals(-64, PrimeFrame.position(frame, 1));
            assertEquals(-17, PrimeFrame.position(frame, 2));
            assertEquals(-1, PrimeFrame.forward(frame, 2));
            assertEquals(1, PrimeFrame.right(frame, 0));
            assertEquals(1, PrimeFrame.up(frame, 1));
            assertEquals(1.2f, PrimeFrame.fov_y(frame));
            assertEquals(1920, PrimeFrame.width(frame));
            assertEquals(1080, PrimeFrame.height(frame));
            assertEquals(39, PrimeFrame.sample_index(frame));
        }
    }
    @Test
    void reusedNativeFrameOverwritesEveryFieldAndRestoresNativeOrder() {
        try (var arena = Arena.ofConfined()) {
            var frame = arena.allocate(PrimeFrame.LAYOUT);
            var bytes = frame.asByteBuffer();
            float[] forward = {0, 0, -1}, right = {1, 0, 0}, up = {0, 1, 0};
            Packets.writeFrame(bytes, 1, 10, 20, 30, forward, right, up, 1, 1920, 1080, 7, 1.3f);
            assertEquals(1.3f, PrimeFrame.solar_hour_angle(frame));
            bytes.order(ByteOrder.BIG_ENDIAN).putLong(0, -1).position(17).limit(23);
            Packets.writeFrame(bytes, 2, -10, -20, -30, forward, right, up, 1.2f, 1920, 1080, 0);
            assertEquals(PrimeFrame.SIZE, bytes.position());
            assertEquals(ByteOrder.nativeOrder(), bytes.order());
            assertEquals(PRIME_ABI_VERSION, PrimeHeader.abi_version(PrimeFrame.header(frame)));
            assertEquals(2, PrimeFrame.epoch(frame));
            assertEquals(-10, PrimeFrame.position(frame, 0));
            assertEquals(0, PrimeFrame.sample_index(frame));
            assertEquals(0, PrimeFrame.solar_hour_angle(frame));
            assertArrayEquals(
                    Packets.frame(2, -10, -20, -30, forward, right, up, 1.2f, 1920, 1080, 0),
                    frame.toArray(JAVA_BYTE));
        }
    }
    @Test
    void malformedCaptureLengthsAreRejectedBeforeFfm() {
        try (var frame = new DynamicFrame()) {
            frame.begin(1, 1, 0, 0, 0);
            assertThrows(IllegalArgumentException.class,
                         () -> frame.append(1, 0, 4, 4, 28, 0, 12, 16, ByteBuffer.allocate(111)));
        }
        assertThrows(IllegalArgumentException.class,
                     ()
                             -> Packets.writeFrame(ByteBuffer.allocateDirect(95), 1, 0, 0, 0,
                                                   new float[] {0, 0, -1}, new float[] {1, 0, 0},
                                                   new float[] {0, 1, 0}, 1, 8, 8, 0));
    }
}
