// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Device-local full-frame luminance histogram and temporal exposure state.
 *
 * <p>The owning display pass records every state transition on the render queue. No CPU readback,
 * cross-thread mutation or lock participates in exposure adaptation.
 */
final class AutoExposurePass implements Destroyable {
    private static final int PUSH_SIZE = 16;
    private static final int HISTOGRAM_BIN_COUNT = 256;
    private static final int HISTOGRAM_SIZE =
            (HISTOGRAM_BIN_COUNT + 1) * Integer.BYTES;
    static final int EXPOSURE_STATE_SIZE = 16;
    private static final int HISTOGRAM_TILE_SIZE = 64;

    private final SharedComputeProgram program;
    private final VulkanBuffer histogram;
    private final VulkanBuffer exposureState;
    private final BoundSet descriptors;
    private final int dispatchX;
    private final int dispatchY;
    private final boolean accumulatedMetering;
    private boolean destroyed;

    private AutoExposurePass(
            SharedComputeProgram program,
            VulkanBuffer histogram,
            VulkanBuffer exposureState,
            BoundSet descriptors,
            int width,
            int height,
            boolean accumulatedMetering) {
        this.program = program;
        this.histogram = histogram;
        this.exposureState = exposureState;
        this.descriptors = descriptors;
        this.dispatchX = DispatchMath.divideRoundUp(width, HISTOGRAM_TILE_SIZE);
        this.dispatchY = DispatchMath.divideRoundUp(height, HISTOGRAM_TILE_SIZE);
        this.accumulatedMetering = accumulatedMetering;
    }

    static AutoExposurePass create(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanImage albedo,
            VulkanImage reconstructionControl,
            boolean accumulatedMetering) {
        SharedComputeProgram program = context.acquireSharedProgram(
                VulkanSharedPrograms.Program.AUTO_EXPOSURE);
        VulkanBuffer histogram = null;
        VulkanBuffer exposureState = null;
        BoundSet descriptors = null;
        try {
            histogram = context.createBuffer(
                    HISTOGRAM_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime auto-exposure histogram");
            exposureState = context.createBuffer(
                    EXPOSURE_STATE_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    false,
                    "Prime auto-exposure state");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                descriptors = VulkanDescriptors.bind(
                        context,
                        stack,
                        program.descriptorSetLayout(),
                        "auto-exposure",
                        VulkanDescriptors.image(
                                0, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                linearInput.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                albedo.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                reconstructionControl.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.buffer(
                                3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                histogram.handle(), 0L, HISTOGRAM_SIZE),
                        VulkanDescriptors.buffer(
                                4, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                exposureState.handle(), 0L, EXPOSURE_STATE_SIZE));
                return new AutoExposurePass(
                        program,
                        histogram,
                        exposureState,
                        descriptors,
                        linearInput.width(),
                        linearInput.height(),
                        accumulatedMetering);
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(descriptors, exception);
            ResourceCleanup.destroy(exposureState, exception);
            ResourceCleanup.destroy(histogram, exception);
            program.release();
            throw exception;
        }
    }

    VulkanBuffer exposureState() {
        return this.exposureState;
    }

    void record(
            VkCommandBuffer commandBuffer,
            int width,
            int height,
            float deltaSeconds,
            boolean reset,
            boolean instant,
            float compensation) {
        if (this.destroyed) {
            throw new IllegalStateException("Auto-exposure pass is destroyed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Auto-exposure extent must be positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0F) {
            throw new IllegalArgumentException(
                    "Auto-exposure frame delta must be finite and non-negative");
        }
        if (!Float.isFinite(compensation) || compensation < 0.0F || compensation > 1.0F) {
            throw new IllegalArgumentException(
                    "Auto-exposure compensation must be finite and between zero and one");
        }
        VK12.vkCmdFillBuffer(
                commandBuffer,
                this.histogram.handle(),
                0L,
                HISTOGRAM_SIZE,
                0);
        writesToCompute(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.program.bindDescriptors(
                    commandBuffer, stack, this.descriptors.handle());
            ByteBuffer histogramPush =
                    stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            histogramPush.putInt(0, width);
            histogramPush.putInt(4, height);
            histogramPush.putInt(8, this.accumulatedMetering ? 1 : 0);
            histogramPush.putInt(12, 0);
            this.program.dispatchBound(
                    commandBuffer, 0, histogramPush, this.dispatchX, this.dispatchY);

            computeBarrier(commandBuffer, this.histogram);
            ByteBuffer updatePush =
                    stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            updatePush.putFloat(0, deltaSeconds);
            updatePush.putInt(4, reset ? 1 : 0);
            updatePush.putInt(8, instant ? 1 : 0);
            updatePush.putFloat(12, compensation);
            this.program.dispatchBound(
                    commandBuffer, 1, updatePush, 1, 1);
        }
        computeBarrier(commandBuffer, this.exposureState);
    }

    private static void writesToCompute(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void computeBarrier(
            VkCommandBuffer commandBuffer, VulkanBuffer buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSync.bufferBarrier(
                    commandBuffer,
                    stack,
                    buffer,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.descriptors.destroy();
        this.exposureState.destroy();
        this.histogram.destroy();
        this.program.release();
        this.destroyed = true;
    }
}
