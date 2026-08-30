package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::ReflexState — {F0BB5985-DAF9-4728-B2FD-AE80A2BD7989}, kStructVersion2. Out-only struct. */
public final class ReflexState {
    public static final int FRAME_REPORT_COUNT = 64;

    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_BOOLEAN.withName("lowLatencyAvailable"),
            JAVA_BOOLEAN.withName("latencyReportAvailable"),
            paddingLayout(2),
            JAVA_INT.withName("statsWindowMessage"),
            MemoryLayout.sequenceLayout(FRAME_REPORT_COUNT, ReflexReport.LAYOUT).withName("frameReport"),
            JAVA_BOOLEAN.withName("flashIndicatorDriverControlled"),
            paddingLayout(7),
            MemoryLayout.sequenceLayout(FRAME_REPORT_COUNT, ReflexReport2.LAYOUT).withName("frameReport2"));

    private static final VarHandle LOW_LATENCY_AVAILABLE = LAYOUT.varHandle(groupElement("lowLatencyAvailable"));
    private static final VarHandle LATENCY_REPORT_AVAILABLE = LAYOUT.varHandle(groupElement("latencyReportAvailable"));
    private static final VarHandle STATS_WINDOW_MESSAGE = LAYOUT.varHandle(groupElement("statsWindowMessage"));
    private static final VarHandle FLASH_INDICATOR_DRIVER_CONTROLLED = LAYOUT.varHandle(groupElement("flashIndicatorDriverControlled"));
    private static final long FRAME_REPORT = LAYOUT.byteOffset(groupElement("frameReport"));
    private static final long FRAME_REPORT_2 = LAYOUT.byteOffset(groupElement("frameReport2"));

    private final MemorySegment segment;

    private ReflexState(MemorySegment segment) {
        this.segment = segment;
    }

    public static ReflexState allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xf0bb5985, (short) 0xdaf9, (short) 0x4728, 0x8979BDA280AEFDB2L, 2);
        return new ReflexState(segment);
    }

    public static ReflexState wrap(MemorySegment segment) {
        return new ReflexState(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public boolean lowLatencyAvailable() {
        return (boolean) LOW_LATENCY_AVAILABLE.get(this.segment, 0L);
    }

    /** Whether the frameReport data below is valid */
    public boolean latencyReportAvailable() {
        return (boolean) LATENCY_REPORT_AVAILABLE.get(this.segment, 0L);
    }

    /** Low latency Windows message id (if ReflexOptions.virtualKey is 0) */
    public int statsWindowMessage() {
        return (int) STATS_WINDOW_MESSAGE.get(this.segment, 0L);
    }

    /** true = flash indicator toggle owned by the driver, false = application */
    public boolean flashIndicatorDriverControlled() {
        return (boolean) FLASH_INDICATOR_DRIVER_CONTROLLED.get(this.segment, 0L);
    }

    public ReflexReport frameReport(int index) {
        return ReflexReport.wrap(this.segment.asSlice(FRAME_REPORT + (long) index * ReflexReport.LAYOUT.byteSize(), ReflexReport.LAYOUT.byteSize()));
    }

    public ReflexReport2 frameReport2(int index) {
        return ReflexReport2.wrap(this.segment.asSlice(FRAME_REPORT_2 + (long) index * ReflexReport2.LAYOUT.byteSize(), ReflexReport2.LAYOUT.byteSize()));
    }
}
