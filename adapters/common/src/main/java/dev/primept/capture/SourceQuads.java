package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Worker-owned source vertices. No Minecraft type, raster lighting, or GPU layout is retained. */
public final class SourceQuads {
    public static final int STRIDE = 24;
    public static final int OPAQUE = 0;
    public static final int CUTOUT = 1;
    public static final int TRANSLUCENT = 2;
    private static final int MAX_BYTES = 64 << 20;
    private final ByteBuffer[] layers = new ByteBuffer[3];
    private int bytes;
    private boolean sealed;

    public void vertex(int layer, float x, float y, float z, int argb, float u, float v) {
        if (sealed)
            throw new IllegalStateException("Source batch is sealed");
        if (layer < 0 || layer >= layers.length)
            throw new IllegalArgumentException("Unsupported source layer");
        if (bytes > MAX_BYTES - STRIDE)
            throw new IllegalStateException("Source section exceeds 64 MiB");
        ByteBuffer target = layers[layer];
        if (target == null)
            target = layers[layer] = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        if (target.remaining() < STRIDE) {
            ByteBuffer grown = ByteBuffer.allocate(Math.min(MAX_BYTES, target.capacity() * 2))
                                       .order(ByteOrder.LITTLE_ENDIAN);
            target.flip();
            grown.put(target);
            target = layers[layer] = grown;
        }
        target.putFloat(x)
                .putFloat(y)
                .putFloat(z)
                .put((byte)(argb >>> 16))
                .put((byte)(argb >>> 8))
                .put((byte)argb)
                .put((byte)(argb >>> 24))
                .putFloat(u)
                .putFloat(v);
        bytes += STRIDE;
    }

    public void seal() {
        if (sealed)
            throw new IllegalStateException("Source batch already sealed");
        for (ByteBuffer layer : layers) {
            if (layer != null && layer.position() % (4 * STRIDE) != 0)
                throw new IllegalStateException("Incomplete source quad");
        }
        sealed = true;
    }

    /** Borrowed only until the worker encodes its immutable publication packet. */
    public ByteBuffer vertices(int layer) {
        if (!sealed)
            throw new IllegalStateException("Source batch is still being written");
        ByteBuffer result = layers[layer];
        return result == null ? ByteBuffer.allocate(0)
                              : result.asReadOnlyBuffer().flip().order(ByteOrder.LITTLE_ENDIAN);
    }
}
