package dev.primept.capture;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.SectionPos;

/** Client-thread dirty work; removing before execution preserves a reentrant new dirty event. */
final class SectionWorkQueue {
    private static final int LIMIT = 262144;
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    void add(long section) {
        if (!pending.contains(section) && pending.size() == LIMIT)
            throw new IllegalStateException(
                    "Exclusive terrain dirty queue exceeds 262144 sections");
        pending.add(section);
    }
    boolean isEmpty() {
        return pending.isEmpty();
    }
    int size() {
        return pending.size();
    }
    long poll() {
        return pending.removeFirstLong();
    }
    void clear() {
        pending.clear();
    }
    void removeChunk(int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; ++y)
            pending.remove(SectionPos.asLong(x, y, z));
    }
}
