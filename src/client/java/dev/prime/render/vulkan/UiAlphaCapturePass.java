package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Extracts Minecraft's post-UI alpha into a Streamline UI_ALPHA image. */
public final class UiAlphaCapturePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = 8;

    private final VulkanContext context;
    private final SharedComputeProgram clearProgram;
    private final SharedComputeProgram extractProgram;
    private final VulkanImage alpha;
    private final long clearDescriptorPool;
    private final long clearDescriptorSet;
    private final long extractDescriptorPool;
    private final long extractDescriptorSet;
    private final int width;
    private final int height;
    private final long sourceImage;
    private final long sourceView;
    private boolean alphaInitialized;
    private boolean destroyed;

    private UiAlphaCapturePass(
            VulkanContext context,
            SharedComputeProgram clearProgram,
            SharedComputeProgram extractProgram,
            VulkanImage alpha,
            long clearDescriptorPool,
            long clearDescriptorSet,
            long extractDescriptorPool,
            long extractDescriptorSet,
            int width,
            int height,
            long sourceImage,
            long sourceView) {
        this.context = context;
        this.clearProgram = clearProgram;
        this.extractProgram = extractProgram;
        this.alpha = alpha;
        this.clearDescriptorPool = clearDescriptorPool;
        this.clearDescriptorSet = clearDescriptorSet;
        this.extractDescriptorPool = extractDescriptorPool;
        this.extractDescriptorSet = extractDescriptorSet;
        this.width = width;
        this.height = height;
        this.sourceImage = sourceImage;
        this.sourceView = sourceView;
    }

    public static UiAlphaCapturePass create(
            VulkanContext context,
            int width,
            int height,
            long sourceImage,
            long sourceView) {
        Objects.requireNonNull(context, "context");
        if (width <= 0 || height <= 0 || sourceImage == 0L || sourceView == 0L) {
            throw new IllegalArgumentException("UI alpha capture dimensions and source must be valid");
        }
        SharedComputeProgram clearProgram = null;
        SharedComputeProgram extractProgram = null;
        VulkanImage alpha = null;
        long clearPool = 0L;
        long extractPool = 0L;
        try {
            clearProgram = context.acquireUiAlphaClearProgram();
            extractProgram = context.acquireUiAlphaExtractProgram();
            alpha = context.createImage2D(
                    width,
                    height,
                    VK12.VK_FORMAT_R8_UNORM,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime UI alpha");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer clearSizes = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1);
                clearPool = VulkanDescriptors.createPool(
                        context, stack, 1, clearSizes,
                        "create UI alpha clear descriptor pool");
                long clearSet = VulkanDescriptors.allocateSet(
                        context, stack, clearPool, clearProgram.descriptorSetLayout(),
                        "allocate UI alpha clear descriptor set");
                VkDescriptorImageInfo clearInfo = VkDescriptorImageInfo.calloc(stack)
                        .imageView(sourceView)
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer clearWrites = VkWriteDescriptorSet.calloc(1, stack);
                clearWrites.get(0)
                        .sType$Default()
                        .dstSet(clearSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(clearInfo.address(), 1));
                VK12.vkUpdateDescriptorSets(context.vkDevice(), clearWrites, null);

                VkDescriptorPoolSize.Buffer extractSizes = VkDescriptorPoolSize.calloc(2, stack);
                extractSizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(1);
                extractSizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1);
                extractPool = VulkanDescriptors.createPool(
                        context, stack, 1, extractSizes,
                        "create UI alpha extraction descriptor pool");
                long extractSet = VulkanDescriptors.allocateSet(
                        context, stack, extractPool, extractProgram.descriptorSetLayout(),
                        "allocate UI alpha extraction descriptor set");
                VkDescriptorImageInfo.Buffer extractInfos = VkDescriptorImageInfo.calloc(2, stack);
                extractInfos.get(0)
                        .imageView(sourceView)
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                extractInfos.get(1)
                        .imageView(alpha.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer extractWrites = VkWriteDescriptorSet.calloc(2, stack);
                extractWrites.get(0)
                        .sType$Default()
                        .dstSet(extractSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(extractInfos.get(0).address(), 1));
                extractWrites.get(1)
                        .sType$Default()
                        .dstSet(extractSet)
                        .dstBinding(1)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(extractInfos.get(1).address(), 1));
                VK12.vkUpdateDescriptorSets(context.vkDevice(), extractWrites, null);
                return new UiAlphaCapturePass(
                        context,
                        clearProgram,
                        extractProgram,
                        alpha,
                        clearPool,
                        clearSet,
                        extractPool,
                        extractSet,
                        width,
                        height,
                        sourceImage,
                        sourceView);
            }
        } catch (RuntimeException exception) {
            if (extractPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), extractPool, null);
            }
            if (clearPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), clearPool, null);
            }
            ResourceCleanup.destroy(alpha, exception);
            if (extractProgram != null) extractProgram.release();
            if (clearProgram != null) clearProgram.release();
            throw exception;
        }
    }

    public VulkanImage alpha() {
        return this.alpha;
    }

    public boolean matches(int requestedWidth, int requestedHeight, long requestedImage, long requestedView) {
        return this.width == requestedWidth
                && this.height == requestedHeight
                && this.sourceImage == requestedImage
                && this.sourceView == requestedView;
    }

    public void recordClear(VkCommandBuffer commandBuffer) {
        this.requireOpen();
        VulkanSync.imageBarrier(
                commandBuffer,
                this.sourceImage,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        record(commandBuffer, this.clearProgram, this.clearDescriptorSet);
        VulkanSync.imageBarrier(
                commandBuffer,
                this.sourceImage,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    public void recordExtract(VkCommandBuffer commandBuffer, VulkanGpuTexture mainColor) {
        this.requireOpen();
        if (mainColor.getWidth(0) != this.width || mainColor.getHeight(0) != this.height) {
            throw new IllegalArgumentException("UI alpha source extent differs from capture extent");
        }
        VulkanSync.imageBarrier(
                commandBuffer,
                this.sourceImage,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_READ_BIT);
        VulkanSync.imageBarrier(
                commandBuffer,
                this.alpha.image(),
                this.alphaInitialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                this.alphaInitialized ? COMPUTE_STAGE : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.alphaInitialized ? VK12.VK_ACCESS_SHADER_WRITE_BIT : 0L,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        record(commandBuffer, this.extractProgram, this.extractDescriptorSet);
        VulkanSync.memoryBarrier(
                commandBuffer,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
        this.alphaInitialized = true;
    }

    private void record(VkCommandBuffer commandBuffer, SharedComputeProgram program, long descriptorSet) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline(0));
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    program.pipelineLayout(),
                    0,
                    stack.longs(descriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, program.pipelineLayout(), COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE),
                    1);
        }
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("UI alpha capture pass is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.clearDescriptorPool, null);
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.extractDescriptorPool, null);
        this.clearProgram.release();
        this.extractProgram.release();
        this.alpha.destroy();
    }
}
