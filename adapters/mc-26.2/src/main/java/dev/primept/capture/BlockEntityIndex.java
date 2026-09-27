package dev.primept.capture;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Owner's persistent active candidates. Membership events, not per-frame full scans, refresh known sources. */
public final class BlockEntityIndex {
    private static final class Column {
        final LevelChunk chunk;
        final long key, order;
        final int x, z;
        final ArrayList<BlockEntity> local = new ArrayList<>(), unbounded = new ArrayList<>();
        boolean fallback;
        Column(LevelChunk chunk, long order) {
            this.chunk = chunk;
            this.order = order;
            var pos = chunk.getPos();
            x = pos.x();
            z = pos.z();
            key = ChunkPos.pack(x, z);
        }
    }
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    private final IdentityHashMap<LevelChunk, Column> identities = new IdentityHashMap<>();
    private final LinkedHashSet<Column> dirty = new LinkedHashSet<>();
    private final TreeMap<Long, Column> unbounded = new TreeMap<>(), selected = new TreeMap<>();
    private ColumnWindow window;
    private long nextOrder, refreshed;
    private boolean selectionDirty;

    public synchronized void add(LevelChunk chunk) {
        if (identities.containsKey(chunk))
            return;
        var column = new Column(chunk, Math.incrementExact(nextOrder));
        nextOrder = column.order;
        // Replacing an existing source keeps its position in the real loaded-column iteration order.
        var old = columns.get(column.key);
        if (old != null) {
            remove(old.x, old.z);
            column = new Column(chunk, old.order);
        }
        columns.put(column.key, column);
        identities.put(chunk, column);
        dirty.add(column);
        selectionDirty = true;
    }
    public synchronized void remove(int x, int z) {
        var old = columns.remove(ChunkPos.pack(x, z));
        if (old == null)
            return;
        identities.remove(old.chunk);
        dirty.remove(old);
        unbounded.remove(old.order);
        selected.remove(old.order);
    }
    public synchronized void changed(LevelChunk chunk) {
        var column = identities.get(chunk);
        if (column != null)
            dirty.add(column);
    }
    public synchronized void clear() {
        columns.clear();
        identities.clear();
        dirty.clear();
        unbounded.clear();
        selected.clear();
        window = null;
        nextOrder = refreshed = 0;
        selectionDirty = false;
    }
    public synchronized Iterable<? extends Iterable<BlockEntity>>
    select(BlockEntityCandidates gate, BlockEntityRenderDispatcher dispatcher, long epoch,
           Vec3 camera) {
        if (gate.prepare(dispatcher, epoch))
            dirty.addAll(columns.values());
        for (var column : dirty)
            refresh(column, gate);
        if (!dirty.isEmpty()) {
            selectionDirty = true;
            dirty.clear();
        }
        // Conservative horizontal bound for the exact default block-center sphere (strictly <64).
        var next = new ColumnWindow(SectionPos.blockToSectionCoord(camera.x - 64.5),
                                    SectionPos.blockToSectionCoord(camera.x + 63.5),
                                    SectionPos.blockToSectionCoord(camera.z - 64.5),
                                    SectionPos.blockToSectionCoord(camera.z + 63.5));
        if (selectionDirty || !next.equals(window)) {
            window = next;
            selected.clear();
            selected.putAll(unbounded);
            next.difference(null, (x, z) -> {
                var column = columns.get(ChunkPos.pack(x, z));
                if (column != null && !column.local.isEmpty())
                    selected.put(column.order, column);
            });
            selectionDirty = false;
        }
        return () -> new java.util.Iterator<Iterable<BlockEntity>>() {
            final java.util.Iterator<Column> iterator = selected.values().iterator();
            public boolean hasNext() {
                return iterator.hasNext();
            }
            public Iterable<BlockEntity> next() {
                var column = iterator.next();
                // Escaped/custom maps are observed once on the actual frame, without caching or callback replay.
                if (column.fallback)
                    return column.chunk.getBlockEntities().values();
                return window.contains(column.x, column.z) ? column.local : column.unbounded;
            }
        };
    }
    private void refresh(Column column, BlockEntityCandidates gate) {
        ++refreshed;
        column.local.clear();
        column.unbounded.clear();
        column.fallback =
                column.chunk.getClass() != LevelChunk.class || !Known.SOURCE ||
                ((BlockEntityMapAccess.Membership)column.chunk).primept$escapedBlockEntities();
        if (!column.fallback) {
            var map = BlockEntityMapAccess.read(column.chunk::getBlockEntities);
            for (var entity : map.values()) {
                var kind = gate.kind(entity);
                if (kind == BlockEntityCandidates.Kind.ABSENT)
                    continue;
                column.local.add(entity);
                if (kind == BlockEntityCandidates.Kind.UNKNOWN)
                    column.unbounded.add(entity);
            }
        }
        if (column.fallback || !column.unbounded.isEmpty())
            unbounded.put(column.order, column);
        else
            unbounded.remove(column.order);
    }
    long refreshedColumns() {
        return refreshed;
    }
    private static final class Known {
        static final boolean SOURCE = BlockEntityCandidates.known(LevelChunk.class) &&
                                      BlockEntityCandidates.known(ChunkAccess.class);
    }
}
