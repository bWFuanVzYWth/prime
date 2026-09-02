package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.prime.infrastructure.ResourceCleanup;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkOffset3D;

/** Common command ownership and image-initialization boundary for one renderer frame. */
final class VulkanFrameSubmission {
    private final VulkanImageInitializationBatch imageInitialization;
    private final MinecraftHostSubmission hostSubmission = new MinecraftHostSubmission();
    private boolean initializationActive;

    VulkanFrameSubmission(VulkanImageInitializationBatch imageInitialization) {
        this.imageInitialization = imageInitialization;
    }

    void begin() {
        this.imageInitialization.begin();
        this.initializationActive = true;
    }

    void copyToMinecraft(
            VkCommandBuffer commandBuffer,
            VulkanImage output,
            VulkanGpuTexture mainColor,
            int width,
            int height) {
        VulkanImageTransitions.prepareImagesForCopy(commandBuffer, output, mainColor);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkOffset3D.Buffer sourceOffsets = VkOffset3D.calloc(2, stack);
            sourceOffsets.get(0).set(0, 0, 0);
            sourceOffsets.get(1).set(width, height, 1);
            VkOffset3D.Buffer destinationOffsets = VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, height, 0);
            destinationOffsets.get(1).set(width, 0, 1);
            VkImageSubresourceLayers sourceLayers = VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkImageSubresourceLayers destinationLayers = VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack)
                    .srcSubresource(sourceLayers)
                    .srcOffsets(sourceOffsets)
                    .dstSubresource(destinationLayers)
                    .dstOffsets(destinationOffsets);
            VK12.vkCmdBlitImage(
                    commandBuffer,
                    output.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    mainColor.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    blit,
                    VK12.VK_FILTER_NEAREST);
        }
        VulkanImageTransitions.finishImageCopy(commandBuffer, output, mainColor);
    }

    void submit(
            VulkanCommandEncoder encoder,
            VkCommandBuffer commandBuffer,
            String endOperation) {
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer), endOperation);
        encoder.execute(commandBuffer);
        this.hostSubmission.acceptedByMinecraftHostSubmission();
    }

    void submitted() {
        this.imageInitialization.submitted();
        this.initializationActive = false;
    }

    RuntimeException abandon(RuntimeException failure) {
        if (!this.initializationActive) {
            return failure;
        }
        return ResourceCleanup.run(this.imageInitialization::abandon, failure);
    }

    boolean wasAcceptedByMinecraftHostSubmission() {
        return this.hostSubmission.wasAcceptedByMinecraftHostSubmission();
    }
}
