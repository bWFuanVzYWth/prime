package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::FeatureRequirements — {66714097-AC6D-4BC6-8915-1E0F55A6B61F}, kStructVersion2. Out-only struct. */
public final class FeatureRequirements {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("maxNumCPUThreads"),
            JAVA_INT.withName("maxNumViewports"),
            JAVA_INT.withName("numRequiredTags"),
            ADDRESS.withName("requiredTags"),
            Version.LAYOUT.withName("osVersionDetected"),
            Version.LAYOUT.withName("osVersionRequired"),
            Version.LAYOUT.withName("driverVersionDetected"),
            Version.LAYOUT.withName("driverVersionRequired"),
            JAVA_INT.withName("vkNumComputeQueuesRequired"),
            JAVA_INT.withName("vkNumGraphicsQueuesRequired"),
            JAVA_INT.withName("vkNumDeviceExtensions"),
            paddingLayout(4),
            ADDRESS.withName("vkDeviceExtensions"),
            JAVA_INT.withName("vkNumInstanceExtensions"),
            paddingLayout(4),
            ADDRESS.withName("vkInstanceExtensions"),
            JAVA_INT.withName("vkNumFeatures12"),
            paddingLayout(4),
            ADDRESS.withName("vkFeatures12"),
            JAVA_INT.withName("vkNumFeatures13"),
            paddingLayout(4),
            ADDRESS.withName("vkFeatures13"),
            JAVA_INT.withName("vkNumOpticalFlowQueuesRequired"),
            paddingLayout(4));

    private static final VarHandle FLAGS = LAYOUT.varHandle(groupElement("flags"));
    private static final VarHandle MAX_NUM_CPU_THREADS = LAYOUT.varHandle(groupElement("maxNumCPUThreads"));
    private static final VarHandle MAX_NUM_VIEWPORTS = LAYOUT.varHandle(groupElement("maxNumViewports"));
    private static final VarHandle NUM_REQUIRED_TAGS = LAYOUT.varHandle(groupElement("numRequiredTags"));
    private static final VarHandle REQUIRED_TAGS = LAYOUT.varHandle(groupElement("requiredTags"));
    private static final long OS_VERSION_DETECTED = LAYOUT.byteOffset(groupElement("osVersionDetected"));
    private static final long OS_VERSION_REQUIRED = LAYOUT.byteOffset(groupElement("osVersionRequired"));
    private static final long DRIVER_VERSION_DETECTED = LAYOUT.byteOffset(groupElement("driverVersionDetected"));
    private static final long DRIVER_VERSION_REQUIRED = LAYOUT.byteOffset(groupElement("driverVersionRequired"));
    private static final VarHandle VK_NUM_COMPUTE_QUEUES_REQUIRED = LAYOUT.varHandle(groupElement("vkNumComputeQueuesRequired"));
    private static final VarHandle VK_NUM_GRAPHICS_QUEUES_REQUIRED = LAYOUT.varHandle(groupElement("vkNumGraphicsQueuesRequired"));
    private static final VarHandle VK_NUM_DEVICE_EXTENSIONS = LAYOUT.varHandle(groupElement("vkNumDeviceExtensions"));
    private static final VarHandle VK_DEVICE_EXTENSIONS = LAYOUT.varHandle(groupElement("vkDeviceExtensions"));
    private static final VarHandle VK_NUM_INSTANCE_EXTENSIONS = LAYOUT.varHandle(groupElement("vkNumInstanceExtensions"));
    private static final VarHandle VK_INSTANCE_EXTENSIONS = LAYOUT.varHandle(groupElement("vkInstanceExtensions"));
    private static final VarHandle VK_NUM_FEATURES_12 = LAYOUT.varHandle(groupElement("vkNumFeatures12"));
    private static final VarHandle VK_FEATURES_12 = LAYOUT.varHandle(groupElement("vkFeatures12"));
    private static final VarHandle VK_NUM_FEATURES_13 = LAYOUT.varHandle(groupElement("vkNumFeatures13"));
    private static final VarHandle VK_FEATURES_13 = LAYOUT.varHandle(groupElement("vkFeatures13"));
    private static final VarHandle VK_NUM_OPTICAL_FLOW_QUEUES_REQUIRED = LAYOUT.varHandle(groupElement("vkNumOpticalFlowQueuesRequired"));

    private final MemorySegment segment;

    private FeatureRequirements(MemorySegment segment) {
        this.segment = segment;
    }

    public static FeatureRequirements allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0x66714097, (short) 0xac6d, (short) 0x4bc6, 0x1FB6A6550F1E1589L, 2);
        return new FeatureRequirements(segment);
    }

    public static FeatureRequirements wrap(MemorySegment segment) {
        return new FeatureRequirements(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** Raw uint32 mask, see {@link FeatureRequirementFlag} */
    public int flags() {
        return (int) FLAGS.get(this.segment, 0L);
    }

    public int maxNumCPUThreads() {
        return (int) MAX_NUM_CPU_THREADS.get(this.segment, 0L);
    }

    public int maxNumViewports() {
        return (int) MAX_NUM_VIEWPORTS.get(this.segment, 0L);
    }

    public int numRequiredTags() {
        return (int) NUM_REQUIRED_TAGS.get(this.segment, 0L);
    }

    /** const sl::BufferType* — lives in native memory owned by Streamline */
    public MemorySegment requiredTags() {
        return (MemorySegment) REQUIRED_TAGS.get(this.segment, 0L);
    }

    public Version osVersionDetected() {
        return Version.read(this.segment, OS_VERSION_DETECTED);
    }

    public Version osVersionRequired() {
        return Version.read(this.segment, OS_VERSION_REQUIRED);
    }

    public Version driverVersionDetected() {
        return Version.read(this.segment, DRIVER_VERSION_DETECTED);
    }

    public Version driverVersionRequired() {
        return Version.read(this.segment, DRIVER_VERSION_REQUIRED);
    }

    public int vkNumComputeQueuesRequired() {
        return (int) VK_NUM_COMPUTE_QUEUES_REQUIRED.get(this.segment, 0L);
    }

    public int vkNumGraphicsQueuesRequired() {
        return (int) VK_NUM_GRAPHICS_QUEUES_REQUIRED.get(this.segment, 0L);
    }

    public int vkNumDeviceExtensions() {
        return (int) VK_NUM_DEVICE_EXTENSIONS.get(this.segment, 0L);
    }

    /** const char** — lives in native memory owned by Streamline */
    public MemorySegment vkDeviceExtensions() {
        return (MemorySegment) VK_DEVICE_EXTENSIONS.get(this.segment, 0L);
    }

    public int vkNumInstanceExtensions() {
        return (int) VK_NUM_INSTANCE_EXTENSIONS.get(this.segment, 0L);
    }

    /** const char** — lives in native memory owned by Streamline */
    public MemorySegment vkInstanceExtensions() {
        return (MemorySegment) VK_INSTANCE_EXTENSIONS.get(this.segment, 0L);
    }

    public int vkNumFeatures12() {
        return (int) VK_NUM_FEATURES_12.get(this.segment, 0L);
    }

    /** const char** — lives in native memory owned by Streamline */
    public MemorySegment vkFeatures12() {
        return (MemorySegment) VK_FEATURES_12.get(this.segment, 0L);
    }

    public int vkNumFeatures13() {
        return (int) VK_NUM_FEATURES_13.get(this.segment, 0L);
    }

    /** const char** — lives in native memory owned by Streamline */
    public MemorySegment vkFeatures13() {
        return (MemorySegment) VK_FEATURES_13.get(this.segment, 0L);
    }

    public int vkNumOpticalFlowQueuesRequired() {
        return (int) VK_NUM_OPTICAL_FLOW_QUEUES_REQUIRED.get(this.segment, 0L);
    }
}
