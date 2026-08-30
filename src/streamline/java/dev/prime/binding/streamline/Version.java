package dev.prime.binding.streamline;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::Version */
public record Version(int major, int minor, int build) {
    static final StructLayout LAYOUT = structLayout(
            JAVA_INT.withName("major"),
            JAVA_INT.withName("minor"),
            JAVA_INT.withName("build"));

    static Version read(MemorySegment segment, long offset) {
        return new Version(
                segment.get(JAVA_INT, offset),
                segment.get(JAVA_INT, offset + 4),
                segment.get(JAVA_INT, offset + 8));
    }
}
