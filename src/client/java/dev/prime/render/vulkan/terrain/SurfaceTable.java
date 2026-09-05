package dev.prime.render.vulkan.terrain;

import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanSync;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/** One fixed-stride GPU array. Growth preserves every index and publication owns buffer retirement. */
final class SurfaceTable implements AutoCloseable {
    final SurfaceRecords records = new SurfaceRecords();
    private final VulkanContext context;
    private VulkanBuffer buffer;
    private Upload pending;

    SurfaceTable(VulkanContext context) {
        this.context = context;
        this.buffer = allocate(32L);
    }

    private VulkanBuffer allocate(long bytes) {
        return this.context.createBuffer(bytes,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false, "Prime global surface records");
    }

    TerrainScene.SurfaceBinding binding() {
        VulkanBuffer effective = this.pending == null ? this.buffer : this.pending.target;
        return new TerrainScene.SurfaceBinding(effective.handle(), effective.size());
    }

    void upload(StagingArena.Batch staging, VkCommandBuffer command) {
        this.records.drain();
        List<SurfaceRecords.Entry> dirty = this.records.dirty();
        if (dirty.isEmpty()) return;
        long required = Math.multiplyExact((long) this.records.extent(), 32L);
        long limit = this.context.maxStorageBufferRange() & -32L;
        if (required > limit) {
            throw new IllegalStateException("Global surface table needs " + required
                    + " bytes; device maxStorageBufferRange is " + limit);
        }
        long capacity = this.buffer.size();
        while (capacity < required) capacity = Math.min(limit, Math.multiplyExact(capacity, 2L));
        VulkanBuffer target = capacity == this.buffer.size() ? this.buffer : allocate(capacity);
        this.pending = new Upload(target, dirty);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (target != this.buffer) {
                // Previous uploads may be earlier on this queue. The old array stays readable by
                // old frames; only never-live or fully retired slots are written in the new array.
                VulkanSync.memoryBarrier(command, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_READ_BIT);
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                copy.get(0).size(this.buffer.size());
                VK12.vkCmdCopyBuffer(command, this.buffer.handle(), target.handle(), copy);
                VulkanSync.memoryBarrier(command, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            }
            int[] words = new int[Math.multiplyExact(dirty.size(), SurfaceRecords.WORDS)];
            for (int i = 0; i < dirty.size(); i++) dirty.get(i).record.write(words, i * SurfaceRecords.WORDS);
            StagingArena.Slice slice = staging.write(words, Integer.BYTES);
            // A single packed staging allocation and bounded region batches also handle reused holes.
            VkBufferCopy.Buffer copies = VkBufferCopy.calloc(Math.min(1024, dirty.size()), stack);
            int cursor = 0;
            while (cursor < dirty.size()) {
                copies.limit(copies.capacity());
                int count = 0;
                while (cursor < dirty.size() && count < copies.capacity()) {
                    int first = cursor++;
                    while (cursor < dirty.size()
                            && dirty.get(cursor).key == dirty.get(cursor - 1).key + 1) {
                        cursor++;
                    }
                    copies.get(count++).srcOffset(slice.offset() + (long) first * 32L)
                            .dstOffset((long) dirty.get(first).key * 32L)
                            .size((long) (cursor - first) * 32L);
                }
                copies.limit(count);
                VK12.vkCmdCopyBuffer(command, slice.buffer(), target.handle(), copies);
            }
        }
    }

    void publish() {
        if (this.pending == null) return;
        Upload upload = this.pending;
        this.pending = null;
        VulkanBuffer previous = this.buffer;
        this.buffer = upload.target;
        this.records.uploaded(upload.entries);
        if (previous != this.buffer) this.context.defer(previous);
    }

    void abort(boolean submitted) {
        if (this.pending == null) return;
        VulkanBuffer failed = this.pending.target;
        this.pending = null;
        if (failed != this.buffer) {
            if (submitted) this.context.defer(failed);
            else failed.destroy();
        }
    }

    @Override public void close() { this.buffer.destroy(); }
    private record Upload(VulkanBuffer target, List<SurfaceRecords.Entry> entries) {}
}
