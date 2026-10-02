package dev.primept.capture;

import dev.primept.PrimeClient;
import dev.primept.NativeBridge;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import java.lang.foreign.MemorySegment;
import dev.primept.abi.PrimeAbi.*;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Host event/field router. The native owner determines radius, requests, dependencies and scheduling. */
public final class ExclusiveTerrainCapture implements AutoCloseable {
    private static ExclusiveTerrainCapture current;
    // Resource ownership follows the native session, not the live/offline source producer.
    private static NativeBridge resourceBridge;
    private static SectionSources resourceSources;
    private static long preparedGeneration;
    private static boolean vanillaSuspended, sourceFrame;
    private static ClientLevel vanillaSnapshotPending;
    // Mirrors only native-approved columns for host entity extraction; never chooses a terrain workset.
    private final Long2ObjectLinkedOpenHashMap<LevelChunk> chunks =
            new Long2ObjectLinkedOpenHashMap<>();
    private final BlockEntityIndex blockEntities = new BlockEntityIndex();
    private final IntArrayList events = new IntArrayList();
    private final McSourceBatch frame = new McSourceBatch(), response = new McSourceBatch(),
                                resources = new McSourceBatch();
    private long resourceGeneration;
    private SectionSources sources;
    private ClientLevel world;
    private long epoch, lastFrame = -1, routedSections;
    private boolean inventory = true;
    private RuntimeException failure;
    private long dirtyEvents, loadedColumns, unloadedColumns, invalidations, lightEngineEvents,
            lightPacketEvents;
    private Stats stats = Stats.EMPTY;
    /** Host source responses only: requested = available + missing, independently of native build backlog. */
    public record
            Stats(long dirty, long entered, long loaded, long unloaded, long invalidations,
                  long requested, long available, long missing, long planNanos, long packNanos,
                  long totalNanos, long lightEngineEvents, long lightPacketEvents, long acceptNanos,
                  long sourceBytes, long tintQueries, long tintNanos) {
        private static final Stats EMPTY =
                new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    public enum LightNotification { ENGINE, PACKET }
    public static void lightNotification(Object level, LightNotification kind) {
        if (current == null || current.world != level)
            return;
        if (kind == LightNotification.ENGINE)
            ++current.lightEngineEvents;
        else
            ++current.lightPacketEvents;
    }
    public static Stats takeStats() {
        if (current == null)
            return Stats.EMPTY;
        var result = current.stats;
        current.stats = Stats.EMPTY;
        return result;
    }
    private ExclusiveTerrainCapture() {}
    public static boolean active() {
        return current != null;
    }
    public static boolean vanillaSuspended() {
        return vanillaSuspended;
    }
    public static boolean sourceFrame() {
        return sourceFrame;
    }
    public static void beginSourceFrame() {
        sourceFrame = true;
    }
    public static void endSourceFrame() {
        sourceFrame = false;
    }
    public static RuntimeException failure() {
        return current == null ? null : current.failure;
    }
    public static long routedSections() {
        return current == null ? 0 : current.routedSections;
    }

    /** Caller has stopped vanilla submissions and proved their host GPU completion. */
    public static void suspendVanilla() {
        vanillaSnapshotPending = null;
        vanillaSuspended = true;
        Minecraft.getInstance().levelRenderer.resetLevelRenderData();
        ((ExclusiveRenderBuffers)Minecraft.getInstance().gameRenderer.renderBuffers())
                .primept$retireTerrainBuffers();
        ((dev.primept.capture.ExclusiveLevelExtractorAccess)Minecraft.getInstance().levelExtractor)
                .primept$discardTerrainTracker();
    }
    public static void acquire() {
        if (current != null)
            throw new IllegalStateException("Exclusive terrain is already owned");
        if (!vanillaSuspended)
            throw new IllegalStateException("Vanilla terrain must retire before PT acquisition");
        current = new ExclusiveTerrainCapture();
    }
    public static void release() {
        if (current != null) {
            current.close();
            current = null;
        }
    }
    public static void releaseResourceSources() {
        resourceBridge = null;
        resourceSources = null;
        preparedGeneration = 0;
    }
    /** Called only after the previous PT backend and its source producer have closed successfully. */
    public static void resumeVanilla() {
        if (current != null)
            throw new IllegalStateException("Cannot restore vanilla while PT terrain is owned");
        ((ExclusiveRenderBuffers)Minecraft.getInstance().gameRenderer.renderBuffers())
                .primept$restoreTerrainBuffers();
        vanillaSuspended = false;
        Minecraft.getInstance().levelExtractor.allChanged();
        vanillaSnapshotPending = Minecraft.getInstance().level;
    }
    /** The former graph was destroyed and PT consumed the cache journal; seed the new owner once. */
    public static void
    restoreVanillaSnapshot(ClientLevel level,
                           net.minecraft.client.renderer.SectionOcclusionGraph graph) {
        if (vanillaSuspended || level != vanillaSnapshotPending || level == null)
            return;
        ((LoadedTerrainSnapshot)level.getChunkSource()).primept$restoreTerrainSnapshot(graph);
        vanillaSnapshotPending = null;
    }

    /** Separate resource transaction, completed before any section demand is issued. */
    public static void prepareResources(NativeBridge bridge) {
        if (current == null)
            return;
        var minecraft = Minecraft.getInstance();
        var models = minecraft.getModelManager().getBlockStateModelSet();
        var fluids = minecraft.getModelManager().getFluidStateModelSet();
        long generation = PrimeClient.CAPTURE.resourceGeneration(models, fluids);
        if (resourceBridge == bridge && preparedGeneration == generation) {
            current.sources = resourceSources;
            current.resourceGeneration = generation;
            return;
        }
        var next = new SectionSources(models, fluids);
        current.resources.clear();
        next.prepareResources(current.resources);
        var atlas = PrimeClient.CAPTURE.atlas();
        long tick = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        bridge.resources(current.resources.resources(SectionSources.GAME_VERSION, generation,
                                                     PrimeClient.CAPTURE.epoch(), 0, tick, true,
                                                     atlas.width(), atlas.height(), atlas.rgba()));
        // Native replacement revokes all old geometry even when only model identity changed.
        // Recreate prototype/instance definitions and resend needed dynamic pixels in this epoch.
        DynamicCapture.close();
        DynamicTextures.invalidatePublished();
        BlockGeometryCache.resourceReload();
        current.sources = next;
        current.resourceGeneration = generation;
        resourceBridge = bridge;
        resourceSources = next;
        preparedGeneration = generation;
        PrimeClient.prepareResourceGeneration();
    }
    public static void prepareWindow(net.minecraft.world.phys.Vec3 camera) {
        if (current == null || current.failure != null || !PrimeClient.exclusiveFrameReady())
            return;
        try {
            current.route(camera);
        } catch (RuntimeException exception) {
            current.failure = exception;
            PrimeClient.LOGGER.error("Native section source routing failed", exception);
        }
    }
    public static void prepareFrame(CameraRenderState camera) {
        prepareWindow(camera.pos);
    }
    private void route(net.minecraft.world.phys.Vec3 camera) {
        long serial = PrimeClient.sourceFrameSequence();
        if (lastFrame == serial)
            return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        long started = System.nanoTime();
        NativeBridge bridge = PrimeClient.prepareNativeSources();
        if (bridge == null)
            return;
        if (world != minecraft.level || epoch != PrimeClient.CAPTURE.epoch()) {
            world = minecraft.level;
            epoch = PrimeClient.CAPTURE.epoch();
            events.clear();
            chunks.clear();
            blockEntities.clear();
            inventory = true;
        }
        var access = (LoadedTerrainSnapshot)world.getChunkSource();
        if (inventory) {
            event(5, 0, 0, 0);
            access.primept$visitLoaded(
                    chunk -> event(1, chunk.getPos().x(), 0, chunk.getPos().z()));
            inventory = false;
        }
        var bounds = access.primept$sourceWindow();
        if (bounds == null)
            throw new IllegalStateException("Missing host source-cache bounds");
        frame.clear();
        for (int i = 0; i < events.size(); i += 4) {
            var value = frame.events.add();
            PrimeMcEvent.kind(value, events.getInt(i));
            PrimeMcEvent.x(value, events.getInt(i + 1));
            PrimeMcEvent.y(value, events.getInt(i + 2));
            PrimeMcEvent.z(value, events.getInt(i + 3));
        }
        events.clear();
        var plan =
                frame.plan(SectionSources.GAME_VERSION, resourceGeneration, epoch, serial, camera.x,
                           camera.z, minecraft.options.getEffectiveRenderDistance(),
                           world.getMinSectionY(), world.getMaxSectionY(),
                           new int[] {bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ()},
                           world.getGameTime());
        long planStart = System.nanoTime();
        MemorySegment request = bridge.requestSections(plan);
        long packStart = System.nanoTime();
        long batch = PrimeMcIdentity.batch(PrimeMcRequests.identity(request));
        long count = PrimeMcRequests.section_count(request),
             columnCount = PrimeMcRequests.column_count(request);
        var columns = PrimeMcRequests.columns(request).reinterpret(
                Math.multiplyExact(columnCount, PrimeMcColumnRequest.SIZE));
        var sections = PrimeMcRequests.sections(request).reinterpret(
                Math.multiplyExact(count, PrimeMcSectionRequest.SIZE));
        long entered = 0;
        for (long i = 0; i < columnCount; ++i) {
            var item = columns.asSlice(i * PrimeMcColumnRequest.SIZE, PrimeMcColumnRequest.SIZE);
            int x = PrimeMcColumnRequest.x(item), z = PrimeMcColumnRequest.z(item);
            if (PrimeMcColumnRequest.active(item) != 0) {
                var chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    chunks.put(ChunkPos.pack(x, z), chunk);
                    blockEntities.add(chunk);
                    ++entered;
                }
            } else {
                chunks.remove(ChunkPos.pack(x, z));
                blockEntities.remove(x, z);
            }
        }
        response.clear();
        resources.clear();
        long available = 0;
        for (long i = 0; i < count; ++i) {
            var item = sections.asSlice(i * PrimeMcSectionRequest.SIZE, PrimeMcSectionRequest.SIZE);
            int x = PrimeMcSectionRequest.x(item), y = PrimeMcSectionRequest.y(item),
                z = PrimeMcSectionRequest.z(item);
            var chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            var section =
                    chunk == null ? null : chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            sources.section(resources, response, x, y, z, section);
            if (section != null)
                ++available;
        }
        if (resources.hasResources()) {
            bridge.resources(resources.resources(SectionSources.GAME_VERSION, resourceGeneration,
                                                 epoch, batch, world.getGameTime(), false, 0, 0,
                                                 null));
            if (resources.sprites.count() != 0)
                PrimeClient.prepareResourceGeneration();
        }
        long packEnd = System.nanoTime();
        long sourceBytes = response.bytes() + resources.bytes();
        var tints = bridge.sections(
                response.sections(SectionSources.GAME_VERSION, resourceGeneration, epoch, batch));
        long tintQueries = 0, tintNanos = 0;
        for (int round = 0; PrimeMcRequests.phase(tints) != 0; ++round) {
            if (round >= 2)
                throw new IllegalStateException("Unexpected color continuation");
            int phase = PrimeMcRequests.phase(tints);
            tintQueries += phase == 4 ? PrimeMcRequests.biome_count(tints)
                                      : PrimeMcRequests.color_count(tints);
            long tintStart = System.nanoTime();
            var reply = SectionTints.respond(tints, response, world, minecraft.getBlockColors(),
                                             minecraft.getModelManager().getFluidStateModelSet(),
                                             world);
            tintNanos += System.nanoTime() - tintStart;
            sourceBytes += response.bytes();
            tints = phase == 4 ? bridge.biomes(reply) : bridge.colors(reply);
        }
        long completed = System.nanoTime();
        lastFrame = serial;
        routedSections += available;
        stats = new Stats(dirtyEvents, entered, loadedColumns, unloadedColumns, invalidations,
                          count, available, count - available, packStart - planStart,
                          packEnd - packStart, completed - started, lightEngineEvents,
                          lightPacketEvents, completed - packEnd - tintNanos,
                          frame.bytes() + sourceBytes, tintQueries, tintNanos);
        dirtyEvents = loadedColumns = unloadedColumns = invalidations = lightEngineEvents =
                lightPacketEvents = 0;
    }
    private void event(int kind, int x, int y, int z) {
        events.add(kind);
        events.add(x);
        events.add(y);
        events.add(z);
    }
    public static void dirtySection(int x, int y, int z) {
        if (current == null || current.world == null || current.failure != null)
            return;
        ++current.dirtyEvents;
        current.event(3, x, y, z);
    }
    public static void tintChanged(int x, int z, boolean all) {
        if (current != null && current.world != null && current.failure == null)
            current.event(all ? 7 : 6, x, 0, z);
    }
    public static void chunkLoaded(int x, int z) {
        if (current == null || current.world == null)
            return;
        ++current.loadedColumns;
        current.event(1, x, 0, z);
        long key = ChunkPos.pack(x, z);
        if (current.chunks.containsKey(key)) {
            var chunk = current.world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) {
                current.chunks.put(key, chunk);
                current.blockEntities.add(chunk);
            }
        }
    }
    public static void chunkUnloaded(int x, int z) {
        if (current == null || current.world == null)
            return;
        ++current.unloadedColumns;
        current.event(2, x, 0, z);
        current.chunks.remove(ChunkPos.pack(x, z));
        current.blockEntities.remove(x, z);
    }
    public static boolean ownsLevel(Object level) {
        return current != null && current.world == level;
    }
    public static void cacheWindowChanged() {
        if (current != null)
            current.inventory = true;
    }
    public static void invalidateAll() {
        if (current == null || current.world == null)
            return;
        ++current.invalidations;
        current.event(4, 0, 0, 0);
    }
    public static void blockEntitiesChanged(LevelChunk chunk) {
        if (current != null)
            current.blockEntities.changed(chunk);
    }
    public static Iterable<? extends Iterable<net.minecraft.world.level.block.entity.BlockEntity>>
    blockEntityCandidates(
            BlockEntityCandidates gate,
            net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher,
            long epoch, net.minecraft.world.phys.Vec3 camera) {
        return current == null ? java.util.List.of()
                               : current.blockEntities.select(gate, dispatcher, epoch, camera);
    }
    public static Iterable<LevelChunk> loadedChunks() {
        return current == null ? java.util.List.of() : current.chunks.values();
    }
    public static LongCollection expectedChunks() {
        return current == null ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET
                               : current.chunks.keySet();
    }
    public static boolean sourceSectionReady(long section) {
        return current != null && current.world != null &&
                SectionPos.y(section) >= current.world.getMinSectionY() &&
                SectionPos.y(section) <= current.world.getMaxSectionY() &&
                current.chunks.containsKey(
                        ChunkPos.pack(SectionPos.x(section), SectionPos.z(section)));
    }
    @Override
    public void close() {
        frame.close();
        response.close();
        resources.close();
        events.clear();
        chunks.clear();
        blockEntities.clear();
    }
}
