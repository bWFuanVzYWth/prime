package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/** Prime's centralized Vulkan image availability, visibility and layout transitions. */
public final class VulkanImageTransitions {
    private VulkanImageTransitions() {
    }

    public static void prepareAtlasForTrace(
            VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        // Minecraft updates animated atlas regions in place and keeps the image in GENERAL.
        // Queue order alone is not a memory dependency: both halves of this read/write pair are
        // required unless atlas ownership gains an equivalent explicit synchronization protocol.
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    public static void finishAtlasRead(
            VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    public static void prepareSceneTexturesForTrace(
            VkCommandBuffer commandBuffer,
            List<TraceBackend.SceneTexture> textures) {
        for (TraceBackend.SceneTexture texture : textures) {
            imageBarrier(
                    commandBuffer,
                    texture.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
        }
    }

    public static void finishSceneTextureReads(
            VkCommandBuffer commandBuffer,
            List<TraceBackend.SceneTexture> textures) {
        for (TraceBackend.SceneTexture texture : textures) {
            imageBarrier(
                    commandBuffer,
                    texture.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT);
        }
    }

    public static void prepareOutputForComposite(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image) {
        boolean initialized = initialization.prepare(image);
        int oldLayout = initialized
                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = initialized
                ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = initialized
                ? VK12.VK_ACCESS_MEMORY_READ_BIT
                : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void prepareAccumulationForTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image) {
        boolean initialized = initialization.prepare(image);
        int oldLayout = initialized
                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = initialized
                // Stable radiance is written by raygen and read by NRD composite across frames.
                ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = initialized
                ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void prepareOfflineDisplay(
            VkCommandBuffer commandBuffer, VulkanImage accumulation) {
        imageBarrier(
                commandBuffer,
                accumulation.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    public static void prepareImagesForCopy(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    public static void finishImageCopy(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_READ_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    VK12.VK_ACCESS_MEMORY_READ_BIT
                            | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier =
                    VkImageMemoryBarrier2.calloc(1, stack);
            fillImageBarrier(
                    barrier.get(0),
                    image,
                    oldLayout,
                    newLayout,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barrier));
        }
    }

    private static void fillImageBarrier(
            VkImageMemoryBarrier2 barrier,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
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
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }
}
