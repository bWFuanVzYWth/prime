package dev.primept.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Reusable native transport pages. No geometry, Minecraft interpretation or scheduling. */
public final class SourcePages implements AutoCloseable {
    public static final int MAGIC = 0x53434d50, VERSION = 2;
    private static final int PAGE_BYTES = 1 << 20;
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private final Arena arena = Arena.ofConfined();
    private final ArrayList<MemorySegment> pages = new ArrayList<>();
    private Arena tableArena;
    private MemorySegment table;
    private int page, offset;
    public void clear() {
        page = offset = 0;
    }
    public long bytes() {
        return (long)page * PAGE_BYTES + offset;
    }
    public long pageCount() {
        return page + (offset == 0 ? 0 : 1);
    }
    private MemorySegment current() {
        if (pages.size() <= page)
            pages.add(arena.allocate(PAGE_BYTES, 8));
        return pages.get(page);
    }
    public SourcePages i(int value) {
        current().set(I32, offset, value);
        advance(4);
        return this;
    }
    public SourcePages l(long value) {
        // All records are four-byte aligned; a long may straddle a page boundary.
        if (offset <= PAGE_BYTES - 8) {
            current().set(I64, offset, value);
            advance(8);
        } else {
            i((int)value);
            i((int)(value >>> 32));
        }
        return this;
    }
    public SourcePages f(float value) {
        return i(Float.floatToRawIntBits(value));
    }
    public SourcePages d(double value) {
        return l(Double.doubleToRawLongBits(value));
    }
    private void advance(int bytes) {
        offset += bytes;
        if (offset == PAGE_BYTES) {
            ++page;
            offset = 0;
        }
    }
    public SourcePages string(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        i(bytes.length);
        // Length-delimited strings are padded to preserve scalar/page alignment.
        for (int at = 0; at < bytes.length; at += 4) {
            int word = 0;
            for (int n = 0; n < Math.min(4, bytes.length - at); ++n)
                word |= (bytes[at + n] & 255) << (n * 8);
            i(word);
        }
        return this;
    }
    public SourcePages longs(long[] values) {
        var source = MemorySegment.ofArray(values);
        long at = 0;
        while (at < source.byteSize()) {
            int size = (int)Math.min(PAGE_BYTES - offset, source.byteSize() - at);
            MemorySegment.copy(source, at, current(), offset, size);
            at += size;
            advance(size);
        }
        return this;
    }
    public SourcePages header(int version, int kind, long epoch, long batch) {
        clear();
        return i(MAGIC).i(VERSION).i(version).i(kind).l(epoch).l(batch);
    }
    public MemorySegment table() {
        long count = pageCount(), length = Math.multiplyExact(count, 16);
        if (count == 0)
            throw new IllegalStateException("Empty source stream");
        if (table == null || table.byteSize() < length) {
            if (tableArena != null)
                tableArena.close();
            tableArena = Arena.ofConfined();
            table = tableArena.allocate(Math.max(1024, length * 2), 8);
        }
        for (int index = 0; index < count; ++index) {
            table.set(ValueLayout.ADDRESS, index * 16L, pages.get(index));
            table.set(I64, index * 16L + 8, index < page ? PAGE_BYTES : offset);
        }
        return table;
    }
    @Override
    public void close() {
        if (tableArena != null)
            tableArena.close();
        arena.close();
    }
}
