// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.terrain;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import java.util.ArrayList;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;

/** Render-thread-owned, completion-retired storage for per-frame dynamic geometry rebuilds. */
final class DynamicBufferPool implements Destroyable {
    private static final long CAPACITY_GRANULARITY = 64L * 1024L;
    private static final int RETAINED_FREE_SLOTS = 4;

    private final VulkanContext context;
    private final ArrayList<Slot> free = new ArrayList<>();
    private int nextSlot;
    private boolean destroyed;

    DynamicBufferPool(VulkanContext context) {
        this.context = context;
    }

    Lease acquire(long positionBytes, long primitiveBytes, long motionBytes) {
        requireSize(positionBytes, "position");
        requireSize(primitiveBytes, "primitive");
        requireSize(motionBytes, "motion");
        if (this.destroyed) {
            throw new IllegalStateException("Dynamic buffer pool is destroyed");
        }
        int best = -1;
        long bestBytes = Long.MAX_VALUE;
        for (int index = 0; index < this.free.size(); index++) {
            Slot candidate = this.free.get(index);
            if (candidate.fits(positionBytes, primitiveBytes, motionBytes)
                    && candidate.totalBytes() < bestBytes) {
                best = index;
                bestBytes = candidate.totalBytes();
            }
        }
        Slot slot = best >= 0
                ? this.free.remove(best)
                : this.createSlot(positionBytes, primitiveBytes, motionBytes);
        return new Lease(this, slot);
    }

    private Slot createSlot(long positionBytes, long primitiveBytes, long motionBytes) {
        int slotIndex = this.nextSlot++;
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        VulkanBuffer motion = null;
        try {
            positions = this.context.createBuffer(
                    capacity(positionBytes),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure
                                    .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    "Prime dynamic slot " + slotIndex + " positions");
            primitives = this.context.createBuffer(
                    capacity(primitiveBytes),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    "Prime dynamic slot " + slotIndex + " primitives");
            motion = this.context.createBuffer(
                    capacity(motionBytes),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    "Prime dynamic slot " + slotIndex + " previous positions");
            return new Slot(positions, primitives, motion);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(motion, exception);
            failure = ResourceCleanup.destroy(primitives, failure);
            failure = ResourceCleanup.destroy(positions, failure);
            throw failure;
        }
    }

    private void release(Slot slot) {
        if (this.destroyed) {
            slot.destroy();
            return;
        }
        this.free.add(slot);
        while (this.free.size() > RETAINED_FREE_SLOTS) {
            int smallest = 0;
            for (int index = 1; index < this.free.size(); index++) {
                if (this.free.get(index).totalBytes()
                        < this.free.get(smallest).totalBytes()) {
                    smallest = index;
                }
            }
            this.free.remove(smallest).destroy();
        }
    }

    static long capacity(long requiredBytes) {
        requireSize(requiredBytes, "dynamic buffer");
        return VulkanContext.alignUp(requiredBytes, CAPACITY_GRANULARITY);
    }

    private static void requireSize(long size, String label) {
        if (size <= 0L) {
            throw new IllegalArgumentException(label + " byte size must be positive");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        RuntimeException failure = null;
        for (int index = this.free.size() - 1; index >= 0; index--) {
            failure = ResourceCleanup.run(this.free.get(index)::destroy, failure);
        }
        this.free.clear();
        ResourceCleanup.throwIfFailed(failure);
    }

    static final class Lease {
        private final DynamicBufferPool owner;
        private Slot slot;

        private Lease(DynamicBufferPool owner, Slot slot) {
            this.owner = owner;
            this.slot = slot;
        }

        VulkanBuffer positions() {
            return this.requireOpen().positions;
        }

        VulkanBuffer primitives() {
            return this.requireOpen().primitives;
        }

        VulkanBuffer motion() {
            return this.requireOpen().motion;
        }

        void release() {
            Slot released = this.requireOpen();
            this.slot = null;
            this.owner.release(released);
        }

        private Slot requireOpen() {
            if (this.slot == null) {
                throw new IllegalStateException("Dynamic buffer lease is already released");
            }
            return this.slot;
        }
    }

    private record Slot(
            VulkanBuffer positions,
            VulkanBuffer primitives,
            VulkanBuffer motion) {
        boolean fits(long positionBytes, long primitiveBytes, long motionBytes) {
            return this.positions.size() >= positionBytes
                    && this.primitives.size() >= primitiveBytes
                    && this.motion.size() >= motionBytes;
        }

        long totalBytes() {
            return Math.addExact(
                    Math.addExact(this.positions.size(), this.primitives.size()),
                    this.motion.size());
        }

        void destroy() {
            RuntimeException failure = ResourceCleanup.destroy(this.motion, null);
            failure = ResourceCleanup.destroy(this.primitives, failure);
            failure = ResourceCleanup.destroy(this.positions, failure);
            ResourceCleanup.throwIfFailed(failure);
        }
    }
}
