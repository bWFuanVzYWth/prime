package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
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

/** Provides persistent top-left-origin depth, motion and color inputs for Streamline. */
public final class StreamlineInputFlipPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = 8;

    private final VulkanContext context;
    private final SharedComputeProgram program;
    private final VulkanImage sourceDepth;
    private final VulkanImage sourceMotion;
    private final VulkanImage sourceColor;
    private final VulkanImage depth;
    private final VulkanImage motion;
    private final VulkanImage color;
    private final long descriptorPool;
    private final long descriptorSet;
    private boolean initialized;
    private boolean destroyed;

    private StreamlineInputFlipPass(
            VulkanContext context,
            SharedComputeProgram program,
            VulkanImage sourceDepth,
            VulkanImage sourceMotion,
            VulkanImage sourceColor,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage color,
            long descriptorPool,
            long descriptorSet) {
        this.context = context;
        this.program = program;
        this.sourceDepth = sourceDepth;
        this.sourceMotion = sourceMotion;
        this.sourceColor = sourceColor;
        this.depth = depth;
        this.motion = motion;
        this.color = color;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
    }

    public static StreamlineInputFlipPass create(
            VulkanContext context,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage color) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(color, "color");
        if (depth.width() != motion.width() || depth.height() != motion.height()) {
            throw new IllegalArgumentException("Streamline depth and motion extents differ");
        }
        if ((depth.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0
                || (motion.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Streamline depth and motion images must support sampled reads");
        }
        requireTransferSource(color, "color");

        SharedComputeProgram program = null;
        VulkanImage flippedDepth = null;
        VulkanImage flippedMotion = null;
        VulkanImage flippedColor = null;
        long descriptorPool = 0L;
        try {
            program = context.acquireStreamlineInputProgram();
            flippedDepth = context.createImage2D(
                    depth.width(),
                    depth.height(),
                    VK12.VK_FORMAT_R32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline reversed depth");
            flippedMotion = context.createImage2D(
                    motion.width(),
                    motion.height(),
                    VK12.VK_FORMAT_R32G32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline top-left motion");
            flippedColor = createColorDestination(context, color);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(2);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(2);
                descriptorPool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        sizes,
                        "create Streamline input descriptor pool");
                long descriptorSet = VulkanDescriptors.allocateSet(
                        context,
                        stack,
                        descriptorPool,
                        program.descriptorSetLayout(),
                        "allocate Streamline input descriptor set");
                VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(4, stack);
                infos.get(0).imageView(depth.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(1).imageView(motion.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(2).imageView(flippedDepth.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(3).imageView(flippedMotion.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
                for (int binding = 0; binding < 4; binding++) {
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(binding)
                            .descriptorCount(1)
                            .descriptorType(binding < 2
                                    ? VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                                    : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(binding).address(), 1));
                }
                VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                return new StreamlineInputFlipPass(
                        context,
                        program,
                        depth,
                        motion,
                        color,
                        flippedDepth,
                        flippedMotion,
                        flippedColor,
                        descriptorPool,
                        descriptorSet);
            }
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            ResourceCleanup.destroy(flippedColor, exception);
            ResourceCleanup.destroy(flippedMotion, exception);
            ResourceCleanup.destroy(flippedDepth, exception);
            if (program != null) program.release();
            throw exception;
        }
    }

    public VulkanImage depth() {
        return this.depth;
    }

    public VulkanImage motion() {
        return this.motion;
    }

    public VulkanImage color() {
        return this.color;
    }

    public boolean matches(VulkanImage depth, VulkanImage motion, VulkanImage color) {
        return this.sourceDepth == depth
                && this.sourceMotion == motion
                && this.sourceColor == color;
    }

    public void record(VkCommandBuffer commandBuffer) {
        requireOpen();
        prepareSampledSource(commandBuffer, this.sourceDepth);
        prepareSampledSource(commandBuffer, this.sourceMotion);
        prepareStorageOutput(commandBuffer, this.depth);
        prepareStorageOutput(commandBuffer, this.motion);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.depth.width());
            push.putInt(4, this.depth.height());
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipeline(0));
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipelineLayout(),
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.program.pipelineLayout(),
                    COMPUTE_STAGE,
                    0,
                    push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    DispatchMath.divideRoundUp(this.depth.width(), LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.depth.height(), LOCAL_SIZE),
                    1);
        }
        VulkanSync.memoryBarrier(
                commandBuffer,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
        recordColorFlip(commandBuffer, this.sourceColor, this.color);
        this.initialized = true;
    }

    private static VulkanImage createColorDestination(
            VulkanContext context, VulkanImage source) {
        return context.createImage2D(
                source.width(),
                source.height(),
                source.format(),
                VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                "Prime Streamline flipped HUD-less color");
    }

    private static void requireTransferSource(VulkanImage image, String name) {
        if ((image.usage() & VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Streamline " + name + " image is missing transfer-source usage");
        }
    }

    private static void prepareSampledSource(
            VkCommandBuffer commandBuffer, VulkanImage source) {
        VulkanSync.imageBarrier(
                commandBuffer,
                source.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private void prepareStorageOutput(
            VkCommandBuffer commandBuffer, VulkanImage destination) {
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                this.initialized
                        ? VK12.VK_IMAGE_LAYOUT_GENERAL
                        : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                this.initialized
                        ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.initialized
                        ? VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT
                        : 0L,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void recordColorFlip(
            VkCommandBuffer commandBuffer, VulkanImage source, VulkanImage destination) {
        VulkanSync.imageBarrier(
                commandBuffer,
                source.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_READ_BIT);
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                this.initialized
                        ? VK12.VK_IMAGE_LAYOUT_GENERAL
                        : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                this.initialized
                        ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.initialized ? VK12.VK_ACCESS_MEMORY_READ_BIT : 0L,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sourceOffsets = org.lwjgl.vulkan.VkOffset3D.calloc(2, stack);
            sourceOffsets.get(0).set(0, source.height(), 0);
            sourceOffsets.get(1).set(source.width(), 0, 1);
            var destinationOffsets = org.lwjgl.vulkan.VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, 0, 0);
            destinationOffsets.get(1).set(destination.width(), destination.height(), 1);
            var sourceLayers = org.lwjgl.vulkan.VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            var destinationLayers = org.lwjgl.vulkan.VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            var blit = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack)
                    .srcSubresource(sourceLayers)
                    .srcOffsets(sourceOffsets)
                    .dstSubresource(destinationLayers)
                    .dstOffsets(destinationOffsets);
            VK12.vkCmdBlitImage(
                    commandBuffer,
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    destination.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit,
                    VK12.VK_FILTER_NEAREST);
        }
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Streamline input flip pass is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        this.program.release();
        this.color.destroy();
        this.motion.destroy();
        this.depth.destroy();
    }
}
