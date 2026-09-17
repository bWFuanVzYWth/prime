// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Composites Prime HDR and vanilla's encoded RGBA8 overlays into linear scRGB. */
final class HdrPresentPass implements Destroyable {
    private static final int PUSH_SIZE = 16;
    private static final int LOCAL_SIZE = 8;

    private final VulkanContext context;
    private final SharedComputeProgram program;
    private final VulkanImage output;
    private final long descriptorPool;
    private final long descriptorSet;
    private final int width;
    private final int height;
    private long hdrView;
    private long baselineView;
    private long uiView;
    private boolean outputInitialized;
    private boolean destroyed;

    private HdrPresentPass(
            VulkanContext context,
            SharedComputeProgram program,
            VulkanImage output,
            long descriptorPool,
            long descriptorSet,
            int width,
            int height) {
        this.context = context;
        this.program = program;
        this.output = output;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.width = width;
        this.height = height;
    }

    static HdrPresentPass create(VulkanContext context, int width, int height) {
        VulkanImage output = null;
        SharedComputeProgram program = null;
        long descriptorPool = 0L;
        try {
            output = context.createImage2D(
                    width,
                    height,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    "Prime linear scRGB presentation");
            program = context.acquireSharedProgram(
                    VulkanSharedPrograms.Program.HDR_PRESENT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
                poolSizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(3);
                poolSizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1);
                descriptorPool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        poolSizes,
                        "create HDR-present descriptor pool");
                long descriptorSet = VulkanDescriptors.allocateSet(
                        context,
                        stack,
                        descriptorPool,
                        program.descriptorSetLayout(),
                        "allocate HDR-present descriptor set");
                return new HdrPresentPass(
                        context,
                        program,
                        output,
                        descriptorPool,
                        descriptorSet,
                        width,
                        height);
            }
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            if (program != null) {
                program.release();
            }
            ResourceCleanup.destroy(output, exception);
            throw exception;
        }
    }

    boolean matches(int requestedWidth, int requestedHeight) {
        return this.width == requestedWidth && this.height == requestedHeight;
    }

    VulkanImage output() {
        return this.output;
    }

    void record(
            VkCommandBuffer commandBuffer,
            long requestedHdrView,
            long requestedBaselineView,
            long requestedUiView,
            boolean compositePrimeHdr,
            float scRgbScale) {
        this.requireOpen();
        if (requestedHdrView == 0L
                || requestedBaselineView == 0L
                || requestedUiView == 0L) {
            throw new IllegalArgumentException("HDR-present image views must be valid");
        }
        if (!Float.isFinite(scRgbScale) || scRgbScale <= 0.0F) {
            throw new IllegalArgumentException("HDR-present scRGB scale must be positive");
        }
        this.updateDescriptors(requestedHdrView, requestedBaselineView, requestedUiView);
        this.prepareImages(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            push.putInt(8, compositePrimeHdr ? 1 : 0);
            push.putFloat(12, scRgbScale);
            this.program.dispatch(
                    commandBuffer,
                    stack,
                    this.descriptorSet,
                    push,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE));
            VulkanSync.imageBarrier(commandBuffer, this.output.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
        }
    }

    private void updateDescriptors(long requestedHdr, long requestedBaseline, long requestedUi) {
        if (this.hdrView == requestedHdr
                && this.baselineView == requestedBaseline
                && this.uiView == requestedUi) {
            return;
        }
        // These sets intentionally omit update-after-bind. Resource identities are stable during
        // ordinary frames; an equal-size target rebuild must retire every prior use before the
        // descriptor is rewritten.
        if (this.hdrView != 0L) {
            this.context.awaitIdle();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(4, stack);
            images.get(0).imageView(requestedHdr).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            images.get(1).imageView(requestedBaseline).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            images.get(2).imageView(requestedUi).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            images.get(3).imageView(this.output.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int binding = 0; binding < 4; binding++) {
                int type = binding == 3
                        ? VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                        : VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                VulkanDescriptors.writeImage(
                        writes.get(binding), this.descriptorSet, binding,
                        type, images.get(binding));
            }
            VK12.vkUpdateDescriptorSets(this.context.vkDevice(), writes, null);
        }
        this.hdrView = requestedHdr;
        this.baselineView = requestedBaseline;
        this.uiView = requestedUi;
    }

    private void prepareImages(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
            VkImageMemoryBarrier2.Buffer image = VkImageMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(this.outputInitialized
                            ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                            : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(this.outputInitialized
                            ? VK12.VK_ACCESS_TRANSFER_READ_BIT
                            : 0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(this.outputInitialized
                            ? VK12.VK_IMAGE_LAYOUT_GENERAL
                            : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(this.output.image());
            image.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pMemoryBarriers(memory)
                            .pImageMemoryBarriers(image));
        }
        this.outputInitialized = true;
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("HDR-present pass is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        this.program.release();
        this.output.destroy();
        this.destroyed = true;
    }
}
