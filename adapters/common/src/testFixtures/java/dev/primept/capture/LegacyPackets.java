package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Independent legacy byte protocol encoders for CPU oracles only. */
public final class LegacyPackets {
    public static final int ABI_VERSION = 7;
    public static final int MAGIC = 0x54505250;
    public static final int TEXTURE_HEADER_BYTES = 40;
    private LegacyPackets() {}

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
        int pixels = Packets.texturePixelBytes(width, height);
        if (rgba.length != pixels)
            throw new IllegalArgumentException("Unexpected texture byte count");
        var output = ByteBuffer.allocate(TEXTURE_HEADER_BYTES + pixels);
        writeTexture(output, epoch, id, width, height, rgba);
        return output.array();
    }

    /** Writes directly into reusable owner storage; the caller retains source pixels and cursors. */
    public static void writeTexture(ByteBuffer output, long epoch, int id, int width, int height,
                                    byte[] rgba) {
        int size = Packets.texturePixelBytes(width, height);
        if (rgba.length != size)
            throw new IllegalArgumentException("Unexpected texture byte count");
        if (output.capacity() < TEXTURE_HEADER_BYTES + size)
            throw new IllegalArgumentException("Texture packet storage is too small");
        output.clear().order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC)
                .putInt(ABI_VERSION)
                .putInt(4)
                .putInt(0)
                .putLong(epoch)
                .putInt(id)
                .putInt(width)
                .putInt(height)
                .putInt(0)
                .put(rgba);
    }

    public static byte[] retireTextures(long epoch, int[] ids) {
        var packet = packet(9, epoch, Math.addExact(8, Math.multiplyExact(ids.length, 4)))
                             .putInt(ids.length)
                             .putInt(0);
        for (int id : ids)
            packet.putInt(id);
        return packet.array();
    }
}
