package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::DLSSGOptions — {FAC5F1CB-2DFD-4F36-A1E6-3A9E865256C5}, kStructVersion5 */
public final class DlssgOptions {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("mode"),
            JAVA_INT.withName("numFramesToGenerate"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("dynamicResWidth"),
            JAVA_INT.withName("dynamicResHeight"),
            JAVA_INT.withName("numBackBuffers"),
            JAVA_INT.withName("mvecDepthWidth"),
            JAVA_INT.withName("mvecDepthHeight"),
            JAVA_INT.withName("colorWidth"),
            JAVA_INT.withName("colorHeight"),
            JAVA_INT.withName("colorBufferFormat"),
            JAVA_INT.withName("mvecBufferFormat"),
            JAVA_INT.withName("depthBufferFormat"),
            JAVA_INT.withName("hudLessBufferFormat"),
            JAVA_INT.withName("uiBufferFormat"),
            paddingLayout(4),
            ADDRESS.withName("onErrorCallback"),
            JAVA_BYTE.withName("reserved15"),
            paddingLayout(3),
            JAVA_INT.withName("queueParallelismMode"),
            JAVA_BYTE.withName("enableUserInterfaceRecomposition"),
            paddingLayout(3),
            JAVA_FLOAT.withName("dynamicTargetFrameRate"));

    private static final VarHandle MODE = LAYOUT.varHandle(groupElement("mode"));
    private static final VarHandle NUM_FRAMES_TO_GENERATE = LAYOUT.varHandle(groupElement("numFramesToGenerate"));
    private static final VarHandle FLAGS = LAYOUT.varHandle(groupElement("flags"));
    private static final VarHandle DYNAMIC_RES_WIDTH = LAYOUT.varHandle(groupElement("dynamicResWidth"));
    private static final VarHandle DYNAMIC_RES_HEIGHT = LAYOUT.varHandle(groupElement("dynamicResHeight"));
    private static final VarHandle NUM_BACK_BUFFERS = LAYOUT.varHandle(groupElement("numBackBuffers"));
    private static final VarHandle MVEC_DEPTH_WIDTH = LAYOUT.varHandle(groupElement("mvecDepthWidth"));
    private static final VarHandle MVEC_DEPTH_HEIGHT = LAYOUT.varHandle(groupElement("mvecDepthHeight"));
    private static final VarHandle COLOR_WIDTH = LAYOUT.varHandle(groupElement("colorWidth"));
    private static final VarHandle COLOR_HEIGHT = LAYOUT.varHandle(groupElement("colorHeight"));
    private static final VarHandle COLOR_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("colorBufferFormat"));
    private static final VarHandle MVEC_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("mvecBufferFormat"));
    private static final VarHandle DEPTH_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("depthBufferFormat"));
    private static final VarHandle HUD_LESS_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("hudLessBufferFormat"));
    private static final VarHandle UI_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("uiBufferFormat"));
    private static final VarHandle ON_ERROR_CALLBACK = LAYOUT.varHandle(groupElement("onErrorCallback"));
    private static final VarHandle RESERVED_15 = LAYOUT.varHandle(groupElement("reserved15"));
    private static final VarHandle QUEUE_PARALLELISM_MODE = LAYOUT.varHandle(groupElement("queueParallelismMode"));
    private static final VarHandle ENABLE_USER_INTERFACE_RECOMPOSITION = LAYOUT.varHandle(groupElement("enableUserInterfaceRecomposition"));
    private static final VarHandle DYNAMIC_TARGET_FRAME_RATE = LAYOUT.varHandle(groupElement("dynamicTargetFrameRate"));

    private final MemorySegment segment;

    private DlssgOptions(MemorySegment segment) {
        this.segment = segment;
    }

    public static DlssgOptions allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xfac5f1cb, (short) 0x2dfd, (short) 0x4f36, 0xC55652869E3AE6A1L, 5);
        NUM_FRAMES_TO_GENERATE.set(segment, 0L, 1);
        RESERVED_15.set(segment, 0L, SlBoolean.INVALID.value);
        return new DlssgOptions(segment);
    }

    public static DlssgOptions wrap(MemorySegment segment) {
        return new DlssgOptions(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public DlssgMode mode() {
        return DlssgMode.fromValue((int) MODE.get(this.segment, 0L));
    }

    public DlssgOptions mode(DlssgMode value) {
        MODE.set(this.segment, 0L, value.value);
        return this;
    }

    /** 1 = 2x frame multiplier, 2 = 3x, 3 = 4x; cannot exceed DlssgState.numFramesToGenerateMax */
    public int numFramesToGenerate() {
        return (int) NUM_FRAMES_TO_GENERATE.get(this.segment, 0L);
    }

    public DlssgOptions numFramesToGenerate(int value) {
        NUM_FRAMES_TO_GENERATE.set(this.segment, 0L, value);
        return this;
    }

    /** Raw uint32 mask, see {@link DlssgFlag} */
    public int flags() {
        return (int) FLAGS.get(this.segment, 0L);
    }

    public DlssgOptions flags(int value) {
        FLAGS.set(this.segment, 0L, value);
        return this;
    }

    public int dynamicResWidth() {
        return (int) DYNAMIC_RES_WIDTH.get(this.segment, 0L);
    }

    public DlssgOptions dynamicResWidth(int value) {
        DYNAMIC_RES_WIDTH.set(this.segment, 0L, value);
        return this;
    }

    public int dynamicResHeight() {
        return (int) DYNAMIC_RES_HEIGHT.get(this.segment, 0L);
    }

    public DlssgOptions dynamicResHeight(int value) {
        DYNAMIC_RES_HEIGHT.set(this.segment, 0L, value);
        return this;
    }

    public int numBackBuffers() {
        return (int) NUM_BACK_BUFFERS.get(this.segment, 0L);
    }

    public DlssgOptions numBackBuffers(int value) {
        NUM_BACK_BUFFERS.set(this.segment, 0L, value);
        return this;
    }

    public int mvecDepthWidth() {
        return (int) MVEC_DEPTH_WIDTH.get(this.segment, 0L);
    }

    public DlssgOptions mvecDepthWidth(int value) {
        MVEC_DEPTH_WIDTH.set(this.segment, 0L, value);
        return this;
    }

    public int mvecDepthHeight() {
        return (int) MVEC_DEPTH_HEIGHT.get(this.segment, 0L);
    }

    public DlssgOptions mvecDepthHeight(int value) {
        MVEC_DEPTH_HEIGHT.set(this.segment, 0L, value);
        return this;
    }

    public int colorWidth() {
        return (int) COLOR_WIDTH.get(this.segment, 0L);
    }

    public DlssgOptions colorWidth(int value) {
        COLOR_WIDTH.set(this.segment, 0L, value);
        return this;
    }

    public int colorHeight() {
        return (int) COLOR_HEIGHT.get(this.segment, 0L);
    }

    public DlssgOptions colorHeight(int value) {
        COLOR_HEIGHT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat of the swap-chain back buffers */
    public int colorBufferFormat() {
        return (int) COLOR_BUFFER_FORMAT.get(this.segment, 0L);
    }

    public DlssgOptions colorBufferFormat(int value) {
        COLOR_BUFFER_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat of the motion vectors buffer */
    public int mvecBufferFormat() {
        return (int) MVEC_BUFFER_FORMAT.get(this.segment, 0L);
    }

    public DlssgOptions mvecBufferFormat(int value) {
        MVEC_BUFFER_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat of the depth buffer */
    public int depthBufferFormat() {
        return (int) DEPTH_BUFFER_FORMAT.get(this.segment, 0L);
    }

    public DlssgOptions depthBufferFormat(int value) {
        DEPTH_BUFFER_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat of the HUD-less color buffer */
    public int hudLessBufferFormat() {
        return (int) HUD_LESS_BUFFER_FORMAT.get(this.segment, 0L);
    }

    public DlssgOptions hudLessBufferFormat(int value) {
        HUD_LESS_BUFFER_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    /** VkFormat of the UI color & alpha buffer */
    public int uiBufferFormat() {
        return (int) UI_BUFFER_FORMAT.get(this.segment, 0L);
    }

    public DlssgOptions uiBufferFormat(int value) {
        UI_BUFFER_FORMAT.set(this.segment, 0L, value);
        return this;
    }

    /** PFunOnAPIErrorCallback function pointer or null */
    public MemorySegment onErrorCallback() {
        return (MemorySegment) ON_ERROR_CALLBACK.get(this.segment, 0L);
    }

    public DlssgOptions onErrorCallback(MemorySegment value) {
        ON_ERROR_CALLBACK.set(this.segment, 0L, value);
        return this;
    }

    public DlssgQueueParallelismMode queueParallelismMode() {
        return DlssgQueueParallelismMode.fromValue((int) QUEUE_PARALLELISM_MODE.get(this.segment, 0L));
    }

    public DlssgOptions queueParallelismMode(DlssgQueueParallelismMode value) {
        QUEUE_PARALLELISM_MODE.set(this.segment, 0L, value.value);
        return this;
    }

    public SlBoolean enableUserInterfaceRecomposition() {
        return SlBoolean.fromValue((byte) ENABLE_USER_INTERFACE_RECOMPOSITION.get(this.segment, 0L));
    }

    public DlssgOptions enableUserInterfaceRecomposition(SlBoolean value) {
        ENABLE_USER_INTERFACE_RECOMPOSITION.set(this.segment, 0L, value.value);
        return this;
    }

    /** Target frame rate for dynamic frame generation; 0 auto-detects the display refresh rate */
    public float dynamicTargetFrameRate() {
        return (float) DYNAMIC_TARGET_FRAME_RATE.get(this.segment, 0L);
    }

    public DlssgOptions dynamicTargetFrameRate(float value) {
        DYNAMIC_TARGET_FRAME_RATE.set(this.segment, 0L, value);
        return this;
    }
}
