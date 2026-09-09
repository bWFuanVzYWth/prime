// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Extracts Minecraft's post-UI alpha into a Streamline UI_ALPHA image. */
public final class UiAlphaCapturePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = 8;

    private final SharedComputeProgram clearProgram;
    private final SharedComputeProgram extractProgram;
    private final VulkanImage alpha;
    private final BoundSet clearDescriptors;
    private final BoundSet extractDescriptors;
    private final int width;
    private final int height;
    private final long sourceImage;
    private final long sourceView;
    private boolean alphaInitialized;
    private boolean destroyed;

    private UiAlphaCapturePass(
            SharedComputeProgram clearProgram,
            SharedComputeProgram extractProgram,
            VulkanImage alpha,
            BoundSet clearDescriptors,
            BoundSet extractDescriptors,
            int width,
            int height,
            long sourceImage,
            long sourceView) {
        this.clearProgram = clearProgram;
        this.extractProgram = extractProgram;
        this.alpha = alpha;
        this.clearDescriptors = clearDescriptors;
        this.extractDescriptors = extractDescriptors;
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
        BoundSet clearDescriptors = null;
        BoundSet extractDescriptors = null;
        try {
            clearProgram = context.acquireSharedProgram(
                    VulkanSharedPrograms.Program.UI_ALPHA_CLEAR);
            extractProgram = context.acquireSharedProgram(
                    VulkanSharedPrograms.Program.UI_ALPHA_EXTRACT);
            alpha = context.createImage2D(
                    width,
                    height,
                    VK12.VK_FORMAT_R8_UNORM,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime UI alpha");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                clearDescriptors = VulkanDescriptors.bind(
                        context, stack, clearProgram.descriptorSetLayout(), "UI alpha clear",
                        VulkanDescriptors.image(
                                0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                sourceView, VK12.VK_IMAGE_LAYOUT_GENERAL));
                extractDescriptors = VulkanDescriptors.bind(
                        context, stack, extractProgram.descriptorSetLayout(), "UI alpha extraction",
                        VulkanDescriptors.image(
                                0, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                sourceView, VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                alpha.view(), VK12.VK_IMAGE_LAYOUT_GENERAL));
                return new UiAlphaCapturePass(
                        clearProgram,
                        extractProgram,
                        alpha,
                        clearDescriptors,
                        extractDescriptors,
                        width,
                        height,
                        sourceImage,
                        sourceView);
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(extractDescriptors, exception);
            ResourceCleanup.destroy(clearDescriptors, exception);
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
        record(commandBuffer, this.clearProgram, this.clearDescriptors.handle());
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
        record(commandBuffer, this.extractProgram, this.extractDescriptors.handle());
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
            program.dispatch(
                    commandBuffer,
                    stack,
                    descriptorSet,
                    push,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE));
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
        this.extractDescriptors.destroy();
        this.clearDescriptors.destroy();
        this.clearProgram.release();
        this.extractProgram.release();
        this.alpha.destroy();
    }
}
