package dev.prime.render.vulkan.terrain;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.scene.SceneRevisionView;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.*;
import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.OpacityMicromapPool;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.TopLevelAccelerationStructure;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanSync;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class TerrainScene implements AutoCloseable {
    private static final long[] EMPTY_EVICTIONS = new long[0];
    private static final int TLAS_SLOT_COUNT = 3;
    private static final int REBASE_DISTANCE = 256;

    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final OpacityMicromapPool opacityMicromapPool;
    private final DynamicBufferPool dynamicBufferPool;
    private final VoxelBlasPool voxelBlasPool = new VoxelBlasPool();
    private final MediumIdRegistry mediumIds = new MediumIdRegistry();
    private final MaterialIdRegistry materialIds = new MaterialIdRegistry(this.mediumIds);
    private final VulkanBuffer materialCoreRecords;
    private final TintSampleTable tintSamples;
    private final boolean measurementsEnabled = Boolean.getBoolean(
            SurfaceTintUsage.MEASUREMENT_ENABLE_PROPERTY);
    private final BlasCompactionScheduler compactionScheduler =
            new BlasCompactionScheduler();
    private Long2ObjectOpenHashMap<GpuCluster> resident = new Long2ObjectOpenHashMap<>();
    private @Nullable GpuCluster dynamicResident;
    private final List<TopLevelAccelerationStructure> tlasSlots = new ArrayList<>(TLAS_SLOT_COUNT);
    private TopLevelAccelerationStructure currentTlas;
    private VulkanBuffer currentWorldLights;
    private CpuWorldLightTree.Result currentWorldLightTree =
            CpuWorldLightTree.Result.empty(0);
    private ResidentSceneView currentView;
    private int originX;
    private int originY;
    private int originZ;
    private long revision;
    private long resetRevision;
    private long occluderRevision;

    public TerrainScene(VulkanContext context, StagingArena stagingArena) {
        this.context = context;
        this.stagingArena = stagingArena;
        this.opacityMicromapPool = new OpacityMicromapPool(context);
        this.dynamicBufferPool = new DynamicBufferPool(context);
        this.materialCoreRecords = context.createBuffer(
                MaterialIdRegistry.BUFFER_BYTES,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false,
                "Prime material core records");
        this.tintSamples = new TintSampleTable(context);
    }

    public boolean updateStatic(
            List<CompiledCluster> uploads,
            long[] evictions,
            double cameraX,
            double cameraY,
            double cameraZ) {
        for (CompiledCluster upload : uploads) {
            if (upload.dynamic()) {
                throw new IllegalArgumentException(
                        "Static scene update contains a dynamic cluster");
            }
        }
        return this.update(
                uploads, evictions, null, false, cameraX, cameraY, cameraZ);
    }

    public boolean updateDynamic(
            CompiledCluster upload,
            double cameraX,
            double cameraY,
            double cameraZ) {
        if (!upload.dynamic()) {
            throw new IllegalArgumentException(
                    "Dynamic scene update contains a static cluster");
        }
        return this.update(
                List.of(), EMPTY_EVICTIONS, upload, true, cameraX, cameraY, cameraZ);
    }

    public boolean clear(double cameraX, double cameraY, double cameraZ) {
        return this.update(
                List.of(),
                this.resident.keySet().toLongArray(),
                null,
                true,
                cameraX,
                cameraY,
                cameraZ);
    }

    private boolean update(
            List<CompiledCluster> staticUploads,
            long[] evictions,
            @Nullable CompiledCluster dynamicUpload,
            boolean replaceDynamic,
            double cameraX,
            double cameraY,
            double cameraZ) {
        List<CompiledCluster> uploads;
        if (dynamicUpload == null) {
            uploads = staticUploads;
        } else if (staticUploads.isEmpty()) {
            uploads = List.of(dynamicUpload);
        } else {
            ArrayList<CompiledCluster> combined =
                    new ArrayList<>(staticUploads.size() + 1);
            combined.addAll(staticUploads);
            combined.add(dynamicUpload);
            uploads = combined;
        }
        // Dynamic capture is the frame clock: replace and rebuild BLAS/TLAS without a dirty check.
        boolean contentChanged = replaceDynamic
                || this.hasActualContentChange(staticUploads, evictions);
        boolean staticContentChanged =
                this.hasActualStaticContentChange(staticUploads, evictions);
        LongOpenHashSet removedKeys = removedKeys(staticUploads, evictions);
        List<TerrainOccluderChange> occluderChanges = staticContentChanged
                ? this.occluderChanges(staticUploads, evictions)
                : List.of();
        boolean needsRebase = this.currentTlas == null
                ? contentChanged
                : RenderOrigin.needsRebase(
                        cameraX,
                        cameraY,
                        cameraZ,
                        this.originX,
                        this.originY,
                        this.originZ,
                        REBASE_DISTANCE);
        if (!contentChanged && !needsRebase) {
            return true;
        }
        int finalClusterCount = this.estimateFinalClusterCount(
                staticUploads, removedKeys, dynamicUpload, replaceDynamic);
        int finalInstanceCount = this.estimateFinalInstanceCount(
                staticUploads, removedKeys, dynamicUpload, replaceDynamic);
        TopLevelAccelerationStructure replacementTlas = null;
        if (finalClusterCount > 0) {
            replacementTlas = this.acquireTlas(finalInstanceCount);
            if (replacementTlas == null) {
                return false;
            }
        }

        int nonEmptyUploadCount = 0;
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                nonEmptyUploadCount++;
            }
        }
        boolean hasPotentialLights = false;
        for (GpuCluster cluster : this.resident.values()) {
            if (!cluster.lights().isEmpty()) {
                hasPotentialLights = true;
                break;
            }
        }
        if (!hasPotentialLights) {
            for (CompiledCluster upload : uploads) {
                if (!upload.mesh().lights().isEmpty()) {
                    hasPotentialLights = true;
                    break;
                }
            }
        }
        boolean needsClusterStaging = nonEmptyUploadCount > 0;
        /*
         * A semantic change already replaces and uploads the complete packed tree. Stable-slot
         * refit would therefore save only CPU construction while retaining inactive reserve nodes
         * and accumulated SAH degradation on the GPU. Rebuild to exactly 2L-1 nodes; BLAS
         * compaction changes only addresses and deliberately reuses the committed tree.
         */
        /*
         * The reserved dynamic instance sorts after every terrain cluster and cannot carry
         * emitters. Replacing it cannot change static cluster indices or light-tree topology.
         */
        boolean rebuildWorldLights = staticContentChanged || needsRebase;
        boolean needsWorldStaging = rebuildWorldLights && finalClusterCount > 0 && hasPotentialLights;
        long clusterStagingBytes = 0L;
        for (CompiledCluster upload : uploads) {
            clusterStagingBytes = ClusterStagingLayout.endOffset(
                    clusterStagingBytes,
                    upload.mesh(),
                    this.context.capabilities().opacityMicromapSupported());
            if (upload.dynamic() && !upload.isEmpty()) {
                clusterStagingBytes = StagingArena.requiredEndOffset(
                        clusterStagingBytes,
                        (long) upload.motionPositions().length * Float.BYTES,
                        Float.BYTES);
            }
        }
        if (needsClusterStaging) {
            // Use the fixed capacity in the admission budget: preparing clusters may allocate
            // any remaining dense IDs before the actual compact upload length is known.
            clusterStagingBytes = StagingArena.requiredEndOffset(
                    clusterStagingBytes,
                    this.materialCoreRecords.size(),
                    ShaderAbi.MATERIAL_CORE_RECORD_SIZE);
        }
        StagingArena.Batch clusterStagingBatch = needsClusterStaging
                ? this.stagingArena.tryBeginBatch(clusterStagingBytes)
                : null;
        if (needsClusterStaging && clusterStagingBatch == null) {
            if (replacementTlas != null) {
                replacementTlas.release();
            }
            return false;
        }
        StagingArena.Batch worldStagingBatch = needsWorldStaging
                ? this.stagingArena.tryBeginBatch()
                : null;
        if (needsWorldStaging && worldStagingBatch == null) {
            if (clusterStagingBatch != null) {
                clusterStagingBatch.close();
            }
            if (replacementTlas != null) {
                replacementTlas.release();
            }
            return false;
        }

        TerrainUpdateTransaction transaction = new TerrainUpdateTransaction(
                this.context,
                this.voxelBlasPool,
                clusterStagingBatch,
                worldStagingBatch,
                replacementTlas,
                nonEmptyUploadCount);
        List<GpuCluster> replacements = transaction.replacements();
        VulkanBuffer replacementWorldLights = null;
        VkCommandBuffer commandBuffer = null;
        try {
            if (nonEmptyUploadCount > 0 || replacementTlas != null) {
                commandBuffer = this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
                this.context.device().instance().debug().beginDebugGroup(commandBuffer, () -> "Prime terrain scene update");
            }

            if (clusterStagingBatch != null) {
                for (CompiledCluster upload : uploads) {
                    if (!upload.isEmpty()) {
                        replacements.add(this.prepareCluster(
                                upload, clusterStagingBatch, commandBuffer));
                    }
                }
            }

            if (clusterStagingBatch != null) {
                int[] materialCore = this.materialIds.encodedCoreRecords();
                copyBuffer(
                        commandBuffer,
                        clusterStagingBatch.write(
                                materialCore, ShaderAbi.MATERIAL_CORE_RECORD_SIZE),
                        this.materialCoreRecords);
            }

            List<GpuCluster> finalClusters = this.buildFinalClusterList(
                    removedKeys, replacements, finalClusterCount, replaceDynamic);
            int nextOriginX = needsRebase ? RenderOrigin.alignToSection(cameraX) : this.originX;
            int nextOriginY = needsRebase ? RenderOrigin.alignToSection(cameraY) : this.originY;
            int nextOriginZ = needsRebase ? RenderOrigin.alignToSection(cameraZ) : this.originZ;
            CpuWorldLightTree.Result worldLightTree = rebuildWorldLights
                    ? CpuWorldLightTree.build(
                            WorldLightTreeInput.capture(
                                    finalClusters.stream()
                                            .filter(cluster -> !cluster.dynamic())
                                            .map(cluster -> new WorldLightTreeInput.Entry(
                                                    cluster.key(),
                                                    cluster.clusterX(),
                                                    cluster.clusterY(),
                                                    cluster.clusterZ(),
                                                    cluster.lights()))
                                            .toList(),
                                    nextOriginX,
                                    nextOriginY,
                                    nextOriginZ))
                    : this.currentWorldLightTree;
            if (requiresWorldLightUpload(rebuildWorldLights, worldLightTree)) {
                if (worldStagingBatch == null || commandBuffer == null) {
                    throw new IllegalStateException("World light tree requires an upload batch");
                }
                int[] packedWorldLights = worldLightTree.pack();
                replacementWorldLights = this.context.createBuffer(
                        (long) packedWorldLights.length * Integer.BYTES,
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime world light tree");
                transaction.worldLights(replacementWorldLights);
                StagingArena.Slice worldLightSlice = worldStagingBatch.write(
                        packedWorldLights, 16L);
                copyBuffer(commandBuffer, worldLightSlice, replacementWorldLights);
            }

            if (!replacements.isEmpty() || replacementWorldLights != null) {
                boolean hasOpacityMicromapBuild = replacements.stream()
                        .anyMatch(cluster -> cluster.hasOpacityMicromapBuild(
                                this.voxelBlasPool));
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | (hasOpacityMicromapBuild
                                        ? EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT
                                        : 0L)
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | (hasOpacityMicromapBuild
                                        ? EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT
                                        : 0L)
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
                if (hasOpacityMicromapBuild) {
                    for (GpuCluster cluster : replacements) {
                        cluster.recordOpacityMicromapBuild(
                                this.voxelBlasPool, commandBuffer);
                    }
                    // EXT micromap construction and BLAS construction are distinct device
                    // operations. The BLAS is allowed to consume the micromap only after its
                    // implementation-owned data is visible; this dependency must remain even
                    // though both commands currently share one transient command buffer.
                    memoryBarrier(
                            commandBuffer,
                            EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT,
                            EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT,
                            KHRAccelerationStructure
                                    .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
                }
                for (GpuCluster cluster : replacements) {
                    cluster.recordBuild(this.voxelBlasPool, commandBuffer);
                }
                if (!replacements.isEmpty()) {
                    memoryBarrier(
                            commandBuffer,
                            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                }
            }

            if (replacementTlas != null) {
                VulkanBuffer effectiveWorldLights = rebuildWorldLights
                        ? replacementWorldLights
                        : this.currentWorldLights;
                populateTlas(
                        replacementTlas,
                        finalInstanceCount,
                        finalClusters,
                        Map.of(),
                        effectiveWorldLights,
                        worldLightTree,
                        nextOriginX,
                        nextOriginY,
                        nextOriginZ);
                memoryBarrier(
                        commandBuffer,
                        VK12.VK_PIPELINE_STAGE_HOST_BIT,
                        VK12.VK_ACCESS_HOST_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                                | VK12.VK_ACCESS_SHADER_READ_BIT);
                replacementTlas.recordBuild(commandBuffer);
                memoryBarrier(
                        commandBuffer,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }

            PreparedUpdate preparedUpdate = this.prepareUpdate(
                    finalClusters,
                    removedKeys,
                    replacementTlas,
                    replacementWorldLights,
                    worldLightTree,
                    rebuildWorldLights,
                    occluderChanges,
                    nextOriginX,
                    nextOriginY,
                    nextOriginZ,
                    replaceDynamic);
            if (commandBuffer != null) {
                this.context.device().instance().debug().endDebugGroup(commandBuffer);
                VulkanContext.check(VK12.vkEndCommandBuffer(commandBuffer), "end Prime terrain command buffer");
                if (clusterStagingBatch != null) {
                    clusterStagingBatch.prepareForSubmission();
                }
                if (worldStagingBatch != null) {
                    worldStagingBatch.prepareForSubmission();
                }
                this.context.commandEncoder().execute(commandBuffer);
                transaction.submitted();
            }
            this.publish(preparedUpdate);
            transaction.published();
            RuntimeException retirementFailure = null;
            for (GpuCluster replacement : replacements) {
                retirementFailure = ResourceCleanup.run(
                        () -> replacement.submitted(this.voxelBlasPool),
                        retirementFailure);
                retirementFailure = ResourceCleanup.run(
                        () -> this.compactionScheduler.register(replacement),
                        retirementFailure);
            }
            for (GpuCluster retired : preparedUpdate.retired()) {
                this.compactionScheduler.unregister(retired);
            }
            retirementFailure = this.retire(preparedUpdate, retirementFailure);
            ResourceCleanup.throwIfFailed(retirementFailure);
            return true;
        } catch (RuntimeException exception) {
            throw transaction.abort(exception);
        } finally {
            transaction.close();
        }
    }

    /** Advances ready static BLAS compactions after uploads have had first access to frame resources. */
    public void advanceCompactions() {
        if (this.currentTlas == null
                || this.resident.isEmpty()
                || !this.compactionScheduler.hasReadyWork()) {
            return;
        }

        int instanceCount = 0;
        for (GpuCluster cluster : this.resident.values()) {
            instanceCount = Math.addExact(instanceCount, cluster.tlasInstanceCount());
        }
        if (this.dynamicResident != null) {
            instanceCount = Math.addExact(
                    instanceCount, this.dynamicResident.tlasInstanceCount());
        }
        TopLevelAccelerationStructure replacementTlas =
                this.acquireCompactionTlas(instanceCount);
        if (replacementTlas == null) {
            return;
        }

        BlasCompactionScheduler.Batch batch = null;
        boolean submitted = false;
        boolean ownershipTransferred = false;
        try {
            batch = this.compactionScheduler.prepareBatch();
            if (batch.isEmpty()) {
                replacementTlas.release();
                replacementTlas = null;
                return;
            }

            LongOpenHashSet noRemovedKeys = new LongOpenHashSet();
            List<GpuCluster> finalClusters = this.buildFinalClusterList(
                    noRemovedKeys,
                    List.of(),
                    this.resident.size() + (this.dynamicResident == null ? 0 : 1),
                    false);
            IdentityHashMap<PreparedBlas, PreparedBlas.Compaction> replacements =
                    new IdentityHashMap<>(batch.compactions().size());
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                if (replacements.put(compaction.owner(), compaction) != null) {
                    throw new IllegalStateException(
                            "A BLAS was selected for compaction more than once");
                }
            }

            VkCommandBuffer commandBuffer =
                    this.context.commandEncoder().allocateAndBeginTransientCommandBuffer();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> "Prime BLAS compaction");
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.recordCopy(commandBuffer);
            }
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            populateTlas(
                    replacementTlas,
                    instanceCount,
                    finalClusters,
                    replacements,
                    this.currentWorldLights,
                    this.currentWorldLightTree,
                    this.originX,
                    this.originY,
                    this.originZ);
            memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    VK12.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                            | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                            | VK12.VK_ACCESS_SHADER_READ_BIT);
            replacementTlas.recordBuild(commandBuffer);
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            // Address-only publication reuses lighting and preserves temporal/occluder identity.
            PreparedUpdate preparedUpdate = this.prepareUpdate(
                    finalClusters,
                    noRemovedKeys,
                    replacementTlas,
                    null,
                    this.currentWorldLightTree,
                    false,
                    List.of(),
                    this.originX,
                    this.originY,
                    this.originZ,
                    false);
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.requirePublishable();
            }
            this.context.device().instance().debug().endDebugGroup(commandBuffer);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime BLAS compaction command buffer");
            this.context.commandEncoder().execute(commandBuffer);
            submitted = true;

            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                compaction.publish();
            }
            this.publish(preparedUpdate);
            batch.commitPublished();
            ownershipTransferred = true;
            replacementTlas = null;

            RuntimeException retirementFailure = null;
            for (PreparedBlas.Compaction compaction : batch.compactions()) {
                retirementFailure = ResourceCleanup.run(
                        compaction::retireSource, retirementFailure);
            }
            retirementFailure = this.retire(preparedUpdate, retirementFailure);
            ResourceCleanup.throwIfFailed(retirementFailure);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (!ownershipTransferred) {
                if (batch != null) {
                    if (submitted) {
                        failure = ResourceCleanup.run(
                                batch::abandonAfterSubmission, failure);
                    } else {
                        failure = ResourceCleanup.close(batch, failure);
                    }
                }
                if (replacementTlas != null) {
                    if (submitted) {
                        TopLevelAccelerationStructure failedTlas = replacementTlas;
                        failure = ResourceCleanup.run(
                                () -> this.context.defer(failedTlas::release), failure);
                    } else {
                        failure = ResourceCleanup.run(replacementTlas::release, failure);
                    }
                }
            }
            throw failure;
        } finally {
            if (ownershipTransferred) {
                ResourceCleanup.close(batch, null);
            }
        }
    }

    public ResidentSceneView residentView() {
        return this.currentView;
    }

    public CompactionStats compactionStats() {
        BlasCompactionScheduler.Snapshot snapshot =
                this.compactionScheduler.snapshot();
        return new CompactionStats(
                snapshot.waiting(),
                snapshot.ready(),
                snapshot.retiring(),
                snapshot.waitingSourceBytes(),
                snapshot.readySourceBytes(),
                snapshot.inFlightSourceBytes(),
                snapshot.knownReclaimableBytes(),
                snapshot.reservedTargetBytes(),
                snapshot.highWaterTargetBytes(),
                snapshot.reclaimedBytes(),
                snapshot.completedCount());
    }

    /** Renderer-lifetime exact-medium allocation totals for opt-in data measurements. */
    public MediumIdStatistics mediumIdStatistics() {
        MediumIdRegistry.Snapshot snapshot = this.mediumIds.snapshot();
        return new MediumIdStatistics(snapshot.assignedCount(), snapshot.highWaterId());
    }

    /** Renderer-lifetime exact-material allocation totals for migration measurements. */
    public MaterialIdStatistics materialIdStatistics() {
        MaterialIdRegistry.Snapshot snapshot = this.materialIds.snapshot();
        return new MaterialIdStatistics(snapshot.assignedCount(), snapshot.highWaterId());
    }

    /** Renderer-lifetime exact-tint allocation totals for opt-in data measurements. */
    public TintIdStatistics tintIdStatistics() {
        TintSampleTable.Snapshot snapshot = this.tintSamples.snapshot();
        return new TintIdStatistics(snapshot.assignedCount(), snapshot.highWaterId());
    }

    public static boolean requiresWorldLightUpload(
            boolean rebuildWorldLights, CpuWorldLightTree.Result worldLightTree) {
        return rebuildWorldLights && !worldLightTree.isEmpty();
    }

    public boolean contains(long key) {
        return this.resident.containsKey(key);
    }

    /** Marks every temporal consumer as unrelated to its previous world. */
    public void beginUnrelatedWorld() {
        this.resetRevision++;
    }

    public int residentCount() {
        return this.resident.size() + (this.dynamicResident == null ? 0 : 1);
    }

    public long[] residentStaticKeys() {
        return this.resident.keySet().toLongArray();
    }

    @Override
    public void close() {
        RuntimeException failure = ResourceCleanup.close(this.compactionScheduler, null);
        for (GpuCluster cluster : this.resident.values()) {
            failure = ResourceCleanup.run(
                    () -> cluster.prepareRetirement(this.voxelBlasPool).destroy(),
                    failure);
        }
        this.resident.clear();
        if (this.dynamicResident != null) {
            failure = ResourceCleanup.run(
                    () -> this.dynamicResident
                            .prepareRetirement(this.voxelBlasPool)
                            .destroy(),
                    failure);
            this.dynamicResident = null;
        }
        for (TopLevelAccelerationStructure slot : this.tlasSlots) {
            failure = ResourceCleanup.run(slot::destroy, failure);
        }
        this.tlasSlots.clear();
        this.currentTlas = null;
        this.currentView = null;
        if (this.currentWorldLights != null) {
            failure = ResourceCleanup.destroy(this.currentWorldLights, failure);
            this.currentWorldLights = null;
        }
        this.currentWorldLightTree = CpuWorldLightTree.Result.empty(0);
        failure = ResourceCleanup.close(this.voxelBlasPool, failure);
        failure = ResourceCleanup.close(this.tintSamples, failure);
        failure = ResourceCleanup.destroy(this.materialCoreRecords, failure);
        failure = ResourceCleanup.run(this.dynamicBufferPool::destroy, failure);
        failure = ResourceCleanup.close(this.opacityMicromapPool, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private static LongOpenHashSet removedKeys(
            List<CompiledCluster> uploads, long[] evictions) {
        LongOpenHashSet result = new LongOpenHashSet(evictions);
        LongOpenHashSet uploadKeys = new LongOpenHashSet();
        for (CompiledCluster upload : uploads) {
            if (upload.dynamic()) {
                throw new IllegalArgumentException(
                        "Static removal set contains a dynamic cluster");
            }
            if (!uploadKeys.add(upload.key())) {
                throw new IllegalArgumentException(
                        "A logical cluster was replaced more than once in one update");
            }
            result.add(upload.key());
        }
        return result;
    }

    private int estimateFinalClusterCount(
            List<CompiledCluster> uploads,
            LongOpenHashSet removedKeys,
            @Nullable CompiledCluster dynamicUpload,
            boolean replaceDynamic) {
        int count = this.resident.size();
        for (long key : removedKeys) {
            if (this.resident.containsKey(key)) {
                count--;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                count++;
            }
        }
        if (replaceDynamic) {
            if (dynamicUpload != null && !dynamicUpload.isEmpty()) {
                count++;
            }
        } else if (this.dynamicResident != null) {
            count++;
        }
        return count;
    }

    private int estimateFinalInstanceCount(
            List<CompiledCluster> uploads,
            LongOpenHashSet removedKeys,
            @Nullable CompiledCluster dynamicUpload,
            boolean replaceDynamic) {
        int count = 0;
        for (GpuCluster cluster : this.resident.values()) {
            if (!removedKeys.contains(cluster.key())) {
                count = Math.addExact(count, cluster.tlasInstanceCount());
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()) {
                count = Math.addExact(
                        count,
                        Math.addExact(1, upload.mesh().voxelInstances().count()));
            }
        }
        GpuCluster retainedDynamic = replaceDynamic ? null : this.dynamicResident;
        if (retainedDynamic != null) {
            count = Math.addExact(count, retainedDynamic.tlasInstanceCount());
        } else if (dynamicUpload != null && !dynamicUpload.isEmpty()) {
            count = Math.addExact(
                    count,
                    Math.addExact(1, dynamicUpload.mesh().voxelInstances().count()));
        }
        return count;
    }

    private boolean hasActualContentChange(
            List<CompiledCluster> uploads,
            long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty() || this.resident.containsKey(upload.key())) {
                return true;
            }
        }
        return false;
    }

    private List<TerrainOccluderChange> occluderChanges(
            List<CompiledCluster> uploads, long[] evictions) {
        LongOpenHashSet changedKeys = new LongOpenHashSet();
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                changedKeys.add(key);
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty()
                    || this.resident.containsKey(upload.key())) {
                changedKeys.add(upload.key());
            }
        }
        List<TerrainOccluderChange> changes = new ArrayList<>(changedKeys.size());
        for (long key : changedKeys) {
            int minimumX = SectionPos.x(key) << 4;
            int minimumY = SectionPos.y(key) << 4;
            int minimumZ = SectionPos.z(key) << 4;
            int clusterBlockSize = SectionCluster.SECTION_SIZE << 4;
            changes.add(new TerrainOccluderChange(
                    minimumX,
                    minimumY,
                    minimumZ,
                    Math.addExact(minimumX, clusterBlockSize),
                    Math.addExact(minimumY, clusterBlockSize),
                    Math.addExact(minimumZ, clusterBlockSize)));
        }
        return List.copyOf(changes);
    }

    private boolean hasActualStaticContentChange(
            List<CompiledCluster> uploads, long[] evictions) {
        for (long key : evictions) {
            if (this.resident.containsKey(key)) {
                return true;
            }
        }
        for (CompiledCluster upload : uploads) {
            if (!upload.isEmpty() || this.resident.containsKey(upload.key())) {
                return true;
            }
        }
        return false;
    }

    private List<GpuCluster> buildFinalClusterList(
            LongOpenHashSet removedKeys,
            List<GpuCluster> replacements,
            int finalClusterCount,
            boolean replaceDynamic) {
        List<GpuCluster> result = new ArrayList<>(finalClusterCount);
        for (GpuCluster cluster : this.resident.values()) {
            if (!removedKeys.contains(cluster.key())) {
                result.add(cluster);
            }
        }
        GpuCluster replacementDynamic = null;
        for (GpuCluster replacement : replacements) {
            if (replacement.dynamic()) {
                if (replacementDynamic != null) {
                    throw new IllegalArgumentException(
                            "A scene update contains more than one dynamic cluster");
                }
                replacementDynamic = replacement;
            } else {
                result.add(replacement);
            }
        }
        result.sort(Comparator.comparingLong(GpuCluster::key));
        GpuCluster finalDynamic = replaceDynamic
                ? replacementDynamic
                : this.dynamicResident;
        if (finalDynamic != null) {
            result.add(finalDynamic);
        }
        if (result.size() != finalClusterCount) {
            throw new IllegalStateException(
                    "Final scene cluster count changed during preparation");
        }
        return result;
    }

    private static void populateTlas(
            TopLevelAccelerationStructure tlas,
            int instanceCount,
            List<GpuCluster> clusters,
            Map<PreparedBlas, PreparedBlas.Compaction> compactions,
            VulkanBuffer worldLights,
            CpuWorldLightTree.Result worldLightTree,
            int originX,
            int originY,
            int originZ) {
        long worldLightAddress = worldLights == null
                ? 0L
                : worldLights.deviceAddress();
        long worldLightLeafAddress = worldLights == null
                ? 0L
                : worldLights.deviceAddress() + worldLightTree.leafByteOffset();
        int worldLightLeafCount = worldLights == null
                ? 0
                : worldLightTree.leafCount();
        tlas.populate(instanceCount, writer -> {
            for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
                GpuCluster cluster = clusters.get(clusterIndex);
                PreparedBlas base = cluster.baseBlas();
                float sectionX = (cluster.clusterX() << 4) - originX;
                float sectionY = (cluster.clusterY() << 4) - originY;
                float sectionZ = (cluster.clusterZ() << 4) - originZ;
                writer.writeInstanced(
                        compactionAddress(base, compactions),
                        base.primitives().deviceAddress(),
                        base.positions().deviceAddress(),
                        cluster.surfaceRelationAddress(),
                        cluster.lightAddress(),
                        worldLightAddress,
                        worldLightLeafAddress,
                        base.cutoutPrimitiveBase(),
                        base.transmissivePrimitiveBase(),
                        base.opaqueMacroTriangleBase(),
                        base.cutoutMacroTriangleBase(),
                        base.transmissiveMacroTriangleBase(),
                        cluster.dynamic()
                                ? CpuLightTree.NO_INDEX
                                : worldLightTree.lightPath(clusterIndex),
                        cluster.lights().emitterCount(),
                        worldLightLeafCount,
                        cluster.blas() == null ? 0 : 0xff,
                        0,
                        sectionX,
                        sectionY,
                        sectionZ,
                        sectionX,
                        sectionY,
                        sectionZ);
            }
            for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
                GpuCluster cluster = clusters.get(clusterIndex);
                float sectionX = (cluster.clusterX() << 4) - originX;
                float sectionY = (cluster.clusterY() << 4) - originY;
                float sectionZ = (cluster.clusterZ() << 4) - originZ;
                ResolvedVoxelInstances instances = cluster.voxelInstances();
                for (int index = 0; index < instances.count(); index++) {
                    PreparedBlas voxel =
                            cluster.voxelBlases().get(instances.meshIndex(index));
                    writer.writeInstanced(
                            compactionAddress(voxel, compactions),
                            voxel.primitives().deviceAddress(),
                            voxel.positions().deviceAddress(),
                            0L,
                            // Dynamic previous positions describe only the base BLAS. Publishing
                            // that address for an instanced voxel BLAS would make its local
                            // triangle id index unrelated storage.
                            cluster.dynamic() ? 0L : cluster.lightAddress(),
                            worldLightAddress,
                            worldLightLeafAddress,
                            voxel.cutoutPrimitiveBase(),
                            voxel.transmissivePrimitiveBase(),
                            voxel.opaqueMacroTriangleBase(),
                            voxel.cutoutMacroTriangleBase(),
                            voxel.transmissiveMacroTriangleBase(),
                            cluster.dynamic()
                                    ? CpuLightTree.NO_INDEX
                                    : worldLightTree.lightPath(clusterIndex),
                            cluster.lights().emitterCount(),
                            worldLightLeafCount,
                            0xff,
                            0x8000_0000 | instances.tintId(index),
                            sectionX + instances.translationX(index),
                            sectionY + instances.translationY(index),
                            sectionZ + instances.translationZ(index),
                            sectionX,
                            sectionY,
                            sectionZ);
                }
            }
        });
    }

    private static long compactionAddress(
            PreparedBlas blas,
            Map<PreparedBlas, PreparedBlas.Compaction> compactions) {
        PreparedBlas.Compaction compaction = compactions.get(blas);
        return compaction == null
                ? blas.accelerationStructure().deviceAddress()
                : compaction.targetDeviceAddress();
    }

    private PreparedUpdate prepareUpdate(
            List<GpuCluster> finalClusters,
            LongOpenHashSet removedKeys,
            TopLevelAccelerationStructure replacementTlas,
            VulkanBuffer replacementWorldLights,
            CpuWorldLightTree.Result replacementWorldLightTree,
            boolean replaceWorldLights,
            List<TerrainOccluderChange> occluderChanges,
            int nextOriginX,
            int nextOriginY,
            int nextOriginZ,
            boolean replaceDynamic) {
        List<GpuCluster> retired = new ArrayList<>();
        Long2ObjectOpenHashMap<GpuCluster> nextResident =
                new Long2ObjectOpenHashMap<>(finalClusters.size());
        GpuCluster nextDynamic = null;
        int tlasInstances = 0;
        long uniqueTriangles = 0L;
        long instancedTriangles = 0L;
        long surfaceRelationSourceBytes = 0L;
        long surfaceRelationGpuBytes = 0L;
        int areaLightEmitters = 0;
        ArrayList<TextureTintUsage> textureTintUsage = this.measurementsEnabled
                ? new ArrayList<>(finalClusters.size())
                : null;
        ArrayList<SurfaceTintUsage> surfaceTintUsage = this.measurementsEnabled
                ? new ArrayList<>(finalClusters.size())
                : null;
        ArrayList<MaterialTableCandidate> materialTableCandidates = this.measurementsEnabled
                ? new ArrayList<>(finalClusters.size())
                : null;
        IdentityHashMap<PreparedBlas, Boolean> uniqueBlases = new IdentityHashMap<>();
        for (var entry : this.resident.long2ObjectEntrySet()) {
            if (removedKeys.contains(entry.getLongKey())) {
                retired.add(entry.getValue());
            }
        }
        for (GpuCluster cluster : finalClusters) {
            if (cluster.dynamic()) {
                if (nextDynamic != null) {
                    throw new IllegalStateException(
                            "Prepared scene contains more than one dynamic cluster");
                }
                nextDynamic = cluster;
            } else if (nextResident.put(cluster.key(), cluster) != null) {
                throw new IllegalStateException(
                        "Prepared terrain scene contains a duplicate logical cluster");
            }
            tlasInstances = Math.addExact(
                    tlasInstances, cluster.tlasInstanceCount());
            cluster.forEachBlas(blas -> uniqueBlases.put(blas, Boolean.TRUE));
            instancedTriangles = Math.addExact(
                    instancedTriangles, cluster.instancedTriangleCount());
            surfaceRelationSourceBytes = Math.addExact(
                    surfaceRelationSourceBytes, cluster.surfaceRelationSourceBytes());
            surfaceRelationGpuBytes = Math.addExact(
                    surfaceRelationGpuBytes, cluster.surfaceRelationGpuBytes());
            areaLightEmitters = Math.addExact(
                    areaLightEmitters, cluster.lights().emitterCount());
            if (textureTintUsage != null) {
                textureTintUsage.add(cluster.textureTintUsage());
                surfaceTintUsage.add(cluster.surfaceTintUsage());
                materialTableCandidates.add(cluster.materialTableCandidate());
            }
        }
        for (PreparedBlas blas : uniqueBlases.keySet()) {
            uniqueTriangles = Math.addExact(
                    uniqueTriangles, GpuCluster.triangleCount(blas));
        }
        if (replaceDynamic
                && this.dynamicResident != null
                && this.dynamicResident != nextDynamic) {
            retired.add(this.dynamicResident);
        }
        SceneStatistics statistics = new SceneStatistics(
                tlasInstances,
                uniqueTriangles,
                instancedTriangles,
                areaLightEmitters,
                replacementWorldLightTree.nodeCount(),
                textureTintUsage == null
                        ? TextureTintUsage.EMPTY
                        : TextureTintUsage.combine(textureTintUsage),
                surfaceTintUsage == null
                        ? SurfaceTintUsage.EMPTY
                        : SurfaceTintUsage.combine(surfaceTintUsage),
                materialTableCandidates == null
                        ? MaterialTableCandidate.EMPTY
                        : MaterialTableCandidate.combine(materialTableCandidates),
                surfaceRelationSourceBytes,
                surfaceRelationGpuBytes);

        TopLevelAccelerationStructure previousTlas = this.currentTlas;
        VulkanBuffer previousWorldLights = replaceWorldLights ? this.currentWorldLights : null;
        long nextRevision = this.revision + 1L;
        long nextOccluderRevision = occluderChanges.isEmpty()
                ? this.occluderRevision
                : this.occluderRevision + 1L;
        ResidentSceneView nextView = replacementTlas == null || finalClusters.isEmpty()
                ? null
                : new ResidentSceneView(
                        replacementTlas.handle(),
                        replacementTlas.sectionTableAddress(),
                        new TintSampleBinding(
                                this.tintSamples.buffer().handle(),
                                this.tintSamples.buffer().size()),
                        new MaterialCoreBinding(
                                this.materialCoreRecords.handle(),
                                this.materialCoreRecords.size()),
                        nextOriginX,
                        nextOriginY,
                        nextOriginZ,
                        nextRevision,
                        this.resetRevision,
                        nextOccluderRevision,
                        occluderChanges,
                        statistics);

        return new PreparedUpdate(
                nextResident,
                nextDynamic,
                replacementTlas,
                replacementWorldLights,
                replacementWorldLightTree,
                replaceWorldLights,
                nextOriginX,
                nextOriginY,
                nextOriginZ,
                nextRevision,
                nextOccluderRevision,
                nextView,
                retired,
                previousTlas,
                previousWorldLights);
    }

    /** Publishes a fully allocated scene state; this path must remain allocation- and I/O-free. */
    private void publish(PreparedUpdate update) {
        this.resident = update.resident();
        this.dynamicResident = update.dynamicResident();
        this.currentTlas = update.tlas();
        if (update.replaceWorldLights()) {
            this.currentWorldLights = update.worldLights();
            this.currentWorldLightTree = update.worldLightTree();
        }
        this.originX = update.originX();
        this.originY = update.originY();
        this.originZ = update.originZ();
        this.revision = update.revision();
        this.occluderRevision = update.occluderRevision();
        this.currentView = update.view();
    }

    private RuntimeException retire(
            PreparedUpdate update, RuntimeException retirementFailure) {
        for (GpuCluster removed : update.retired()) {
            retirementFailure = ResourceCleanup.run(
                    () -> {
                        Destroyable cleanup = removed.prepareRetirement(
                                this.voxelBlasPool);
                        this.context.defer(cleanup);
                    },
                    retirementFailure);
        }
        if (update.previousTlas() != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(update.previousTlas()::release),
                    retirementFailure);
        }
        if (update.previousWorldLights() != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(update.previousWorldLights()),
                    retirementFailure);
        }
        return retirementFailure;
    }

    private TopLevelAccelerationStructure acquireTlas(int capacity) {
        for (int index = 0; index < this.tlasSlots.size(); index++) {
            TopLevelAccelerationStructure slot = this.tlasSlots.get(index);
            if (slot == this.currentTlas || !slot.tryAcquire()) {
                continue;
            }
            if (slot.hasCapacity(capacity)) {
                return slot;
            }
            slot.destroy();
            TopLevelAccelerationStructure replacement = TopLevelAccelerationStructure.create(
                    this.context, capacity, "Prime TLAS slot " + index);
            if (!replacement.tryAcquire()) {
                throw new IllegalStateException("New TLAS slot was unexpectedly busy");
            }
            this.tlasSlots.set(index, replacement);
            return replacement;
        }
        if (this.tlasSlots.size() >= TLAS_SLOT_COUNT) {
            return null;
        }
        int index = this.tlasSlots.size();
        TopLevelAccelerationStructure slot = TopLevelAccelerationStructure.create(
                this.context, capacity, "Prime TLAS slot " + index);
        if (!slot.tryAcquire()) {
            throw new IllegalStateException("New TLAS slot was unexpectedly busy");
        }
        this.tlasSlots.add(slot);
        return slot;
    }

    private TopLevelAccelerationStructure acquireCompactionTlas(int capacity) {
        TopLevelAccelerationStructure replacement = this.acquireTlas(capacity);
        if (replacement == null) {
            return null;
        }
        /*
         * Dynamic capture occurs later in the frame and has the same immediate-publication
         * priority as terrain uploads. Prove that one additional TLAS slot is available before
         * compact targets consume memory; no other render-thread work can claim it in between.
         */
        TopLevelAccelerationStructure dynamicReserve;
        try {
            dynamicReserve = this.acquireTlas(capacity);
        } catch (RuntimeException exception) {
            replacement.release();
            throw exception;
        }
        if (dynamicReserve == null) {
            replacement.release();
            return null;
        }
        dynamicReserve.release();
        return replacement;
    }

    private GpuCluster prepareCluster(
            CompiledCluster upload,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer) {
        CpuClusterMesh mesh = upload.mesh();
        PreparedBlas.CompactionPolicy compactionPolicy =
                compactionPolicy(upload.dynamic());
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        VulkanBuffer lights = null;
        VulkanBuffer motion = null;
        PreparedBlas blas = null;
        DynamicBufferPool.Lease dynamicBuffers = null;
        GpuSurfaceRelationTable.Encoding relationEncoding =
                GpuSurfaceRelationTable.encodeResolved(
                        new int[0], 0, mesh.lights().emitterCount());
        ArrayList<PreparedBlas> voxelBlases =
                new ArrayList<>(mesh.voxelMeshes().size());
        try {
            if (mesh.triangleCount() != 0L) {
                int primitiveCount = Math.toIntExact(mesh.primitiveCount());
                int[] mediumMap = this.mediumIds.resolve(mesh.mediumCatalog());
                MaterialIdResolver.Cache materialCache = MaterialIdResolver.cache(
                        mesh.mediumCatalog(), this.materialIds::resolve);
                IntUnaryOperator tintResolver = this.tintSamples::resolve;
                int[] sourceRelations = mesh.surfaceRelationRecords();
                int[] relations = sourceRelations;
                if (relations.length != 0) {
                    relations = MediumIdResolver.surfaceRelations(
                            relations, primitiveCount, mediumMap);
                    relations = MaterialIdResolver.surfaceRelations(
                            relations,
                            sourceRelations,
                            primitiveCount,
                            materialCache);
                    relations = TintIdResolver.surfaceRelations(
                            relations,
                            sourceRelations,
                            primitiveCount,
                            tintResolver);
                }
                relationEncoding = GpuSurfaceRelationTable.encodeResolved(
                        relations, primitiveCount, mesh.lights().emitterCount());
                long surfaceRelationBytes = relationEncoding.byteSize();
                long primitiveBytes = Math.addExact(
                        mesh.primitiveBytes(), surfaceRelationBytes);
                if (upload.dynamic()) {
                    dynamicBuffers = this.dynamicBufferPool.acquire(
                            mesh.positionBytes(),
                            primitiveBytes,
                            (long) upload.motionPositions().length * Float.BYTES);
                    positions = dynamicBuffers.positions();
                    primitives = dynamicBuffers.primitives();
                    motion = dynamicBuffers.motion();
                } else {
                    positions = this.context.createBuffer(
                            mesh.positionBytes(),
                            VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                            false,
                            "Prime cluster " + upload.key() + " positions");
                    primitives = this.context.createBuffer(
                            primitiveBytes,
                            VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                    | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            false,
                            "Prime cluster " + upload.key() + " primitives");
                }
                copyMeshSegments(
                        commandBuffer,
                        stagingBatch,
                        mesh,
                        mediumMap,
                        materialCache,
                        tintResolver,
                        relationEncoding,
                        positions,
                        primitives);
                if (upload.dynamic()) {
                    blas = PreparedBlas.createWithBorrowedGeometry(
                            this.context,
                            this.opacityMicromapPool,
                            positions,
                            primitives,
                            mesh.opacityMicromap(),
                            stagingBatch,
                            commandBuffer,
                            mesh.opaqueTriangleCount(),
                            mesh.cutoutTriangleCount(),
                            mesh.transmissiveTriangleCount(),
                            mesh.opaqueMacroTriangleCount(),
                            mesh.cutoutMacroTriangleCount(),
                            mesh.transmissiveMacroTriangleCount(),
                            compactionPolicy,
                            "Prime cluster " + upload.key() + " BLAS");
                } else {
                    blas = PreparedBlas.create(
                            this.context,
                            this.opacityMicromapPool,
                            positions,
                            primitives,
                            mesh.opacityMicromap(),
                            stagingBatch,
                            commandBuffer,
                            mesh.opaqueTriangleCount(),
                            mesh.cutoutTriangleCount(),
                            mesh.transmissiveTriangleCount(),
                            mesh.opaqueMacroTriangleCount(),
                            mesh.cutoutMacroTriangleCount(),
                            mesh.transmissiveMacroTriangleCount(),
                            compactionPolicy,
                            "Prime cluster " + upload.key() + " BLAS");
                }
            }
            if (!mesh.lights().isEmpty()) {
                lights = this.context.createBuffer(
                        mesh.lights().byteSize(),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        "Prime cluster " + upload.key() + " lights");
            }
            if (lights != null) {
                copyBuffer(
                        commandBuffer,
                        stagingBatch.write(
                                mesh.lights().relocate(
                                        lights.deviceAddress(),
                                        this.tintSamples::resolve,
                                        relationEncoding.completedEmitterOffsets()),
                                16L),
                        lights);
            }
            CompiledClusterLights.Summary lightSummary = mesh.lights().summary();
            for (int index = 0; index < mesh.voxelMeshes().size(); index++) {
                CpuVoxelMesh voxelMesh = mesh.voxelMeshes().get(index);
                String label = "Prime cluster " + upload.key()
                        + " voxel mesh " + index;
                voxelBlases.add(this.voxelBlasPool.acquire(
                        voxelMesh,
                        () -> this.prepareVoxelMesh(
                                voxelMesh,
                                stagingBatch,
                                commandBuffer,
                                this.tintSamples::resolve,
                                label)));
            }
            if (upload.dynamic() && mesh.triangleCount() != 0L) {
                copyBuffer(
                        commandBuffer,
                        stagingBatch.write(
                                upload.motionPositions(), Float.BYTES),
                        motion);
            }
            return new GpuCluster(
                    upload.key(),
                    upload.clusterX(),
                    upload.clusterY(),
                    upload.clusterZ(),
                    blas,
                    voxelBlases,
                    ResolvedVoxelInstances.resolve(
                            mesh.voxelInstances(), this.tintSamples::resolve),
                    !relationEncoding.isEmpty()
                            ? primitives.deviceAddress() + mesh.primitiveBytes()
                            : 0L,
                    lights,
                    motion,
                    lightSummary,
                    this.measurementsEnabled
                            ? TextureTintUsage.measure(mesh)
                            : TextureTintUsage.EMPTY,
                    this.measurementsEnabled
                            ? mesh.surfaceTintUsage()
                            : SurfaceTintUsage.EMPTY,
                    this.measurementsEnabled
                            ? MaterialTableCandidate.measure(mesh)
                            : MaterialTableCandidate.EMPTY,
                    mesh.surfaceRelationBytes(),
                    relationEncoding.byteSize(),
                    upload.dynamic(),
                    dynamicBuffers);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (blas != null) {
                blas.releaseSharedResources();
                failure = ResourceCleanup.run(blas::destroyAllResources, failure);
            } else if (dynamicBuffers == null) {
                failure = ResourceCleanup.destroy(positions, failure);
                failure = ResourceCleanup.destroy(primitives, failure);
            }
            for (PreparedBlas voxelBlas : voxelBlases) {
                PreparedBlas released = this.voxelBlasPool.release(voxelBlas);
                if (released != null) {
                    released.releaseSharedResources();
                    failure = ResourceCleanup.run(
                            released::destroyAllResources, failure);
                }
            }
            failure = ResourceCleanup.destroy(lights, failure);
            if (dynamicBuffers != null) {
                DynamicBufferPool.Lease failedBuffers = dynamicBuffers;
                failure = ResourceCleanup.run(failedBuffers::release, failure);
            } else {
                failure = ResourceCleanup.destroy(motion, failure);
            }
            throw failure;
        }
    }

    private PreparedBlas prepareVoxelMesh(
            CpuVoxelMesh mesh,
            StagingArena.Batch stagingBatch,
            VkCommandBuffer commandBuffer,
            IntUnaryOperator tintResolver,
            String label) {
        VulkanBuffer positions = null;
        VulkanBuffer primitives = null;
        PreparedBlas blas = null;
        try {
            positions = this.context.createBuffer(
                    mesh.positionBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    label + " positions");
            primitives = this.context.createBuffer(
                    mesh.primitiveBytes(),
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    label + " primitives");
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(mesh.positions(), Float.BYTES),
                    positions);
            copyBuffer(
                    commandBuffer,
                    stagingBatch.write(
                            TintIdResolver.primitiveRecords(
                                    MaterialIdResolver.primitiveRecords(
                                            mesh.primitiveRecords(),
                                            mesh.primitiveRecords(),
                                            CompiledClusterLights.EMPTY,
                                            MaterialIdResolver.cache(
                                                    List.of(),
                                                    this.materialIds::resolve)),
                                    mesh.primitiveRecords(),
                                    tintResolver),
                            Integer.BYTES),
                    primitives);
            blas = PreparedBlas.create(
                    this.context,
                    this.opacityMicromapPool,
                    positions,
                    primitives,
                    mesh.opacityMicromap(),
                    stagingBatch,
                    commandBuffer,
                    mesh.opaqueTriangleCount(),
                    mesh.cutoutTriangleCount(),
                    mesh.transmissiveTriangleCount(),
                    PreparedBlas.CompactionPolicy.ENABLED,
                    label + " BLAS");
            return blas;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (blas != null) {
                failure = ResourceCleanup.run(
                        blas::destroyAllResources, failure);
            } else {
                failure = ResourceCleanup.destroy(positions, failure);
                failure = ResourceCleanup.destroy(primitives, failure);
            }
            throw failure;
        }
    }

    private static void copyMeshSegments(
            VkCommandBuffer commandBuffer,
            StagingArena.Batch staging,
            CpuClusterMesh mesh,
            int[] localToRendererMediumId,
            MaterialIdResolver.Cache materialCache,
            IntUnaryOperator tintResolver,
            GpuSurfaceRelationTable.Encoding relationEncoding,
            VulkanBuffer positions,
            VulkanBuffer primitives) {
        long[] positionCursors = new long[] {
            0L,
            Math.multiplyExact(mesh.opaqueTriangleCount(), 9L * Float.BYTES),
            Math.multiplyExact(
                    Math.addExact(mesh.opaqueTriangleCount(), mesh.cutoutTriangleCount()),
                    9L * Float.BYTES)
        };
        long[] primitiveCursors = new long[] {
            0L,
            Math.multiplyExact(
                    mesh.opaquePrimitiveCount(),
                    (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES),
            Math.multiplyExact(
                    Math.addExact(mesh.opaquePrimitiveCount(), mesh.cutoutPrimitiveCount()),
                    (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES)
        };
        int[] relationCursors = new int[] {
            0,
            Math.toIntExact(mesh.opaquePrimitiveCount()),
            Math.toIntExact(
                    Math.addExact(
                            mesh.opaquePrimitiveCount(), mesh.cutoutPrimitiveCount()))
        };
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            int[] primitiveRecords = MediumIdResolver.primitiveRecords(
                    segment.primitiveRecords(), localToRendererMediumId);
            primitiveRecords = MaterialIdResolver.primitiveRecords(
                    primitiveRecords,
                    segment.primitiveRecords(),
                    mesh.lights(),
                    materialCache);
            primitiveRecords = TintIdResolver.primitiveRecords(
                    primitiveRecords, segment.primitiveRecords(), tintResolver);
            primitiveRecords = GpuSurfaceRelationTable.primitiveRecords(
                    primitiveRecords,
                    segment.opaquePrimitiveCount(),
                    segment.cutoutPrimitiveCount(),
                    segment.transmissivePrimitiveCount(),
                    relationCursors[0],
                    relationCursors[1],
                    relationCursors[2],
                    relationEncoding);
            int sourcePosition = 0;
            int sourcePrimitive = 0;
            for (int category = 0; category < 3; category++) {
                int triangleCount = switch (category) {
                    case 0 -> segment.opaqueTriangleCount();
                    case 1 -> segment.cutoutTriangleCount();
                    default -> segment.transmissiveTriangleCount();
                };
                int primitiveCount = switch (category) {
                    case 0 -> segment.opaquePrimitiveCount();
                    case 1 -> segment.cutoutPrimitiveCount();
                    default -> segment.transmissivePrimitiveCount();
                };
                int positionWords = Math.multiplyExact(triangleCount, 9);
                int primitiveWords = Math.multiplyExact(
                        primitiveCount, CpuSectionMesh.PRIMITIVE_WORDS);
                if (triangleCount != 0) {
                    StagingArena.Slice positionSlice = staging.write(
                            segment.positions(), sourcePosition, positionWords, Float.BYTES);
                    copyBuffer(
                            commandBuffer,
                            positionSlice,
                            positions,
                            positionCursors[category]);
                    StagingArena.Slice primitiveSlice = staging.write(
                            primitiveRecords,
                            sourcePrimitive,
                            primitiveWords,
                            Integer.BYTES);
                    copyBuffer(
                            commandBuffer,
                            primitiveSlice,
                            primitives,
                            primitiveCursors[category]);
                }
                sourcePosition += positionWords;
                sourcePrimitive += primitiveWords;
                positionCursors[category] += (long) positionWords * Float.BYTES;
                primitiveCursors[category] += (long) primitiveWords * Integer.BYTES;
                relationCursors[category] += primitiveCount;
            }
        }
        if (!relationEncoding.isEmpty()) {
            copyBuffer(
                    commandBuffer,
                    staging.write(relationEncoding.words(), Integer.BYTES),
                    primitives,
                    mesh.primitiveBytes());
        }
    }

    private static void copyBuffer(VkCommandBuffer commandBuffer, StagingArena.Slice source, VulkanBuffer destination) {
        copyBuffer(commandBuffer, source, destination, 0L);
    }

    private static void copyBuffer(
            VkCommandBuffer commandBuffer,
            StagingArena.Slice source,
            VulkanBuffer destination,
            long destinationOffset) {
        if (destinationOffset < 0L
                || source.size() > destination.size()
                || destinationOffset > destination.size() - source.size()) {
            throw new IndexOutOfBoundsException(
                    "Vulkan copy exceeds destination buffer");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(source.offset())
                    .dstOffset(destinationOffset)
                    .size(source.size());
            VK12.vkCmdCopyBuffer(commandBuffer, source.buffer(), destination.handle(), copy);
        }
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                sourceStage,
                sourceAccess,
                destinationStage,
                destinationAccess);
    }

    public static PreparedBlas.CompactionPolicy compactionPolicy(boolean dynamic) {
        return dynamic
                ? PreparedBlas.CompactionPolicy.DISABLED
                : PreparedBlas.CompactionPolicy.ENABLED;
    }

    private record PreparedUpdate(
            Long2ObjectOpenHashMap<GpuCluster> resident,
            @Nullable GpuCluster dynamicResident,
            TopLevelAccelerationStructure tlas,
            VulkanBuffer worldLights,
            CpuWorldLightTree.Result worldLightTree,
            boolean replaceWorldLights,
            int originX,
            int originY,
            int originZ,
            long revision,
            long occluderRevision,
            ResidentSceneView view,
            List<GpuCluster> retired,
            TopLevelAccelerationStructure previousTlas,
            VulkanBuffer previousWorldLights) {
    }

    /** Immutable GPU-resident scene identity consumed by one or more frame plans. */
    public record ResidentSceneView(
            long tlas,
            long sectionTableAddress,
            TintSampleBinding tintSamples,
            MaterialCoreBinding materialCore,
            int originX,
            int originY,
            int originZ,
            long revision,
            long resetRevision,
            long occluderRevision,
            List<TerrainOccluderChange> occluderChanges,
            SceneStatistics statistics) implements SceneRevisionView {
        public ResidentSceneView {
            tintSamples = java.util.Objects.requireNonNull(
                    tintSamples, "tintSamples");
            materialCore = java.util.Objects.requireNonNull(
                    materialCore, "materialCore");
            occluderChanges = List.copyOf(occluderChanges);
            statistics = java.util.Objects.requireNonNull(statistics, "statistics");
        }

        public ResidentSceneView(
                long tlas,
                long sectionTableAddress,
                TintSampleBinding tintSamples,
                int originX,
                int originY,
                int originZ,
                long revision,
                long resetRevision,
                long occluderRevision,
                List<TerrainOccluderChange> occluderChanges,
                SceneStatistics statistics) {
            this(
                    tlas,
                    sectionTableAddress,
                    tintSamples,
                    MaterialCoreBinding.EMPTY,
                    originX,
                    originY,
                    originZ,
                    revision,
                    resetRevision,
                    occluderRevision,
                    occluderChanges,
                    statistics);
        }

        public ResidentSceneView(
                long tlas,
                long sectionTableAddress,
                int originX,
                int originY,
                int originZ,
                long revision,
                long resetRevision,
                long occluderRevision,
                List<TerrainOccluderChange> occluderChanges) {
            this(
                    tlas,
                    sectionTableAddress,
                    TintSampleBinding.EMPTY,
                    MaterialCoreBinding.EMPTY,
                    originX,
                    originY,
                    originZ,
                    revision,
                    resetRevision,
                    occluderRevision,
                    occluderChanges,
                    SceneStatistics.EMPTY);
        }

        public ResidentSceneView(
                long tlas,
                long sectionTableAddress,
                int originX,
                int originY,
                int originZ,
                long revision,
                long resetRevision) {
            this(
                    tlas,
                    sectionTableAddress,
                    TintSampleBinding.EMPTY,
                    MaterialCoreBinding.EMPTY,
                    originX,
                    originY,
                    originZ,
                    revision,
                    resetRevision,
                    revision,
                    List.of(),
                    SceneStatistics.EMPTY);
        }
    }

    public record SceneStatistics(
            int tlasInstanceCount,
            long uniqueBlasTriangleCount,
            long instancedTriangleCount,
            int areaLightEmitterCount,
            int topLevelLightTreeNodeCount,
            TextureTintUsage textureTintUsage,
            SurfaceTintUsage surfaceTintUsage,
            MaterialTableCandidate materialTableCandidate,
            long surfaceRelationSourceBytes,
            long surfaceRelationGpuBytes) {
        static final SceneStatistics EMPTY =
                new SceneStatistics(
                        0,
                        0L,
                        0L,
                        0,
                        0,
                        TextureTintUsage.EMPTY,
                        SurfaceTintUsage.EMPTY,
                        MaterialTableCandidate.EMPTY,
                        0L,
                        0L);

        public SceneStatistics(
                int tlasInstanceCount,
                long uniqueBlasTriangleCount,
                long instancedTriangleCount,
                int areaLightEmitterCount,
                int topLevelLightTreeNodeCount,
                TextureTintUsage textureTintUsage,
                MaterialTableCandidate materialTableCandidate,
                long surfaceRelationSourceBytes,
                long surfaceRelationGpuBytes) {
            this(
                    tlasInstanceCount,
                    uniqueBlasTriangleCount,
                    instancedTriangleCount,
                    areaLightEmitterCount,
                    topLevelLightTreeNodeCount,
                    textureTintUsage,
                    SurfaceTintUsage.EMPTY,
                    materialTableCandidate,
                    surfaceRelationSourceBytes,
                    surfaceRelationGpuBytes);
        }

        public SceneStatistics(
                int tlasInstanceCount,
                long uniqueBlasTriangleCount,
                long instancedTriangleCount,
                int areaLightEmitterCount,
                int topLevelLightTreeNodeCount,
                TextureTintUsage textureTintUsage,
                MaterialTableCandidate materialTableCandidate) {
            this(
                    tlasInstanceCount,
                    uniqueBlasTriangleCount,
                    instancedTriangleCount,
                    areaLightEmitterCount,
                    topLevelLightTreeNodeCount,
                    textureTintUsage,
                    SurfaceTintUsage.EMPTY,
                    materialTableCandidate,
                    0L,
                    0L);
        }

        public SceneStatistics(
                int tlasInstanceCount,
                long uniqueBlasTriangleCount,
                long instancedTriangleCount,
                int areaLightEmitterCount,
                int topLevelLightTreeNodeCount,
                TextureTintUsage textureTintUsage,
                SurfaceTintUsage surfaceTintUsage,
                MaterialTableCandidate materialTableCandidate) {
            this(
                    tlasInstanceCount,
                    uniqueBlasTriangleCount,
                    instancedTriangleCount,
                    areaLightEmitterCount,
                    topLevelLightTreeNodeCount,
                    textureTintUsage,
                    surfaceTintUsage,
                    materialTableCandidate,
                    0L,
                    0L);
        }

        public SceneStatistics(
                int tlasInstanceCount,
                long uniqueBlasTriangleCount,
                long instancedTriangleCount,
                int areaLightEmitterCount,
                int topLevelLightTreeNodeCount,
                TextureTintUsage textureTintUsage) {
            this(
                    tlasInstanceCount,
                    uniqueBlasTriangleCount,
                    instancedTriangleCount,
                    areaLightEmitterCount,
                    topLevelLightTreeNodeCount,
                    textureTintUsage,
                    SurfaceTintUsage.EMPTY,
                    MaterialTableCandidate.EMPTY,
                    0L,
                    0L);
        }

        public SceneStatistics(
                int tlasInstanceCount,
                long uniqueBlasTriangleCount,
                long instancedTriangleCount,
                int areaLightEmitterCount,
                int topLevelLightTreeNodeCount) {
            this(
                    tlasInstanceCount,
                    uniqueBlasTriangleCount,
                    instancedTriangleCount,
                    areaLightEmitterCount,
                    topLevelLightTreeNodeCount,
                    TextureTintUsage.EMPTY,
                    SurfaceTintUsage.EMPTY,
                    MaterialTableCandidate.EMPTY,
                    0L,
                    0L);
        }

        public SceneStatistics {
            textureTintUsage = java.util.Objects.requireNonNull(
                    textureTintUsage, "textureTintUsage");
            surfaceTintUsage = java.util.Objects.requireNonNull(
                    surfaceTintUsage, "surfaceTintUsage");
            materialTableCandidate = java.util.Objects.requireNonNull(
                    materialTableCandidate, "materialTableCandidate");
            if (tlasInstanceCount < 0
                    || uniqueBlasTriangleCount < 0L
                    || instancedTriangleCount < 0L
                    || areaLightEmitterCount < 0
                    || topLevelLightTreeNodeCount < 0
                    || surfaceRelationSourceBytes < 0L
                    || surfaceRelationGpuBytes < 0L
                    || surfaceRelationGpuBytes > surfaceRelationSourceBytes) {
                throw new IllegalArgumentException(
                        "Resident scene statistics must be non-negative");
            }
        }
    }

    /** Stable borrowed descriptor identity owned by this scene's render-thread lifetime. */
    public record TintSampleBinding(long buffer, long bytes) {
        static final TintSampleBinding EMPTY = new TintSampleBinding(0L, 0L);

        public TintSampleBinding {
            if ((buffer == 0L) != (bytes == 0L) || bytes < 0L) {
                throw new IllegalArgumentException("Tint-sample binding is incomplete");
            }
        }

        public boolean present() {
            return this.buffer != 0L;
        }
    }

    /** Stable fixed-width material-core descriptor owned by the renderer scene lifetime. */
    public record MaterialCoreBinding(long buffer, long bytes) {
        static final MaterialCoreBinding EMPTY = new MaterialCoreBinding(0L, 0L);

        public MaterialCoreBinding {
            if ((buffer == 0L) != (bytes == 0L)
                    || bytes < 0L
                    || bytes != 0L && bytes != MaterialIdRegistry.BUFFER_BYTES) {
                throw new IllegalArgumentException("Material-core binding is incomplete");
            }
        }

        public boolean present() {
            return this.buffer != 0L;
        }
    }

    public record MediumIdStatistics(int assignedCount, long highWaterId) {
        public MediumIdStatistics {
            if (assignedCount < 1 || highWaterId < MediumIdRegistry.WATER_ID
                    || highWaterId > MaterialIdResolver.MAX_ID) {
                throw new IllegalArgumentException("Invalid renderer MediumId statistics");
            }
        }
    }

    public record MaterialIdStatistics(int assignedCount, int highWaterId) {
        public MaterialIdStatistics {
            if (assignedCount < 0
                    || assignedCount > MaterialIdResolver.MAX_ID
                    || highWaterId < 0
                    || highWaterId > MaterialIdResolver.MAX_ID
                    || assignedCount != highWaterId) {
                throw new IllegalArgumentException("Invalid renderer MaterialId statistics");
            }
        }
    }

    public record TintIdStatistics(int assignedCount, int highWaterId) {
        public TintIdStatistics {
            if (assignedCount < 1
                    || assignedCount > TintSampleTable.MAX_TINT_ID + 1
                    || highWaterId < 0
                    || highWaterId > TintSampleTable.MAX_TINT_ID
                    || assignedCount != highWaterId + 1) {
                throw new IllegalArgumentException("Invalid renderer TintId statistics");
            }
        }

        public int entryBytes() {
            return TintSampleTable.ENTRY_SIZE;
        }

        public long activeSampleBytes() {
            return Math.multiplyExact((long) this.assignedCount, this.entryBytes());
        }

        public long reservedSampleBytes() {
            return Math.multiplyExact(
                    (long) TintSampleTable.MAX_TINT_ID + 1L,
                    this.entryBytes());
        }
    }

    public record CompactionStats(
            int waiting,
            int ready,
            int retiring,
            long waitingSourceBytes,
            long readySourceBytes,
            long inFlightSourceBytes,
            long knownReclaimableBytes,
            long reservedTargetBytes,
            long highWaterTargetBytes,
            long reclaimedBytes,
            long completedCount) {}
}
