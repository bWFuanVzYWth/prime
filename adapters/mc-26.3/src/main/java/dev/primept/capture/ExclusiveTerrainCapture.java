package dev.primept.capture;

import dev.primept.PrimeClient;
import dev.primept.NativeBridge;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** Host event/field router. The native owner determines radius, requests, dependencies and scheduling. */
public final class ExclusiveTerrainCapture implements AutoCloseable {
    private static ExclusiveTerrainCapture current;
    private static boolean vanillaSuspended, sourceFrame;
    private static ClientLevel vanillaSnapshotPending;
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    // Mirrors only native-approved columns for host entity extraction; never chooses a terrain workset.
    private final Long2ObjectLinkedOpenHashMap<LevelChunk> chunks =
            new Long2ObjectLinkedOpenHashMap<>();
    private final BlockEntityIndex blockEntities = new BlockEntityIndex();
    private final IntArrayList events = new IntArrayList();
    private final SourcePages frame = new SourcePages(), response = new SourcePages();
    private SectionSources sources;
    private ClientLevel world;
    private long epoch, lastFrame = -1, routedSections;
    private boolean inventory = true;
    private RuntimeException failure;
    private long dirtyEvents, loadedColumns, unloadedColumns, invalidations, lightEngineEvents,
            lightPacketEvents;
    private Stats stats = Stats.EMPTY;
    public record Stats(long dirty, long entered, long loaded, long unloaded, long invalidations,
                        long selected, long emptyPublished, long emptyRetained, long routed,
                        long deferred, long waiting, long planNanos, long packNanos,
                        long totalNanos, long lightEngineEvents, long lightPacketEvents,
                        long acceptNanos, long sourceBytes) {
        private static final Stats EMPTY =
                new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
    public static int pendingSections() {
        return 0; // Source requests are synchronous; only the Rust owner schedules sections.
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
            sources = new SectionSources(minecraft.getModelManager().getBlockStateModelSet());
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
        frame.header(SectionSources.GAME_VERSION, 1, epoch, serial)
                .d(camera.x)
                .d(camera.z)
                .i(minecraft.options.getEffectiveRenderDistance())
                .i(world.getMinSectionY())
                .i(world.getMaxSectionY())
                .i(bounds.minX())
                .i(bounds.maxX())
                .i(bounds.minZ())
                .i(bounds.maxZ());
        for (int i = 0; i < events.size(); ++i)
            frame.i(events.getInt(i));
        frame.i(0);
        events.clear();
        long planStart = System.nanoTime();
        MemorySegment request = bridge.requestSections(frame);
        long packStart = System.nanoTime();
        long batch = request.get(I64, 0), count = request.get(I64, 8),
             columnCount = request.get(I64, 16);
        long at = 32 + Math.multiplyExact(count, 16);
        long entered = 0;
        for (long i = 0; i < columnCount; ++i, at += 12) {
            int x = request.get(I32, at), z = request.get(I32, at + 4);
            if (request.get(I32, at + 8) != 0) {
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
        response.header(SectionSources.GAME_VERSION, 2, epoch, batch);
        long available = 0;
        for (long i = 0; i < count; ++i) {
            at = 32 + i * 16;
            int x = request.get(I32, at), y = request.get(I32, at + 4),
                z = request.get(I32, at + 8);
            var chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            var section =
                    chunk == null ? null : chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            sources.section(response, x, y, z, section);
            if (section != null)
                ++available;
        }
        response.i(0);
        long packEnd = System.nanoTime();
        bridge.sections(response);
        long completed = System.nanoTime();
        lastFrame = serial;
        routedSections += available;
        stats = new Stats(dirtyEvents, entered, loadedColumns, unloadedColumns, invalidations,
                          count, 0, 0, available, count - available, 0, packStart - planStart,
                          packEnd - packStart, completed - started, lightEngineEvents,
                          lightPacketEvents, completed - packEnd, frame.bytes() + response.bytes());
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
    public static void lightStatusChanged(LevelLightEngine engine, int x, int z) {
        // P007: this raw-state prototype has no light-dependent source callbacks/readiness gate.
    }
    public static void invalidateAll() {
        if (current == null || current.world == null)
            return;
        ++current.invalidations;
        current.sources = new SectionSources(
                Minecraft.getInstance().getModelManager().getBlockStateModelSet());
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
        events.clear();
        chunks.clear();
        blockEntities.clear();
    }
}
