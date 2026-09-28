package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SourcePagesTest {
    private static byte[] read(SourcePages pages) {
        var table = pages.table();
        byte[] result = new byte[Math.toIntExact(pages.bytes())];
        long at = 0;
        for (long i = 0; i < pages.pageCount(); ++i) {
            long size = table.get(ValueLayout.JAVA_LONG, i * 16 + 8);
            var source = table.get(ValueLayout.ADDRESS, i * 16).reinterpret(size);
            MemorySegment.copy(source, 0, MemorySegment.ofArray(result), at, size);
            at += size;
        }
        assertEquals(result.length, at);
        return result;
    }

    @Test
    void splitLongBulkStorageAndResetKeepExactBytesAndBorrowedPageOwnership() {
        try (var pages = new SourcePages()) {
            int boundary = 1 << 20;
            for (int i = 0; i < boundary / 4 - 1; ++i)
                pages.i(i);
            pages.l(0x8877665544332211L);
            pages.longs(new long[] {0x0102030405060708L, -1L});
            pages.string("水abc");
            var bytes = ByteBuffer.wrap(read(pages)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, pages.pageCount());
            for (int i = 0; i < boundary / 4 - 1; ++i)
                assertEquals(i, bytes.getInt());
            assertEquals(0x8877665544332211L, bytes.getLong());
            assertEquals(0x0102030405060708L, bytes.getLong());
            assertEquals(-1L, bytes.getLong());
            assertEquals(6, bytes.getInt());
            assertEquals(0x61b4b0e6, bytes.getInt());
            assertEquals(0x00006362, bytes.getInt());
            assertFalse(bytes.hasRemaining());
            long address = pages.table().get(ValueLayout.ADDRESS, 0).address();
            pages.header(263, 2, 10, 99).i(0);
            bytes = ByteBuffer.wrap(read(pages)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, pages.pageCount());
            assertEquals(address, pages.table().get(ValueLayout.ADDRESS, 0).address());
            assertEquals(SourcePages.MAGIC, bytes.getInt());
            assertEquals(SourcePages.VERSION, bytes.getInt());
            assertEquals(263, bytes.getInt());
            assertEquals(2, bytes.getInt());
            assertEquals(10, bytes.getLong());
            assertEquals(99, bytes.getLong());
            assertEquals(0, bytes.getInt());
            assertFalse(bytes.hasRemaining());
        }
    }
}
