package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Wire encoding only. Geometry/material interpretation belongs to Rust. */
public final class Packets {
    public static final int ABI_VERSION = 7;
    public static final int MAGIC = 0x54505250;
    private Packets() {}

    public static ByteBuffer packet(int operation, long epoch, int payloadSize) {
        return ByteBuffer.allocate(Math.addExact(24, payloadSize))
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .putInt(ABI_VERSION)
                .putInt(operation)
                .putInt(0)
                .putLong(epoch);
    }

    public static byte[] reset(long epoch) {
        return packet(1, epoch, 0).array();
    }

    public static byte[] remove(long epoch, long section, long revision) {
        return packet(3, epoch, 16).putLong(section).putLong(revision).array();
    }

    public static byte[] sectionWatermark(long epoch, long completed) {
        return packet(10, epoch, 8).putLong(completed).array();
    }

    public static byte[] removeSections(long epoch, long[] sections, long[] sequences) {
        if (sections.length != sequences.length)
            throw new IllegalArgumentException("Mismatched removal records");
        var packet = packet(11, epoch, Math.addExact(8, Math.multiplyExact(sections.length, 16)))
                             .putInt(sections.length)
                             .putInt(0);
        for (int i = 0; i < sections.length; i++)
            packet.putLong(sections[i]).putLong(sequences[i]);
        return packet.array();
    }

    /** One accepted source layer; buffer cursors remain owned by the caller. */
    public record SectionLayer(int layer, int texture, int flags, int topology, int count,
                               int stride, int position, int color, int uv, ByteBuffer vertices) {}

    /** Atomically replaces every layer of a section; an empty list removes its geometry. */
    public static byte[] sectionReplace(long epoch, long section, long sequence, double x, double y,
                                        double z, List<SectionLayer> layers) {
        int payload = 48;
        for (SectionLayer layer : layers) {
            int size = Math.multiplyExact(layer.count, layer.stride);
            if (layer.vertices.remaining() != size)
                throw new IllegalArgumentException("Unexpected section layer length");
            payload = Math.addExact(payload, Math.addExact(40, size));
        }
        ByteBuffer output = packet(8, epoch, payload)
                                    .putLong(section)
                                    .putLong(sequence)
                                    .putDouble(x)
                                    .putDouble(y)
                                    .putDouble(z)
                                    .putInt(layers.size())
                                    .putInt(0);
        for (SectionLayer layer : layers) {
            output.putInt(layer.layer)
                    .putInt(layer.texture)
                    .putInt(layer.flags)
                    .putInt(layer.topology)
                    .putInt(layer.count)
                    .putInt(layer.stride)
                    .putInt(layer.position)
                    .putInt(layer.color)
                    .putInt(layer.uv)
                    .putInt(0)
                    .put(layer.vertices.duplicate());
        }
        return output.array();
    }

    public static byte[] texture(long epoch, int width, int height, byte[] rgba) {
        return texture(epoch, 1, width, height, rgba);
    }

    public static byte[] texture(long epoch, int id, int width, int height, byte[] rgba) {
        if (rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4))
            throw new IllegalArgumentException("Unexpected texture byte count");
        return packet(4, epoch, Math.addExact(16, rgba.length))
                .putInt(id)
                .putInt(width)
                .putInt(height)
                .putInt(0)
                .put(rgba)
                .array();
    }

    public static byte[] retireTextures(long epoch, int[] ids) {
        var packet = packet(9, epoch, Math.addExact(8, Math.multiplyExact(ids.length, 4)))
                             .putInt(ids.length)
                             .putInt(0);
        for (int id : ids)
            packet.putInt(id);
        return packet.array();
    }

    public static byte[] frame(long epoch, double x, double y, double z, float[] forward,
                               float[] right, float[] up, float fovY, int width, int height,
                               int sample) {
        var bytes = ByteBuffer.allocate(104);
        writeFrame(bytes, epoch, x, y, z, forward, right, up, fovY, width, height, sample);
        return bytes.array();
    }

    public static void writeFrame(ByteBuffer bytes, long epoch, double x, double y, double z,
                                  float[] forward, float[] right, float[] up, float fovY, int width,
                                  int height, int sample) {
        writeFrame(bytes, epoch, x, y, z, forward, right, up, fovY, width, height, sample, 0);
    }

    public static void writeFrame(ByteBuffer bytes, long epoch, double x, double y, double z,
                                  float[] forward, float[] right, float[] up, float fovY, int width,
                                  int height, int sample, float solarHourAngle) {
        if (bytes.capacity() != 104)
            throw new IllegalArgumentException("Frame packet requires 104 bytes");
        bytes.clear().order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(MAGIC)
                .putInt(ABI_VERSION)
                .putInt(5)
                .putInt(0)
                .putLong(epoch)
                .putDouble(x)
                .putDouble(y)
                .putDouble(z);
        for (float[] basis : new float[][] {forward, right, up}) {
            if (basis.length != 3)
                throw new IllegalArgumentException("Camera basis requires three components");
            for (float component : basis)
                bytes.putFloat(component);
        }
        bytes.putFloat(fovY).putInt(width).putInt(height).putInt(sample).putFloat(solarHourAngle);
    }

    /** Reuses the last rendered camera packet; frozen frames transmit only the frame ABI, no scene data. */
    public static void resizeFrozenFrame(ByteBuffer bytes, int width, int height, int sample) {
        bytes.putInt(88, width).putInt(92, height).putInt(96, sample);
        bytes.position(104);
    }
}
