package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::VulkanInfo — {0EED6FD5-82CD-43A9-BDB5-47A5BA2F45D6}, kStructVersion3 */
public final class VulkanInfo {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            ADDRESS.withName("device"),
            ADDRESS.withName("instance"),
            ADDRESS.withName("physicalDevice"),
            JAVA_INT.withName("computeQueueIndex"),
            JAVA_INT.withName("computeQueueFamily"),
            JAVA_INT.withName("graphicsQueueIndex"),
            JAVA_INT.withName("graphicsQueueFamily"),
            JAVA_INT.withName("opticalFlowQueueIndex"),
            JAVA_INT.withName("opticalFlowQueueFamily"),
            JAVA_BOOLEAN.withName("useNativeOpticalFlowMode"),
            paddingLayout(3),
            JAVA_INT.withName("computeQueueCreateFlags"),
            JAVA_INT.withName("graphicsQueueCreateFlags"),
            JAVA_INT.withName("opticalFlowQueueCreateFlags"));

    private static final VarHandle DEVICE = LAYOUT.varHandle(groupElement("device"));
    private static final VarHandle INSTANCE = LAYOUT.varHandle(groupElement("instance"));
    private static final VarHandle PHYSICAL_DEVICE = LAYOUT.varHandle(groupElement("physicalDevice"));
    private static final VarHandle COMPUTE_QUEUE_INDEX = LAYOUT.varHandle(groupElement("computeQueueIndex"));
    private static final VarHandle COMPUTE_QUEUE_FAMILY = LAYOUT.varHandle(groupElement("computeQueueFamily"));
    private static final VarHandle GRAPHICS_QUEUE_INDEX = LAYOUT.varHandle(groupElement("graphicsQueueIndex"));
    private static final VarHandle GRAPHICS_QUEUE_FAMILY = LAYOUT.varHandle(groupElement("graphicsQueueFamily"));
    private static final VarHandle OPTICAL_FLOW_QUEUE_INDEX = LAYOUT.varHandle(groupElement("opticalFlowQueueIndex"));
    private static final VarHandle OPTICAL_FLOW_QUEUE_FAMILY = LAYOUT.varHandle(groupElement("opticalFlowQueueFamily"));
    private static final VarHandle USE_NATIVE_OPTICAL_FLOW_MODE = LAYOUT.varHandle(groupElement("useNativeOpticalFlowMode"));
    private static final VarHandle COMPUTE_QUEUE_CREATE_FLAGS = LAYOUT.varHandle(groupElement("computeQueueCreateFlags"));
    private static final VarHandle GRAPHICS_QUEUE_CREATE_FLAGS = LAYOUT.varHandle(groupElement("graphicsQueueCreateFlags"));
    private static final VarHandle OPTICAL_FLOW_QUEUE_CREATE_FLAGS = LAYOUT.varHandle(groupElement("opticalFlowQueueCreateFlags"));

    private final MemorySegment segment;

    private VulkanInfo(MemorySegment segment) {
        this.segment = segment;
    }

    public static VulkanInfo allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x0eed6fd5, (short) 0x82cd, (short) 0x43a9, 0xD6452FBAA547B5BDL, 3);
        return new VulkanInfo(segment);
    }

    public static VulkanInfo wrap(MemorySegment segment) {
        return new VulkanInfo(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** VkDevice handle value */
    public MemorySegment device() {
        return (MemorySegment) DEVICE.get(this.segment, 0L);
    }

    public VulkanInfo device(MemorySegment value) {
        DEVICE.set(this.segment, 0L, value);
        return this;
    }

    /** VkInstance handle value */
    public MemorySegment instance() {
        return (MemorySegment) INSTANCE.get(this.segment, 0L);
    }

    public VulkanInfo instance(MemorySegment value) {
        INSTANCE.set(this.segment, 0L, value);
        return this;
    }

    /** VkPhysicalDevice handle value */
    public MemorySegment physicalDevice() {
        return (MemorySegment) PHYSICAL_DEVICE.get(this.segment, 0L);
    }

    public VulkanInfo physicalDevice(MemorySegment value) {
        PHYSICAL_DEVICE.set(this.segment, 0L, value);
        return this;
    }

    public int computeQueueIndex() {
        return (int) COMPUTE_QUEUE_INDEX.get(this.segment, 0L);
    }

    public VulkanInfo computeQueueIndex(int value) {
        COMPUTE_QUEUE_INDEX.set(this.segment, 0L, value);
        return this;
    }

    public int computeQueueFamily() {
        return (int) COMPUTE_QUEUE_FAMILY.get(this.segment, 0L);
    }

    public VulkanInfo computeQueueFamily(int value) {
        COMPUTE_QUEUE_FAMILY.set(this.segment, 0L, value);
        return this;
    }

    public int graphicsQueueIndex() {
        return (int) GRAPHICS_QUEUE_INDEX.get(this.segment, 0L);
    }

    public VulkanInfo graphicsQueueIndex(int value) {
        GRAPHICS_QUEUE_INDEX.set(this.segment, 0L, value);
        return this;
    }

    public int graphicsQueueFamily() {
        return (int) GRAPHICS_QUEUE_FAMILY.get(this.segment, 0L);
    }

    public VulkanInfo graphicsQueueFamily(int value) {
        GRAPHICS_QUEUE_FAMILY.set(this.segment, 0L, value);
        return this;
    }

    public int opticalFlowQueueIndex() {
        return (int) OPTICAL_FLOW_QUEUE_INDEX.get(this.segment, 0L);
    }

    public VulkanInfo opticalFlowQueueIndex(int value) {
        OPTICAL_FLOW_QUEUE_INDEX.set(this.segment, 0L, value);
        return this;
    }

    public int opticalFlowQueueFamily() {
        return (int) OPTICAL_FLOW_QUEUE_FAMILY.get(this.segment, 0L);
    }

    public VulkanInfo opticalFlowQueueFamily(int value) {
        OPTICAL_FLOW_QUEUE_FAMILY.set(this.segment, 0L, value);
        return this;
    }

    public boolean useNativeOpticalFlowMode() {
        return (boolean) USE_NATIVE_OPTICAL_FLOW_MODE.get(this.segment, 0L);
    }

    public VulkanInfo useNativeOpticalFlowMode(boolean value) {
        USE_NATIVE_OPTICAL_FLOW_MODE.set(this.segment, 0L, value);
        return this;
    }

    public int computeQueueCreateFlags() {
        return (int) COMPUTE_QUEUE_CREATE_FLAGS.get(this.segment, 0L);
    }

    public VulkanInfo computeQueueCreateFlags(int value) {
        COMPUTE_QUEUE_CREATE_FLAGS.set(this.segment, 0L, value);
        return this;
    }

    public int graphicsQueueCreateFlags() {
        return (int) GRAPHICS_QUEUE_CREATE_FLAGS.get(this.segment, 0L);
    }

    public VulkanInfo graphicsQueueCreateFlags(int value) {
        GRAPHICS_QUEUE_CREATE_FLAGS.set(this.segment, 0L, value);
        return this;
    }

    public int opticalFlowQueueCreateFlags() {
        return (int) OPTICAL_FLOW_QUEUE_CREATE_FLAGS.get(this.segment, 0L);
    }

    public VulkanInfo opticalFlowQueueCreateFlags(int value) {
        OPTICAL_FLOW_QUEUE_CREATE_FLAGS.set(this.segment, 0L, value);
        return this;
    }
}
