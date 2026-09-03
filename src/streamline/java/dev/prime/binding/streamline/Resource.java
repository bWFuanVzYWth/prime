package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** sl::Resource — {3A9D70CF-2418-4B72-8391-13F8721C7261}, kStructVersion1 */
public final class Resource {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_BYTE.withName("type"),
            paddingLayout(7),
            ADDRESS.withName("native"),
            ADDRESS.withName("memory"),
            ADDRESS.withName("view"),
            JAVA_INT.withName("state"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("nativeFormat"),
            JAVA_INT.withName("mipLevels"),
            JAVA_INT.withName("arrayLayers"),
            JAVA_LONG.withName("gpuVirtualAddress"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("usage"),
            JAVA_INT.withName("reserved"),
            paddingLayout(4));

    private static final VarHandle NATIVE = LAYOUT.varHandle(groupElement("native"));
    private static final VarHandle VIEW = LAYOUT.varHandle(groupElement("view"));
    private static final VarHandle STATE = LAYOUT.varHandle(groupElement("state"));
    private static final VarHandle WIDTH = LAYOUT.varHandle(groupElement("width"));
    private static final VarHandle HEIGHT = LAYOUT.varHandle(groupElement("height"));
    private static final VarHandle NATIVE_FORMAT = LAYOUT.varHandle(groupElement("nativeFormat"));
    private static final VarHandle MIP_LEVELS = LAYOUT.varHandle(groupElement("mipLevels"));
    private static final VarHandle ARRAY_LAYERS = LAYOUT.varHandle(groupElement("arrayLayers"));
    private static final VarHandle USAGE = LAYOUT.varHandle(groupElement("usage"));

    private final MemorySegment segment;

    private Resource(MemorySegment segment) {
        this.segment = segment;
    }

    public static Resource texture2D(
            Arena arena,
            MemorySegment nativeHandle,
            MemorySegment view,
            int state,
            int width,
            int height,
            int nativeFormat,
            int mipLevels,
            int usage) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x3a9d70cf, (short) 0x2418, (short) 0x4b72, 0x61721C72F8139183L, 1);
        NATIVE.set(segment, 0L, nativeHandle);
        VIEW.set(segment, 0L, view);
        STATE.set(segment, 0L, state);
        WIDTH.set(segment, 0L, width);
        HEIGHT.set(segment, 0L, height);
        NATIVE_FORMAT.set(segment, 0L, nativeFormat);
        MIP_LEVELS.set(segment, 0L, mipLevels);
        ARRAY_LAYERS.set(segment, 0L, 1);
        USAGE.set(segment, 0L, usage);
        return new Resource(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

}
