package dev.prime.binding.streamline;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** sl::ReflexReport2 — {68BB0632-5E1C-402B-899D-B49F633C56C2}, kStructVersion1. Out-only struct. */
public final class ReflexReport2 {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_LONG.withName("cameraConstructedTime"),
            JAVA_INT.withName("crossAdapterCopyTimeUs"),
            paddingLayout(4));

    private static final VarHandle CAMERA_CONSTRUCTED_TIME = LAYOUT.varHandle(groupElement("cameraConstructedTime"));
    private static final VarHandle CROSS_ADAPTER_COPY_TIME_US = LAYOUT.varHandle(groupElement("crossAdapterCopyTimeUs"));

    private final MemorySegment segment;

    private ReflexReport2(MemorySegment segment) {
        this.segment = segment;
    }

    public static ReflexReport2 wrap(MemorySegment segment) {
        return new ReflexReport2(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public long cameraConstructedTime() {
        return (long) CAMERA_CONSTRUCTED_TIME.get(this.segment, 0L);
    }

    public int crossAdapterCopyTimeUs() {
        return (int) CROSS_ADAPTER_COPY_TIME_US.get(this.segment, 0L);
    }
}
