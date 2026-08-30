package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::AdapterInfo — {0677315F-A746-4492-9F42-CB6142C9C3D4}, kStructVersion1 */
public final class AdapterInfo {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            ADDRESS.withName("deviceLUID"),
            JAVA_INT.withName("deviceLUIDSizeInBytes"),
            paddingLayout(4),
            ADDRESS.withName("vkPhysicalDevice"));

    private static final VarHandle DEVICE_LUID = LAYOUT.varHandle(groupElement("deviceLUID"));
    private static final VarHandle DEVICE_LUID_SIZE_IN_BYTES = LAYOUT.varHandle(groupElement("deviceLUIDSizeInBytes"));
    private static final VarHandle VK_PHYSICAL_DEVICE = LAYOUT.varHandle(groupElement("vkPhysicalDevice"));

    private final MemorySegment segment;

    private AdapterInfo(MemorySegment segment) {
        this.segment = segment;
    }

    public static AdapterInfo allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x0677315f, (short) 0xa746, (short) 0x4492, 0xD4C3C94261CB429FL, 1);
        return new AdapterInfo(segment);
    }

    public static AdapterInfo wrap(MemorySegment segment) {
        return new AdapterInfo(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** uint8_t* — caller-managed LUID bytes */
    public MemorySegment deviceLUID() {
        return (MemorySegment) DEVICE_LUID.get(this.segment, 0L);
    }

    public AdapterInfo deviceLUID(MemorySegment value) {
        DEVICE_LUID.set(this.segment, 0L, value);
        return this;
    }

    public int deviceLUIDSizeInBytes() {
        return (int) DEVICE_LUID_SIZE_IN_BYTES.get(this.segment, 0L);
    }

    public AdapterInfo deviceLUIDSizeInBytes(int value) {
        DEVICE_LUID_SIZE_IN_BYTES.set(this.segment, 0L, value);
        return this;
    }

    /** VkPhysicalDevice handle value; if set the LUID is ignored */
    public MemorySegment vkPhysicalDevice() {
        return (MemorySegment) VK_PHYSICAL_DEVICE.get(this.segment, 0L);
    }

    public AdapterInfo vkPhysicalDevice(MemorySegment value) {
        VK_PHYSICAL_DEVICE.set(this.segment, 0L, value);
        return this;
    }
}
