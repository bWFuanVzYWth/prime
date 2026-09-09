// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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

    private static final VarHandle STATUS = LAYOUT.varHandle(groupElement("status"));
    private static final VarHandle MIN_WIDTH_OR_HEIGHT = LAYOUT.varHandle(groupElement("minWidthOrHeight"));
    private static final VarHandle NUM_FRAMES_TO_GENERATE_MAX = LAYOUT.varHandle(groupElement("numFramesToGenerateMax"));

    private final MemorySegment segment;

    private DlssgState(MemorySegment segment) {
        this.segment = segment;
    }

    public static DlssgState allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xcc8ac8e1, (short) 0xa179, (short) 0x44f5, 0x61BCF91241E7FA97L, 4);
        return new DlssgState(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    /** Raw uint32 mask, see {@link DlssgStatus} */
    public int status() {
        return (int) STATUS.get(this.segment, 0L);
    }

    public int minWidthOrHeight() {
        return (int) MIN_WIDTH_OR_HEIGHT.get(this.segment, 0L);
    }

    /** Upper bound for DlssgOptions.numFramesToGenerate */
    public int numFramesToGenerateMax() {
        return (int) NUM_FRAMES_TO_GENERATE_MAX.get(this.segment, 0L);
    }

}
