package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.TriangleLayout;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

public final class PreparedBlas {
    private static final int GEOMETRY_COUNT = 3;
    private static final long MAX_TRIANGLES_PER_GEOMETRY = 0x1_0000_0000L / 3L;
    private static final long MAX_PRIMITIVE_RECORDS = 0x1_0000_0000L;
    private static final long MAX_IDENTITY_TRIANGLES = 0x8000_0000L;
    private static final int BASE_BUILD_FLAGS =
            KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

    private final VulkanContext context;
    private AccelerationStructure accelerationStructure;
    private VulkanBuffer positions;
    private final VulkanBuffer primitives;
    private VulkanBuffer scratch;
    private final OpacityMicromap opacityMicromap;
    private VulkanBuffer compactionResult;
    private long compactionQueryPool;
    private final CompactionPolicy compactionPolicy;
    private final boolean ownsGeometryBuffers;
    private CompactionState compactionState;
    private long compactionSourceSize;
    private long compactedSize;
    private final String label;
    private final TriangleLayout triangleLayout;

    private PreparedBlas(
            VulkanContext context,
            AccelerationStructure accelerationStructure,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            VulkanBuffer scratch,
            OpacityMicromap opacityMicromap,
            VulkanBuffer compactionResult,
            long compactionQueryPool,
            TriangleLayout triangleLayout,
            CompactionPolicy compactionPolicy,
            boolean ownsGeometryBuffers,
            String label) {
        this.context = context;
        this.accelerationStructure = accelerationStructure;
        this.positions = positions;
        this.primitives = primitives;
        this.scratch = scratch;
        this.opacityMicromap = opacityMicromap;
        this.compactionResult = compactionResult;
        this.compactionQueryPool = compactionQueryPool;
        this.triangleLayout = triangleLayout;
        this.compactionPolicy = compactionPolicy;
        this.ownsGeometryBuffers = ownsGeometryBuffers;
        this.compactionState = compactionPolicy == CompactionPolicy.ENABLED
                ? CompactionState.BUILD_PENDING
                : CompactionState.DISABLED;
        this.label = label;
    }

    public enum CompactionPolicy {
        ENABLED,
        DISABLED
    }

    public enum CompactionState {
        DISABLED,
        BUILD_PENDING,
        QUERY_PENDING,
        QUERY_READY,
        READY,
        PREPARED,
        ABANDONING_TARGET,
        RETIRING_SOURCE,
        COMPACTED,
        NOT_BENEFICIAL,
        DESTROYED
    }

    enum CompactionEvent {
        BUILD_SUBMITTED,
        BUILD_SUBMISSION_FAILED,
        QUERY_COMPLETED,
        BENEFICIAL_SIZE_RESOLVED,
        UNBENEFICIAL_SIZE_RESOLVED,
        TARGET_PREPARED,
        PREPARED_TARGET_ROLLED_BACK,
        TARGET_ABANDONED,
        ABANDONED_TARGET_RETIRED,
        TARGET_PUBLISHED,
        SOURCE_RETIRED,
        DESTROYED
    }

    public static PreparedBlas create(
            VulkanContext context,
            OpacityMicromapPool opacityMicromapPool,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            OpacityMicromapData opacityMicromapData,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            TriangleLayout triangleLayout,
            CompactionPolicy compactionPolicy,
            String label) {
        return create(
                context,
                opacityMicromapPool,
                positions,
                primitives,
                opacityMicromapData,
                staging,
                commandBuffer,
                triangleLayout,
                compactionPolicy,
                true,
                label);
    }

    /** Creates a BLAS whose position and primitive buffers outlive this build object. */
    public static PreparedBlas createWithBorrowedGeometry(
            VulkanContext context,
            OpacityMicromapPool opacityMicromapPool,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            OpacityMicromapData opacityMicromapData,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            TriangleLayout triangleLayout,
            CompactionPolicy compactionPolicy,
            String label) {
        return create(
                context,
                opacityMicromapPool,
                positions,
                primitives,
                opacityMicromapData,
                staging,
                commandBuffer,
                triangleLayout,
                compactionPolicy,
                false,
                label);
    }

    private static PreparedBlas create(
            VulkanContext context,
            OpacityMicromapPool opacityMicromapPool,
            VulkanBuffer positions,
            VulkanBuffer primitives,
            OpacityMicromapData opacityMicromapData,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            TriangleLayout triangleLayout,
            CompactionPolicy compactionPolicy,
            boolean ownsGeometryBuffers,
            String label) {
        if (compactionPolicy == null) {
            throw new IllegalArgumentException("BLAS compaction policy must not be null");
        }
        if (triangleLayout == null) {
            throw new IllegalArgumentException("BLAS triangle layout must not be null");
        }
        validateCounts(
                triangleLayout.opaqueTriangleCount(),
                triangleLayout.cutoutTriangleCount(),
                triangleLayout.transmissiveTriangleCount(),
                context.capabilities().maxAccelerationStructurePrimitiveCount());
        OpacityMicromap opacityMicromap = opacityMicromapPool.acquire(
                opacityMicromapData,
                staging,
                commandBuffer,
                label + " OMM");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries = geometries(
                    stack,
                    positions.deviceAddress(),
                    triangleLayout,
                    opacityMicromap);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(compactionPolicy))
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(GEOMETRY_COUNT)
                    .pGeometries(geometries);
            IntBuffer primitiveCounts = stack.ints(
                    (int) triangleLayout.opaqueTriangleCount(),
                    (int) triangleLayout.cutoutTriangleCount(),
                    (int) triangleLayout.transmissiveTriangleCount());
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    context.vkDevice(),
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    primitiveCounts,
                    sizes);

            AccelerationStructure accelerationStructure = null;
            VulkanBuffer scratch = null;
            VulkanBuffer compactionResult = null;
            long compactionQueryPool = 0L;
            try {
                accelerationStructure = AccelerationStructure.create(
                        context,
                        sizes.accelerationStructureSize(),
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                        label);
                long scratchSize = sizes.buildScratchSize()
                        + context.capabilities().accelerationStructureScratchAlignment() - 1L;
                scratch = context.createBuffer(
                        scratchSize,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        label + " scratch");
                if (compactionPolicy == CompactionPolicy.ENABLED) {
                    compactionResult = context.createBuffer(
                            Long.BYTES,
                            VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            true,
                            label + " compacted size");
                    VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR)
                            .queryCount(1);
                    LongBuffer queryPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK10.vkCreateQueryPool(context.vkDevice(), queryInfo, null, queryPointer),
                            "create " + label + " compaction query");
                    compactionQueryPool = queryPointer.get(0);
                }
                return new PreparedBlas(
                        context,
                        accelerationStructure,
                        positions,
                        primitives,
                        scratch,
                        opacityMicromap,
                        compactionResult,
                        compactionQueryPool,
                        triangleLayout,
                        compactionPolicy,
                        ownsGeometryBuffers,
                        label);
            } catch (RuntimeException exception) {
                RuntimeException failure = ResourceCleanup.destroy(
                        accelerationStructure, exception);
                failure = ResourceCleanup.destroy(scratch, failure);
                failure = ResourceCleanup.destroy(compactionResult, failure);
                if (compactionQueryPool != 0L) {
                    VK10.vkDestroyQueryPool(context.vkDevice(), compactionQueryPool, null);
                }
                throw failure;
            }
        } catch (RuntimeException exception) {
            throw ResourceCleanup.destroy(opacityMicromap, exception);
        }
    }

    public void recordBuild(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries = geometries(
                    stack,
                    this.positions.deviceAddress(),
                    this.triangleLayout,
                    this.opacityMicromap);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfo.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(buildFlags(this.compactionPolicy))
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(GEOMETRY_COUNT)
                    .pGeometries(geometries)
                    .dstAccelerationStructure(this.accelerationStructure.handle());
            long scratchAddress = VulkanContext.alignUp(
                    this.scratch.deviceAddress(),
                    this.context.capabilities().accelerationStructureScratchAlignment());
            buildInfo.get(0).scratchData().deviceAddress(scratchAddress);

            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(GEOMETRY_COUNT, stack);
            ranges.get(0)
                    .primitiveCount((int) this.triangleLayout.opaqueTriangleCount())
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            ranges.get(1)
                    .primitiveCount((int) this.triangleLayout.cutoutTriangleCount())
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            ranges.get(2)
                    .primitiveCount((int) this.triangleLayout.transmissiveTriangleCount())
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            PointerBuffer rangePointers = stack.mallocPointer(1).put(0, ranges.address());
            if (this.compactionQueryPool != 0L) {
                VK10.vkCmdResetQueryPool(commandBuffer, this.compactionQueryPool, 0, 1);
            }
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);
            if (this.compactionQueryPool != 0L) {
                LongBuffer structures = stack.longs(this.accelerationStructure.handle());
                KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR(
                        commandBuffer,
                        structures,
                        KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                        this.compactionQueryPool,
                        0);
                VK10.vkCmdCopyQueryPoolResults(
                        commandBuffer,
                        this.compactionQueryPool,
                        0,
                        1,
                        this.compactionResult.handle(),
                        0L,
                        Long.BYTES,
                        VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WAIT_BIT);
            }
        }
    }

    /** Registers readiness only after the build/query submission has completed on the real queue. */
    public void onBuildSubmitted() {
        synchronized (this) {
            if (this.compactionState == CompactionState.DISABLED) {
                return;
            }
            this.transition(CompactionEvent.BUILD_SUBMITTED);
        }
        try {
            this.context.afterSubmission(() -> {
                synchronized (PreparedBlas.this) {
                    PreparedBlas.this.transition(CompactionEvent.QUERY_COMPLETED);
                }
            });
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (this.compactionState == CompactionState.QUERY_PENDING) {
                    this.transition(CompactionEvent.BUILD_SUBMISSION_FAILED);
                }
            }
            throw exception;
        }
    }

    public void recordOpacityMicromapBuild(VkCommandBuffer commandBuffer) {
        if (this.opacityMicromap != null) {
            this.opacityMicromap.recordBuild(commandBuffer);
        }
    }

    public boolean hasOpacityMicromapBuild() {
        return this.opacityMicromap != null && this.opacityMicromap.requiresBuild();
    }

    public synchronized CompactionState compactionState() {
        return this.compactionState;
    }

    public synchronized void resolveCompactionSize() {
        requireState(CompactionState.QUERY_READY);
        this.compactionResult.invalidate(0L, Long.BYTES);
        this.compactionSourceSize = this.accelerationStructure.backingSize();
        this.compactedSize = MemoryUtil.memGetLong(this.compactionResult.mappedAddress());
        this.destroyCompactionQuery();
        this.transition(this.compactedSize > 0L
                        && this.compactedSize < this.compactionSourceSize
                ? CompactionEvent.BENEFICIAL_SIZE_RESOLVED
                : CompactionEvent.UNBENEFICIAL_SIZE_RESOLVED);
    }

    public synchronized long compactedSize() {
        requireState(CompactionState.READY);
        return this.compactedSize;
    }

    public synchronized long compactionReclaimedBytes() {
        requireState(CompactionState.READY);
        return this.compactionSourceSize - this.compactedSize;
    }

    public boolean compactionEnabled() {
        return this.compactionPolicy == CompactionPolicy.ENABLED;
    }

    public synchronized Compaction prepareCompaction() {
        requireState(CompactionState.READY);
        AccelerationStructure source = this.accelerationStructure;
        AccelerationStructure compacted = AccelerationStructure.create(
                this.context,
                this.compactedSize,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                this.label + " compacted");
        this.transition(CompactionEvent.TARGET_PREPARED);
        return new Compaction(this, source, compacted);
    }

    static int buildFlags(CompactionPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("BLAS compaction policy must not be null");
        }
        return policy == CompactionPolicy.ENABLED
                ? BASE_BUILD_FLAGS
                        | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR
                : BASE_BUILD_FLAGS;
    }

    public AccelerationStructure accelerationStructure() {
        return this.accelerationStructure;
    }

    public VulkanBuffer primitives() {
        return this.primitives;
    }

    public VulkanBuffer positions() {
        return this.positions;
    }

    public TriangleLayout triangleLayout() {
        return this.triangleLayout;
    }

    /** Scratch storage is build-only; positions remain shader-visible for exact hit reconstruction. */
    public void retireBuildResources() {
        VulkanBuffer retiredScratch = this.scratch;
        this.scratch = null;
        RuntimeException failure = null;
        if (retiredScratch != null) {
            failure = ResourceCleanup.run(
                    () -> this.context.defer(retiredScratch::destroy), null);
        }
        if (this.opacityMicromap != null) {
            failure = ResourceCleanup.run(
                    this.opacityMicromap::retireBuildResources, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    /** Releases pooled dependencies on the render thread before deferred destruction. */
    public void releaseSharedResources() {
        if (this.opacityMicromap != null) {
            this.opacityMicromap.releasePoolReference();
        }
    }

    private void destroyPersistentResources() {
        synchronized (this) {
            this.transition(CompactionEvent.DESTROYED);
        }
        RuntimeException failure = ResourceCleanup.destroy(this.accelerationStructure, null);
        if (this.ownsGeometryBuffers) {
            failure = ResourceCleanup.destroy(this.primitives, failure);
        }
        failure = ResourceCleanup.destroy(this.opacityMicromap, failure);
        failure = ResourceCleanup.run(this::destroyCompactionQuery, failure);
        if (this.ownsGeometryBuffers && this.positions != null) {
            failure = ResourceCleanup.destroy(this.positions, failure);
        }
        this.positions = null;
        ResourceCleanup.throwIfFailed(failure);
    }

    public void destroyAllResources() {
        RuntimeException failure = null;
        if (this.scratch != null) {
            failure = ResourceCleanup.destroy(this.scratch, null);
            this.scratch = null;
        }
        failure = ResourceCleanup.run(this::destroyPersistentResources, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private synchronized void destroyCompactionQuery() {
        if (this.compactionResult != null) {
            this.compactionResult.destroy();
            this.compactionResult = null;
        }
        if (this.compactionQueryPool != 0L) {
            VK10.vkDestroyQueryPool(this.context.vkDevice(), this.compactionQueryPool, null);
            this.compactionQueryPool = 0L;
        }
    }

    private void requireState(CompactionState expected) {
        if (this.compactionState != expected) {
            throw new IllegalStateException(
                    "BLAS compaction state is " + this.compactionState + ", expected " + expected);
        }
    }

    private void transition(CompactionEvent event) {
        this.compactionState = transition(this.compactionState, event);
    }

    static CompactionState transition(
            CompactionState state, CompactionEvent event) {
        if (state == null || event == null) {
            throw new IllegalArgumentException(
                    "BLAS compaction state and event must not be null");
        }
        if (state == CompactionState.DESTROYED
                && (event == CompactionEvent.QUERY_COMPLETED
                        || event == CompactionEvent.ABANDONED_TARGET_RETIRED
                        || event == CompactionEvent.SOURCE_RETIRED
                        || event == CompactionEvent.DESTROYED)) {
            return CompactionState.DESTROYED;
        }
        CompactionState next = switch (event) {
            case BUILD_SUBMITTED -> state == CompactionState.BUILD_PENDING
                    ? CompactionState.QUERY_PENDING
                    : null;
            case BUILD_SUBMISSION_FAILED -> state == CompactionState.QUERY_PENDING
                    ? CompactionState.BUILD_PENDING
                    : null;
            case QUERY_COMPLETED -> state == CompactionState.QUERY_PENDING
                    ? CompactionState.QUERY_READY
                    : null;
            case BENEFICIAL_SIZE_RESOLVED -> state == CompactionState.QUERY_READY
                    ? CompactionState.READY
                    : null;
            case UNBENEFICIAL_SIZE_RESOLVED -> state == CompactionState.QUERY_READY
                    ? CompactionState.NOT_BENEFICIAL
                    : null;
            case TARGET_PREPARED -> state == CompactionState.READY
                    ? CompactionState.PREPARED
                    : null;
            case PREPARED_TARGET_ROLLED_BACK -> state == CompactionState.PREPARED
                    ? CompactionState.READY
                    : null;
            case TARGET_ABANDONED -> state == CompactionState.PREPARED
                    ? CompactionState.ABANDONING_TARGET
                    : null;
            case ABANDONED_TARGET_RETIRED -> state == CompactionState.ABANDONING_TARGET
                    ? CompactionState.READY
                    : null;
            case TARGET_PUBLISHED -> state == CompactionState.PREPARED
                    ? CompactionState.RETIRING_SOURCE
                    : null;
            case SOURCE_RETIRED -> state == CompactionState.RETIRING_SOURCE
                    ? CompactionState.COMPACTED
                    : null;
            case DESTROYED -> CompactionState.DESTROYED;
        };
        if (next == null) {
            throw new IllegalStateException(
                    "BLAS compaction event " + event + " is invalid from " + state);
        }
        return next;
    }

    private synchronized void preparedCompactionClosed(
            AccelerationStructure source) {
        if (this.compactionState == CompactionState.PREPARED
                && this.accelerationStructure == source) {
            this.transition(CompactionEvent.PREPARED_TARGET_ROLLED_BACK);
        }
    }

    private synchronized void compactionTargetAbandoned(
            AccelerationStructure source) {
        if (this.compactionState == CompactionState.ABANDONING_TARGET
                && this.accelerationStructure == source) {
            this.transition(CompactionEvent.ABANDONED_TARGET_RETIRED);
        } else if (this.compactionState == CompactionState.DESTROYED) {
            this.transition(CompactionEvent.ABANDONED_TARGET_RETIRED);
        }
    }

    private synchronized void compactionSourceRetired(
            AccelerationStructure source) {
        if (this.compactionState == CompactionState.RETIRING_SOURCE
                && this.accelerationStructure != source) {
            this.transition(CompactionEvent.SOURCE_RETIRED);
        } else if (this.compactionState == CompactionState.DESTROYED) {
            this.transition(CompactionEvent.SOURCE_RETIRED);
        }
    }

    public static final class Compaction implements AutoCloseable {
        private final PreparedBlas owner;
        private final AccelerationStructure source;
        private final AccelerationStructure target;
        private boolean published;
        private boolean retired;
        private volatile boolean retirementComplete;

        private Compaction(
                PreparedBlas owner,
                AccelerationStructure source,
                AccelerationStructure target) {
            this.owner = owner;
            this.source = source;
            this.target = target;
        }

        public long targetDeviceAddress() {
            return this.target.deviceAddress();
        }

        public PreparedBlas owner() {
            return this.owner;
        }

        public long targetSize() {
            return this.target.backingSize();
        }

        public long sourceSize() {
            return this.source.backingSize();
        }

        public long reclaimedBytes() {
            return this.source.backingSize() - this.target.backingSize();
        }

        public boolean retirementComplete() {
            return this.retirementComplete;
        }

        public void recordCopy(VkCommandBuffer commandBuffer) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                        .sType$Default()
                        .src(this.source.handle())
                        .dst(this.target.handle())
                        .mode(KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
                KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR(commandBuffer, copy);
            }
        }

        public void requirePublishable() {
            synchronized (this.owner) {
                if (this.published
                        || this.retired
                        || this.owner.accelerationStructure != this.source
                        || this.owner.compactionState != CompactionState.PREPARED) {
                    throw new IllegalStateException("BLAS changed before compaction publication");
                }
            }
        }

        /**
         * Publishes the copied address without performing fallible retirement work.
         *
         * <p>The containing scene must validate every compaction first and publish its replacement
         * TLAS immediately after this call.
         */
        public void publish() {
            synchronized (this.owner) {
                if (this.published
                        || this.retired
                        || this.owner.accelerationStructure != this.source
                        || this.owner.compactionState != CompactionState.PREPARED) {
                    throw new IllegalStateException("BLAS changed before compaction publication");
                }
                this.owner.accelerationStructure = this.target;
                this.owner.transition(CompactionEvent.TARGET_PUBLISHED);
            }
            this.published = true;
        }

        /** Retires the old address only after both the copy and replacement TLAS were published. */
        public void retireSource() {
            if (!this.published || this.retired) {
                throw new IllegalStateException(
                        "Compaction source can be retired only once after publication");
            }
            this.retired = true;
            this.owner.context.defer(() -> {
                try {
                    this.source.destroy();
                } finally {
                    this.owner.compactionSourceRetired(this.source);
                    this.retirementComplete = true;
                }
            });
        }

        public void abandonAfterSubmission() {
            if (!this.published && !this.retired) {
                synchronized (this.owner) {
                    if (this.owner.compactionState == CompactionState.PREPARED
                            && this.owner.accelerationStructure == this.source) {
                        this.owner.transition(CompactionEvent.TARGET_ABANDONED);
                    }
                }
                this.retired = true;
                this.owner.context.defer(() -> {
                    try {
                        this.target.destroy();
                    } finally {
                        this.owner.compactionTargetAbandoned(this.source);
                        this.retirementComplete = true;
                    }
                });
            }
        }

        @Override
        public void close() {
            if (!this.published && !this.retired) {
                this.retired = true;
                try {
                    this.target.destroy();
                } finally {
                    this.owner.preparedCompactionClosed(this.source);
                    this.retirementComplete = true;
                }
            }
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer geometries(
            MemoryStack stack,
            long positionAddress,
            TriangleLayout triangleLayout,
            OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(GEOMETRY_COUNT, stack);
        fillGeometry(
                geometries.get(0),
                positionAddress,
                triangleLayout.opaqueTriangleCount(),
                true);
        fillGeometry(
                geometries.get(1),
                cutoutGeometryVertexAddress(
                        positionAddress,
                        triangleLayout.opaqueTriangleCount(),
                        triangleLayout.cutoutTriangleCount()),
                triangleLayout.cutoutTriangleCount(),
                false);
        if (opacityMicromap != null) {
            opacityMicromap.attach(geometries.get(1).geometry().triangles(), stack);
        }
        fillGeometry(
                geometries.get(2),
                transmissiveGeometryVertexAddress(
                        positionAddress,
                        triangleLayout.opaqueTriangleCount(),
                        triangleLayout.cutoutTriangleCount(),
                        triangleLayout.transmissiveTriangleCount()),
                triangleLayout.transmissiveTriangleCount(),
                false);
        return geometries;
    }

    static long cutoutGeometryVertexAddress(
            long positionAddress,
            long opaqueTriangleCount,
            long cutoutTriangleCount) {
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        if (cutoutTriangleCount == 0) {
            // The second geometry is retained so geometry index 1 always selects the cutout SBT record.
            // Vulkan still requires its vertex address to belong to a live build-input buffer even when
            // its primitive count is zero, so it must not point one byte past the opaque vertex data.
            return positionAddress;
        }
        long opaqueBytes = Math.multiplyExact(opaqueTriangleCount, 3L * 3L * Float.BYTES);
        return Math.addExact(positionAddress, opaqueBytes);
    }

    static long transmissiveGeometryVertexAddress(
            long positionAddress,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount) {
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0 || transmissiveTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        if (transmissiveTriangleCount == 0) {
            // The fixed geometry/SBT mapping must not produce a one-past-end build address.
            return positionAddress;
        }
        long precedingTriangles = Math.addExact(
                opaqueTriangleCount, cutoutTriangleCount);
        long precedingBytes = Math.multiplyExact(
                precedingTriangles, 3L * 3L * Float.BYTES);
        return Math.addExact(positionAddress, precedingBytes);
    }

    private static void fillGeometry(
            VkAccelerationStructureGeometryKHR geometry,
            long vertexAddress,
            long triangleCount,
            boolean opaque) {
        geometry.sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque
                        ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR
                        : KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        geometry.geometry().triangles()
                .sType$Default()
                .vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3L * Float.BYTES)
                .maxVertex(triangleCount == 0L
                        ? 0
                        : (int) (Math.multiplyExact(triangleCount, 3L) - 1L))
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
        geometry.geometry().triangles().vertexData().deviceAddress(vertexAddress);
        geometry.geometry().triangles().indexData().deviceAddress(0L);
        geometry.geometry().triangles().transformData().deviceAddress(0L);
    }

    private static void validateGeometryCount(String name, long triangleCount) {
        if (triangleCount > MAX_TRIANGLES_PER_GEOMETRY) {
            throw new IllegalStateException(
                    name + " geometry exceeds Vulkan's uint maxVertex range");
        }
    }

    static long validateCounts(
            long opaque,
            long cutout,
            long transmissive,
            long devicePrimitiveLimit) {
        if (opaque < 0L || cutout < 0L || transmissive < 0L) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        long total = Math.addExact(Math.addExact(opaque, cutout), transmissive);
        if (total == 0L) {
            throw new IllegalArgumentException("A BLAS must contain at least one triangle");
        }
        if (Long.compareUnsigned(total, devicePrimitiveLimit) > 0) {
            throw new IllegalStateException(
                    "BLAS requires "
                            + total
                            + " primitives, but the Vulkan device supports "
                            + Long.toUnsignedString(devicePrimitiveLimit));
        }
        validateGeometryCount("opaque", opaque);
        validateGeometryCount("cutout", cutout);
        validateGeometryCount("transmissive", transmissive);
        if (total > MAX_PRIMITIVE_RECORDS) {
            throw new IllegalStateException(
                    "BLAS primitive records exceed the shader uint address space");
        }
        if (total >= MAX_IDENTITY_TRIANGLES) {
            throw new IllegalStateException(
                    "BLAS triangle identities exceed the shader 31-bit ABI");
        }
        return total;
    }

}
