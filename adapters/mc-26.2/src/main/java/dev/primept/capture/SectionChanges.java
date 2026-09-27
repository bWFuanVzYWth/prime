package dev.primept.capture;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** Host dirty observations, distinct from first sources waiting for host prerequisites. */
final class SectionChanges {
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private final Long2ObjectOpenHashMap<LongLinkedOpenHashSet> waiting =
            new Long2ObjectOpenHashMap<>();
    private int waitingCount;

    synchronized void add(long section) {
        long column = column(section);
        var blocked = waiting.get(column);
        if (blocked != null && blocked.remove(section)) {
            --waitingCount;
            if (blocked.isEmpty())
                waiting.remove(column);
        }
        pending.add(section);
    }
    synchronized void awaitSource(long section) {
        // A newer/reentrant dirty observation already belongs to the next sealed batch.
        if (pending.contains(section))
            return;
        if (waiting.computeIfAbsent(column(section), ignored -> new LongLinkedOpenHashSet())
                    .add(section))
            ++waitingCount;
    }
    /** Only wake existing first-source requests. A neighbor event never dirties resident geometry. */
    synchronized void sourceColumnChanged(int x, int z) {
        if (waiting.isEmpty())
            return;
        for (int dz = -1; dz <= 1; ++dz)
            for (int dx = -1; dx <= 1; ++dx) {
                var blocked = waiting.remove(ChunkPos.pack(x + dx, z + dz));
                if (blocked != null) {
                    waitingCount -= blocked.size();
                    pending.addAll(blocked);
                }
            }
    }
    /** Existing cache slots may become readable without a new chunk packet. */
    synchronized void sourceWindowChanged(ColumnWindow previous, ColumnWindow next) {
        if (!waiting.isEmpty() && next != null)
            next.difference(previous, this::sourceColumnChanged);
    }
    synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
    synchronized int size() {
        return pending.size();
    }
    synchronized int waitingSize() {
        return waitingCount;
    }
    synchronized long[] seal() {
        long[] sealed = pending.toLongArray();
        pending.clear();
        return sealed;
    }
    synchronized void clear() {
        pending.clear();
        waiting.clear();
        waitingCount = 0;
    }
    synchronized void removeChunk(int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; ++y)
            pending.remove(SectionPos.asLong(x, y, z));
        var blocked = waiting.remove(ChunkPos.pack(x, z));
        if (blocked != null)
            waitingCount -= blocked.size();
    }
    private static long column(long section) {
        return ChunkPos.pack(SectionPos.x(section), SectionPos.z(section));
    }
}
