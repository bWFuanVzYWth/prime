// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;

public final class VulkanBuffer implements Destroyable {
    private final long allocator;
    private final long allocation;
    private final long handle;
    private final long deviceAddress;
    private final long mappedAddress;
    private final long size;
    private boolean destroyed;

    VulkanBuffer(
            long allocator,
            long allocation,
            long handle,
            long deviceAddress,
            long mappedAddress,
            long size) {
        this.allocator = allocator;
        this.allocation = allocation;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.mappedAddress = mappedAddress;
        this.size = size;
    }

    public long handle() {
        return this.handle;
    }

    public long deviceAddress() {
        return this.deviceAddress;
    }

    public long mappedAddress() {
        if (this.destroyed) {
            throw new IllegalStateException("Buffer is destroyed");
        }
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("Buffer is not host visible");
        }
        return this.mappedAddress;
    }

    public long size() {
        return this.size;
    }

    public void put(long offset, java.nio.ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        long length = source.remaining();
        validateMappedRange(offset, length);
        copyRemaining(source, this.mappedAddress() + offset);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    /** memAddress already includes position; preserve the source view's position and limit. */
    static void copyRemaining(java.nio.ByteBuffer source, long destination) {
        if (!source.isDirect()) {
            throw new IllegalArgumentException("Native uploads require a direct buffer");
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), destination, source.remaining());
    }

    public void put(long offset, long sourceAddress, long length) {
        if (sourceAddress == 0L && length != 0L) {
            throw new IllegalArgumentException("Buffer source address is null");
        }
        validateMappedRange(offset, length);
        MemoryUtil.memCopy(sourceAddress, this.mappedAddress() + offset, length);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    public void flush(long offset, long length) {
        validateMappedRange(offset, length);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    public void invalidate(long offset, long length) {
        validateMappedRange(offset, length);
        Vma.vmaInvalidateAllocation(this.allocator, this.allocation, offset, length);
    }

    /** Reads a completed GPU write from a host-visible allocation. */
    public byte[] read(long offset, int length) {
        validateMappedRange(offset, length);
        this.invalidate(offset, length);
        byte[] result = new byte[length];
        MemoryUtil.memByteBuffer(this.mappedAddress() + offset, length).get(result);
        return result;
    }

    private void validateMappedRange(long offset, long length) {
        if (this.destroyed) {
            throw new IllegalStateException("Buffer is destroyed");
        }
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("Buffer is not host visible");
        }
        if (offset < 0L || length < 0L || length > this.size || offset > this.size - length) {
            throw new IndexOutOfBoundsException("Buffer range exceeds allocation");
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            Vma.vmaDestroyBuffer(this.allocator, this.handle, this.allocation);
        }
    }
}
