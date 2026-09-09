// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.prime.infrastructure.ResourceCleanup;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Common command ownership and image-initialization boundary for one renderer frame. */
final class VulkanFrameSubmission {
    private final VulkanImageInitializationBatch imageInitialization;
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
        VulkanImageTransitions.blitFlipped(
                commandBuffer,
                output.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                mainColor.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                width,
                height);
        VulkanImageTransitions.finishImageCopy(commandBuffer, output, mainColor);
    }

    void copyPrimaryRayToMinecraft(
            VkCommandBuffer commandBuffer,
            VulkanImage output,
            VulkanGpuTexture mainColor,
            int width,
            int height) {
        VulkanImageTransitions.preparePrimaryRayImageForCopy(
                commandBuffer, output, mainColor);
        VulkanImageTransitions.blitFlipped(
                commandBuffer,
                output.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                mainColor.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                width,
                height);
        VulkanImageTransitions.finishImageCopy(commandBuffer, output, mainColor);
    }

    void submit(
            VulkanCommandEncoder encoder,
            VkCommandBuffer commandBuffer,
            String endOperation) {
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer), endOperation);
        encoder.execute(commandBuffer);
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
}
