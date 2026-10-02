package dev.primept.capture;

import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static dev.primept.abi.PrimeAbi.*;
import dev.primept.NativeBridge;
import java.nio.ByteOrder;

/** Typed frame binding and texture budget. Interpretation belongs to Rust. */
public final class Packets {
    public static final int MAX_PACKET_BYTES = 256 << 20;
    private Packets() {}

    /** Validates the complete typed texture batch budget before any source pixels or packet storage are allocated. */
    public static int texturePixelBytes(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid texture dimensions");
        long pixels = (long)width * height;
        if (pixels > (MAX_PACKET_BYTES - PrimeTextureBatch.SIZE - PrimeTextureSource.SIZE) / 4)
            throw new IllegalArgumentException("Texture exceeds the 256 MiB packet capacity");
        return (int)pixels * 4;
    }

    public static byte[] frame(long epoch, double x, double y, double z, float[] forward,
                               float[] right, float[] up, float fovY, int width, int height,
                               int sample) {
        try (var arena = Arena.ofConfined()) {
            var memory = arena.allocate(PrimeFrame.LAYOUT);
            writeFrame(memory.asByteBuffer(), epoch, x, y, z, forward, right, up, fovY, width,
                       height, sample);
            return memory.toArray(JAVA_BYTE);
        }
    }

    public static void writeFrame(ByteBuffer bytes, long epoch, double x, double y, double z,
                                  float[] forward, float[] right, float[] up, float fovY, int width,
                                  int height, int sample) {
        writeFrame(bytes, epoch, x, y, z, forward, right, up, fovY, width, height, sample, 0);
    }

    public static void writeFrame(ByteBuffer bytes, long epoch, double x, double y, double z,
                                  float[] forward, float[] right, float[] up, float fovY, int width,
                                  int height, int sample, float solarHourAngle) {
        if (bytes.capacity() != PrimeFrame.SIZE)
            throw new IllegalArgumentException("Frame structure size mismatch");
        if (forward.length != 3 || right.length != 3 || up.length != 3)
            throw new IllegalArgumentException("Camera basis requires three components");
        bytes.clear().order(ByteOrder.nativeOrder());
        var frame = MemorySegment.ofBuffer(bytes);
        NativeBridge.header(PrimeFrame.header(frame), PrimeFrame.SIZE);
        PrimeFrame.epoch(frame, epoch);
        PrimeFrame.position(frame, 0, x);
        PrimeFrame.position(frame, 1, y);
        PrimeFrame.position(frame, 2, z);
        for (int i = 0; i < 3; i++) {
            PrimeFrame.forward(frame, i, forward[i]);
            PrimeFrame.right(frame, i, right[i]);
            PrimeFrame.up(frame, i, up[i]);
        }
        PrimeFrame.fov_y(frame, fovY);
        PrimeFrame.width(frame, width);
        PrimeFrame.height(frame, height);
        PrimeFrame.sample_index(frame, sample);
        PrimeFrame.solar_hour_angle(frame, solarHourAngle);
        bytes.position((int)PrimeFrame.SIZE);
    }

    /** Reuses the last rendered camera packet; frozen frames transmit only the frame ABI, no scene data. */
    public static void resizeFrozenFrame(ByteBuffer bytes, int width, int height, int sample) {
        var frame = MemorySegment.ofBuffer(bytes.duplicate().clear());
        PrimeFrame.width(frame, width);
        PrimeFrame.height(frame, height);
        PrimeFrame.sample_index(frame, sample);
        bytes.position((int)PrimeFrame.SIZE);
    }
}
