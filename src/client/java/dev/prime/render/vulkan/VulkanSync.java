// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Shared synchronization2 memory barrier recording. */
public final class VulkanSync {
    private VulkanSync() {
    }

    public static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pMemoryBarriers(barrier));
        }
    }

    public static void imageBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            setImageBarrier(barrier.get(0), image, oldLayout, newLayout,
                    sourceStage, sourceAccess, destinationStage, destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barrier));
        }
    }

    public static void imageBarriers(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                setImageBarrier(barriers.get(index), images[index].image(),
                        oldLayout, newLayout, sourceStage, sourceAccess,
                        destinationStage, destinationAccess);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    public static void prepareImages(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage[] images,
            long initializedSourceStage,
            long initializedSourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                VulkanImage image = images[index];
                boolean initialized = initialization.prepare(image);
                setImageBarrier(barriers.get(index), image.image(),
                        initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        initialized ? initializedSourceStage : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        initialized ? initializedSourceAccess : 0L,
                        destinationStage, destinationAccess);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    public static void prepareImage(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image,
            long initializedSourceStage,
            long initializedSourceAccess,
            long destinationStage,
            long destinationAccess) {
        boolean initialized = initialization.prepare(image);
        imageBarrier(commandBuffer, image.image(),
                initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                initialized ? initializedSourceStage : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                initialized ? initializedSourceAccess : 0L,
                destinationStage, destinationAccess);
    }

    public static void bufferBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer buffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        bufferBarrier(commandBuffer, stack, buffer.handle(), 0L, buffer.size(),
                sourceStage, sourceAccess, destinationStage, destinationAccess);
    }

    public static void bufferBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long buffer,
            long offset,
            long size,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack frame = stack.push()) {
            VkBufferMemoryBarrier2.Buffer barriers = VkBufferMemoryBarrier2.calloc(1, frame);
            setBufferBarrier(barriers.get(0), buffer, offset, size,
                    sourceStage, sourceAccess, destinationStage, destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(frame).sType$Default()
                            .pBufferMemoryBarriers(barriers));
        }
    }

    public static void resourceBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer buffer,
            long[] images,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        resourceBarrier(
                commandBuffer,
                stack,
                buffer,
                null,
                images,
                sourceStage,
                sourceAccess,
                destinationStage,
                destinationAccess);
    }

    public static void resourceBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer firstBuffer,
            VulkanBuffer secondBuffer,
            long[] images,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack frame = stack.push()) {
            int bufferCount = secondBuffer == null ? 1 : 2;
            VkBufferMemoryBarrier2.Buffer bufferBarriers =
                    VkBufferMemoryBarrier2.calloc(bufferCount, frame);
            setBufferBarrier(
                    bufferBarriers.get(0),
                    firstBuffer.handle(),
                    0L,
                    firstBuffer.size(),
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
            if (secondBuffer != null) {
                setBufferBarrier(
                        bufferBarriers.get(1),
                        secondBuffer.handle(),
                        0L,
                        secondBuffer.size(),
                        sourceStage,
                        sourceAccess,
                        destinationStage,
                        destinationAccess);
            }
            VkImageMemoryBarrier2.Buffer imageBarriers =
                    VkImageMemoryBarrier2.calloc(images.length, frame);
            for (int index = 0; index < images.length; index++) {
                setImageBarrier(imageBarriers.get(index), images[index],
                        VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                        sourceStage, sourceAccess, destinationStage, destinationAccess);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(frame).sType$Default()
                            .pBufferMemoryBarriers(bufferBarriers)
                            .pImageMemoryBarriers(imageBarriers));
        }
    }

    static void setImageBarrier(
            VkImageMemoryBarrier2 barrier,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        setImageBarrier(barrier, image, oldLayout, newLayout,
                sourceStage, sourceAccess, destinationStage, destinationAccess, 1);
    }

    static void setImageBarrier(
            VkImageMemoryBarrier2 barrier,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            int levelCount) {
        barrier.sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(levelCount).baseArrayLayer(0).layerCount(1);
    }

    private static void setBufferBarrier(
            VkBufferMemoryBarrier2 barrier,
            long buffer,
            long offset,
            long size,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        barrier.sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer).offset(offset).size(size);
    }
}
