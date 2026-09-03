package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanImage;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** One reusable descriptor/constant allocation for a submitted NRD frame. */
final class NrdFrameBindings implements Destroyable {
    private final NrdDenoiser owner;
    private final long descriptorPool;
    private final VulkanBuffer constantBuffer;
    private final int dispatchCapacity;
    private long[] resourceDescriptorSets = new long[0];
    private long[] constantsDescriptorSets = new long[0];
    private int[] allocatedPipelineIndices = new int[0];
    private int allocatedSetCount;
    private boolean destroyed;

    private NrdFrameBindings(
            NrdDenoiser owner,
            long descriptorPool,
            VulkanBuffer constantBuffer,
            int dispatchCapacity) {
        this.owner = owner;
        this.descriptorPool = descriptorPool;
        this.constantBuffer = constantBuffer;
        this.dispatchCapacity = dispatchCapacity;
    }

    static NrdFrameBindings create(NrdDenoiser owner, int requiredDispatches) {
        NrdNative.Description description = owner.description;
        // The native pool description can count identical dispatch names only once, while the
        // frame dispatch list contains one sequence per denoiser identifier.
        int dispatchCapacity = Math.max(Math.max(description.setsMaxNum(), requiredDispatches), 1);
        int maxTextures = 1;
        int maxStorages = 1;
        for (NrdNative.Pipeline pipeline : description.pipelines()) {
            int textures = 0;
            int storages = 0;
            for (NrdNative.PipelineRange range : pipeline.ranges()) {
                if (range.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                    textures += range.descriptorsNum();
                } else {
                    storages += range.descriptorsNum();
                }
            }
            maxTextures = Math.max(maxTextures, textures);
            maxStorages = Math.max(maxStorages, storages);
        }
        long descriptorPool = 0L;
        VulkanBuffer constantBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0)
                    .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                    .descriptorCount(dispatchCapacity * description.samplers().size());
            sizes.get(1)
                    .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(dispatchCapacity);
            sizes.get(2)
                    .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(dispatchCapacity * maxTextures);
            sizes.get(3)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(dispatchCapacity * maxStorages);
            descriptorPool = VulkanDescriptors.createPool(
                    owner.context,
                    stack,
                    Math.multiplyExact(dispatchCapacity, 2),
                    sizes,
                    "create Prime NRD frame descriptor pool");
            long stride = VulkanContext.alignUp(
                    Math.max(description.constantBufferMaxDataSize(), 1),
                    Math.max(owner.context.uniformBufferOffsetAlignment(), 1L));
            constantBuffer = owner.context.createBuffer(
                    Math.multiplyExact(stride, dispatchCapacity),
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    true,
                    "Prime NRD frame constants");
            return new NrdFrameBindings(
                    owner, descriptorPool, constantBuffer, dispatchCapacity);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(constantBuffer, exception);
            if (descriptorPool != 0L) {
                long failedPool = descriptorPool;
                failure = ResourceCleanup.run(
                        () -> VK12.vkDestroyDescriptorPool(
                                owner.context.vkDevice(), failedPool, null),
                        failure);
            }
            throw failure;
        }
    }

    int dispatchCapacity() {
        return this.dispatchCapacity;
    }

    long resourceDescriptorSet(int dispatchIndex) {
        return this.resourceDescriptorSets[dispatchIndex];
    }

    long constantsDescriptorSet(int dispatchIndex) {
        return this.constantsDescriptorSets[dispatchIndex];
    }

    void prepare(
            NrdNative.DispatchList dispatches,
            NrdDenoiser denoiser,
            PreparedNrdFrame prepared) {
        if (this.destroyed) {
            throw new IllegalStateException("NRD frame bindings are destroyed");
        }
        if (dispatches.size() > this.dispatchCapacity) {
            throw new IllegalStateException(
                    "NRD dispatch count exceeds its descriptor pool contract");
        }
        if (dispatches.isEmpty()) {
            return;
        }
        if (this.resourceDescriptorSets.length < dispatches.size()) {
            this.resourceDescriptorSets = new long[dispatches.size()];
            this.constantsDescriptorSets = new long[dispatches.size()];
            this.allocatedPipelineIndices = new int[dispatches.size()];
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.matchesAllocatedLayouts(dispatches)) {
                this.allocateDescriptorSets(stack, dispatches, denoiser);
            }
            long constantStride = VulkanContext.alignUp(
                    Math.max(denoiser.description.constantBufferMaxDataSize(), 1),
                    Math.max(denoiser.context.uniformBufferOffsetAlignment(), 1L));
            for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                this.writeDispatch(
                        stack,
                        dispatchIndex,
                        dispatches,
                        constantStride,
                        denoiser,
                        prepared);
            }
        }
    }

    private void allocateDescriptorSets(
            MemoryStack stack,
            NrdNative.DispatchList dispatches,
            NrdDenoiser denoiser) {
        this.allocatedSetCount = 0;
        VulkanContext.check(
                VK12.vkResetDescriptorPool(
                        denoiser.context.vkDevice(), this.descriptorPool, 0),
                "reset Prime NRD descriptor pool");
        LongBuffer layouts = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
        for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
            int pipelineIndex = dispatches.pipelineIndex(dispatchIndex);
            NrdDenoiser.ComputePipeline pipeline = denoiser.pipelines[pipelineIndex];
            layouts.put(pipeline.resourceDescriptorSetLayout);
            layouts.put(pipeline.constantsDescriptorSetLayout);
            this.allocatedPipelineIndices[dispatchIndex] = pipelineIndex;
        }
        layouts.flip();
        VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(this.descriptorPool)
                .pSetLayouts(layouts);
        LongBuffer sets = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
        VulkanContext.check(
                VK12.vkAllocateDescriptorSets(
                        denoiser.context.vkDevice(), allocateInfo, sets),
                "allocate Prime NRD frame descriptor sets");
        for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
            this.resourceDescriptorSets[dispatchIndex] = sets.get();
            this.constantsDescriptorSets[dispatchIndex] = sets.get();
        }
        this.allocatedSetCount = dispatches.size();
    }

    private boolean matchesAllocatedLayouts(NrdNative.DispatchList dispatches) {
        if (this.allocatedSetCount != dispatches.size()) {
            return false;
        }
        for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
            if (this.allocatedPipelineIndices[dispatchIndex]
                    != dispatches.pipelineIndex(dispatchIndex)) {
                return false;
            }
        }
        return true;
    }

    private void writeDispatch(
            MemoryStack stack,
            int dispatchIndex,
            NrdNative.DispatchList dispatches,
            long constantStride,
            NrdDenoiser denoiser,
            PreparedNrdFrame prepared) {
        boolean hasConstants = denoiser.description.pipelines()
                .get(dispatches.pipelineIndex(dispatchIndex))
                .hasConstantData();
        int constantDataSize = dispatches.constantDataSize(dispatchIndex);
        if (hasConstants && constantDataSize == 0) {
            throw new IllegalStateException("NRD pipeline requires missing constant data");
        }
        if (constantDataSize > denoiser.description.constantBufferMaxDataSize()) {
            throw new IllegalStateException("NRD constant data exceeds its declared maximum");
        }
        int resourceCount = dispatches.resourceCount(dispatchIndex);
        int writeCount = resourceCount + (hasConstants ? 1 : 0);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
        VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(resourceCount, stack);
        int writeIndex = 0;
        if (hasConstants) {
            long constantOffset = constantStride * dispatchIndex;
            this.constantBuffer.put(
                    constantOffset,
                    dispatches.constantDataAddress(dispatchIndex),
                    constantDataSize);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.constantBuffer.handle())
                    .offset(constantOffset)
                    .range(constantDataSize);
            VulkanDescriptors.writeBuffer(
                    writes.get(writeIndex++), this.constantsDescriptorSets[dispatchIndex],
                    denoiser.description.constantBufferOffset()
                            + denoiser.description.constantBufferRegisterIndex(),
                    VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, bufferInfo.get(0));
        }
        int textureIndex = 0;
        int storageIndex = 0;
        for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++) {
            int resourceType = dispatches.resourceType(dispatchIndex, resourceIndex);
            VulkanImage image = denoiser.resolveResource(
                    prepared,
                    resourceType,
                    dispatches.resourceIndexInPool(dispatchIndex, resourceIndex),
                    dispatches.identifier(dispatchIndex));
            int binding;
            int descriptorType;
            int nativeDescriptorType = dispatches.resourceDescriptorType(
                    dispatchIndex, resourceIndex);
            if (nativeDescriptorType == NrdNative.DESCRIPTOR_TEXTURE) {
                binding = denoiser.description.textureOffset()
                        + denoiser.description.resourcesBaseRegisterIndex()
                        + textureIndex++;
                descriptorType = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            } else if (nativeDescriptorType == NrdNative.DESCRIPTOR_STORAGE_TEXTURE) {
                binding = denoiser.description.storageTextureOffset()
                        + denoiser.description.resourcesBaseRegisterIndex()
                        + storageIndex++;
                descriptorType = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            } else {
                throw new IllegalStateException(
                        "Unknown NRD resource descriptor type " + nativeDescriptorType);
            }
            imageInfos.get(resourceIndex)
                    .imageView(image.view())
                    .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VulkanDescriptors.writeImage(
                    writes.get(writeIndex++), this.resourceDescriptorSets[dispatchIndex],
                    binding, descriptorType, imageInfos.get(resourceIndex));
        }
        VK12.vkUpdateDescriptorSets(denoiser.context.vkDevice(), writes, null);
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            RuntimeException failure = ResourceCleanup.destroy(this.constantBuffer, null);
            failure = ResourceCleanup.run(
                    () -> VK12.vkDestroyDescriptorPool(
                            this.owner.context.vkDevice(), this.descriptorPool, null),
                    failure);
            ResourceCleanup.throwIfFailed(failure);
        }
    }
}
