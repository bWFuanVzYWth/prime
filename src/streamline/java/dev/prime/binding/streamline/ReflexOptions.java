package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** sl::ReflexOptions — {F03AF81A-6D0B-4902-A651-C4965E215434}, kStructVersion1 */
public final class ReflexOptions {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("mode"),
            JAVA_INT.withName("frameLimitUs"),
            JAVA_BOOLEAN.withName("useMarkersToOptimize"),
            paddingLayout(1),
            JAVA_SHORT.withName("virtualKey"),
            JAVA_INT.withName("idThread"),
            paddingLayout(4));

    private static final VarHandle MODE = LAYOUT.varHandle(groupElement("mode"));
    private static final VarHandle FRAME_LIMIT_US = LAYOUT.varHandle(groupElement("frameLimitUs"));
    private static final VarHandle USE_MARKERS_TO_OPTIMIZE = LAYOUT.varHandle(groupElement("useMarkersToOptimize"));
    private static final VarHandle VIRTUAL_KEY = LAYOUT.varHandle(groupElement("virtualKey"));
    private static final VarHandle ID_THREAD = LAYOUT.varHandle(groupElement("idThread"));

    private final MemorySegment segment;

    private ReflexOptions(MemorySegment segment) {
        this.segment = segment;
    }

    public static ReflexOptions allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xf03af81a, (short) 0x6d0b, (short) 0x4902, 0x3454215E96C451A6L, 1);
        return new ReflexOptions(segment);
    }

    public static ReflexOptions wrap(MemorySegment segment) {
        return new ReflexOptions(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public ReflexMode mode() {
        return ReflexMode.fromValue((int) MODE.get(this.segment, 0L));
    }

    public ReflexOptions mode(ReflexMode value) {
        MODE.set(this.segment, 0L, value.value);
        return this;
    }

    /** Frame limit (FPS cap) in microseconds; 0 disables */
    public int frameLimitUs() {
        return (int) FRAME_LIMIT_US.get(this.segment, 0L);
    }

    public ReflexOptions frameLimitUs(int value) {
        FRAME_LIMIT_US.set(this.segment, 0L, value);
        return this;
    }

    /** Should only be enabled in specific scenarios; most integrations leave this false */
    public boolean useMarkersToOptimize() {
        return (boolean) USE_MARKERS_TO_OPTIMIZE.get(this.segment, 0L);
    }

    public ReflexOptions useMarkersToOptimize(boolean value) {
        USE_MARKERS_TO_OPTIMIZE.set(this.segment, 0L, value);
        return this;
    }

    public HotKey virtualKey() {
        return HotKey.fromValue((short) VIRTUAL_KEY.get(this.segment, 0L));
    }

    public ReflexOptions virtualKey(HotKey value) {
        VIRTUAL_KEY.set(this.segment, 0L, value.value);
        return this;
    }

    /** ThreadID for PCL Stats messages; most integrations leave this 0 */
    public int idThread() {
        return (int) ID_THREAD.get(this.segment, 0L);
    }

    public ReflexOptions idThread(int value) {
        ID_THREAD.set(this.segment, 0L, value);
        return this;
    }
}
