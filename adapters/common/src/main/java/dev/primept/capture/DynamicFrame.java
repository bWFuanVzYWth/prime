package dev.primept.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Render-thread-owned op=6 packet storage, reused across frames. Spans carry source layout;
 * Rust performs primitive expansion. Adjacent identical spans share one wire descriptor.
 * A sealed segment is borrowed only until the next begin or close; native submit must consume
 * it synchronously. Growth replaces the arena, so no writable view escapes this owner.
 */
public final class DynamicFrame implements AutoCloseable {
    private static final int HEADER_BYTES = 64;
    private static final int SPAN_BYTES = 32;
    private static final int MAX_BYTES = 256 << 20;
    private final Thread owner = Thread.currentThread();
    private Arena arena;
    private MemorySegment memory;
    private ByteBuffer bytes;
    private int spanCount, vertexCount, spanStart = -1, previousSpan = -1, spanDataStart;
    private int initialCount, openTopology, openStride, growths;
    private boolean started, sealed, closed;

    public DynamicFrame() { this(1 << 20); }

    /** Initial capacity is a reserve, not a limit; retained memory grows geometrically. */
    public DynamicFrame(int initialCapacity) {
        if (initialCapacity < HEADER_BYTES || initialCapacity > MAX_BYTES)
            throw new IllegalArgumentException("Invalid dynamic packet capacity");
        allocate(initialCapacity);
    }

    public void begin(long epoch, long sequence, double x, double y, double z) {
        checkOwner();
        if (epoch <= 0 || sequence <= 0) throw new IllegalArgumentException("Invalid dynamic identity");
        bytes.clear();
        bytes.putInt(Packets.MAGIC).putInt(1).putInt(6).putInt(0).putLong(epoch)
                .putLong(sequence).putDouble(x).putDouble(y).putDouble(z).putInt(0).putInt(0);
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
        if (source.remaining() != size) throw new IllegalArgumentException("Unexpected source byte count");
        if (count == 0) return;
        openSpan(textureId, flags, topology, stride, positionOffset, colorOffset, uvOffset);
        ensure(size);
        bytes.put(source.duplicate());
        endSpan();
    }

    public void beginSpan(int textureId, int flags, int topology) {
        checkWriting();
        openSpan(textureId, flags, topology, SourceQuads.STRIDE, 0, 12, 16);
    }

    /** Writes source encoded color; lighting and quad-to-triangle expansion belong elsewhere. */
    public void vertex(float x, float y, float z, int argb, float u, float v) {
        if (spanStart < 0) throw new IllegalStateException("No dynamic span is open");
        ensure(SourceQuads.STRIDE);
        bytes.putFloat(x).putFloat(y).putFloat(z)
                .put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24))
                .putFloat(u).putFloat(v);
    }

    public void endSpan() {
        checkWriting();
        if (spanStart < 0) throw new IllegalStateException("No dynamic span is open");
        int count = (bytes.position() - spanDataStart) / openStride;
        if (count % openTopology != 0) throw new IllegalStateException("Incomplete dynamic primitive");
        if (count == 0 && initialCount == 0) {
            bytes.position(spanStart);
            --spanCount;
        } else {
            bytes.putInt(spanStart + 12, Math.addExact(initialCount, count));
            vertexCount = Math.addExact(vertexCount, count);
            previousSpan = spanStart;
        }
        spanStart = -1;
    }

    public MemorySegment seal() {
        checkWriting();
        if (spanStart >= 0) throw new IllegalStateException("Dynamic span is still open");
        bytes.putInt(56, spanCount);
        sealed = true;
        return memory.asSlice(0, bytes.position()).asReadOnly();
    }

    public int spanCount() { return spanCount; }
    public int vertexCount() { return vertexCount; }
    public int byteSize() { return bytes.position(); }
    public int capacity() { return bytes.capacity(); }
    public int growthCount() { return growths; }

    private void openSpan(int textureId, int flags, int topology, int stride,
            int positionOffset, int colorOffset, int uvOffset) {
        if (spanStart >= 0) throw new IllegalStateException("Dynamic span is already open");
        if (flags < 0 || flags > 2 || (topology != 3 && topology != 4)
                || stride < 24 || stride > 256 || positionOffset < 0 || positionOffset > stride - 12
                || colorOffset < 0 || colorOffset > stride - 4 || uvOffset < 0 || uvOffset > stride - 8)
            throw new IllegalArgumentException("Unsupported source layout or material");
        if (previousSpan >= 0 && bytes.getInt(previousSpan) == textureId
                && bytes.getInt(previousSpan + 4) == flags && bytes.getInt(previousSpan + 8) == topology
                && bytes.getInt(previousSpan + 16) == stride && bytes.getInt(previousSpan + 20) == positionOffset
                && bytes.getInt(previousSpan + 24) == colorOffset && bytes.getInt(previousSpan + 28) == uvOffset) {
            spanStart = previousSpan;
            initialCount = bytes.getInt(spanStart + 12);
        } else {
            ensure(SPAN_BYTES);
            spanStart = bytes.position();
            bytes.putInt(textureId).putInt(flags).putInt(topology).putInt(0)
                    .putInt(stride).putInt(positionOffset).putInt(colorOffset).putInt(uvOffset);
            initialCount = 0;
            ++spanCount;
        }
        spanDataStart = bytes.position();
        openTopology = topology;
        openStride = stride;
    }

    private void ensure(int extra) {
        if (extra < 0 || bytes.position() > MAX_BYTES - extra)
            throw new IllegalStateException("Dynamic packet exceeds 256 MiB");
        if (bytes.remaining() >= extra) return;
        int required = bytes.position() + extra;
        allocate((int) Math.min(MAX_BYTES, Math.max(required, (long) bytes.capacity() * 2)));
        ++growths;
    }

    private void allocate(int capacity) {
        Arena replacement = Arena.ofConfined();
        try {
            MemorySegment nextMemory = replacement.allocate(capacity, 8);
            ByteBuffer nextBytes = nextMemory.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            if (bytes != null) nextBytes.put(bytes.duplicate().flip());
            if (arena != null) arena.close();
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
        if (!started || sealed) throw new IllegalStateException("Dynamic frame is not writable");
    }

    private void checkOwner() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Dynamic frame called off its owner thread");
        if (closed) throw new IllegalStateException("Dynamic frame is closed");
    }

    @Override public void close() {
        if (closed) return;
        checkOwner();
        arena.close();
        closed = true;
    }
}
