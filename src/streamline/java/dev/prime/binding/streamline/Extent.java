package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::Extent — plain struct, no BaseStructure header */
public final class Extent {
    public static final StructLayout LAYOUT = structLayout(
            JAVA_INT.withName("top"),
            JAVA_INT.withName("left"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"));

    private static final VarHandle TOP = LAYOUT.varHandle(groupElement("top"));
    private static final VarHandle LEFT = LAYOUT.varHandle(groupElement("left"));
    private static final VarHandle WIDTH = LAYOUT.varHandle(groupElement("width"));
    private static final VarHandle HEIGHT = LAYOUT.varHandle(groupElement("height"));

    private final MemorySegment segment;

    private Extent(MemorySegment segment) {
        this.segment = segment;
    }

    public static Extent allocate(Arena arena) {
        return new Extent(arena.allocate(LAYOUT));
    }

    public static Extent wrap(MemorySegment segment) {
        return new Extent(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public int top() {
        return (int) TOP.get(this.segment, 0L);
    }

    public Extent top(int value) {
        TOP.set(this.segment, 0L, value);
        return this;
    }

    public int left() {
        return (int) LEFT.get(this.segment, 0L);
    }

    public Extent left(int value) {
        LEFT.set(this.segment, 0L, value);
        return this;
    }

    public int width() {
        return (int) WIDTH.get(this.segment, 0L);
    }

    public Extent width(int value) {
        WIDTH.set(this.segment, 0L, value);
        return this;
    }

    public int height() {
        return (int) HEIGHT.get(this.segment, 0L);
    }

    public Extent height(int value) {
        HEIGHT.set(this.segment, 0L, value);
        return this;
    }
}
