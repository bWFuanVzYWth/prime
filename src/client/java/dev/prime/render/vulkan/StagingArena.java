package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

public final class StagingArena implements AutoCloseable {
    public static final long PAGE_SIZE = 64L * 1024L * 1024L;
    private static final int MAX_PAGES = 3;

    private final VulkanContext context;
    private final List<Page> pages = new ArrayList<>();
    private int oversizedPageSerial;

    public StagingArena(VulkanContext context) {
        this.context = context;
    }

    public Batch tryBeginBatch() {
        return this.tryBeginBatch(PAGE_SIZE);
    }

    /** Acquires one reusable page large enough for an indivisible upload transaction. */
    public Batch tryBeginBatch(long minimumCapacity) {
        if (minimumCapacity < 0L) {
            throw new IllegalArgumentException("Staging capacity must not be negative");
        }
        long requiredCapacity = allocationCapacity(minimumCapacity);
        if (requiredCapacity > PAGE_SIZE) {
            Page page = this.createPage(
                    requiredCapacity,
                    "oversized " + this.oversizedPageSerial++);
            page.acquireNew();
            return new Batch(page, true);
        }
        for (Page page : this.pages) {
            if (page.tryAcquire(requiredCapacity)) {
                return new Batch(page, false);
            }
        }
        if (this.pages.size() < MAX_PAGES) {
            int index = this.pages.size();
            Page page = this.createPage(PAGE_SIZE, Integer.toString(index));
            page.acquireNew();
            this.pages.add(page);
            return new Batch(page, false);
        }
        return null;
    }

    private Page createPage(long capacity, String index) {
        return new Page(this.context.createBuffer(
                capacity,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                "Prime staging page " + index + " (" + capacity + " bytes)"), capacity);
    }

    static long allocationCapacity(long minimumCapacity) {
        long requested = Math.max(1L, minimumCapacity);
        if (requested > Long.MAX_VALUE - PAGE_SIZE + 1L) {
            throw new IllegalArgumentException("Staging capacity is too large to align safely");
        }
        return VulkanContext.alignUp(requested, PAGE_SIZE);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Page page : this.pages) {
            failure = ResourceCleanup.destroy(page.buffer, failure);
        }
        this.pages.clear();
        ResourceCleanup.throwIfFailed(failure);
    }

    public final class Batch implements AutoCloseable {
        private final Page page;
        private final boolean disposable;
        private long cursor;
        private boolean prepared;
        private boolean submitted;
        private boolean closed;

        private Batch(Page page, boolean disposable) {
            this.page = page;
            this.disposable = disposable;
        }

        public Slice write(ByteBuffer data, long alignment) {
            long size = data.remaining();
            Slice slice = this.allocate(size, alignment);
            VulkanBuffer.copyRemaining(data, this.page.buffer.mappedAddress() + slice.offset());
            return slice;
        }

        public Slice write(float[] data, long alignment) {
            return this.write(data, 0, data.length, alignment);
        }

        public Slice write(float[] data, int offset, int length, long alignment) {
            Objects.checkFromIndexSize(offset, length, data.length);
            Slice slice = this.allocate((long) length * Float.BYTES, alignment);
            MemoryUtil.memFloatBuffer(this.page.buffer.mappedAddress() + slice.offset(), length)
                    .put(data, offset, length);
            return slice;
        }

        public Slice write(int[] data, long alignment) {
            return this.write(data, 0, data.length, alignment);
        }

        public Slice write(int[] data, int offset, int length, long alignment) {
            Objects.checkFromIndexSize(offset, length, data.length);
            Slice slice = this.allocate((long) length * Integer.BYTES, alignment);
            MemoryUtil.memIntBuffer(this.page.buffer.mappedAddress() + slice.offset(), length)
                    .put(data, offset, length);
            return slice;
        }

        public Slice write(byte[] data, long alignment) {
            Slice slice = this.allocate(data.length, alignment);
            MemoryUtil.memByteBuffer(
                    this.page.buffer.mappedAddress() + slice.offset(), data.length).put(data);
            return slice;
        }

        /** Reserves mapped staging storage for producers that write directly into the page. */
        public Slice allocate(long size, long alignment) {
            if (this.prepared || this.submitted || this.closed) {
                throw new IllegalStateException(
                        "Cannot write a staging batch after submission preparation");
            }
            long endOffset = requiredEndOffset(this.cursor, size, alignment);
            if (endOffset > this.page.capacity) {
                throw new IllegalStateException(
                        "Prime staging batch exceeded its " + this.page.capacity + " byte capacity");
            }
            long offset = endOffset - size;
            this.cursor = endOffset;
            return new Slice(
                    this.page.buffer.handle(),
                    offset,
                    size,
                    this.page.buffer.mappedAddress() + offset);
        }

        public long capacity() {
            return this.page.capacity;
        }

        /** Flushes host writes without transferring ownership to a command submission. */
        public void prepareForSubmission() {
            if (this.prepared || this.submitted || this.closed) {
                throw new IllegalStateException(
                        "Staging batch is already prepared, submitted or closed");
            }
            this.page.buffer.flush(0L, this.cursor);
            this.prepared = true;
        }

        /** Transfers lifetime ownership only after the consuming command buffer is accepted. */
        public void submitted() {
            if (!this.prepared || this.submitted || this.closed) {
                throw new IllegalStateException(
                        "Staging batch must be prepared and open exactly once before submission");
            }
            this.submitted = true;
            if (this.disposable) {
                StagingArena.this.context.defer(this.page.buffer);
            } else {
                StagingArena.this.context.defer(this.page::release);
            }
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                if (!this.submitted) {
                    if (this.disposable) {
                        this.page.buffer.destroy();
                    } else {
                        this.page.release();
                    }
                }
            }
        }
    }

    public record Slice(long buffer, long offset, long size, long mappedAddress) {
    }

    /**
     * Computes the exact cursor after one allocation, including the alignment consumed before it.
     * Upload scheduling uses this same function so its byte budget cannot drift from the arena.
     */
    public static long requiredEndOffset(long cursor, long size, long alignment) {
        if (cursor < 0L || size < 0L) {
            throw new IllegalArgumentException("Staging offsets and sizes must not be negative");
        }
        return Math.addExact(VulkanContext.alignUp(cursor, alignment), size);
    }

    private static final class Page {
        private final VulkanBuffer buffer;
        private final long capacity;
        private boolean available = true;

        private Page(VulkanBuffer buffer, long capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
        }

        private synchronized boolean tryAcquire(long requiredCapacity) {
            if (!this.available || this.capacity < requiredCapacity) {
                return false;
            }
            this.available = false;
            return true;
        }

        private synchronized void acquireNew() {
            if (!this.available) {
                throw new IllegalStateException("New staging page was unexpectedly busy");
            }
            this.available = false;
        }

        private synchronized void release() {
            this.available = true;
        }
    }
}
