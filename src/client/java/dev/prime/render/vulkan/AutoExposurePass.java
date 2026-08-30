package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Device-local full-frame luminance histogram and temporal exposure state.
 *
 * <p>The owning display pass records every state transition on the render queue. No CPU readback,
 * cross-thread mutation or lock participates in exposure adaptation.
 */
final class AutoExposurePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 16;
    private static final int HISTOGRAM_BIN_COUNT = 256;
    private static final int HISTOGRAM_SIZE =
            (HISTOGRAM_BIN_COUNT + 1) * Integer.BYTES;
    static final int EXPOSURE_STATE_SIZE = 16;
    private static final int HISTOGRAM_TILE_SIZE = 64;

    private final VulkanContext context;
    private final SharedComputeProgram program;
    private final VulkanBuffer histogram;
    private final VulkanBuffer exposureState;
    private final long descriptorPool;
    private final long descriptorSet;
    private final int dispatchX;
    private final int dispatchY;
    private final boolean accumulatedMetering;
    private boolean destroyed;

    private AutoExposurePass(
            VulkanContext context,
            SharedComputeProgram program,
            VulkanBuffer histogram,
            VulkanBuffer exposureState,
            long descriptorPool,
            long descriptorSet,
            int width,
            int height,
            boolean accumulatedMetering) {
        this.context = context;
        this.program = program;
        this.histogram = histogram;
        this.exposureState = exposureState;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.dispatchX = DispatchMath.divideRoundUp(width, HISTOGRAM_TILE_SIZE);
        this.dispatchY = DispatchMath.divideRoundUp(height, HISTOGRAM_TILE_SIZE);
        this.accumulatedMetering = accumulatedMetering;
    }

    static AutoExposurePass create(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanImage albedo,
            VulkanImage materialClass,
            boolean accumulatedMetering) {
        SharedComputeProgram program = context.acquireAutoExposureProgram();
        VulkanBuffer histogram = null;
        VulkanBuffer exposureState = null;
        long descriptorPool = 0L;
        try {
            histogram = context.createBuffer(
                    HISTOGRAM_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime auto-exposure histogram");
            exposureState = context.createBuffer(
                    EXPOSURE_STATE_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    false,
                    "Prime auto-exposure state");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer poolSizes =
                        VkDescriptorPoolSize.calloc(3, stack);
                poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(1);
                poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(2);
                poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(2);
                descriptorPool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        poolSizes,
                        "create auto-exposure descriptor pool");
                long descriptorSet = VulkanDescriptors.allocateSet(
                        context,
                        stack,
                        descriptorPool,
                        program.descriptorSetLayout(),
                        "allocate auto-exposure descriptor set");
                VkDescriptorImageInfo.Buffer imageInfo =
                        VkDescriptorImageInfo.calloc(3, stack);
                imageInfo.get(0).imageView(linearInput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfo.get(1).imageView(albedo.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfo.get(2).imageView(materialClass.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer bufferInfos =
                        VkDescriptorBufferInfo.calloc(2, stack);
                bufferInfos.get(0).buffer(histogram.handle())
                        .offset(0L).range(HISTOGRAM_SIZE);
                bufferInfos.get(1).buffer(exposureState.handle())
                        .offset(0L).range(EXPOSURE_STATE_SIZE);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(5, stack);
                writes.get(0).sType$Default()
                        .dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfo.get(0).address(), 1));
                for (int binding = 1; binding < 3; binding++) {
                    writes.get(binding).sType$Default()
                            .dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfo.get(binding).address(), 1));
                }
                for (int binding = 3; binding < 5; binding++) {
                    writes.get(binding).sType$Default()
                            .dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(binding - 3).address(), 1));
                }
                VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                return new AutoExposurePass(
                        context,
                        program,
                        histogram,
                        exposureState,
                        descriptorPool,
                        descriptorSet,
                        linearInput.width(),
                        linearInput.height(),
                        accumulatedMetering);
            }
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            ResourceCleanup.destroy(exposureState, exception);
            ResourceCleanup.destroy(histogram, exception);
            program.release();
            throw exception;
        }
    }

    VulkanBuffer exposureState() {
        return this.exposureState;
    }

    void record(
            VkCommandBuffer commandBuffer,
            int width,
            int height,
            float deltaSeconds,
            boolean reset,
            boolean instant,
            float compensation) {
        if (this.destroyed) {
            throw new IllegalStateException("Auto-exposure pass is destroyed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Auto-exposure extent must be positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0F) {
            throw new IllegalArgumentException(
                    "Auto-exposure frame delta must be finite and non-negative");
        }
        if (!Float.isFinite(compensation) || compensation < 0.0F || compensation > 1.0F) {
            throw new IllegalArgumentException(
                    "Auto-exposure compensation must be finite and between zero and one");
        }
        VK12.vkCmdFillBuffer(
                commandBuffer,
                this.histogram.handle(),
                0L,
                HISTOGRAM_SIZE,
                0);
        writesToCompute(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipelineLayout(),
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipeline(0));
            ByteBuffer histogramPush =
                    stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            histogramPush.putInt(0, width);
            histogramPush.putInt(4, height);
            histogramPush.putInt(8, this.accumulatedMetering ? 1 : 0);
            histogramPush.putInt(12, 0);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.program.pipelineLayout(),
                    COMPUTE_STAGE,
                    0,
                    histogramPush);
            VK12.vkCmdDispatch(
                    commandBuffer, this.dispatchX, this.dispatchY, 1);

            computeBarrier(commandBuffer);
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipeline(1));
            ByteBuffer updatePush =
                    stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            updatePush.putFloat(0, deltaSeconds);
            updatePush.putInt(4, reset ? 1 : 0);
            updatePush.putInt(8, instant ? 1 : 0);
            updatePush.putFloat(12, compensation);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.program.pipelineLayout(),
                    COMPUTE_STAGE,
                    0,
                    updatePush);
            VK12.vkCmdDispatch(commandBuffer, 1, 1, 1);
        }
        computeBarrier(commandBuffer);
    }

    private static void writesToCompute(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void computeBarrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        VK12.vkDestroyDescriptorPool(
                this.context.vkDevice(), this.descriptorPool, null);
        this.exposureState.destroy();
        this.histogram.destroy();
        this.program.release();
        this.destroyed = true;
    }
}
