package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.util.Objects;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Provides top-left-origin copies of Prime images for Streamline resource tagging. */
public final class StreamlineInputFlipPass implements Destroyable {
    private final VulkanContext context;
    private final VulkanImage depth;
    private final VulkanImage motion;
    private final VulkanImage color;
    private final VulkanImage[] sources;
    private boolean destroyed;

    private StreamlineInputFlipPass(
            VulkanContext context,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage color,
            VulkanImage[] sources) {
        this.context = context;
        this.depth = depth;
        this.motion = motion;
        this.color = color;
        this.sources = sources;
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
        requireTransferSource(depth, "depth");
        requireTransferSource(motion, "motion");
        requireTransferSource(color, "color");
        VulkanImage flippedDepth = null;
        VulkanImage flippedMotion = null;
        VulkanImage flippedColor = null;
        try {
            flippedDepth = createDestination(context, depth, "Prime Streamline flipped depth");
            flippedMotion = createDestination(context, motion, "Prime Streamline flipped motion");
            flippedColor = createDestination(context, color, "Prime Streamline flipped HUD-less color");
            return new StreamlineInputFlipPass(
                    context,
                    flippedDepth,
                    flippedMotion,
                    flippedColor,
                    new VulkanImage[] {depth, motion, color});
        } catch (RuntimeException exception) {
            if (flippedColor != null) flippedColor.destroy();
            if (flippedMotion != null) flippedMotion.destroy();
            if (flippedDepth != null) flippedDepth.destroy();
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

    public void record(VkCommandBuffer commandBuffer) {
        requireOpen();
        recordFlip(commandBuffer, this.sources[0], this.depth);
        recordFlip(commandBuffer, this.sources[1], this.motion);
        recordFlip(commandBuffer, this.sources[2], this.color);
    }

    private static VulkanImage createDestination(
            VulkanContext context, VulkanImage source, String label) {
        return context.createImage2D(
                source.width(),
                source.height(),
                source.format(),
                VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                label);
    }

    private static void requireTransferSource(VulkanImage image, String name) {
        if ((image.usage() & VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Streamline " + name + " image is missing transfer-source usage");
        }
    }

    private static void recordFlip(
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
                VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                0L,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
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
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.color.destroy();
        this.motion.destroy();
        this.depth.destroy();
    }
}
