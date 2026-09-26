package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Wire encoding only. Geometry/material interpretation belongs to Rust. */
public final class Packets {
    public static final int MAGIC = 0x54505250;
    private Packets() { }

    public static ByteBuffer packet(int operation, long epoch, int payloadSize) {
        return ByteBuffer.allocate(Math.addExact(24, payloadSize)).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putInt(1).putInt(operation).putInt(0).putLong(epoch);
    }

    public static byte[] reset(long epoch) { return packet(1, epoch, 0).array(); }

    public static byte[] remove(long epoch, long section, long revision) {
        return packet(3, epoch, 16).putLong(section).putLong(revision).array();
    }

    public static byte[] mesh(long epoch, long section, long revision,
            double x, double y, double z, int count, int stride, int position, int color, int uv,
            int topology, int flags, int layer, ByteBuffer vertices) {
        int size = Math.multiplyExact(count, stride);
        if (vertices.remaining() != size) throw new IllegalArgumentException("Unexpected vertex buffer size");
        return packet(2, epoch, Math.addExact(80, size))
                .putLong(section).putLong(revision).putDouble(x).putDouble(y).putDouble(z)
                .putInt(count).putInt(stride).putInt(position).putInt(color).putInt(uv)
                .putInt(topology).putInt(1).putInt(flags).putInt(layer).putInt(0)
                .put(vertices.duplicate()).array();
    }

    public static byte[] texture(long epoch, int width, int height, byte[] rgba) {
        return texture(epoch, 1, width, height, rgba);
    }

    public static byte[] texture(long epoch, int id, int width, int height, byte[] rgba) {
        if (rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4))
            throw new IllegalArgumentException("Unexpected texture byte count");
        return packet(4, epoch, Math.addExact(16, rgba.length))
                .putInt(id).putInt(width).putInt(height).putInt(0).put(rgba).array();
    }

    public static byte[] frame(long epoch, double x, double y, double z,
            float[] forward, float[] right, float[] up, float fovY, int width, int height, int sample) {
        var bytes = ByteBuffer.allocate(104);
        writeFrame(bytes, epoch, x, y, z, forward, right, up, fovY, width, height, sample);
        return bytes.array();
    }

    public static void writeFrame(ByteBuffer bytes, long epoch, double x, double y, double z,
            float[] forward, float[] right, float[] up, float fovY, int width, int height, int sample) {
        if (bytes.capacity() != 104) throw new IllegalArgumentException("Frame packet requires 104 bytes");
        bytes.clear().order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(MAGIC).putInt(1).putInt(5).putInt(0).putLong(epoch).putDouble(x).putDouble(y).putDouble(z);
        for (float[] basis : new float[][] { forward, right, up }) {
            if (basis.length != 3) throw new IllegalArgumentException("Camera basis requires three components");
            for (float component : basis) bytes.putFloat(component);
        }
        bytes.putFloat(fovY).putInt(width).putInt(height).putInt(sample).putInt(0);
    }
}
