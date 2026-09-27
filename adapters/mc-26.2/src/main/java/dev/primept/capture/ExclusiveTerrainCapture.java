package dev.primept.capture;

import dev.primept.PrimeClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Sole PT terrain producer. Reuses the actual MC compiler without a raster dispatcher or GPU mesh. */
public final class ExclusiveTerrainCapture implements AutoCloseable {
    private static ExclusiveTerrainCapture current;
    private static boolean vanillaSuspended;
    private static boolean sourceFrame;
    private static ClientLevel vanillaSnapshotPending;
    private final SectionChanges work = new SectionChanges();
    private final Long2ObjectLinkedOpenHashMap<LevelChunk> chunks =
            new Long2ObjectLinkedOpenHashMap<>();
    private final LongOpenHashSet compiled = new LongOpenHashSet();
    private final LongOpenHashSet compiledEmpty = new LongOpenHashSet();
    private final BlockEntityIndex blockEntities = new BlockEntityIndex();
    private SynchronousWorkers<CompilerWorkspace> workers;
    private ClientLevel world;
    private record CompilerWorkspace(SectionCompiler compiler, SectionBufferBuilderPack buffers,
                                     SourceQuads source) implements AutoCloseable {
        public void close() {
            buffers.close();
        }
    }
    private record
            Compilation(long key, net.minecraft.client.renderer.chunk.RenderSectionRegion region) {}
    private int centerX = Integer.MIN_VALUE, centerZ, radius = -1;
    private long epoch;
    private RuntimeException failure;
    private long compilations;
    private boolean cacheWindowChanged;
    private ColumnWindow window;
    private static final boolean PROFILE = Boolean.getBoolean("primept.profile");
    private static final boolean KNOWN_EMPTY =
            BlockEntityCandidates.known(SectionCompiler.class) &&
            BlockEntityCandidates.known(RenderRegionCache.class) &&
            BlockEntityCandidates.known(
                    net.minecraft.client.renderer.chunk.RenderSectionRegion.class) &&
            BlockEntityCandidates.known(net.minecraft.client.renderer.chunk.SectionCopy.class) &&
            BlockEntityCandidates.known(net.minecraft.world.level.chunk.LevelChunkSection.class) &&
            BlockEntityCandidates.known(LevelChunk.class) &&
            BlockEntityCandidates.known(net.minecraft.world.level.chunk.ChunkAccess.class);
    private Stats stats = Stats.EMPTY;
    public record Stats(long dirty, long entered, long loaded, long unloaded, long invalidations,
                        long selected, long emptyPublished, long emptyRetained, long compiled,
                        long snapshotNanos, long compileNanos, long totalNanos) {
        private static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    private long dirtyEvents, enteredColumns, loadedColumns, unloadedColumns, invalidations;
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
    public static long compilations() {
        return current == null ? 0 : current.compilations;
    }
    public static int pendingSections() {
        return current == null ? 0 : current.work.size();
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

    public static void prepareFrame(CameraRenderState camera) {
        if (current == null || current.failure != null || !PrimeClient.exclusiveFrameReady())
            return;
        try {
            current.prepare(camera);
        } catch (RuntimeException exception) {
            current.failure = exception;
            PrimeClient.LOGGER.error("Exclusive terrain stopped after a source compilation failure",
                                     exception);
        }
    }
    private void prepare(CameraRenderState camera) {
        synchronizeWindow(camera.pos);
        if (world == null)
            return;
        prepareSections(Minecraft.getInstance().options.cutoutLeaves().get(), world.isDebug());
    }
    /** Owner snapshots; workers finish the entire sealed batch before returning. */
    void prepareSections(boolean cutout, boolean debugWorld) {
        long prepareStart = PROFILE ? System.nanoTime() : 0;
        long[] sealed = work.seal();
        long emptyPublished = 0, emptyRetained = 0, compiledCount = 0, snapshotTime = 0,
             compileTime = 0;
        // Snapshot bounded waves on the owner, then join each wave. All sealed work
        // finishes in this call; a wave is workspace sizing, never a frame quota.
        for (int first = 0; first < sealed.length; first += 256) {
            long snapshotStart = PROFILE ? System.nanoTime() : 0;
            var inputs = new java.util.ArrayList<Compilation>();
            var regions = new RenderRegionCache();
            for (int i = first; i < Math.min(sealed.length, first + 256); i++) {
                long key = sealed[i];
                var chunk = chunks.get(ChunkPos.pack(SectionPos.x(key), SectionPos.z(key)));
                if (chunk == null)
                    continue;
                if (knownEmpty(chunk, key, debugWorld)) {
                    if (compiledEmpty.add(key)) {
                        try (var capture = TerrainCapture.open(PrimeClient.CAPTURE,
                                                               SectionPos.of(key), cutout)) {
                            capture.publish();
                        }
                        compiled.add(key);
                        ++emptyPublished;
                    } else {
                        ++emptyRetained;
                    }
                    continue;
                }
                compiledEmpty.remove(key);
                inputs.add(new Compilation(key, regions.createRegion(world, key)));
            }
            if (PROFILE)
                snapshotTime += System.nanoTime() - snapshotStart;
            long compileStart = PROFILE ? System.nanoTime() : 0;
            workers.run(inputs.size(), 8, (workspace, start, end) -> {
                for (int i = start; i < end; i++) {
                    var input = inputs.get(i);
                    if (input.region == null) {
                        try (var capture = TerrainCapture.open(PrimeClient.CAPTURE,
                                                               SectionPos.of(input.key), cutout)) {
                            capture.publish();
                        }
                    } else {
                        boolean success = false;
                        try (var output = TerrainRasterOutput.open(workspace.source)) {
                            var result = workspace.compiler.compile(SectionPos.of(input.key),
                                                                    input.region, output.sorting,
                                                                    workspace.buffers);
                            result.release();
                            success = true;
                        } finally {
                            if (success)
                                workspace.buffers.clearAll();
                            else
                                workspace.buffers.discardAll();
                        }
                    }
                }
            });
            if (PROFILE)
                compileTime += System.nanoTime() - compileStart;
            compiledCount += inputs.size();
            for (var input : inputs) {
                compiled.add(input.key);
                ++compilations;
            }
            if (PrimeClient.CAPTURE.failure() != null)
                throw PrimeClient.CAPTURE.failure();
        }
        if (PROFILE) {
            stats = new Stats(dirtyEvents, enteredColumns, loadedColumns, unloadedColumns,
                              invalidations, sealed.length, emptyPublished, emptyRetained,
                              compiledCount, snapshotTime, compileTime,
                              System.nanoTime() - prepareStart);
            dirtyEvents = enteredColumns = loadedColumns = unloadedColumns = invalidations = 0;
        }
    }
    static boolean knownEmpty(LevelChunk chunk, long key, boolean debugWorld) {
        // RenderRegionCache in these versions always returns a region, even for air. Debug worlds
        // synthesize blocks in SectionCopy; unknown compiler/region hooks retain their actual callbacks.
        return KNOWN_EMPTY && !debugWorld && chunk.getClass() == LevelChunk.class &&
                chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).hasOnlyAir();
    }
    /** Runs before BE extraction so first-frame and moved-window callbacks see the current loaded set. */
    public static void prepareWindow(net.minecraft.world.phys.Vec3 camera) {
        if (current == null || current.failure != null)
            return;
        try {
            current.synchronizeWindow(camera);
        } catch (RuntimeException exception) {
            current.failure = exception;
        }
    }
    private void synchronizeWindow(net.minecraft.world.phys.Vec3 camera) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        if (world != minecraft.level || epoch != PrimeClient.CAPTURE.epoch()) {
            world = minecraft.level;
            epoch = PrimeClient.CAPTURE.epoch();
            work.clear();
            chunks.clear();
            blockEntities.clear();
            compiled.clear();
            compiledEmpty.clear();
            centerX = Integer.MIN_VALUE;
            window = null;
            configureCompiler();
        }
        int x = SectionPos.blockToSectionCoord(camera.x),
            z = SectionPos.blockToSectionCoord(camera.z);
        int distance = minecraft.options.getEffectiveRenderDistance();
        if (cacheWindowChanged || x != centerX || z != centerZ || distance != radius)
            updateWindow(x, z, distance);
    }
    private void configureCompiler() {
        var minecraft = Minecraft.getInstance();
        net.minecraft.world.level.block.LeavesBlock.setCutoutLeaves(
                minecraft.options.cutoutLeaves().get());
        if (workers != null)
            workers.close();
        boolean ao = minecraft.options.ambientOcclusion().get(),
                cutout = minecraft.options.cutoutLeaves().get();
        var blocks = minecraft.getModelManager().getBlockStateModelSet();
        var fluids = minecraft.getModelManager().getFluidStateModelSet();
        var colors = minecraft.getBlockColors();
        int threads = Integer.getInteger("primept.compilerThreads",
                                         Math.min(8, Runtime.getRuntime().availableProcessors()));
        workers = new SynchronousWorkers<>(
                threads,
                ignored
                -> new CompilerWorkspace(new SectionCompiler(ao, cutout, blocks, fluids, colors),
                                         new SectionBufferBuilderPack(), new SourceQuads()));
    }
    private void updateWindow(int x, int z, int distance) {
        var next = ColumnWindow.centered(x, z, distance);
        var source = ((LoadedTerrainSnapshot)world.getChunkSource()).primept$sourceWindow();
        if (source != null)
            next = next.intersect(source);
        var previous = window;
        window = next;
        centerX = x;
        centerZ = z;
        radius = distance;
        if (previous != null)
            previous.difference(next, (columnX, columnZ) -> {
                if (chunks.remove(ChunkPos.pack(columnX, columnZ)) != null)
                    retireChunk(columnX, columnZ);
            });
        next.difference(previous, this::visitColumn);
        cacheWindowChanged = false;
    }
    private void visitColumn(int x, int z) {
        long key = ChunkPos.pack(x, z);
        if (chunks.containsKey(key))
            return;
        var chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk != null) {
            if (PROFILE)
                ++enteredColumns;
            chunks.put(key, chunk);
            blockEntities.add(chunk);
            dirtyChunkContents(chunk, false);
        }
    }
    private boolean inside(int x, int z) {
        return window != null && window.contains(x, z);
    }
    private void dirtyChunkContents(LevelChunk chunk, boolean includeEmpty) {
        var pos = chunk.getPos();
        for (int y = world.getMinSectionY(); y <= world.getMaxSectionY(); ++y) {
            long key = SectionPos.asLong(pos.x(), y, pos.z());
            // An observed empty compile is a complete source snapshot, not a missing section.
            // Capture does not know which snapshots the native translator will batch together.
            if (!compiled.contains(key) ||
                !chunk.getSection(y - world.getMinSectionY()).hasOnlyAir() || includeEmpty)
                work.add(key);
        }
    }
    private void retireChunk(int x, int z) {
        forgetColumn(x, z);
        PrimeClient.CAPTURE.dropChunk(x, z);
    }
    private void forgetColumn(int x, int z) {
        if (PROFILE)
            ++unloadedColumns;
        blockEntities.remove(x, z);
        if (world == null)
            return;
        work.removeChunk(x, z, world.getMinSectionY(), world.getMaxSectionY());
        for (int y = world.getMinSectionY(); y <= world.getMaxSectionY(); ++y) {
            long key = SectionPos.asLong(x, y, z);
            compiled.remove(key);
            compiledEmpty.remove(key);
        }
    }
    public static void dirtySection(int x, int y, int z) {
        if (current == null || current.failure != null || current.world == null)
            return;
        if (y < current.world.getMinSectionY() || y > current.world.getMaxSectionY() ||
            !current.inside(x, z))
            return;
        if (current.chunks.containsKey(ChunkPos.pack(x, z))) {
            if (PROFILE)
                ++current.dirtyEvents;
            current.work.add(SectionPos.asLong(x, y, z));
        }
    }
    public static void chunkLoaded(int x, int z) {
        if (current == null || current.world == null || !current.inside(x, z))
            return;
        var chunk = current.world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null)
            return;
        if (PROFILE)
            ++current.loadedColumns;
        current.chunks.put(ChunkPos.pack(x, z), chunk);
        current.blockEntities.add(chunk);
        current.dirtyChunkContents(chunk, true);
        // A changed neighbor alters culling/fluid geometry on the bordering column.
        for (int dz = -1; dz <= 1; ++dz)
            for (int dx = -1; dx <= 1; ++dx) {
                var neighbor = current.chunks.get(ChunkPos.pack(x + dx, z + dz));
                if (neighbor != null && (dx != 0 || dz != 0))
                    current.dirtyChunkContents(neighbor, false);
            }
    }
    public static boolean ownsLevel(Object level) {
        return current != null && current.world == level;
    }
    public static void cacheWindowChanged() {
        if (current != null)
            current.cacheWindowChanged = true;
    }
    public static void chunkUnloaded(int x, int z) {
        if (current == null)
            return;
        current.chunks.remove(ChunkPos.pack(x, z));
        current.forgetColumn(x, z);
        for (int dz = -1; dz <= 1; ++dz)
            for (int dx = -1; dx <= 1; ++dx) {
                var neighbor = current.chunks.get(ChunkPos.pack(x + dx, z + dz));
                if (neighbor != null)
                    current.dirtyChunkContents(neighbor, false);
            }
    }
    public static void invalidateAll() {
        if (current == null || current.world == null)
            return;
        if (PROFILE)
            ++current.invalidations;
        current.compiledEmpty.clear();
        current.configureCompiler();
        for (var chunk : current.chunks.values())
            current.dirtyChunkContents(chunk, true);
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
        if (current == null || current.world == null)
            return false;
        var chunk = current.chunks.get(ChunkPos.pack(SectionPos.x(section), SectionPos.z(section)));
        int index = SectionPos.y(section) - current.world.getMinSectionY();
        return chunk != null && (current.compiled.contains(section) ||
                                 index >= 0 && index < chunk.getSections().length &&
                                         chunk.getSection(index).hasOnlyAir());
    }
    @Override
    public void close() {
        if (workers != null)
            workers.close();
        work.clear();
        chunks.clear();
        blockEntities.clear();
        compiled.clear();
        compiledEmpty.clear();
    }
}
