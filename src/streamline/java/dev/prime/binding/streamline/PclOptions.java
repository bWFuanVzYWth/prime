package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** sl::PCLOptions — {CFA32F9B-023C-420E-9056-6832B74F89B4}, kStructVersion1 */
public final class PclOptions {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_SHORT.withName("virtualKey"),
            paddingLayout(2),
            JAVA_INT.withName("idThread"));

    private static final VarHandle VIRTUAL_KEY = LAYOUT.varHandle(groupElement("virtualKey"));
    private static final VarHandle ID_THREAD = LAYOUT.varHandle(groupElement("idThread"));

    private final MemorySegment segment;

    private PclOptions(MemorySegment segment) {
        this.segment = segment;
    }

    public static PclOptions allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xcfa32f9b, (short) 0x023c, (short) 0x420e, 0xB4894FB732685690L, 1);
        return new PclOptions(segment);
    }

    public static PclOptions wrap(MemorySegment segment) {
        return new PclOptions(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public HotKey virtualKey() {
        return HotKey.fromValue((short) VIRTUAL_KEY.get(this.segment, 0L));
    }

    public PclOptions virtualKey(HotKey value) {
        VIRTUAL_KEY.set(this.segment, 0L, value.value);
        return this;
    }

    /** ThreadID for PCL messages; most integrations leave this 0 */
    public int idThread() {
        return (int) ID_THREAD.get(this.segment, 0L);
    }

    public PclOptions idThread(int value) {
        ID_THREAD.set(this.segment, 0L, value);
        return this;
    }
}
