package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

/** Render-thread-owned exact RGB8-to-TintId registry and immutable-index GPU operator table. */
final class TintOperatorTable implements AutoCloseable {
    static final int MAX_TINT_ID = dev.prime.render.terrain.TintIdResolver.MAX_TINT_ID;
    static final int ENTRY_SIZE = 48;
    private static final int WHITE_RGB = 0x00ff_ffff;

    private final Map<Integer, Integer> ids = new HashMap<>();
    private final VulkanBuffer buffer;
    private int nextId = 1;

    TintOperatorTable(VulkanContext context) {
        this.buffer = context.createBuffer(
                Math.multiplyExact((long) MAX_TINT_ID + 1L, ENTRY_SIZE),
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime exact tint operators");
        this.ids.put(WHITE_RGB, 0);
        this.write(0, WHITE_RGB);
    }

    int resolve(int packedRgb) {
        if ((packedRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("Packed tint exceeds RGB8");
        }
        Integer existing = this.ids.get(packedRgb);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > MAX_TINT_ID) {
            throw new IllegalStateException(
                    "Prime exhausted the exact 16-bit TintId encoding");
        }
        int tintId = this.nextId++;
        this.ids.put(packedRgb, tintId);
        this.write(tintId, packedRgb);
        return tintId;
    }

    VulkanBuffer buffer() {
        return this.buffer;
    }

    Snapshot snapshot() {
        return new Snapshot(this.ids.size(), this.nextId - 1);
    }

    private void write(int tintId, int packedRgb) {
        float[] entry = encodedEntry(packedRgb);
        long offset = Math.multiplyExact((long) tintId, ENTRY_SIZE);
        long target = this.buffer.mappedAddress() + offset;
        for (int word = 0; word < entry.length; word++) {
            MemoryUtil.memPutFloat(target + word * Float.BYTES, entry[word]);
        }
        this.buffer.flush(offset, ENTRY_SIZE);
    }

    static float[] encodedEntry(int packedRgb) {
        CanonicalColorEncoding.TintOperator operator =
                CanonicalColorEncoding.tintOperator(packedRgb);
        return new float[] {
            operator.m00(), operator.m01(), operator.m02(),
            clamp(operator.m00() + operator.m01() + operator.m02()),
            operator.m10(), operator.m11(), operator.m12(),
            clamp(operator.m10() + operator.m11() + operator.m12()),
            operator.m20(), operator.m21(), operator.m22(),
            clamp(operator.m20() + operator.m21() + operator.m22())
        };
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    @Override
    public void close() {
        this.buffer.destroy();
    }

    record Snapshot(int assignedCount, int highWaterId) {
    }
}
