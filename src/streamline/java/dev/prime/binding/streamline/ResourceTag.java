package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::ResourceTag — {4C6A5AAD-B445-496C-87FF-1AF3845BE653}, kStructVersion1 */
public final class ResourceTag {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            ADDRESS.withName("resource"),
            JAVA_INT.withName("type"),
            JAVA_INT.withName("lifecycle"),
            structLayout(
                    JAVA_INT.withName("top"),
                    JAVA_INT.withName("left"),
                    JAVA_INT.withName("width"),
                    JAVA_INT.withName("height")).withName("extent"));

    private static final VarHandle RESOURCE = LAYOUT.varHandle(groupElement("resource"));
    private static final VarHandle TYPE = LAYOUT.varHandle(groupElement("type"));
    private static final VarHandle LIFECYCLE = LAYOUT.varHandle(groupElement("lifecycle"));
    private static final VarHandle EXTENT_TOP = LAYOUT.varHandle(groupElement("extent"), groupElement("top"));
    private static final VarHandle EXTENT_LEFT = LAYOUT.varHandle(groupElement("extent"), groupElement("left"));
    private static final VarHandle EXTENT_WIDTH = LAYOUT.varHandle(groupElement("extent"), groupElement("width"));
    private static final VarHandle EXTENT_HEIGHT = LAYOUT.varHandle(groupElement("extent"), groupElement("height"));

    private final MemorySegment segment;

    private ResourceTag(MemorySegment segment) {
        this.segment = segment;
    }

    /** Allocates an array of tags with headers initialized; pass the returned segment to slSetTagForFrame. */
    public static MemorySegment allocateArray(Arena arena, int count) {
        MemorySegment array = arena.allocate(LAYOUT, count);
        for (long i = 0; i < count; i++) {
            initHeader(array.asSlice(i * LAYOUT.byteSize(), LAYOUT.byteSize()));
        }
        return array;
    }

    /** Wraps the i-th element of a tag array. */
    public static ResourceTag wrap(MemorySegment array, long index) {
        return new ResourceTag(array.asSlice(index * LAYOUT.byteSize(), LAYOUT.byteSize()));
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** Pointer to the tagged {@link Resource} struct */
    public ResourceTag resource(MemorySegment value) {
        RESOURCE.set(this.segment, 0L, value);
        return this;
    }

    public ResourceTag type(BufferType value) {
        TYPE.set(this.segment, 0L, value.value);
        return this;
    }

    public ResourceTag lifecycle(ResourceLifecycle value) {
        LIFECYCLE.set(this.segment, 0L, value.value);
        return this;
    }

    public ResourceTag extent(int top, int left, int width, int height) {
        EXTENT_TOP.set(this.segment, 0L, top);
        EXTENT_LEFT.set(this.segment, 0L, left);
        EXTENT_WIDTH.set(this.segment, 0L, width);
        EXTENT_HEIGHT.set(this.segment, 0L, height);
        return this;
    }

    private static void initHeader(MemorySegment segment) {
        StructHeader.init(segment, 0x4c6a5aad, (short) 0xb445, (short) 0x496c, 0x53E65B84F31AFF87L, 1);
    }
}
