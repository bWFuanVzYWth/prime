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

/** sl::DLSSGState — {CC8AC8E1-A179-44F5-97FA-E74112F9BC61}, kStructVersion4. Out-only struct. */
public final class DlssgState {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_LONG.withName("estimatedVRAMUsageInBytes"),
            JAVA_INT.withName("status"),
            JAVA_INT.withName("minWidthOrHeight"),
            JAVA_INT.withName("numFramesActuallyPresented"),
            JAVA_INT.withName("numFramesToGenerateMax"),
            JAVA_BYTE.withName("reserved4"),
            JAVA_BYTE.withName("vsyncSupportAvailable"),
            paddingLayout(6),
            ADDRESS.withName("inputsProcessingCompletionFence"),
            JAVA_LONG.withName("lastPresentInputsProcessingCompletionFenceValue"),
            JAVA_BYTE.withName("dynamicMFGSupported"),
            paddingLayout(7));

    private static final VarHandle ESTIMATED_VRAM_USAGE_IN_BYTES = LAYOUT.varHandle(groupElement("estimatedVRAMUsageInBytes"));
    private static final VarHandle STATUS = LAYOUT.varHandle(groupElement("status"));
    private static final VarHandle MIN_WIDTH_OR_HEIGHT = LAYOUT.varHandle(groupElement("minWidthOrHeight"));
    private static final VarHandle NUM_FRAMES_ACTUALLY_PRESENTED = LAYOUT.varHandle(groupElement("numFramesActuallyPresented"));
    private static final VarHandle NUM_FRAMES_TO_GENERATE_MAX = LAYOUT.varHandle(groupElement("numFramesToGenerateMax"));
    private static final VarHandle VSYNC_SUPPORT_AVAILABLE = LAYOUT.varHandle(groupElement("vsyncSupportAvailable"));
    private static final VarHandle INPUTS_PROCESSING_COMPLETION_FENCE = LAYOUT.varHandle(groupElement("inputsProcessingCompletionFence"));
    private static final VarHandle LAST_PRESENT_INPUTS_PROCESSING_COMPLETION_FENCE_VALUE = LAYOUT.varHandle(groupElement("lastPresentInputsProcessingCompletionFenceValue"));
    private static final VarHandle DYNAMIC_MFG_SUPPORTED = LAYOUT.varHandle(groupElement("dynamicMFGSupported"));

    private final MemorySegment segment;

    private DlssgState(MemorySegment segment) {
        this.segment = segment;
    }

    public static DlssgState allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xcc8ac8e1, (short) 0xa179, (short) 0x44f5, 0x61BCF91241E7FA97L, 4);
        return new DlssgState(segment);
    }

    public static DlssgState wrap(MemorySegment segment) {
        return new DlssgState(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public long estimatedVRAMUsageInBytes() {
        return (long) ESTIMATED_VRAM_USAGE_IN_BYTES.get(this.segment, 0L);
    }

    /** Raw uint32 mask, see {@link DlssgStatus} */
    public int status() {
        return (int) STATUS.get(this.segment, 0L);
    }

    public int minWidthOrHeight() {
        return (int) MIN_WIDTH_OR_HEIGHT.get(this.segment, 0L);
    }

    public int numFramesActuallyPresented() {
        return (int) NUM_FRAMES_ACTUALLY_PRESENTED.get(this.segment, 0L);
    }

    /** Upper bound for DlssgOptions.numFramesToGenerate */
    public int numFramesToGenerateMax() {
        return (int) NUM_FRAMES_TO_GENERATE_MAX.get(this.segment, 0L);
    }

    public SlBoolean vsyncSupportAvailable() {
        return SlBoolean.fromValue((byte) VSYNC_SUPPORT_AVAILABLE.get(this.segment, 0L));
    }

    /** VkFence handle value; wait on it before modifying tagged inputs on a non-presenting queue */
    public MemorySegment inputsProcessingCompletionFence() {
        return (MemorySegment) INPUTS_PROCESSING_COMPLETION_FENCE.get(this.segment, 0L);
    }

    public long lastPresentInputsProcessingCompletionFenceValue() {
        return (long) LAST_PRESENT_INPUTS_PROCESSING_COMPLETION_FENCE_VALUE.get(this.segment, 0L);
    }

    public SlBoolean dynamicMFGSupported() {
        return SlBoolean.fromValue((byte) DYNAMIC_MFG_SUPPORTED.get(this.segment, 0L));
    }
}
