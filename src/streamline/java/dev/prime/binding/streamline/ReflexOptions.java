// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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
    private static final short VK_F13 = 0x7c;

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
    private static final VarHandle VIRTUAL_KEY = LAYOUT.varHandle(groupElement("virtualKey"));
    private static final VarHandle ID_THREAD = LAYOUT.varHandle(groupElement("idThread"));

    private final MemorySegment segment;

    private ReflexOptions(MemorySegment segment) {
        this.segment = segment;
    }

    public static ReflexOptions allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xf03af81a, (short) 0x6d0b, (short) 0x4902, 0x3454215E96C451A6L, 1);
        VIRTUAL_KEY.set(segment, 0L, VK_F13);
        return new ReflexOptions(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public void set(ReflexMode value, int frameLimitUs, int threadId) {
        MODE.set(this.segment, 0L, value.value);
        FRAME_LIMIT_US.set(this.segment, 0L, frameLimitUs);
        ID_THREAD.set(this.segment, 0L, threadId);
    }
}
