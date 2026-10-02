package dev.primept.capture;

import java.lang.foreign.Arena;
import dev.primept.NativeBridge;
import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Render-thread-owned typed batch storage, reused across frames. Spans carry source layout;
 * Rust performs primitive expansion. Adjacent identical spans share one C descriptor.
 * A sealed segment is borrowed only until the next begin or close; native submit must consume
 * it synchronously. Growth replaces the arena, so no writable view escapes this owner.
 */
public final class DynamicFrame implements AutoCloseable {
    private static final int HEADER_BYTES = (int)PrimeDynamicBatch.SIZE;
    private static final int SPAN_BYTES = (int)PrimeMeshSpan.SIZE;
    private static final int VERTEX_BYTES = 24;
    private static final int MAX_BYTES = 256 << 20;
    private final Thread owner = Thread.currentThread();
    private Arena arena, descriptorArena;
    private MemorySegment descriptors;
    private int descriptorCapacity;
    private int[] offsets = new int[16];
    private MemorySegment memory;
    private ByteBuffer bytes;
    private int spanCount, vertexCount, spanStart = -1, previousSpan = -1, spanDataStart;
    private int initialCount, openTopology, openStride, growths;
    private boolean started, sealed, closed;

    public DynamicFrame() {
        this(1 << 20);
    }

    /** Initial capacity is a reserve, not a limit; retained memory grows geometrically. */
    public DynamicFrame(int initialCapacity) {
        if (initialCapacity < HEADER_BYTES || initialCapacity > MAX_BYTES)
            throw new IllegalArgumentException("Invalid dynamic packet capacity");
        allocate(initialCapacity);
        ensureDescriptors(16);
    }

    public void begin(long epoch, long sequence, double x, double y, double z) {
        checkOwner();
        if (epoch <= 0 || sequence <= 0)
            throw new IllegalArgumentException("Invalid dynamic identity");
        bytes.clear();
        NativeBridge.header(PrimeDynamicBatch.header(descriptors), PrimeDynamicBatch.SIZE);
        PrimeDynamicBatch.epoch(descriptors, epoch);
        PrimeDynamicBatch.sequence(descriptors, sequence);
        PrimeDynamicBatch.origin(descriptors, 0, x);
        PrimeDynamicBatch.origin(descriptors, 1, y);
        PrimeDynamicBatch.origin(descriptors, 2, z);
        spanCount = vertexCount = 0;
        spanStart = previousSpan = -1;
        started = true;
        sealed = false;
    }

    /** Appends the selected range without changing the producer's position, limit or byte order. */
    public void append(int textureId, int flags, int topology, int count, int stride,
                       int positionOffset, int colorOffset, int uvOffset, ByteBuffer source) {
        checkWriting();
        if (count < 0 || (topology != 3 && topology != 4) || count % topology != 0)
            throw new IllegalArgumentException("Incomplete dynamic primitive");
        int size = Math.multiplyExact(count, stride);
        if (source.remaining() != size)
            throw new IllegalArgumentException("Unexpected source byte count");
        if (count == 0)
            return;
        openSpan(textureId, flags, topology, stride, positionOffset, colorOffset, uvOffset);
        ensure(size);
        bytes.put(source.duplicate());
        endSpan();
    }

    /** Billboard parameters are source appearance; native owns rotation and corner expansion. */
    public void beginParticles(int textureId, int flags) {
        checkWriting();
        openSpan(textureId, flags, 1, 52, 0, 48, 32);
    }
    public void particle(float x, float y, float z, float qx, float qy, float qz, float qw,
                         float scale, float u0, float u1, float v0, float v1, int argb, int light) {
        if (spanStart < 0 || openTopology != 1)
            throw new IllegalStateException("No particle source span is open");
        ensure(52);
        bytes.putFloat(x)
                .putFloat(y)
                .putFloat(z)
                .putFloat(qx)
                .putFloat(qy)
                .putFloat(qz)
                .putFloat(qw)
                .putFloat(scale)
                .putFloat(u0)
                .putFloat(u1)
                .putFloat(v0)
                .putFloat(v1)
                .put((byte)(argb >>> 16))
                .put((byte)(argb >>> 8))
                .put((byte)argb)
                .put((byte)(argb >>> 24));
    }

    public void beginSpan(int textureId, int flags, int topology) {
        checkWriting();
        openSpan(textureId, flags, topology, VERTEX_BYTES, 0, 12, 16);
    }

    /** Writes source encoded color; lighting and quad-to-triangle expansion belong elsewhere. */
    public void vertex(float x, float y, float z, int argb, float u, float v) {
        if (spanStart < 0)
            throw new IllegalStateException("No dynamic span is open");
        ensure(VERTEX_BYTES);
        bytes.putFloat(x)
                .putFloat(y)
                .putFloat(z)
                .put((byte)(argb >>> 16))
                .put((byte)(argb >>> 8))
                .put((byte)argb)
                .put((byte)(argb >>> 24))
                .putFloat(u)
                .putFloat(v);
    }

    public void endSpan() {
        checkWriting();
        if (spanStart < 0)
            throw new IllegalStateException("No dynamic span is open");
        int count = (bytes.position() - spanDataStart) / openStride;
        if (count % openTopology != 0)
            throw new IllegalStateException("Incomplete dynamic primitive");
        if (count == 0 && initialCount == 0) {
            --spanCount;
        } else {
            PrimeMeshSpan.vertex_count(descriptor(spanStart), Math.addExact(initialCount, count));
            vertexCount = Math.addExact(vertexCount,
                                        Math.multiplyExact(count, openTopology == 1 ? 4 : 1));
            previousSpan = spanStart;
        }
        spanStart = -1;
    }

    public MemorySegment seal() {
        checkWriting();
        if (spanStart >= 0)
            throw new IllegalStateException("Dynamic span is still open");
        for (int i = 0; i < spanCount; i++) {
            var span = descriptor(i);
            var payload = PrimeMeshSpan.vertices(span);
            long length = (long)PrimeMeshSpan.vertex_count(span) * PrimeMeshSpan.stride(span);
            PrimeByteSpan.data(payload, memory.asSlice(offsets[i], length));
            PrimeByteSpan.count(payload, length);
        }
        PrimeDynamicBatch.spans(descriptors,
                                descriptors.asSlice(HEADER_BYTES, (long)spanCount * SPAN_BYTES));
        PrimeDynamicBatch.count(descriptors, spanCount);
        sealed = true;
        return descriptors.asSlice(0, HEADER_BYTES).asReadOnly();
    }

    public int spanCount() {
        return spanCount;
    }
    public int vertexCount() {
        return vertexCount;
    }
    public int byteSize() {
        return Math.addExact(bytes.position(), HEADER_BYTES + spanCount * SPAN_BYTES);
    }
    public int capacity() {
        return Math.addExact(bytes.capacity(), Math.toIntExact(descriptors.byteSize()));
    }
    public int growthCount() {
        return growths;
    }

    private void openSpan(int textureId, int flags, int topology, int stride, int positionOffset,
                          int colorOffset, int uvOffset) {
        if (spanStart >= 0)
            throw new IllegalStateException("Dynamic span is already open");
        if (flags < 0 || flags > 2 || (topology != 1 && topology != 3 && topology != 4) ||
            (topology == 1 &&
             (stride != 52 || positionOffset != 0 || colorOffset != 48 || uvOffset != 32)) ||
            stride < 24 || stride > 256 || positionOffset < 0 || positionOffset > stride - 12 ||
            colorOffset < 0 || colorOffset > stride - 4 || uvOffset < 0 || uvOffset > stride - 8)
            throw new IllegalArgumentException("Unsupported source layout or material");
        var previous = previousSpan >= 0 ? descriptor(previousSpan) : null;
        if (previous != null && PrimeMeshSpan.texture_id(previous) == textureId &&
            PrimeMeshSpan.flags(previous) == flags &&
            PrimeMeshSpan.topology(previous) == topology &&
            PrimeMeshSpan.stride(previous) == stride &&
            PrimeMeshSpan.position_offset(previous) == positionOffset &&
            PrimeMeshSpan.color_offset(previous) == colorOffset &&
            PrimeMeshSpan.uv_offset(previous) == uvOffset) {
            spanStart = previousSpan;
            initialCount = PrimeMeshSpan.vertex_count(previous);
        } else {
            ensureDescriptors(spanCount + 1);
            spanStart = spanCount++;
            offsets[spanStart] = bytes.position();
            var span = descriptor(spanStart);
            PrimeMeshSpan.texture_id(span, textureId);
            PrimeMeshSpan.flags(span, flags);
            PrimeMeshSpan.topology(span, topology);
            PrimeMeshSpan.vertex_count(span, 0);
            PrimeMeshSpan.stride(span, stride);
            PrimeMeshSpan.position_offset(span, positionOffset);
            PrimeMeshSpan.color_offset(span, colorOffset);
            PrimeMeshSpan.uv_offset(span, uvOffset);
            initialCount = 0;
        }
        spanDataStart = bytes.position();
        openTopology = topology;
        openStride = stride;
    }

    private MemorySegment descriptor(int index) {
        return descriptors.asSlice(HEADER_BYTES + (long)index * SPAN_BYTES, SPAN_BYTES);
    }
    private void ensureDescriptors(int count) {
        if (count <= descriptorCapacity)
            return;
        int capacity = Math.max(count, Math.max(16, descriptorCapacity * 2));
        long size = HEADER_BYTES + (long)capacity * SPAN_BYTES;
        if (size > MAX_BYTES)
            throw new IllegalStateException("Dynamic descriptor capacity exceeds 256 MiB");
        var replacement = Arena.ofConfined();
        try {
            var next = replacement.allocate(size, 8);
            if (descriptors != null)
                next.asSlice(0, HEADER_BYTES + (long)spanCount * SPAN_BYTES)
                        .copyFrom(descriptors.asSlice(0,
                                                      HEADER_BYTES + (long)spanCount * SPAN_BYTES));
            if (descriptorArena != null) {
                descriptorArena.close();
                ++growths;
            }
            descriptorArena = replacement;
            descriptors = next;
            descriptorCapacity = capacity;
            offsets = java.util.Arrays.copyOf(offsets, capacity);
        } catch (Throwable failure) {
            replacement.close();
            throw failure;
        }
    }

    private void ensure(int extra) {
        if (extra < 0 ||
            bytes.position() > MAX_BYTES - extra - HEADER_BYTES - spanCount * SPAN_BYTES)
            throw new IllegalStateException("Dynamic packet exceeds 256 MiB");
        if (bytes.remaining() >= extra)
            return;
        int required = bytes.position() + extra;
        allocate((int)Math.min(MAX_BYTES, Math.max(required, (long)bytes.capacity() * 2)));
        ++growths;
    }

    private void allocate(int capacity) {
        Arena replacement = Arena.ofConfined();
        try {
            MemorySegment nextMemory = replacement.allocate(capacity, 8);
            ByteBuffer nextBytes = nextMemory.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            if (bytes != null)
                nextBytes.put(bytes.duplicate().flip());
            if (arena != null)
                arena.close();
            arena = replacement;
            memory = nextMemory;
            bytes = nextBytes;
        } catch (Throwable failure) {
            replacement.close();
            throw failure;
        }
    }

    private void checkWriting() {
        checkOwner();
        if (!started || sealed)
            throw new IllegalStateException("Dynamic frame is not writable");
    }

    private void checkOwner() {
        if (owner != Thread.currentThread())
            throw new IllegalStateException("Dynamic frame called off its owner thread");
        if (closed)
            throw new IllegalStateException("Dynamic frame is closed");
    }

    @Override
    public void close() {
        if (closed)
            return;
        checkOwner();
        arena.close();
        descriptorArena.close();
        closed = true;
    }
}
