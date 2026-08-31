package dev.prime.render.runtime.terrain;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.terrain.*;
import dev.prime.render.scene.vanilla.VanillaClusterCompiler;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.scene.vanilla.DynamicSceneMotion;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.terrain.ClusterStagingLayout;
import dev.prime.render.vulkan.terrain.TerrainScene;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;

/**
 * Owns the terrain portion of Prime's scene independently from vanilla's raster renderer.
 *
 * <p>This is the sole authority for which virtual clusters Prime wants, when they become dirty,
 * which generation is current, and how long uploaded geometry remains resident. One aligned
 * 4x4x4 logical cluster replaces its 64 Sections atomically. Its ordinary geometry owns one BLAS;
 * optional experimental detail meshes add reusable BLASes and per-face TLAS instances. Its CPU
 * build input may use any number of storage segments. In
 * particular, this scheduler must never depend on {@code LevelRenderer.visibleSections()}, the
 * occlusion graph, or vanilla's raster compilation queue: those are presentation decisions and can
 * omit geometry that still contributes to ray-traced visibility and global illumination.
 *
 * <p>Independence ends at mesh semantics. Once this class has selected the stable 6x6x6 snapshot
 * neighborhood around a cluster, each of its 64 inner Sections is delegated to
 * {@link VanillaClusterCompiler}. Prime does not maintain a second block/fluid mesher and does
 * not merge geometry captured from vanilla's raster tasks.
 */
public final class TerrainStreamer implements AutoCloseable {
    private static final long[] EMPTY_EVICTIONS = new long[0];
    // A normal frame targets one reusable staging page. An oversized atomic replacement obtains a
    // correspondingly sized transient page instead of being rejected by a content policy.
    private static final long TARGET_UPLOAD_BYTES_PER_FRAME = StagingArena.PAGE_SIZE;
    private static final int MAX_UNLOADED_PROBES_PER_FRAME = 64;
    private static final int MAX_EXTERNAL_DIRTY_CLUSTERS = 16_384;
    private final TerrainScene scene;
    private final VanillaClusterCompiler clusterCompiler;
    private final boolean opacityMicromapSupported;
    private final int maxOpacity2StateSubdivisionLevel;
    private final int maxOpacity4StateSubdivisionLevel;
    private final int segmentTriangleTarget;
    private final Executor workers;
    private final int maximumWorkerThreads;
    private final int inFlightCapacity;
    // Workers publish one immutable result per accepted job. The render-thread-owned count is
    // never reset across worlds, so the fixed queue remains a proof-backed bound during churn.
    private final ArrayBlockingQueue<CompletedCluster> completed;
    private final ResourceEpochCoordinator resourceEpoch = new ResourceEpochCoordinator();
    private final BoundedDirtyClusters externalDirty =
            new BoundedDirtyClusters(MAX_EXTERNAL_DIRTY_CLUSTERS);
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet();
    private final LongOpenHashSet pendingEvictions = new LongOpenHashSet();
    private final ClusterGenerationTracker generations = new ClusterGenerationTracker();
    private final ClusterPipelineState pipelineState = new ClusterPipelineState();
    private final PriorityQueue<ClusterRequest> requests = new PriorityQueue<>(Comparator
            .comparingInt(ClusterRequest::priority)
            .thenComparingLong(ClusterRequest::distanceSquared)
            .thenComparingLong(ClusterRequest::key));
    private final ArrayDeque<CompletedCluster> readyForUpload = new ArrayDeque<>();
    private final ArrayList<CompiledCluster> uploadBatch = new ArrayList<>();
    private final ArrayList<Long> uploadResourceGenerations = new ArrayList<>();
    // Render-thread-owned: compatibility reports never introduce worker synchronization.
    private final Set<CompatibilityReportKey> reportedCompatibility = new HashSet<>();
    private final ArrayList<ClusterRequest> unloadedRequests =
            new ArrayList<>(MAX_UNLOADED_PROBES_PER_FRAME);
    private final ArrayList<ClusterRequest> blockedRequests =
            new ArrayList<>(MAX_UNLOADED_PROBES_PER_FRAME);

    private ClientLevel world;
    private int centerSectionX = Integer.MIN_VALUE;
    private int centerSectionY = Integer.MIN_VALUE;
    private int centerSectionZ = Integer.MIN_VALUE;
    private int renderDistance = -1;
    private int minimumSectionY;
    private int maximumSectionY;
    private LabPbrMaterialSet labPbrMaterials = LabPbrMaterialSet.EMPTY;
    private LabPbrMaterialSet translatedLabPbrMaterials = LabPbrMaterialSet.EMPTY;
    private SurfaceDetailMode surfaceDetailMode = SurfaceDetailMode.DEFAULT;
    private int voxelSurfaceStrengthSteps = VoxelSurfaceSettings.DEFAULT_STEPS;
    private int workerPercentage = TerrainWorkerSettings.DEFAULT_PERCENTAGE;
    private int workerJobs;
    private boolean discardResidentMaterialGeneration;
    private long reportedResourceGeneration = Long.MIN_VALUE;

    public TerrainStreamer(VulkanContext context, StagingArena stagingArena) {
        this.scene = new TerrainScene(context, stagingArena);
        this.opacityMicromapSupported = context.capabilities().opacityMicromapSupported();
        this.maxOpacity2StateSubdivisionLevel =
                context.capabilities().maxOpacity2StateSubdivisionLevel();
        this.maxOpacity4StateSubdivisionLevel =
                context.capabilities().maxOpacity4StateSubdivisionLevel();
        this.segmentTriangleTarget = TerrainMemoryBudget.segmentTriangleTarget(
                context.capabilities().maxAccelerationStructurePrimitiveCount());
        this.clusterCompiler = new VanillaClusterCompiler();
        // Prime shares vanilla's work-stealing pool, but admission stays below its full configured
        // capacity so scene translation cannot occupy every worker needed by world loading.
        this.workers = Util.backgroundExecutor();
        this.maximumWorkerThreads = Math.max(1, Util.maxAllowedExecutorThreads());
        this.inFlightCapacity = TerrainMemoryBudget.maximumInFlight(
                this.maximumWorkerThreads,
                Runtime.getRuntime().maxMemory());
        this.completed = new ArrayBlockingQueue<>(this.inFlightCapacity);
    }

    public void update(Minecraft minecraft, double cameraX, double cameraY, double cameraZ) {
        ClientLevel currentWorld = minecraft.level;
        if (currentWorld == null || minecraft.player == null) {
            if (this.world != null) {
                this.clearWorld(cameraX, cameraY, cameraZ);
            }
            return;
        }
        if (this.world != currentWorld) {
            this.clearWorld(cameraX, cameraY, cameraZ);
            this.world = currentWorld;
            this.externalDirty.invalidateAll();
        }

        int playerSectionX = (int) Math.floor(cameraX) >> 4;
        int playerSectionY = (int) Math.floor(cameraY) >> 4;
        int playerSectionZ = (int) Math.floor(cameraZ) >> 4;
        int previousCenterX = this.centerSectionX;
        int previousCenterY = this.centerSectionY;
        int previousCenterZ = this.centerSectionZ;
        int viewDistance = ViewDistanceLimits.primeDistance(
                minecraft.options.getEffectiveRenderDistance(), minecraft.isLocalServer());
        int minSectionY = currentWorld.getMinY() >> 4;
        int maxSectionY = currentWorld.getMinY() + currentWorld.getHeight() - 1 >> 4;
        if (playerSectionX != this.centerSectionX
                || playerSectionY != this.centerSectionY
                || playerSectionZ != this.centerSectionZ
                || viewDistance != this.renderDistance
                || minSectionY != this.minimumSectionY
                || maxSectionY != this.maximumSectionY) {
            this.synchronizeWindow(
                    playerSectionX,
                    playerSectionY,
                    playerSectionZ,
                    viewDistance,
                    minSectionY,
                    maxSectionY);
            if (this.surfaceDetailMode.usesGeometryDisplacement()
                    && previousCenterX != Integer.MIN_VALUE
                    && (SectionCluster.origin(previousCenterX)
                                    != SectionCluster.origin(playerSectionX)
                            || SectionCluster.origin(previousCenterY)
                                    != SectionCluster.origin(playerSectionY)
                            || SectionCluster.origin(previousCenterZ)
                                    != SectionCluster.origin(playerSectionZ))) {
                this.invalidateVoxelSurfaceWindow(
                        previousCenterX,
                        previousCenterY,
                        previousCenterZ,
                        playerSectionX,
                        playerSectionY,
                        playerSectionZ);
            }
        }

        this.drainInvalidations();
        this.drainCompleted();
        this.uploadReady(cameraX, cameraY, cameraZ);
        // Upload publication owns the first chance at staging/TLAS resources each frame.
        this.scene.advanceCompactions();
        this.dispatchSnapshots(minecraft, currentWorld);
    }

    public TerrainScene.ResidentSceneView residentScene() {
        return this.scene.residentView();
    }

    public TerrainScene.CompactionStats compactionStats() {
        return this.scene.compactionStats();
    }

    public TerrainScene.MediumIdStatistics mediumIdStatistics() {
        return this.scene.mediumIdStatistics();
    }

    public TerrainScene.TintIdStatistics tintIdStatistics() {
        return this.scene.tintIdStatistics();
    }

    /**
     * Atomically replaces the one render-thread-owned dynamic BLAS.
     *
     * <p>The reserved instance is always sorted after terrain clusters and carries an empty light
     * payload, so replacing it cannot add an emitter to either light tree.
     */
    public boolean updateDynamic(DynamicSceneMotion motion) {
        DynamicSceneFrame frame = motion.frame();
        CompiledCluster dynamic = CompiledCluster.dynamic(
                frame.clusterX(),
                frame.clusterY(),
                frame.clusterZ(),
                frame.mesh(),
                motion.previousPositions());
        double cameraX = (frame.clusterX() << 4) + 32.0;
        double cameraY = (frame.clusterY() << 4) + 32.0;
        double cameraZ = (frame.clusterZ() << 4) + 32.0;
        return this.scene.updateDynamic(dynamic, cameraX, cameraY, cameraZ);
    }

    public void setLabPbrMaterials(LabPbrMaterialSet materials) {
        LabPbrMaterialSet translated = this.surfaceDetailMode.usesResourceNormals()
                ? materials
                : materials.withoutNormalTextures();
        if (!this.translatedLabPbrMaterials.translationEquivalent(
                translated, this.surfaceDetailMode)) {
            this.discardResidentMaterialGeneration |=
                    this.translatedLabPbrMaterials.invalidatesResidentTextureLookups(translated);
            this.invalidateAll();
        }
        this.labPbrMaterials = materials;
        this.translatedLabPbrMaterials = translated;
    }

    public void setSurfaceDetailMode(SurfaceDetailMode mode, int strengthSteps) {
        java.util.Objects.requireNonNull(mode, "mode");
        VoxelSurfaceSettings.maximumHeight(strengthSteps);
        boolean rebuild = this.surfaceDetailMode != mode
                || mode.usesGeometryDisplacement()
                        && this.voxelSurfaceStrengthSteps != strengthSteps;
        this.surfaceDetailMode = mode;
        this.voxelSurfaceStrengthSteps = strengthSteps;
        this.translatedLabPbrMaterials = mode.usesResourceNormals()
                ? this.labPbrMaterials
                : this.labPbrMaterials.withoutNormalTextures();
        if (rebuild) {
            this.invalidateAll();
        }
    }

    public void setWorkerPercentage(int percentage) {
        this.workerPercentage = TerrainWorkerSettings.validatePercentage(percentage);
    }

    public boolean isNearCameraReady() {
        if (this.world == null || this.centerSectionX == Integer.MIN_VALUE) {
            return false;
        }
        for (int z = this.centerSectionZ - 1; z <= this.centerSectionZ + 1; z++) {
            for (int y = Math.max(this.minimumSectionY, this.centerSectionY - 1);
                    y <= Math.min(this.maximumSectionY, this.centerSectionY + 1);
                    y++) {
                for (int x = this.centerSectionX - 1; x <= this.centerSectionX + 1; x++) {
                    long key = SectionCluster.keyForSection(x, y, z);
                    if (!this.scene.contains(key) && !this.empty.contains(key)) {
                        return false;
                    }
                }
            }
        }
        return this.scene.residentView() != null;
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        this.externalDirty.addExpandedBlockRange(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    public void invalidateAll() {
        this.externalDirty.invalidateAll();
    }

    public ResourceReload beginResourceReload() {
        return new ResourceReload(this.resourceEpoch.pause());
    }

    public void finishResourceReload(ResourceReload reload) {
        this.resourceEpoch.finish(requireReload(reload));
    }

    public void abortResourceReload(ResourceReload reload) {
        this.resourceEpoch.abort(requireReload(reload));
    }

    @Override
    public void close() {
        // The executor belongs to Minecraft and is shut down by Minecraft. World epochs and the
        // interpreter's closed flag make late results harmless without taking ownership here.
        RuntimeException failure = ResourceCleanup.close(this.resourceEpoch, null);
        failure = ResourceCleanup.close(this.clusterCompiler, failure);
        failure = ResourceCleanup.close(this.scene, failure);
        this.completed.clear();
        this.externalDirty.clear();
        this.readyForUpload.clear();
        this.pipelineState.clear();
        ResourceCleanup.throwIfFailed(failure);
    }

    private void synchronizeWindow(
            int centerX,
            int centerY,
            int centerZ,
            int distance,
            int minSectionY,
            int maxSectionY) {
        this.centerSectionX = centerX;
        this.centerSectionY = centerY;
        this.centerSectionZ = centerZ;
        this.renderDistance = distance;
        this.minimumSectionY = minSectionY;
        this.maximumSectionY = maxSectionY;

        LongOpenHashSet replacement = new LongOpenHashSet();
        int diameter = distance * 2 + 1;
        int verticalCount = maxSectionY - minSectionY + 1;
        replacement.ensureCapacity(diameter * diameter * verticalCount);
        for (int z = centerZ - distance; z <= centerZ + distance; z++) {
            for (int x = centerX - distance; x <= centerX + distance; x++) {
                int deltaX = x - centerX;
                int deltaZ = z - centerZ;
                if (deltaX * deltaX + deltaZ * deltaZ > distance * distance) {
                    continue;
                }
                for (int y = minSectionY; y <= maxSectionY; y++) {
                    replacement.add(SectionCluster.keyForSection(x, y, z));
                }
            }
        }

        for (long key : this.scene.residentStaticKeys()) {
            if (!replacement.contains(key)) {
                this.pendingEvictions.add(key);
            }
        }
        this.pendingEvictions.removeIf(replacement::contains);
        this.empty.removeIf(key -> !replacement.contains(key));
        this.desired.clear();
        this.desired.addAll(replacement);
        this.rebuildRequestQueue(1);
    }

    private void drainInvalidations() {
        BoundedDirtyClusters.Batch batch = this.externalDirty.drain();
        if (batch.fullInvalidation()) {
            this.empty.clear();
            this.rebuildRequestQueue(0);
            return;
        }
        for (long clusterKey : batch.keys()) {
            if (!this.desired.contains(clusterKey)) {
                continue;
            }
            this.empty.remove(clusterKey);
            long nextGeneration = this.generations.advance(clusterKey);
            this.enqueue(clusterKey, 0, nextGeneration);
        }
    }

    private void rebuildRequestQueue(int priority) {
        this.requests.clear();
        this.pipelineState.clearQueued();
        for (long key : this.desired) {
            if (!this.scene.contains(key) || priority == 0) {
                long nextGeneration = priority == 0
                        ? this.generations.advance(key)
                        : this.generations.current(key);
                this.enqueue(key, priority, nextGeneration);
            }
        }
    }

    private void enqueue(long clusterKey, int priority, long token) {
        if (!this.pipelineState.enqueue(clusterKey, token)) {
            return;
        }
        int x = SectionPos.x(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int y = SectionPos.y(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        int z = SectionPos.z(clusterKey) + SectionCluster.SECTION_SIZE / 2;
        long dx = x - this.centerSectionX;
        long dy = y - this.centerSectionY;
        long dz = z - this.centerSectionZ;
        long distanceSquared = ((dx * dx + dz * dz) << 8) | Math.min(255L, Math.abs(dy));
        this.requests.add(new ClusterRequest(clusterKey, token, priority, distanceSquared));
        this.compactRequestQueueIfNeeded();
    }

    private void dispatchSnapshots(Minecraft minecraft, ClientLevel level) {
        ResourceEpochCoordinator.Lease lease = this.resourceEpoch.tryAcquire();
        if (lease == null) {
            return;
        }
        try (lease) {
            this.dispatchSnapshots(minecraft, level, lease.epoch());
        }
    }

    private void dispatchSnapshots(
            Minecraft minecraft,
            ClientLevel level,
            ResourceEpochCoordinator.Epoch resourceEpoch) {
        // Count every stage after snapshot capture so a temporarily busy GPU cannot turn the
        // shared executor into an unbounded producer of completed cluster payloads.
        int workerLimit = TerrainWorkerSettings.workerLimit(
                this.maximumWorkerThreads, this.workerPercentage);
        int maximumInFlight = Math.min(this.inFlightCapacity, workerLimit);
        int admittedPriority = this.requests.isEmpty()
                ? Integer.MAX_VALUE : this.requests.peek().priority();
        int admissionLimit = TerrainWorkerSettings.admissionLimit(
                maximumInFlight, admittedPriority == 0);
        int outstanding = this.workerJobs + this.readyForUpload.size();
        int dispatchBudget = Math.max(0, admissionLimit - outstanding);
        if (dispatchBudget == 0 || this.requests.isEmpty()) {
            return;
        }
        VanillaClusterCompiler.CaptureSession captureSession =
                this.clusterCompiler.beginCapture(minecraft, level);
        LabPbrMaterialSet materialSnapshot = this.translatedLabPbrMaterials;
        this.unloadedRequests.clear();
        this.blockedRequests.clear();
        int examined = 0;
        int accepted = 0;
        while (accepted < dispatchBudget
                && examined < MAX_UNLOADED_PROBES_PER_FRAME
                && !this.requests.isEmpty()) {
            ClusterRequest request = this.requests.poll();
            if (request.priority() != admittedPriority) {
                this.requests.add(request);
                break;
            }
            examined++;
            if (!this.pipelineState.isQueued(request.key(), request.generation())) {
                continue;
            }
            if (!this.desired.contains(request.key())
                    || !this.generations.isCurrent(request.key(), request.generation())) {
                this.pipelineState.cancelQueued(request.key(), request.generation());
                continue;
            }
            if (this.pipelineState.hasInFlight(request.key())) {
                this.blockedRequests.add(request);
                continue;
            }
            this.pipelineState.cancelQueued(request.key(), request.generation());
            int clusterX = SectionPos.x(request.key());
            int clusterY = SectionPos.y(request.key());
            int clusterZ = SectionPos.z(request.key());
            boolean voxelSurfaces = this.surfaceDetailMode.usesGeometryDisplacement()
                    && VoxelSurfaceCoverage.includes(
                            clusterX,
                            clusterY,
                            clusterZ,
                            this.centerSectionX,
                            this.centerSectionY,
                            this.centerSectionZ);
            float voxelSurfaceMaximumHeight = VoxelSurfaceSettings.maximumHeight(
                    this.voxelSurfaceStrengthSteps);
            ClusterTranslationSettings translationSettings =
                    VanillaClusterCompiler.translationSettings(
                            this.opacityMicromapSupported,
                            this.segmentTriangleTarget,
                            this.maxOpacity2StateSubdivisionLevel,
                            this.maxOpacity4StateSubdivisionLevel,
                            voxelSurfaces,
                            voxelSurfaceMaximumHeight);
            VanillaClusterCompiler.Capture capture = this.clusterCompiler.capture(
                    captureSession,
                    clusterX,
                    clusterY,
                    clusterZ,
                    this.minimumSectionY,
                    this.maximumSectionY);
            if (capture == null) {
                // A 4x4x4 virtual chunk needs one Section of source data around every face.
                // Minecraft loads vertical Sections as part of the same chunk column, so checking
                // the 6x6 horizontal chunk columns establishes the complete 6x6x6 snapshot.
                this.unloadedRequests.add(request);
                continue;
            }

            if (capture.isEmpty()) {
                if (this.scene.contains(request.key())) {
                    CompletedCluster result = new CompletedCluster(
                            this.generations.worldEpoch(),
                            resourceEpoch.id(),
                            request.key(),
                            request.generation(),
                            clusterX,
                            clusterY,
                            clusterZ,
                            request.priority(),
                            new WorkerSuccess(CpuClusterMesh.empty()));
                    if (this.pipelineState.completeToReady(
                            result.key(), result.generation())) {
                        this.addReady(result);
                    }
                } else {
                    this.empty.add(request.key());
                }
                accepted++;
                continue;
            }
            ClusterPipelineState.Cancellation cancellation = this.pipelineState.beginInFlight(
                    request.key(), request.generation());
            long worldEpoch = this.generations.worldEpoch();
            try {
                this.workers.execute(() -> {
                    WorkerResult workerResult;
                    WorkerStage workerStage = WorkerStage.SETUP;
                    int sectionX = 0;
                    int sectionY = 0;
                    int sectionZ = 0;
                    ResourceEpochCoordinator.Lease workerLease =
                            TerrainStreamer.this.resourceEpoch.tryAcquire(resourceEpoch);
                    if (workerLease == null) {
                        workerResult = WorkerCancelled.INSTANCE;
                    } else {
                        try (workerLease) {
                            CpuClusterMesh mesh = TerrainStreamer.this.clusterCompiler.compile(
                                    capture,
                                    materialSnapshot,
                                    translationSettings,
                                    cancellation::cancelled);
                            workerResult = new WorkerSuccess(mesh);
                        } catch (VanillaClusterCompiler.CompilationCancelledException ignored) {
                            workerResult = WorkerCancelled.INSTANCE;
                        } catch (VanillaClusterCompiler.CompilationException exception) {
                            workerResult = new WorkerFailure(
                                    exception.getCause(),
                                    switch (exception.stage()) {
                                        case SETUP -> WorkerStage.SETUP;
                                        case SECTION_COMPILATION ->
                                                WorkerStage.SECTION_COMPILATION;
                                        case CLUSTER_TRANSLATION ->
                                                WorkerStage.CLUSTER_TRANSLATION;
                                    },
                                    exception.sectionX(),
                                    exception.sectionY(),
                                    exception.sectionZ());
                        } catch (Throwable throwable) {
                            workerResult = new WorkerFailure(
                                    throwable, workerStage, sectionX, sectionY, sectionZ);
                        }
                    }
                    CompletedCluster completedCluster = new CompletedCluster(
                            worldEpoch,
                            resourceEpoch.id(),
                            request.key(),
                            request.generation(),
                            clusterX,
                            clusterY,
                            clusterZ,
                            request.priority(),
                            workerResult);
                    TerrainStreamer.this.completed.add(completedCluster);
                });
                this.workerJobs++;
            } catch (RejectedExecutionException ignored) {
                this.pipelineState.cancelInFlight(request.key(), request.generation());
                this.enqueue(request.key(), request.priority(), request.generation());
                PrimeInfo.LOGGER.debug("Terrain executor is temporarily saturated");
                break;
            }
            accepted++;
        }
        this.requests.addAll(this.blockedRequests);
        for (ClusterRequest request : this.unloadedRequests) {
            this.enqueue(request.key(), request.priority(), request.generation());
        }
        this.blockedRequests.clear();
        this.unloadedRequests.clear();
    }

    private void drainCompleted() {
        CompletedCluster result;
        while ((result = this.completed.poll()) != null) {
            this.workerJobs--;
            if (result.worldEpoch() != this.generations.worldEpoch()) {
                continue;
            }
            this.pipelineState.cancelInFlight(result.key(), result.generation());
            if (!this.desired.contains(result.key())
                    || !this.generations.isCurrent(result.key(), result.generation())) {
                continue;
            }
            switch (result.result().status()) {
                case CANCELLED -> {
                    this.enqueue(result.key(), result.priority(), result.generation());
                    continue;
                }
                case FAILURE -> {
                    WorkerFailure failed = (WorkerFailure) result.result();
                    // Retrying the same immutable work cannot repair a deterministic failure.
                    // Escalate once so the runtime performs its defined vanilla fallback.
                    String cluster = "(" + result.clusterX() + ", "
                            + result.clusterY() + ", " + result.clusterZ() + ")";
                    String message = switch (failed.stage()) {
                        case SETUP -> "Terrain setup failed for cluster " + cluster;
                        case SECTION_COMPILATION -> "Terrain section ("
                                + failed.sectionX() + ", " + failed.sectionY() + ", "
                                + failed.sectionZ() + ") failed in cluster " + cluster;
                        case CLUSTER_TRANSLATION ->
                                "Terrain translation failed for cluster " + cluster;
                    };
                    throw new IllegalStateException(message, failed.failure());
                }
                case SUCCESS -> {
                }
            }
            if (this.pipelineState.completeToReady(result.key(), result.generation())) {
                this.addReady(result);
            }
        }
    }

    private void addReady(CompletedCluster result) {
        if (result.priority() == 0) {
            this.readyForUpload.addFirst(result);
        } else {
            this.readyForUpload.addLast(result);
        }
    }

    private void uploadReady(double cameraX, double cameraY, double cameraZ) {
        List<CompiledCluster> uploads = this.uploadBatch;
        uploads.clear();
        this.uploadResourceGenerations.clear();
        long uploadBytes = 0L;
        while (!this.readyForUpload.isEmpty()) {
            CompletedCluster next = this.readyForUpload.peekFirst();
            if (!this.generations.isCurrent(next.key(), next.generation()) || !this.desired.contains(next.key())) {
                this.readyForUpload.removeFirst();
                this.pipelineState.consumeReady(next.key(), next.generation());
                continue;
            }
            CpuClusterMesh mesh = next.mesh();
            long nextEndOffset = ClusterStagingLayout.endOffset(
                    uploadBytes, mesh, this.opacityMicromapSupported);
            if (!uploads.isEmpty() && nextEndOffset > TARGET_UPLOAD_BYTES_PER_FRAME) {
                break;
            }
            this.readyForUpload.removeFirst();
            this.pipelineState.consumeReady(next.key(), next.generation());
            uploadBytes = nextEndOffset;
            uploads.add(new CompiledCluster(
                    next.key(), next.clusterX(), next.clusterY(), next.clusterZ(), mesh));
            this.uploadResourceGenerations.add(next.resourceGeneration());
        }
        if (this.discardResidentMaterialGeneration) {
            for (long key : this.scene.residentStaticKeys()) {
                this.pendingEvictions.add(key);
            }
        }
        long[] evictions = this.pendingEvictions.isEmpty()
                ? EMPTY_EVICTIONS
                : this.pendingEvictions.toLongArray();
        boolean updated = this.scene.updateStatic(
                uploads, evictions, cameraX, cameraY, cameraZ);
        if (!updated) {
            for (int index = uploads.size() - 1; index >= 0; index--) {
                CompiledCluster upload = uploads.get(index);
                CompletedCluster result = new CompletedCluster(
                        this.generations.worldEpoch(),
                        this.uploadResourceGenerations.get(index),
                        upload.key(),
                        this.generations.current(upload.key()),
                        upload.clusterX(),
                        upload.clusterY(),
                        upload.clusterZ(),
                        0,
                        new WorkerSuccess(upload.mesh()));
                if (this.pipelineState.completeToReady(
                        result.key(), result.generation())) {
                    this.readyForUpload.addFirst(result);
                }
            }
            return;
        }
        this.pendingEvictions.clear();
        this.discardResidentMaterialGeneration = false;
        for (int index = 0; index < uploads.size(); index++) {
            this.reportCompatibility(
                    this.uploadResourceGenerations.get(index),
                    uploads.get(index).mesh().compatibilityIssues());
        }
        for (long key : evictions) {
            this.empty.remove(key);
        }
        for (CompiledCluster upload : uploads) {
            if (upload.isEmpty()) {
                this.empty.add(upload.key());
            } else {
                this.empty.remove(upload.key());
            }
        }
    }

    private void reportCompatibility(
            long resourceGeneration,
            Set<StaticCompatibilityIssue> issues) {
        if (this.reportedResourceGeneration != resourceGeneration) {
            this.reportedCompatibility.clear();
            this.reportedResourceGeneration = resourceGeneration;
        }
        for (StaticCompatibilityIssue issue : issues) {
            CompatibilityReportKey key = new CompatibilityReportKey(
                    resourceGeneration, issue.type(), issue.textureId());
            if (this.reportedCompatibility.add(key)) {
                PrimeInfo.LOGGER.warn(
                        "Prime static scene compatibility (TextureId {}): {}",
                        issue.textureId(),
                        issue.type().description());
            }
        }
    }

    private void clearWorld(double cameraX, double cameraY, double cameraZ) {
        this.scene.beginUnrelatedWorld();
        this.scene.clear(cameraX, cameraY, cameraZ);
        this.world = null;
        this.desired.clear();
        this.empty.clear();
        this.pendingEvictions.clear();
        this.discardResidentMaterialGeneration = false;
        this.generations.resetWorld();
        this.pipelineState.clear();
        this.requests.clear();
        this.externalDirty.clear();
        this.readyForUpload.clear();
        this.centerSectionX = Integer.MIN_VALUE;
        this.centerSectionY = Integer.MIN_VALUE;
        this.centerSectionZ = Integer.MIN_VALUE;
        this.renderDistance = -1;
    }

    private void invalidateVoxelSurfaceWindow(
            int oldSectionX,
            int oldSectionY,
            int oldSectionZ,
            int newSectionX,
            int newSectionY,
            int newSectionZ) {
        for (long key : VoxelSurfaceCoverage.changedKeys(
                oldSectionX,
                oldSectionY,
                oldSectionZ,
                newSectionX,
                newSectionY,
                newSectionZ)) {
            if (this.desired.contains(key)) {
                this.externalDirty.addCluster(key);
            }
        }
    }

    private void compactRequestQueueIfNeeded() {
        long desiredLimit = Math.max(1024L, (long) this.desired.size() * 2L);
        if (this.requests.size() <= desiredLimit) {
            return;
        }
        this.requests.removeIf(request ->
                !this.desired.contains(request.key())
                        || !this.generations.isCurrent(request.key(), request.generation())
                        || !this.pipelineState.isQueued(
                                request.key(), request.generation()));
    }

    private record ClusterRequest(long key, long generation, int priority, long distanceSquared) {
    }

    public static final class ResourceReload {
        private final ResourceEpochCoordinator.Reload reload;

        private ResourceReload(ResourceEpochCoordinator.Reload reload) {
            this.reload = reload;
        }

        public java.util.concurrent.CompletableFuture<Void> ready() {
            return this.reload.ready();
        }
    }

    private static ResourceEpochCoordinator.Reload requireReload(ResourceReload reload) {
        if (reload == null) {
            throw new NullPointerException("reload");
        }
        return reload.reload;
    }

    private sealed interface WorkerResult
            permits WorkerSuccess, WorkerFailure, WorkerCancelled {
        WorkerStatus status();
    }

    private record WorkerSuccess(CpuClusterMesh mesh) implements WorkerResult {
        @Override
        public WorkerStatus status() {
            return WorkerStatus.SUCCESS;
        }
    }

    private record WorkerFailure(
            Throwable failure,
            WorkerStage stage,
            int sectionX,
            int sectionY,
            int sectionZ) implements WorkerResult {
        @Override
        public WorkerStatus status() {
            return WorkerStatus.FAILURE;
        }
    }

    private enum WorkerStage {
        SETUP,
        SECTION_COMPILATION,
        CLUSTER_TRANSLATION
    }

    private enum WorkerCancelled implements WorkerResult {
        INSTANCE;

        @Override
        public WorkerStatus status() {
            return WorkerStatus.CANCELLED;
        }
    }

    private enum WorkerStatus {
        SUCCESS,
        CANCELLED,
        FAILURE
    }

    private record CompletedCluster(
            long worldEpoch,
            long resourceGeneration,
            long key,
            long generation,
            int clusterX,
            int clusterY,
            int clusterZ,
            int priority,
            WorkerResult result) {

        private CpuClusterMesh mesh() {
            if (this.result instanceof WorkerSuccess success) {
                return success.mesh();
            }
            throw new IllegalStateException("Only successful terrain work has a mesh");
        }
    }

    private record CompatibilityReportKey(
            long resourceGeneration,
            StaticCompatibilityIssue.Type type,
            int textureId) {
    }
}
