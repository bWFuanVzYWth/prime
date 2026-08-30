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

    private static final VarHandle TYPE = LAYOUT.varHandle(groupElement("type"));
    private static final VarHandle NATIVE = LAYOUT.varHandle(groupElement("native"));
    private static final VarHandle MEMORY = LAYOUT.varHandle(groupElement("memory"));
    private static final VarHandle VIEW = LAYOUT.varHandle(groupElement("view"));
    private static final VarHandle STATE = LAYOUT.varHandle(groupElement("state"));
    private static final VarHandle WIDTH = LAYOUT.varHandle(groupElement("width"));
    private static final VarHandle HEIGHT = LAYOUT.varHandle(groupElement("height"));
    private static final VarHandle NATIVE_FORMAT = LAYOUT.varHandle(groupElement("nativeFormat"));
    private static final VarHandle MIP_LEVELS = LAYOUT.varHandle(groupElement("mipLevels"));
    private static final VarHandle ARRAY_LAYERS = LAYOUT.varHandle(groupElement("arrayLayers"));
    private static final VarHandle GPU_VIRTUAL_ADDRESS = LAYOUT.varHandle(groupElement("gpuVirtualAddress"));
    private static final VarHandle FLAGS = LAYOUT.varHandle(groupElement("flags"));
    private static final VarHandle USAGE = LAYOUT.varHandle(groupElement("usage"));

    private final MemorySegment segment;

    private Resource(MemorySegment segment) {
        this.segment = segment;
    }

    public static Resource allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x3a9d70cf, (short) 0x2418, (short) 0x4b72, 0x61721C72F8139183L, 1);
        STATE.set(segment, 0L, -1);
        return new Resource(segment);
    }

    public static Resource wrap(MemorySegment segment) {
        return new Resource(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public ResourceType type() {
        return ResourceType.fromValue((byte) TYPE.get(this.segment, 0L));
    }

    public Resource type(ResourceType value) {
        TYPE.set(this.segment, 0L, value.value);
        return this;
    }

    /** VkImage / VkBuffer handle value */
    public MemorySegment nativeHandle() {
        return (MemorySegment) NATIVE.get(this.segment, 0L);
    }

    public Resource nativeHandle(MemorySegment value) {
        NATIVE.set(this.segment, 0L, value);
        return this;
    }

    /** VkDeviceMemory handle value or null */
    public MemorySegment memory() {
        return (MemorySegment) MEMORY.get(this.segment, 0L);
    }

    public Resource memory(MemorySegment value) {
        MEMORY.set(this.segment, 0L, value);
        return this;
    }

    /** VkImageView / VkBufferView handle value or null */
    public MemorySegment view() {
        return (MemorySegment) VIEW.get(this.segment, 0L);
    }

    public Resource view(MemorySegment value) {
        VIEW.set(this.segment, 0L, value);
        return this;
    }

    /** VkImageLayout when tagged resources are actually used */
    public int state() {
        return (int) STATE.get(this.segment, 0L);
    }

    public Resource state(int value) {
        STATE.set(this.segment, 0L, value);
        return this;
    }

    public int width() {
        return (int) WIDTH.get(this.segment, 0L);
    }

    public Resource width(int value) {
        WIDTH.set(this.segment, 0L, value);
        return this;
    }

    public int height() {
        return (int) HEIGHT.get(this.segment, 0L);
    }

    public Resource height(int value) {
        HEIGHT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat value */
    public int nativeFormat() {
        return (int) NATIVE_FORMAT.get(this.segment, 0L);
    }

    public Resource nativeFormat(int value) {
        NATIVE_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    public int mipLevels() {
        return (int) MIP_LEVELS.get(this.segment, 0L);
    }

    public Resource mipLevels(int value) {
        MIP_LEVELS.set(this.segment, 0L, value);
        return this;
    }

    public int arrayLayers() {
        return (int) ARRAY_LAYERS.get(this.segment, 0L);
    }

    public Resource arrayLayers(int value) {
        ARRAY_LAYERS.set(this.segment, 0L, value);
        return this;
    }

    public long gpuVirtualAddress() {
        return (long) GPU_VIRTUAL_ADDRESS.get(this.segment, 0L);
    }

    public Resource gpuVirtualAddress(long value) {
        GPU_VIRTUAL_ADDRESS.set(this.segment, 0L, value);
        return this;
    }

    /** VkImageCreateFlags */
    public int flags() {
        return (int) FLAGS.get(this.segment, 0L);
    }

    public Resource flags(int value) {
        FLAGS.set(this.segment, 0L, value);
        return this;
    }

    /** VkImageUsageFlags */
    public int usage() {
        return (int) USAGE.get(this.segment, 0L);
    }

    public Resource usage(int value) {
        USAGE.set(this.segment, 0L, value);
        return this;
    }
}
