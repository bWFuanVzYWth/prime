package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

/** Render-thread-owned exact RGBA8-to-TintId registry and immutable RGBA16F sample table. */
final class TintSampleTable implements AutoCloseable {
    static final int MAX_TINT_ID = dev.prime.render.terrain.TintIdResolver.MAX_TINT_ID;
    static final int ENTRY_SIZE = Long.BYTES;
    private static final int OPAQUE_WHITE_RGBA = 0xffff_ffff;

    private final Map<Integer, Integer> ids = new HashMap<>();
    private final VulkanBuffer buffer;
    private int nextId = 1;

    TintSampleTable(VulkanContext context) {
        this.buffer = context.createBuffer(
                Math.multiplyExact((long) MAX_TINT_ID + 1L, ENTRY_SIZE),
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true,
                "Prime linear tint samples");
        this.ids.put(OPAQUE_WHITE_RGBA, 0);
        this.write(0, OPAQUE_WHITE_RGBA);
    }

    int resolve(int packedRgba) {
        Integer existing = this.ids.get(packedRgba);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > MAX_TINT_ID) {
            throw new IllegalStateException(
                    "Prime exhausted the exact 16-bit TintId encoding");
        }
        int tintId = this.nextId++;
        this.ids.put(packedRgba, tintId);
        this.write(tintId, packedRgba);
        return tintId;
    }

    VulkanBuffer buffer() {
        return this.buffer;
    }

    Snapshot snapshot() {
        return new Snapshot(this.ids.size(), this.nextId - 1);
    }

    private void write(int tintId, int packedRgba) {
        long offset = Math.multiplyExact((long) tintId, ENTRY_SIZE);
        MemoryUtil.memPutLong(
                this.buffer.mappedAddress() + offset,
                encodedEntry(packedRgba));
        this.buffer.flush(offset, ENTRY_SIZE);
    }

    static long encodedEntry(int packedRgba) {
        int argb = packedRgba & 0xff00_0000
                | (packedRgba & 0xff) << 16
                | packedRgba & 0x0000_ff00
                | packedRgba >>> 16 & 0xff;
        return CanonicalColorEncoding.encodeLinearSrgbTintRgba16f(argb);
    }

    @Override
    public void close() {
        this.buffer.destroy();
    }

    record Snapshot(int assignedCount, int highWaterId) {
    }
}
