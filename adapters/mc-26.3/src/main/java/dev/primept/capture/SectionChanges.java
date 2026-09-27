package dev.primept.capture;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.SectionPos;

/** Net dirty identities. seal() separates finite accepted work from reentrant events. */
final class SectionChanges {
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    synchronized void add(long section) {
        pending.add(section);
    }
    synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
    synchronized int size() {
        return pending.size();
    }
    synchronized long[] seal() {
        long[] sealed = pending.toLongArray();
        pending.clear();
        return sealed;
    }
    synchronized void clear() {
        pending.clear();
    }
    synchronized void removeChunk(int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; ++y)
            pending.remove(SectionPos.asLong(x, y, z));
    }
}
