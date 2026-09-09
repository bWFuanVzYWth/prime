// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Device-local exposure snapshot and diagnostic readback owned by one offline session. */
public final class FrozenExposureState implements Destroyable {
    private final VulkanContext context;
    private final VulkanBuffer buffer;
    private VulkanBuffer diagnosticReadback;
    private volatile DisplayExposureDiagnostics.Snapshot diagnosticSnapshot;
    private volatile boolean ready;
    private boolean destroyed;

    private FrozenExposureState(
            VulkanContext context,
            VulkanBuffer buffer,
            VulkanBuffer diagnosticReadback) {
        this.context = context;
        this.buffer = buffer;
        this.diagnosticReadback = diagnosticReadback;
    }

    public static FrozenExposureState capture(
            VulkanContext context, long sourceBuffer) {
        if (sourceBuffer == 0L) {
            throw new IllegalArgumentException("Exposure source buffer is null");
        }
        VulkanBuffer snapshot = context.createBuffer(
                AutoExposurePass.EXPOSURE_STATE_SIZE,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false,
                "Prime frozen offline exposure");
        VulkanBuffer readback;
        try {
            readback = context.createReadbackBuffer(
                    AutoExposurePass.EXPOSURE_STATE_SIZE,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    "Prime frozen exposure diagnostics");
        } catch (RuntimeException exception) {
            throw ResourceCleanup.destroy(snapshot, exception);
        }
        FrozenExposureState result =
                new FrozenExposureState(context, snapshot, readback);
        boolean submitted = false;
        try {
            var encoder = context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0L)
                        .dstOffset(0L)
                        .size(AutoExposurePass.EXPOSURE_STATE_SIZE);
                VK12.vkCmdCopyBuffer(
                        commandBuffer, sourceBuffer, snapshot.handle(), copy);
                VK12.vkCmdCopyBuffer(
                        commandBuffer, sourceBuffer, readback.handle(), copy);
            }
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end frozen exposure copy command buffer");
            encoder.execute(commandBuffer);
            submitted = true;
            context.afterSubmission(result::complete);
            return result;
        } catch (RuntimeException exception) {
            if (!submitted) {
                RuntimeException failure = ResourceCleanup.destroy(readback, exception);
                ResourceCleanup.destroy(snapshot, failure);
            } else {
                ResourceCleanup.destroy(result, exception);
            }
            throw exception;
        }
    }

    public boolean ready() {
        return this.ready;
    }

    public VulkanBuffer buffer() {
        if (this.destroyed) {
            throw new IllegalStateException("Frozen exposure state is destroyed");
        }
        if (!this.ready) {
            throw new IllegalStateException("Frozen exposure copy is still pending");
        }
        return this.buffer;
    }

    public DisplayExposureDiagnostics.Snapshot diagnosticSnapshot() {
        return this.diagnosticSnapshot;
    }

    private void complete() {
        if (this.destroyed) {
            return;
        }
        VulkanBuffer readback = this.diagnosticReadback;
        if (readback == null) {
            throw new IllegalStateException(
                    "Frozen exposure diagnostic readback is missing");
        }
        try {
            ByteBuffer state = ByteBuffer.wrap(
                            readback.read(0L, AutoExposurePass.EXPOSURE_STATE_SIZE))
                    .order(ByteOrder.nativeOrder());
            this.diagnosticSnapshot = new DisplayExposureDiagnostics.Snapshot(
                    state.getFloat(0),
                    state.getInt(4) != 0,
                    state.getFloat(8),
                    state.getFloat(12));
        } finally {
            this.diagnosticReadback = null;
            readback.destroy();
            this.ready = true;
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VulkanBuffer readback = this.diagnosticReadback;
            this.diagnosticReadback = null;
            if (this.ready) {
                this.buffer.destroy();
                if (readback != null) {
                    readback.destroy();
                }
            } else {
                // The copy lives in Minecraft's current Submission. Retire its destination on
                // that same queue timeline instead of invalidating the recorded command buffer.
                this.context.defer(this.buffer);
                if (readback != null) {
                    this.context.defer(readback);
                }
            }
        }
    }
}
