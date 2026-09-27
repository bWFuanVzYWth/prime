package dev.primept.capture;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Actual transformed cache/graph behavior with data-only chunk fixtures; no device or integrated server. */
final class TerrainRestoreCpuSmoke {
    static void run() throws Exception {
        var cache = blank(ClientChunkCache.class);
        Object storage = storage(cache, 2);
        var source = (LoadedTerrainSnapshot)cache;
        check(source.primept$sourceWindow().equals(ColumnWindow.centered(0, 0, 2)),
              "Actual storage window bound");
        cache.updateViewCenter(5, -7);
        check(source.primept$sourceWindow().equals(ColumnWindow.centered(5, -7, 2)),
              "Storage center updates live without rescanning slots");
        cache.updateViewCenter(0, 0);
        put(storage, chunk(0, 0, -4, true, false, true));
        put(storage, chunk(-1, 1, -4, false, true));
        put(storage,
            chunk(4, 0, -4, true)); // Occupies a slot but falls outside the current cache window.
        cache.flipUpdateTrackingSets();
        cache.flipUpdateTrackingSets();
        var graph = new SectionOcclusionGraph();
        var oldLoaded = new LongOpenHashSet();
        oldLoaded.add(ChunkPos.pack(15, 15));
        var oldEmpty = new LongOpenHashSet();
        oldEmpty.add(SectionPos.asLong(15, 2, 15));
        graph.updateLoadedChunks(oldLoaded, new LongOpenHashSet());
        graph.updateEmptySections(oldEmpty, new LongOpenHashSet());
        graph.waitAndReset(
                null); // The real retirement clears readiness, even though chunks remain loaded.
        check(set(graph, "loadedChunks").isEmpty() && set(graph, "emptySections").isEmpty(),
              "Retired graph discarded readiness");
        ((LoadedTerrainSnapshot)cache).primept$restoreTerrainSnapshot(graph);
        check(set(graph, "loadedChunks")
                      .equals(LongOpenHashSet.of(ChunkPos.pack(0, 0), ChunkPos.pack(-1, 1))),
              "Already-loaded columns restored without new packet events; stale out-of-range slots excluded");
        check(set(graph, "emptySections")
                      .equals(LongOpenHashSet.of(SectionPos.asLong(0, -4, 0),
                                                 SectionPos.asLong(0, -2, 0),
                                                 SectionPos.asLong(-1, -3, 1))),
              "Empty source sections restored with actual minimum Y");
        check(cache.addedLoadedChunks().isEmpty() && cache.addedEmptySections().isEmpty(),
              "Shared event journals remain untouched");

        Object replacement = storage(cache, 3);
        check(source.primept$sourceWindow().equals(ColumnWindow.centered(0, 0, 3)),
              "Replacement storage rebinds source bounds");
        put(replacement, chunk(2, 0, 2, false, true));
        graph.waitAndReset(null);
        ((LoadedTerrainSnapshot)cache).primept$restoreTerrainSnapshot(graph);
        check(set(graph, "loadedChunks").equals(LongOpenHashSet.of(ChunkPos.pack(2, 0))) &&
                      set(graph, "emptySections")
                              .equals(LongOpenHashSet.of(SectionPos.asLong(2, 3, 0))),
              "Storage replacement binds the new array without retaining old loaded columns");
        System.out.println(
                "PRIME_PT_TERRAIN_RESTORE_CPU_OK: actual graph reset + exhausted journal + complete loaded/empty snapshot; cache resize; stale slot filtering");
    }
    private static Object storage(ClientChunkCache owner, int radius) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.multiplayer.ClientChunkCache$Storage");
        var constructor = type.getDeclaredConstructor(ClientChunkCache.class, int.class);
        constructor.setAccessible(true);
        Object storage =
                constructor.newInstance(owner, radius); // Executes the production binding mixin.
        field(ClientChunkCache.class, "storage").set(owner, storage);
        return storage;
    }
    private static LevelChunk chunk(int x, int z, int minSection, boolean... empty)
            throws Exception {
        var chunk = blank(LevelChunk.class);
        var sections = new LevelChunkSection[empty.length];
        for (int i = 0; i < sections.length; ++i) {
            sections[i] = blank(LevelChunkSection.class);
            field(LevelChunkSection.class, "nonEmptyBlockCount")
                    .setShort(sections[i], (short)(empty[i] ? 0 : 1));
        }
        field(ChunkAccess.class, "chunkPos").set(chunk, new ChunkPos(x, z));
        field(ChunkAccess.class, "sections").set(chunk, sections);
        field(ChunkAccess.class, "levelHeightAccessor")
                .set(chunk, LevelHeightAccessor.create(minSection * 16, empty.length * 16));
        return chunk;
    }
    @SuppressWarnings("unchecked")
    private static void put(Object storage, LevelChunk chunk) throws Exception {
        var method = storage.getClass().getDeclaredMethod("getIndex", int.class, int.class);
        method.setAccessible(true);
        int index = (int)method.invoke(storage, chunk.getPos().x(), chunk.getPos().z());
        ((AtomicReferenceArray<LevelChunk>)field(storage.getClass(), "chunks").get(storage))
                .set(index, chunk);
    }
    private static LongOpenHashSet set(SectionOcclusionGraph graph, String name) throws Exception {
        return (LongOpenHashSet)field(SectionOcclusionGraph.class, name).get(graph);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static <T> T blank(Class<T> type) throws Exception {
        // Isolate source metadata without constructing a client/server. No production code uses uninitialized objects.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object allocator = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, type));
    }
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
}
