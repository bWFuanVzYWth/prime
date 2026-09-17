// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.terrain.OpacityMicromapData;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;

/** Per-BLAS OMM index mapping backed by a pooled immutable micromap. */
final class OpacityMicromap implements Destroyable {
    // VUID-vkCmdBuildMicromapsEXT-pInfos-07515 requires both device addresses to be 256-byte
    // aligned even when the buffer allocation itself reports a weaker alignment.
    private static final long BUILD_INPUT_ALIGNMENT = 256L;

    private final VulkanContext context;
    private final OpacityMicromapPool pool;
    private OpacityMicromapPool.Entry entry;
    private final Shared shared;
    private VulkanBuffer indices;
    private final int[] mappedTwoStateTriangleCounts;
    private final int[] mappedFourStateTriangleCounts;
    private boolean destroyed;

    private OpacityMicromap(
            VulkanContext context,
            OpacityMicromapPool pool,
            OpacityMicromapPool.Entry entry,
            VulkanBuffer indices,
            int[] mappedTwoStateTriangleCounts,
            int[] mappedFourStateTriangleCounts) {
        this.context = context;
        this.pool = pool;
        this.entry = entry;
        this.shared = entry == null ? null : entry.shared;
        this.indices = indices;
        this.mappedTwoStateTriangleCounts = mappedTwoStateTriangleCounts;
        this.mappedFourStateTriangleCounts = mappedFourStateTriangleCounts;
    }

    static OpacityMicromap createBinding(
            OpacityMicromapPool pool,
            OpacityMicromapPool.Entry entry,
            int[] triangleIndices,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            String label) {
        VulkanContext context = pool.context();
        int[] mappedTwoStateTriangleCounts = new int[16];
        int[] mappedFourStateTriangleCounts = new int[16];
        for (int index : triangleIndices) {
            if (index < 0) {
                continue;
            }
            if (entry == null || index >= entry.content.blockCount()) {
                throw new IllegalArgumentException(
                        "Opacity micromap triangle references an invalid pooled block");
            }
            OpacityMicromapPool.Block block = entry.content.block(index);
            if (block.format == OpacityMicromapData.TWO_STATE_FORMAT) {
                mappedTwoStateTriangleCounts[block.subdivisionLevel]++;
            } else if (block.format == OpacityMicromapData.FOUR_STATE_FORMAT) {
                mappedFourStateTriangleCounts[block.subdivisionLevel]++;
            } else {
                throw new IllegalArgumentException(
                        "Opacity micromap triangle references an unsupported format");
            }
        }
        VulkanBuffer indices = null;
        try {
            indices = context.createBuffer(
                    (long) triangleIndices.length * Integer.BYTES,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | KHRAccelerationStructure
                                    .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    false,
                    label + " indices");
            copyBuffer(commandBuffer, staging.write(triangleIndices, Integer.BYTES), indices);
            OpacityMicromap result = new OpacityMicromap(
                    context,
                    pool,
                    entry,
                    indices,
                    mappedTwoStateTriangleCounts,
                    mappedFourStateTriangleCounts);
            if (result.shared != null) {
                result.shared.retainBinding();
            }
            return result;
        } catch (RuntimeException exception) {
            throw ResourceCleanup.destroy(indices, exception);
        }
    }

    void recordBuild(VkCommandBuffer commandBuffer) {
        if (this.shared != null) {
            this.shared.recordBuild(commandBuffer);
        }
    }

    boolean requiresBuild() {
        return this.shared != null && this.shared.requiresBuild();
    }

    void attach(
            VkAccelerationStructureGeometryTrianglesDataKHR trianglesData,
            MemoryStack stack) {
        long handle = this.shared == null ? 0L : this.shared.handle();
        VkAccelerationStructureTrianglesOpacityMicromapEXT attachment =
                VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack)
                        .sType$Default()
                        .indexType(VK12.VK_INDEX_TYPE_UINT32)
                        .indexStride(Integer.BYTES)
                        .baseTriangle(0)
                        .micromap(handle);
        attachment.indexBuffer().deviceAddress(this.indices.deviceAddress());
        if (total(this.mappedTwoStateTriangleCounts) != 0
                || total(this.mappedFourStateTriangleCounts) != 0) {
            VkMicromapUsageEXT.Buffer usage = usage(
                    stack,
                    this.mappedTwoStateTriangleCounts,
                    this.mappedFourStateTriangleCounts);
            attachment.usageCountsCount(usage.remaining()).pUsageCounts(usage);
        }
        trianglesData.pNext(attachment.address());
    }

    void retireBuildResources() {
        VulkanBuffer retiredIndices = this.indices;
        this.indices = null;
        RuntimeException failure = null;
        if (retiredIndices != null) {
            failure = ResourceCleanup.run(
                    () -> this.context.defer(retiredIndices::destroy), null);
        }
        if (this.shared != null) {
            failure = ResourceCleanup.run(
                    this.shared::retireBuildResources, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    /** Detaches the render-thread-owned pool reference before deferred BLAS destruction. */
    void releasePoolReference() {
        OpacityMicromapPool.Entry released = this.entry;
        this.entry = null;
        this.pool.release(released);
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        RuntimeException failure = ResourceCleanup.destroy(this.indices, null);
        this.indices = null;
        failure = ResourceCleanup.run(this::releasePoolReference, failure);
        if (this.shared != null) {
            failure = ResourceCleanup.run(this.shared::releaseBinding, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    static final class Shared implements Destroyable {
        private final VulkanContext context;
        private long handle;
        private VulkanBuffer storage;
        private VulkanBuffer data;
        private VulkanBuffer triangles;
        private VulkanBuffer scratch;
        private final long dataAddress;
        private final long triangleAddress;
        private final long scratchAddress;
        private final int[] twoStateBlockCounts;
        private final int[] fourStateBlockCounts;
        private boolean buildRecorded;
        private boolean buildResourcesRetired;
        private final AtomicInteger bindingReferences = new AtomicInteger();
        private final AtomicBoolean retiredFromPool = new AtomicBoolean();
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private Shared(
                VulkanContext context,
                long handle,
                VulkanBuffer storage,
                VulkanBuffer data,
                VulkanBuffer triangles,
                VulkanBuffer scratch,
                long dataAddress,
                long triangleAddress,
                long scratchAddress,
                int[] twoStateBlockCounts,
                int[] fourStateBlockCounts) {
            this.context = context;
            this.handle = handle;
            this.storage = storage;
            this.data = data;
            this.triangles = triangles;
            this.scratch = scratch;
            this.dataAddress = dataAddress;
            this.triangleAddress = triangleAddress;
            this.scratchAddress = scratchAddress;
            this.twoStateBlockCounts = twoStateBlockCounts;
            this.fourStateBlockCounts = fourStateBlockCounts;
        }

        static Shared create(
                VulkanContext context,
                OpacityMicromapPool.Content content,
                StagingArena.Batch staging,
                VkCommandBuffer commandBuffer,
                String label) {
            VulkanBuffer data = null;
            VulkanBuffer triangles = null;
            VulkanBuffer storage = null;
            VulkanBuffer scratch = null;
            long handle = 0L;
            try {
                int[] twoStateBlockCounts = new int[16];
                int[] fourStateBlockCounts = new int[16];
                for (int index = 0; index < content.blockCount(); index++) {
                    OpacityMicromapPool.Block block = content.block(index);
                    if (block.format == OpacityMicromapData.TWO_STATE_FORMAT) {
                        if (block.subdivisionLevel
                                > context.capabilities().maxOpacity2StateSubdivisionLevel()) {
                            throw new IllegalArgumentException(
                                    "Two-state opacity micromap subdivision level exceeds the device limit");
                        }
                        twoStateBlockCounts[block.subdivisionLevel]++;
                    } else if (block.format == OpacityMicromapData.FOUR_STATE_FORMAT) {
                        if (block.subdivisionLevel
                                > context.capabilities().maxOpacity4StateSubdivisionLevel()) {
                            throw new IllegalArgumentException(
                                    "Four-state opacity micromap subdivision level exceeds the device limit");
                        }
                        fourStateBlockCounts[block.subdivisionLevel]++;
                    } else {
                        throw new IllegalArgumentException(
                                "Opacity micromap contains an unsupported format");
                    }
                }
                byte[] blocks = content.packedBlocks();
                data = context.createBuffer(
                        alignedAllocationSize(blocks.length, BUILD_INPUT_ALIGNMENT),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | EXTOpacityMicromap
                                        .VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                        false,
                        label + " states");
                int[] triangleDescriptors = triangleDescriptors(
                        content.blockOffsets(),
                        content.blockFormats(),
                        content.blockSubdivisionLevels());
                triangles = context.createBuffer(
                        alignedAllocationSize(
                                (long) triangleDescriptors.length * Integer.BYTES,
                                BUILD_INPUT_ALIGNMENT),
                        VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | EXTOpacityMicromap
                                        .VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT,
                        false,
                        label + " triangles");
                long dataOffset = alignedOffset(data.deviceAddress(), BUILD_INPUT_ALIGNMENT);
                long triangleOffset = alignedOffset(
                        triangles.deviceAddress(), BUILD_INPUT_ALIGNMENT);
                long dataAddress = data.deviceAddress() + dataOffset;
                long triangleAddress = triangles.deviceAddress() + triangleOffset;
                copyBuffer(commandBuffer, staging.write(blocks, 16L), data, dataOffset);
                copyBuffer(
                        commandBuffer,
                        staging.write(triangleDescriptors, Integer.BYTES),
                        triangles,
                        triangleOffset);

                long scratchAddress = 0L;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkMicromapUsageEXT.Buffer usage = usage(
                            stack, twoStateBlockCounts, fourStateBlockCounts);
                    VkMicromapBuildInfoEXT buildInfo = VkMicromapBuildInfoEXT.calloc(stack)
                            .sType$Default()
                            .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                            .flags(EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                            .mode(EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                            .usageCountsCount(usage.remaining())
                            .pUsageCounts(usage)
                            .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
                    buildInfo.data().deviceAddress(dataAddress);
                    buildInfo.triangleArray().deviceAddress(triangleAddress);
                    VkMicromapBuildSizesInfoEXT sizes =
                            VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
                    EXTOpacityMicromap.vkGetMicromapBuildSizesEXT(
                            context.vkDevice(),
                            KHRAccelerationStructure
                                    .VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                            buildInfo,
                            sizes);
                    storage = context.createBuffer(
                            sizes.micromapSize(),
                            EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT,
                            false,
                            label + " storage");
                    if (sizes.buildScratchSize() > 0L) {
                        long scratchAlignment =
                                context.capabilities().accelerationStructureScratchAlignment();
                        scratch = context.createBuffer(
                                alignedAllocationSize(
                                        sizes.buildScratchSize(), scratchAlignment),
                                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                false,
                                label + " scratch");
                        scratchAddress = VulkanContext.alignUp(
                                scratch.deviceAddress(), scratchAlignment);
                    }
                    VkMicromapCreateInfoEXT createInfo = VkMicromapCreateInfoEXT.calloc(stack)
                            .sType$Default()
                            .buffer(storage.handle())
                            .offset(0L)
                            .size(sizes.micromapSize())
                            .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
                    LongBuffer pointer = stack.mallocLong(1);
                    VulkanContext.check(
                            EXTOpacityMicromap.vkCreateMicromapEXT(
                                    context.vkDevice(), createInfo, null, pointer),
                            "create " + label);
                    handle = pointer.get(0);
                    context.device().instance().debug().setObjectName(
                            context.vkDevice(),
                            EXTOpacityMicromap.VK_OBJECT_TYPE_MICROMAP_EXT,
                            handle,
                            label);
                }
                return new Shared(
                        context,
                        handle,
                        storage,
                        data,
                        triangles,
                        scratch,
                        dataAddress,
                        triangleAddress,
                        scratchAddress,
                        twoStateBlockCounts,
                        fourStateBlockCounts);
            } catch (RuntimeException exception) {
                if (handle != 0L) {
                    EXTOpacityMicromap.vkDestroyMicromapEXT(
                            context.vkDevice(), handle, null);
                }
                RuntimeException failure = ResourceCleanup.destroy(storage, exception);
                failure = ResourceCleanup.destroy(data, failure);
                failure = ResourceCleanup.destroy(triangles, failure);
                failure = ResourceCleanup.destroy(scratch, failure);
                throw failure;
            }
        }

        long handle() {
            return this.handle;
        }

        void retainBinding() {
            if (this.retiredFromPool.get() || this.destroyed.get()) {
                throw new IllegalStateException(
                        "Retired opacity micromap accepted a new binding");
            }
            this.bindingReferences.incrementAndGet();
        }

        void retireFromPool() {
            if (!this.retiredFromPool.compareAndSet(false, true)) {
                throw new IllegalStateException("Opacity micromap was retired twice");
            }
            if (this.bindingReferences.get() == 0) {
                this.destroy();
            }
        }

        void releaseBinding() {
            int remaining = this.bindingReferences.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException(
                        "Opacity micromap binding reference underflow");
            }
            if (remaining == 0 && this.retiredFromPool.get()) {
                this.destroy();
            }
        }

        void recordBuild(VkCommandBuffer commandBuffer) {
            if (this.buildRecorded) {
                return;
            }
            if (this.buildResourcesRetired) {
                throw new IllegalStateException(
                        "Opacity micromap build resources retired before construction");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMicromapUsageEXT.Buffer usage = usage(
                        stack, this.twoStateBlockCounts, this.fourStateBlockCounts);
                VkMicromapBuildInfoEXT.Buffer buildInfos =
                        VkMicromapBuildInfoEXT.calloc(1, stack);
                VkMicromapBuildInfoEXT buildInfo = buildInfos.get(0)
                        .sType$Default()
                        .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                        .flags(EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                        .mode(EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                        .dstMicromap(this.handle)
                        .usageCountsCount(usage.remaining())
                        .pUsageCounts(usage)
                        .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
                buildInfo.data().deviceAddress(this.dataAddress);
                buildInfo.triangleArray().deviceAddress(this.triangleAddress);
                buildInfo.scratchData().deviceAddress(this.scratchAddress);
                EXTOpacityMicromap.vkCmdBuildMicromapsEXT(commandBuffer, buildInfos);
            }
            this.buildRecorded = true;
        }

        boolean requiresBuild() {
            return !this.buildRecorded;
        }

        void retireBuildResources() {
            if (this.buildResourcesRetired) {
                return;
            }
            if (!this.buildRecorded) {
                throw new IllegalStateException(
                        "Opacity micromap build resources retired before construction");
            }
            this.buildResourcesRetired = true;
            VulkanBuffer retiredData = this.data;
            VulkanBuffer retiredTriangles = this.triangles;
            VulkanBuffer retiredScratch = this.scratch;
            this.data = null;
            this.triangles = null;
            this.scratch = null;
            this.context.defer(() -> {
                RuntimeException failure = ResourceCleanup.destroy(retiredData, null);
                failure = ResourceCleanup.destroy(retiredTriangles, failure);
                failure = ResourceCleanup.destroy(retiredScratch, failure);
                ResourceCleanup.throwIfFailed(failure);
            });
        }

        @Override
        public void destroy() {
            if (!this.destroyed.compareAndSet(false, true)) {
                return;
            }
            if (this.handle != 0L) {
                EXTOpacityMicromap.vkDestroyMicromapEXT(
                        this.context.vkDevice(), this.handle, null);
                this.handle = 0L;
            }
            RuntimeException failure = ResourceCleanup.destroy(this.storage, null);
            failure = ResourceCleanup.destroy(this.data, failure);
            failure = ResourceCleanup.destroy(this.triangles, failure);
            failure = ResourceCleanup.destroy(this.scratch, failure);
            this.storage = null;
            this.data = null;
            this.triangles = null;
            this.scratch = null;
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private static VkMicromapUsageEXT.Buffer usage(
            MemoryStack stack, int[] twoStateCounts, int[] fourStateCounts) {
        if (twoStateCounts.length != fourStateCounts.length) {
            throw new IllegalArgumentException("Opacity micromap usage levels are inconsistent");
        }
        int usageCount = 0;
        for (int level = 0; level < twoStateCounts.length; level++) {
            usageCount += twoStateCounts[level] == 0 ? 0 : 1;
            usageCount += fourStateCounts[level] == 0 ? 0 : 1;
        }
        if (usageCount == 0) {
            throw new IllegalArgumentException(
                    "Opacity micromap usage must contain at least one mapped block");
        }
        VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(usageCount, stack);
        int index = 0;
        for (int level = 0; level < twoStateCounts.length; level++) {
            if (twoStateCounts[level] != 0) {
                setUsage(
                        usage.get(index++),
                        twoStateCounts[level],
                        level,
                        OpacityMicromapData.TWO_STATE_FORMAT);
            }
            if (fourStateCounts[level] != 0) {
                setUsage(
                        usage.get(index++),
                        fourStateCounts[level],
                        level,
                        OpacityMicromapData.FOUR_STATE_FORMAT);
            }
        }
        return usage;
    }

    private static void setUsage(
            VkMicromapUsageEXT usage, int count, int subdivisionLevel, int format) {
        usage.count(count).subdivisionLevel(subdivisionLevel).format(format);
    }

    static int[] triangleDescriptors(
            int[] blockOffsets, int[] blockFormats, int[] blockSubdivisionLevels) {
        if (blockOffsets.length != blockFormats.length
                || blockOffsets.length != blockSubdivisionLevels.length) {
            throw new IllegalArgumentException("Opacity micromap block metadata is inconsistent");
        }
        int[] result = new int[Math.multiplyExact(blockOffsets.length, 2)];
        for (int index = 0; index < blockOffsets.length; index++) {
            result[index * 2] = blockOffsets[index];
            result[index * 2 + 1] = blockSubdivisionLevels[index]
                    | blockFormats[index] << 16;
        }
        return result;
    }

    private static void copyBuffer(
            VkCommandBuffer commandBuffer,
            StagingArena.Slice source,
            VulkanBuffer destination) {
        copyBuffer(commandBuffer, source, destination, 0L);
    }

    private static void copyBuffer(
            VkCommandBuffer commandBuffer,
            StagingArena.Slice source,
            VulkanBuffer destination,
            long destinationOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(source.offset())
                    .dstOffset(destinationOffset)
                    .size(source.size());
            VK12.vkCmdCopyBuffer(
                    commandBuffer, source.buffer(), destination.handle(), copy);
        }
    }

    private static long alignedAllocationSize(long dataSize, long alignment) {
        return Math.addExact(dataSize, alignment - 1L);
    }

    private static long alignedOffset(long deviceAddress, long alignment) {
        return VulkanContext.alignUp(deviceAddress, alignment) - deviceAddress;
    }

    private static int total(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total = Math.addExact(total, count);
        }
        return total;
    }
}
