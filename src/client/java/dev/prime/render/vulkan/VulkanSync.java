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
            memoryBarrier(
                    commandBuffer,
                    stack,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
        }
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack frame = stack.push()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, frame);
            barrier.get(0).sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(frame)
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
            barrier.get(0).sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            barrier.get(0).subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barrier));
        }
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
                VkImageMemoryBarrier2 image = imageBarriers.get(index)
                        .sType$Default()
                        .srcStageMask(sourceStage)
                        .srcAccessMask(sourceAccess)
                        .dstStageMask(destinationStage)
                        .dstAccessMask(destinationAccess)
                        .oldLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(images[index]);
                image.subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(frame).sType$Default()
                            .pBufferMemoryBarriers(bufferBarriers)
                            .pImageMemoryBarriers(imageBarriers));
        }
    }

    public static void bufferBarriers(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer[] buffers,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack frame = stack.push()) {
            VkBufferMemoryBarrier2.Buffer barriers =
                    VkBufferMemoryBarrier2.calloc(buffers.length, frame);
            for (int index = 0; index < buffers.length; index++) {
                VulkanBuffer buffer = buffers[index];
                setBufferBarrier(barriers.get(index), buffer.handle(), 0L, buffer.size(),
                        sourceStage, sourceAccess, destinationStage, destinationAccess);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(frame).sType$Default()
                            .pBufferMemoryBarriers(barriers));
        }
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
