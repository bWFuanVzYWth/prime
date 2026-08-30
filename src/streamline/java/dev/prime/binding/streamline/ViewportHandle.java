package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::ViewportHandle — {171B6435-9B3C-4FC8-9994-FBE52569AAA4}, kStructVersion1 */
public final class ViewportHandle {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("value"),
            paddingLayout(4));

    private static final VarHandle VALUE = LAYOUT.varHandle(groupElement("value"));

    private final MemorySegment segment;

    private ViewportHandle(MemorySegment segment) {
        this.segment = segment;
    }

    public static ViewportHandle allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x171b6435, (short) 0x9b3c, (short) 0x4fc8, 0xA4AA6925E5FB9499L, 1);
        VALUE.set(segment, 0L, -1);
        return new ViewportHandle(segment);
    }

    public static ViewportHandle wrap(MemorySegment segment) {
        return new ViewportHandle(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public int value() {
        return (int) VALUE.get(this.segment, 0L);
    }

    public ViewportHandle value(int value) {
        VALUE.set(this.segment, 0L, value);
        return this;
    }
}
