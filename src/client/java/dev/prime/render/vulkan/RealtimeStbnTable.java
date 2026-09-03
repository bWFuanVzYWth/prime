package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Owns the immutable integer STBN banks used only by realtime direct-light sampling. */
final class RealtimeStbnTable implements Destroyable {
    static final String RESOURCE = "/prime/stbn/realtime_128x128x64x3.rg16ui";
    static final int BYTE_SIZE = ShaderAbi.REALTIME_STBN_WIDTH
            * ShaderAbi.REALTIME_STBN_HEIGHT
            * ShaderAbi.REALTIME_STBN_DEPTH
            * ShaderAbi.REALTIME_STBN_BANK_COUNT
            * ShaderAbi.REALTIME_STBN_CHANNELS
            * ShaderAbi.REALTIME_STBN_CHANNEL_BITS / Byte.SIZE;

    private final VulkanContext context;
    private final VulkanBuffer table;
    private VulkanBuffer upload;
    private boolean pending;
    private boolean destroyed;

    RealtimeStbnTable(VulkanContext context) {
        this.context = context;
        VulkanBuffer newTable = null;
        VulkanBuffer newUpload = null;
        try {
            newTable = context.createBuffer(
                    BYTE_SIZE,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    false,
                    "Prime realtime STBN");
            newUpload = StaticSampledTexture.createUpload(
                    context,
                    BYTE_SIZE,
                    "Prime realtime STBN upload",
                    RESOURCE);
            this.table = newTable;
            this.upload = newUpload;
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(newUpload, exception);
            throw ResourceCleanup.destroy(newTable, failure);
        }
    }

    VulkanBuffer buffer() {
        return this.table;
    }

    boolean prepare(VkCommandBuffer commandBuffer) {
        if (this.upload == null) {
            return false;
        }
        if (this.pending) {
            throw new IllegalStateException("Realtime STBN upload is already pending submission");
        }
        this.pending = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.upload.flush(0L, BYTE_SIZE);
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0L)
                    .dstOffset(0L)
                    .size(BYTE_SIZE);
            VK12.vkCmdCopyBuffer(
                    commandBuffer, this.upload.handle(), this.table.handle(), copy);
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            return true;
        } catch (RuntimeException exception) {
            this.pending = false;
            throw exception;
        }
    }

    void submitted() {
        if (!this.pending) {
            throw new IllegalStateException("Realtime STBN upload is not pending submission");
        }
        this.context.defer(this.upload);
        this.upload = null;
        this.pending = false;
    }

    void abandon() {
        if (!this.pending) {
            throw new IllegalStateException("Realtime STBN upload is not pending submission");
        }
        this.pending = false;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.upload != null) {
                this.upload.destroy();
                this.upload = null;
            }
            this.table.destroy();
        }
    }
}
