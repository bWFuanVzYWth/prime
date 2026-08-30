package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::PCLState — {CFA32F9B-023C-420E-9056-6832B74F89B5}, kStructVersion1. Out-only struct. */
public final class PclState {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("statsWindowMessage"),
            paddingLayout(4));

    private static final VarHandle STATS_WINDOW_MESSAGE = LAYOUT.varHandle(groupElement("statsWindowMessage"));

    private final MemorySegment segment;

    private PclState(MemorySegment segment) {
        this.segment = segment;
    }

    public static PclState allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xcfa32f9b, (short) 0x023c, (short) 0x420e, 0xB5894FB732685690L, 1);
        return new PclState(segment);
    }

    public static PclState wrap(MemorySegment segment) {
        return new PclState(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** PCL Windows message id (if PclOptions.virtualKey is 0) */
    public int statsWindowMessage() {
        return (int) STATS_WINDOW_MESSAGE.get(this.segment, 0L);
    }
}
