package dev.primept.capture;

import dev.primept.PrimeClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** Actual host dirty/readiness hooks, with no client loop, compiler, GPU or world loading. */
final class TerrainRequestsCpuSmoke {
    @SuppressWarnings("unchecked")
    static void run() throws Exception {
        var constructor = ExclusiveTerrainCapture.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var level = blank(World.class);
        level.loaded = new Long2ObjectOpenHashMap<>();
        level.light = blank(Light.class);
        level.light.enabled = new LongOpenHashSet();
        level.cache = blank(Cache.class);
        level.cache.world = level;
        var prior = field(ExclusiveTerrainCapture.class, "current").get(null);
        boolean suspended =
                field(ExclusiveTerrainCapture.class, "vanillaSuspended").getBoolean(null);
        PrimeClient.CAPTURE.enable();
        long initialSequence = PrimeClient.CAPTURE.seal().completedSequence();
        try (var owner = constructor.newInstance()) {
            field(ExclusiveTerrainCapture.class, "current").set(null, owner);
            field(ExclusiveTerrainCapture.class, "vanillaSuspended").setBoolean(null, true);
            field(ExclusiveTerrainCapture.class, "world").set(owner, level);
            field(ExclusiveTerrainCapture.class, "window")
                    .set(owner, ColumnWindow.centered(0, 0, 16));
            field(ExclusiveTerrainCapture.class, "sourceReadiness")
                    .set(owner, new SectionUpdateTracker(LevelHeightAccessor.create(-64, 384), 0));
            field(ExclusiveTerrainCapture.class, "router")
                    .set(owner, new TerrainRouter(PrimeClient.CAPTURE, null, null, null));
            var chunks = (Long2ObjectLinkedOpenHashMap<LevelChunk>)field(
                                 ExclusiveTerrainCapture.class, "chunks")
                                 .get(owner);
            var observed =
                    (LongOpenHashSet)field(ExclusiveTerrainCapture.class, "observed").get(owner);
            var work = (SectionChanges)field(ExclusiveTerrainCapture.class, "work").get(owner);
            var extractor = blank(LevelExtractor.class);

            // A full radius-16 source window. All sections have content but lighting is incomplete.
            for (int z = -16; z <= 16; ++z)
                for (int x = -16; x <= 16; ++x) {
                    var chunk = chunk(level, x, z);
                    level.loaded.put(ChunkPos.pack(x, z), chunk);
                    chunks.put(ChunkPos.pack(x, z), chunk);
                    for (int y = -4; y <= 19; ++y)
                        work.add(SectionPos.asLong(x, y, z));
                }
            long begin = System.nanoTime();
            owner.prepareSections(false);
            check(work.size() == 0 && work.waitingSize() == 33 * 33 * 24,
                  "Unready first sources remain upstream");
            var blocked = PrimeClient.CAPTURE.seal();
            check(blocked.batches().isEmpty() && blocked.completedSequence() == initialSequence,
                  "No unready source may create a snapshot, model read, token or FFM packet");
            int queries = level.light.queries;
            for (int frame = 0; frame < 120; ++frame)
                owner.prepareSections(false);
            check(level.light.queries == queries && PrimeClient.CAPTURE.seal().batches().isEmpty(),
                  "No event: blocked sources are not polled or exposed downstream every frame");
            long section = SectionPos.asLong(0, 0, 0);
            check(!owner.sourceReady(section),
                  "Existing neighbors without enabled light are not ready");
            for (int z = -1; z <= 1; ++z)
                for (int x = -1; x <= 1; ++x)
                    level.light.setLightEnabled(new ChunkPos(x, z),
                                                true); // Executes production mixin.
            check(owner.sourceReady(section),
                  "Actual vanilla readiness becomes true after light admission");
            check(work.size() == 5 * 5 * 24 && work.waitingSize() == (33 * 33 - 25) * 24,
                  "Readiness events wake only blocked nearby columns, never the whole window");
            work.clear();
            level.light.setLightEnabled(new ChunkPos(0, 0), true);
            check(work.isEmpty(),
                  "A readiness event alone cannot dirty previously observed geometry");

            var missing = level.loaded.remove(ChunkPos.pack(1, 1));
            check(!owner.sourceReady(section),
                  "A diagonal neighbor is required for first compilation");
            observed.add(section);
            check(owner.sourceReady(section), "A published section follows vanilla's rebuild rule");
            observed.remove(section);
            level.loaded.put(ChunkPos.pack(1, 1), missing);
            for (int repeat = 0; repeat < 10000; ++repeat)
                extractor.setSectionDirty(0, 0, 0);
            check(work.size() == 1, "Real host dirty notifications coalesce before routing");
            check(work.seal()[0] == section && work.isEmpty(),
                  "Exactly the actual dirty section is selected");
            extractor.setSectionRangeDirty(-1, 0, -1, 1, 0, 1);
            check(work.size() == 9,
                  "Host neighbor footprint is preserved, with no extra whole-column expansion");
            work.clear();
            ExclusiveTerrainCapture.chunkLoaded(0, 0);
            check(work.isEmpty(),
                  "A repeated packet-availability notification is not a fresh full-column update");

            // Route an actual nonempty snapshot only after host prerequisites pass.
            for (int z = -1; z <= 1; ++z)
                for (int x = -1; x <= 1; ++x)
                    for (int y = 3; y <= 5; ++y)
                        field(LevelChunkSection.class, "nonEmptyBlockCount")
                                .setShort(chunks.get(ChunkPos.pack(x, z)).getSection(y), (short)0);
            var state = Blocks.STONE.defaultBlockState();
            var states = new PalettedContainer<>(
                    Blocks.AIR.defaultBlockState(),
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            states.set(0, 0, 0, state);
            var center = chunks.get(ChunkPos.pack(0, 0)).getSection(4);
            field(LevelChunkSection.class, "states").set(center, states);
            field(LevelChunkSection.class, "nonEmptyBlockCount").setShort(center, (short)1);
            var model = new TerrainRouterCpuSmoke.Model(false);
            var models = new BlockStateModelSet(Map.of(state, model), model);
            field(ExclusiveTerrainCapture.class, "router")
                    .set(owner,
                         new TerrainRouter(PrimeClient.CAPTURE, models, null, new BlockColors()));
            level.allowSnapshot = true;
            extractor.setSectionDirty(0, 0, 0);
            owner.prepareSections(false);
            var admitted = PrimeClient.CAPTURE.seal();
            check(model.calls == 1 && admitted.batches().size() == 3 && observed.contains(section),
                  "A ready nonempty source routes once through the actual region and model");
            var source = ByteBuffer.wrap(admitted.batches().get(1).packets().getFirst())
                                 .order(ByteOrder.LITTLE_ENDIAN);
            check(source.getInt(8) == 12 && source.getInt(64) == 1,
                  "Only the admitted active placement is exposed to FFM");
            owner.prepareSections(false);
            check(model.calls == 1 && PrimeClient.CAPTURE.seal().batches().isEmpty(),
                  "No new host event: a resident nonempty source is not routed again");
            level.allowSnapshot = false;

            // Explicit dirty observations can make a waiting source empty without any neighbor event.
            work.awaitSource(section);
            extractor.setSectionDirty(0, 0, 0);
            check(work.waitingSize() == 0 && work.size() == 1,
                  "A new dirty observation rechecks its actual source");
            var chunk = chunks.get(ChunkPos.pack(0, 0));
            field(LevelChunkSection.class, "nonEmptyBlockCount")
                    .setShort(chunk.getSection(4), (short)0);
            owner.prepareSections(false);
            check(PrimeClient.CAPTURE.seal().batches().size() == 1,
                  "Actual empty completion publishes once");
            extractor.setSectionDirty(0, 0, 0);
            owner.prepareSections(false);
            check(PrimeClient.CAPTURE.seal().batches().isEmpty(),
                  "Retained empty source has zero FFM bytes");

            work.awaitSource(section);
            ExclusiveTerrainCapture.chunkUnloaded(0, 0);
            check(work.size() == 0 && work.waitingSize() == 0,
                  "Unloading cancels waiting state without dirtying resident neighbors");
            level.light.setLightEnabled(new ChunkPos(0, 0), true);
            check(work.isEmpty(), "Late readiness cannot resurrect an unloaded source");
            extractor.setSectionDirty(0, 0, 0);
            check(work.isEmpty(), "Inactive columns never enter the downstream work set");
            work.awaitSource(SectionPos.asLong(16, 0, 0));
            level.light.setLightEnabled(new ChunkPos(17, 0), true);
            check(work.size() == 1 && work.waitingSize() == 0,
                  "A dependency outside the PT window can awaken an active boundary source");
            work.clear();
            long boundary = SectionPos.asLong(16, 0, 0), distant = SectionPos.asLong(-15, 0, 0);
            work.awaitSource(boundary);
            work.awaitSource(distant);
            var oldWindow = ColumnWindow.centered(0, 0, 16);
            work.sourceWindowChanged(oldWindow, oldWindow);
            check(work.isEmpty() && work.waitingSize() == 2,
                  "An unchanged source window cannot wake waiting geometry");
            work.sourceWindowChanged(oldWindow, ColumnWindow.centered(1, 0, 16));
            check(work.size() == 1 && work.waitingSize() == 1 && work.seal()[0] == boundary,
                  "Newly accessible cache slots wake only dependent blocked columns");
            work.clear();
            work.awaitSource(section);
            var foreignLight = blank(Light.class);
            foreignLight.enabled = new LongOpenHashSet();
            foreignLight.setLightEnabled(new ChunkPos(0, 0), true);
            check(work.isEmpty() && work.waitingSize() == 1,
                  "An unrelated light-engine owner cannot wake this renderer's sources");
            work.clear();
            level.light.setLightEnabled(new ChunkPos(0, 0), true);
            check(work.isEmpty() && work.waitingSize() == 0,
                  "Epoch/reset cancels all pending readiness dependencies");
            System.out.println(
                    "PRIME_PT_TERRAIN_REQUESTS_CPU_OK: 26136 blocked first sources=0 packets; 120 idle frames=0 readiness polls; ready source/model=1 publication then idle=0 packets; actual dirty/light hooks; no synthetic neighbor invalidation; source cancellation; elapsedMs=" +
                    (System.nanoTime() - begin) / 1e6);
        } finally {
            field(ExclusiveTerrainCapture.class, "current").set(null, prior);
            field(ExclusiveTerrainCapture.class, "vanillaSuspended").setBoolean(null, suspended);
            PrimeClient.CAPTURE.reset();
        }
    }
    private static final class World extends ClientLevel {
        Long2ObjectOpenHashMap<LevelChunk> loaded;
        Light light;
        Cache cache;
        boolean allowSnapshot;
        World() {
            super(null, null, null, null, 0, 0, null, false, 0L, 0);
        }
        @Override
        public LevelChunk getChunk(int x, int z, ChunkStatus status, boolean create) {
            check(!create, "Readiness cannot load new chunks");
            return loaded.get(ChunkPos.pack(x, z));
        }
        @Override
        public LevelChunk getChunk(int x, int z) {
            check(allowSnapshot, "Blocked sources cannot create a host region snapshot");
            return loaded.get(ChunkPos.pack(x, z));
        }
        @Override
        public net.minecraft.world.level.CardinalLighting cardinalLighting() {
            return null; // Region stores it, but source routing must never evaluate raster shading.
        }
        @Override
        public LevelLightEngine getLightEngine() {
            return light;
        }
        @Override
        public ClientChunkCache getChunkSource() {
            return cache;
        }
        @Override
        public int getMinY() {
            return -64;
        }
        @Override
        public int getHeight() {
            return 384;
        }
    }
    private static final class Cache extends ClientChunkCache {
        World world;
        Cache() {
            super(null, 0);
        }
        @Override
        public LevelChunk getChunk(int x, int z, ChunkStatus status, boolean create) {
            return world.getChunk(x, z, status, create);
        }
    }
    private static final class Light extends LevelLightEngine {
        LongOpenHashSet enabled;
        int queries;
        Light() {
            super(null, false, false);
        }
        @Override
        public boolean lightOnInColumn(long section) {
            ++queries;
            return enabled.contains(ChunkPos.pack(SectionPos.x(section), SectionPos.z(section)));
        }
        @Override
        public void setLightEnabled(ChunkPos pos, boolean value) {
            if (value)
                enabled.add(pos.pack());
            else
                enabled.remove(pos.pack());
            super.setLightEnabled(pos, value);
        }
    }
    private static LevelChunk chunk(ClientLevel level, int x, int z) throws Exception {
        var chunk = blank(LevelChunk.class);
        var sections = new LevelChunkSection[24];
        for (int i = 0; i < sections.length; ++i) {
            sections[i] = blank(LevelChunkSection.class);
            field(LevelChunkSection.class, "nonEmptyBlockCount").setShort(sections[i], (short)1);
        }
        field(ChunkAccess.class, "chunkPos").set(chunk, new ChunkPos(x, z));
        field(ChunkAccess.class, "sections").set(chunk, sections);
        field(ChunkAccess.class, "levelHeightAccessor")
                .set(chunk, LevelHeightAccessor.create(-64, 384));
        field(LevelChunk.class, "level").set(chunk, level);
        field(ChunkAccess.class, "blockEntities").set(chunk, Map.of());
        return chunk;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static <T> T blank(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return type.cast(unsafe.getMethod("allocateInstance", Class.class)
                                 .invoke(field(unsafe, "theUnsafe").get(null), type));
    }
    private static void check(boolean ok, String message) {
        if (!ok)
            throw new AssertionError(message);
    }
}
