package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.zip.GZIPInputStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Owns the immutable full-resolution night-sky texture. */
public final class StarmapTexture implements Destroyable {
    static final int WIDTH = 8192;
    static final int HEIGHT = 4096;
    static final int STRIPE_ROWS = 1024;
    private static final int STRIPE_BYTES =
            WIDTH * STRIPE_ROWS * 4 * Short.BYTES;
    private static final String RESOURCE_ROOT = "/prime/starmap/";

    private final VulkanContext context;
    private final VulkanImage image;
    private final long sampler;
    private VulkanBuffer[] uploads;
    private boolean pending;
    private boolean destroyed;

    public StarmapTexture(VulkanContext context) {
        this.context = context;
        VulkanImage newImage = null;
        VulkanBuffer[] newUploads = new VulkanBuffer[4];
        long newSampler = 0L;
        try {
            newImage = context.createImage2D(
                    WIDTH,
                    HEIGHT,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "Prime NASA 2020 starmap");
            for (int index = 0; index < 4; index++) {
                newUploads[index] = createUpload(
                        context,
                        STRIPE_BYTES,
                        "starmap stripe " + index,
                        "starmap_2020_8k_" + index + ".rgba16f.gz");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                        .sType$Default()
                        .magFilter(VK12.VK_FILTER_LINEAR)
                        .minFilter(VK12.VK_FILTER_LINEAR)
                        .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0.0F)
                        .maxLod(0.0F)
                        .maxAnisotropy(1.0F);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                        "create Prime starmap sampler");
                newSampler = pointer.get(0);
            }
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_SAMPLER,
                    newSampler,
                    "Prime NASA 2020 starmap sampler");
            this.image = newImage;
            this.uploads = newUploads;
            this.sampler = newSampler;
        } catch (RuntimeException exception) {
            if (newSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), newSampler, null);
            }
            for (int index = newUploads.length - 1; index >= 0; index--) {
                ResourceCleanup.destroy(newUploads[index], exception);
            }
            ResourceCleanup.destroy(newImage, exception);
            throw exception;
        }
    }

    VulkanImage image() {
        return this.image;
    }

    long sampler() {
        return this.sampler;
    }

    boolean prepare(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        if (this.uploads == null) {
            return false;
        }
        if (this.pending) {
            throw new IllegalStateException(
                    "Starmap upload is already pending submission");
        }
        this.pending = true;
        VulkanBuffer[] pending = this.uploads;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (initialization.prepare(this.image)) {
                throw new IllegalStateException(
                        "Starmap image is initialized without committed upload state");
            }
            for (VulkanBuffer upload : pending) {
                upload.flush(0L, upload.size());
            }
            VulkanImageTransitions.imageBarrier(
                    commandBuffer,
                    this.image.image(),
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    0L,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);

            for (int index = 0; index < 4; index++) {
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                copy.get(0).imageSubresource()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.get(0).imageOffset().set(0, index * STRIPE_ROWS, 0);
                copy.get(0).imageExtent().set(WIDTH, STRIPE_ROWS, 1);
                VK12.vkCmdCopyBufferToImage(
                        commandBuffer,
                        pending[index].handle(),
                        this.image.image(),
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copy);
            }

            VulkanImageTransitions.imageBarrier(
                    commandBuffer,
                    this.image.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
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
            throw new IllegalStateException(
                    "Starmap upload is not pending submission");
        }
        VulkanBuffer[] pending = this.uploads;
        for (VulkanBuffer upload : pending) {
            this.context.defer(upload);
        }
        this.uploads = null;
        this.pending = false;
    }

    void abandon() {
        if (!this.pending) {
            throw new IllegalStateException(
                    "Starmap upload is not pending submission");
        }
        this.pending = false;
    }

    private static VulkanBuffer createUpload(
            VulkanContext context,
            int byteSize,
            String label,
            String resourceName) {
        VulkanBuffer buffer = context.createBuffer(
                byteSize,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                "Prime " + label + " upload");
        try {
            writeResource(buffer, byteSize, resourceName);
            return buffer;
        } catch (RuntimeException exception) {
            buffer.destroy();
            throw exception;
        }
    }

    private static void writeResource(
            VulkanBuffer destination,
            int expectedBytes,
            String resourceName) {
        ByteBuffer target = MemoryUtil.memByteBuffer(
                destination.mappedAddress(), expectedBytes);
        byte[] chunk = new byte[64 * 1024];
        int total = 0;
        try (InputStream encoded = StarmapTexture.class.getResourceAsStream(
                        RESOURCE_ROOT + resourceName)) {
            if (encoded == null) {
                throw new IllegalStateException("Missing starmap resource " + resourceName);
            }
            try (GZIPInputStream decompressed = new GZIPInputStream(encoded)) {
                int count;
                while ((count = decompressed.read(chunk)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    if (count > target.remaining()) {
                        throw new IllegalStateException(
                                "Starmap resource exceeds its declared size: " + resourceName);
                    }
                    target.put(chunk, 0, count);
                    total += count;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read starmap resource " + resourceName, exception);
        }
        if (total != expectedBytes) {
            throw new IllegalStateException(
                    "Unexpected starmap resource size "
                            + total
                            + " for "
                            + resourceName
                            + ", expected "
                            + expectedBytes);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VulkanBuffer[] pending = this.uploads;
            if (pending != null) {
                for (int index = pending.length - 1; index >= 0; index--) {
                    pending[index].destroy();
                }
                this.uploads = null;
            }
            VK12.vkDestroySampler(this.context.vkDevice(), this.sampler, null);
            this.image.destroy();
        }
    }
}
