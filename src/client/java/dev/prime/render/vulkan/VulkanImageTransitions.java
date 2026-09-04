package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkOffset3D;

/** Prime's centralized Vulkan image availability, visibility and layout transitions. */
public final class VulkanImageTransitions {
    private VulkanImageTransitions() {
    }

    public static void prepareTraceTextures(
            VkCommandBuffer commandBuffer,
            VulkanGpuTexture atlas,
            List<TraceBackend.SceneTexture> textures) {
        // Minecraft updates animated atlas regions in place and keeps the image in GENERAL.
        // Queue order alone is not a memory dependency: both halves of this read/write pair are
        // required unless atlas ownership gains an equivalent explicit synchronization protocol.
        traceTextureBarriers(
                commandBuffer,
                atlas,
                textures,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    public static void finishTraceTextureReads(
            VkCommandBuffer commandBuffer,
            VulkanGpuTexture atlas,
            List<TraceBackend.SceneTexture> textures) {
        traceTextureBarriers(
                commandBuffer,
                atlas,
                textures,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    private static void traceTextureBarriers(
            VkCommandBuffer commandBuffer,
            VulkanGpuTexture atlas,
            List<TraceBackend.SceneTexture> textures,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(textures.size() + 1, stack);
            VulkanSync.setImageBarrier(
                    barriers.get(0),
                    atlas.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
            for (int index = 0; index < textures.size(); index++) {
                VulkanSync.setImageBarrier(
                        barriers.get(index + 1),
                        textures.get(index).image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        sourceStage,
                        sourceAccess,
                        destinationStage,
                        destinationAccess);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    public static void prepareOutputForComposite(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image) {
        VulkanSync.prepareImage(
                commandBuffer, initialization, image,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void prepareAccumulationForTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image) {
        // Stable radiance is written by raygen and read by NRD composite across frames.
        VulkanSync.prepareImage(
                commandBuffer, initialization, image,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void preparePrimaryRayOutput(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            VulkanImage image) {
        VulkanSync.prepareImage(
                commandBuffer,
                initialization,
                image,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_READ_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void prepareOfflineDisplay(
            VkCommandBuffer commandBuffer, VulkanImage accumulation) {
        VulkanSync.imageBarrier(
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
        prepareImagesForCopy(
                commandBuffer,
                source,
                destination,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public static void preparePrimaryRayImageForCopy(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanGpuTexture destination) {
        prepareImagesForCopy(
                commandBuffer,
                source,
                destination,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void prepareImagesForCopy(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanGpuTexture destination,
            long sourceStage,
            long sourceAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(2, stack);
            VulkanSync.setImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    sourceStage,
                    sourceAccess,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
            VulkanSync.setImageBarrier(
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
            VulkanSync.setImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_READ_BIT);
            VulkanSync.setImageBarrier(
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

    public static void blitFlipped(
            VkCommandBuffer commandBuffer,
            long source,
            int sourceLayout,
            long destination,
            int destinationLayout,
            int width,
            int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkOffset3D.Buffer sourceOffsets = VkOffset3D.calloc(2, stack);
            sourceOffsets.get(1).set(width, height, 1);
            VkOffset3D.Buffer destinationOffsets = VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, height, 0);
            destinationOffsets.get(1).set(width, 0, 1);
            VkImageSubresourceLayers layers = VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(1);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack)
                    .srcSubresource(layers)
                    .srcOffsets(sourceOffsets)
                    .dstSubresource(layers)
                    .dstOffsets(destinationOffsets);
            VK12.vkCmdBlitImage(
                    commandBuffer,
                    source,
                    sourceLayout,
                    destination,
                    destinationLayout,
                    blit,
                    VK12.VK_FILTER_NEAREST);
        }
    }

}
