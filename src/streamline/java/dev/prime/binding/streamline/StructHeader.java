package dev.prime.binding.streamline;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Handles the 32-byte sl::BaseStructure header prepended to every SL_STRUCT_BEGIN struct. */
final class StructHeader {
    private StructHeader() {
    }

    static StructLayout structWith(MemoryLayout... fields) {
        MemoryLayout[] all = new MemoryLayout[fields.length + 3];
        all[0] = ADDRESS.withName("next");
        all[1] = sequenceLayout(16, JAVA_BYTE).withName("structType");
        all[2] = JAVA_LONG.withName("structVersion");
        System.arraycopy(fields, 0, all, 3, fields.length);
        return structLayout(all);
    }

    /** Writes the StructType GUID (little-endian data1/data2/data3 + raw data4 bytes) and struct version. */
    static void init(MemorySegment segment, int data1, short data2, short data3, long data4, long version) {
        segment.set(JAVA_INT, 8, data1);
        segment.set(JAVA_SHORT, 12, data2);
        segment.set(JAVA_SHORT, 14, data3);
        segment.set(JAVA_LONG, 16, data4);
        segment.set(JAVA_LONG, 24, version);
    }
}
