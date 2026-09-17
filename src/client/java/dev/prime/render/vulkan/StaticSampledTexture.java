// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/** Immutable sampled texture whose staging buffers retire after its first committed upload. */
final class StaticSampledTexture implements Destroyable {
    private static final int LUT_WIDTH = 44;
    private static final int LUT_HEIGHT = 32;
    private static final int LUT_DEPTH = 159;
    private static final int LUT_BYTES = LUT_WIDTH * LUT_HEIGHT * LUT_DEPTH * 4 * Short.BYTES;
    private static final int STARMAP_WIDTH = ShaderAbi.STARMAP_WIDTH;
    private static final int STARMAP_HEIGHT = ShaderAbi.STARMAP_HEIGHT;
    private static final int STARMAP_STRIPE_ROWS = ShaderAbi.STARMAP_STRIPE_ROWS;

    private final VulkanContext context;
    private final String label;
    private final VulkanImage image;
    private final long sampler;
    private final Copy[] copies;
    private VulkanBuffer[] uploads;
    private boolean pending;
    private boolean destroyed;

    private StaticSampledTexture(
            VulkanContext context,
            String label,
            VulkanImage image,
            long sampler,
            VulkanBuffer[] uploads,
            Copy[] copies) {
        this.context = context;
        this.label = label;
        this.image = image;
        this.sampler = sampler;
        this.uploads = uploads;
        this.copies = copies;
    }

    static StaticSampledTexture transmissionGgx(VulkanContext context) {
        VulkanImage image = null;
        VulkanBuffer upload = null;
        long sampler = 0L;
        try {
            image = context.createSampledImage3D(
                    LUT_WIDTH,
                    LUT_HEIGHT,
                    LUT_DEPTH,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "Prime transmission GGX energy");
            upload = createUpload(
                    context,
                    LUT_BYTES,
                    "Prime transmission GGX energy upload",
                    "/prime/bsdf/trans_ggx.bytes.gz.b64");
            sampler = createSampler(
                    context,
                    VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                    1,
                    "Prime transmission GGX energy sampler");
            return new StaticSampledTexture(
                    context,
                    "BSDF lookup",
                    image,
                    sampler,
                    new VulkanBuffer[] {upload},
                    new Copy[] {new Copy(LUT_WIDTH, LUT_HEIGHT, LUT_DEPTH, 0, 0)});
        } catch (RuntimeException exception) {
            destroy(context, sampler, upload, image, exception);
            throw exception;
        }
    }

    static StaticSampledTexture starmap(VulkanContext context) {
        VulkanImage image = null;
        int stripes = STARMAP_HEIGHT / STARMAP_STRIPE_ROWS;
        VulkanBuffer[] uploads = new VulkanBuffer[stripes + ShaderAbi.STARMAP_MIP_LEVELS - 1];
        Copy[] copies = new Copy[uploads.length];
        long sampler = 0L;
        try {
            image = context.createMipmappedImage2D(
                    STARMAP_WIDTH,
                    STARMAP_HEIGHT,
                    ShaderAbi.STARMAP_MIP_LEVELS,
                    VK12.VK_FORMAT_BC6H_UFLOAT_BLOCK,
                    VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "Prime NASA 2020 16K BC6H starmap");
            for (int index = 0; index < uploads.length; index++) {
                int level = Math.max(0, index - stripes + 1);
                int width = Math.max(1, STARMAP_WIDTH >> level);
                int height = level == 0 ? STARMAP_STRIPE_ROWS : Math.max(1, STARMAP_HEIGHT >> level);
                copies[index] = new Copy(width, height, 1, level, level == 0 ? index * height : 0);
                uploads[index] = createUpload(
                        context,
                        ((width + 3) / 4) * ((height + 3) / 4) * 16,
                        "Prime starmap upload " + index,
                        "/prime/starmap/starmap_2020_16k_"
                                + (level == 0 ? index : "mip" + level) + ".bc6h.gz");
            }
            sampler = createSampler(
                    context,
                    VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT,
                    ShaderAbi.STARMAP_MIP_LEVELS,
                    "Prime NASA 2020 starmap sampler");
            return new StaticSampledTexture(
                    context,
                    "Starmap",
                    image,
                    sampler,
                    uploads,
                    copies);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if (sampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), sampler, null);
            }
            failure = destroyUploads(uploads, failure);
            throw ResourceCleanup.destroy(image, failure);
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
            throw new IllegalStateException(this.label + " upload is already pending submission");
        }
        this.pending = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (initialization.prepare(this.image)) {
                throw new IllegalStateException(
                        this.label + " image is initialized without committed upload state");
            }
            VulkanSync.imageBarrier(
                    commandBuffer,
                    this.image.image(),
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    0L,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT, this.image.mipLevels());
            for (int index = 0; index < this.uploads.length; index++) {
                VulkanBuffer upload = this.uploads[index];
                Copy region = this.copies[index];
                upload.flush(0L, upload.size());
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                copy.get(0).imageSubresource()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(region.level())
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.get(0).imageOffset().set(0, region.y(), 0);
                copy.get(0).imageExtent().set(
                        region.width(), region.height(), region.depth());
                VK12.vkCmdCopyBufferToImage(
                        commandBuffer,
                        upload.handle(),
                        this.image.image(),
                        VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copy);
            }
            VulkanSync.imageBarrier(
                    commandBuffer,
                    this.image.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                            | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT, this.image.mipLevels());
            return true;
        } catch (RuntimeException exception) {
            this.pending = false;
            throw exception;
        }
    }

    void submitted() {
        if (!this.pending) {
            throw new IllegalStateException(this.label + " upload is not pending submission");
        }
        for (VulkanBuffer upload : this.uploads) {
            this.context.defer(upload);
        }
        this.uploads = null;
        this.pending = false;
    }

    void abandon() {
        if (!this.pending) {
            throw new IllegalStateException(this.label + " upload is not pending submission");
        }
        this.pending = false;
    }

    static VulkanBuffer createUpload(
            VulkanContext context,
            int byteSize,
            String label,
            String resource) {
        VulkanBuffer upload = context.createBuffer(
                byteSize,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                label);
        try {
            writeResource(upload, byteSize, resource);
            return upload;
        } catch (RuntimeException exception) {
            upload.destroy();
            throw exception;
        }
    }

    private static void writeResource(
            VulkanBuffer destination,
            int expectedBytes,
            String resource) {
        ByteBuffer target = MemoryUtil.memByteBuffer(
                destination.mappedAddress(), expectedBytes);
        byte[] chunk = new byte[64 * 1024];
        int total = 0;
        try (InputStream encoded = StaticSampledTexture.class.getResourceAsStream(resource)) {
            if (encoded == null) {
                throw new IllegalStateException("Missing static texture resource " + resource);
            }
            InputStream decoded = resource.endsWith(".b64")
                    ? Base64.getMimeDecoder().wrap(encoded)
                    : encoded;
            try (InputStream input = resource.endsWith(".gz") || resource.endsWith(".gz.b64")
                    ? new GZIPInputStream(decoded)
                    : decoded) {
                int count;
                while ((count = input.read(chunk)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    if (count > target.remaining()) {
                        throw new IllegalStateException(
                                "Static resource exceeds its declared size: " + resource);
                    }
                    target.put(chunk, 0, count);
                    total += count;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read static resource " + resource, exception);
        }
        if (total != expectedBytes) {
            throw new IllegalStateException(
                    "Unexpected static resource size " + total + " for " + resource
                            + ", expected " + expectedBytes);
        }
    }

    private static long createSampler(VulkanContext context, int addressModeU, int mipLevels, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK12.VK_FILTER_LINEAR)
                    .minFilter(VK12.VK_FILTER_LINEAR)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(addressModeU)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0F)
                    .maxLod(mipLevels - 1.0F)
                    .maxAnisotropy(1.0F);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                    "create " + label);
            long sampler = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(), VK12.VK_OBJECT_TYPE_SAMPLER, sampler, label);
            return sampler;
        }
    }

    private record Copy(int width, int height, int depth, int level, int y) {}

    private static void destroy(
            VulkanContext context,
            long sampler,
            VulkanBuffer upload,
            VulkanImage image,
            RuntimeException failure) {
        if (sampler != 0L) {
            VK12.vkDestroySampler(context.vkDevice(), sampler, null);
        }
        ResourceCleanup.destroy(upload, failure);
        ResourceCleanup.destroy(image, failure);
    }

    private static RuntimeException destroyUploads(
            VulkanBuffer[] uploads, RuntimeException failure) {
        for (int index = uploads.length - 1; index >= 0; index--) {
            failure = ResourceCleanup.destroy(uploads[index], failure);
        }
        return failure;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        RuntimeException failure = this.uploads == null
                ? null
                : destroyUploads(this.uploads, null);
        this.uploads = null;
        VK12.vkDestroySampler(this.context.vkDevice(), this.sampler, null);
        failure = ResourceCleanup.destroy(this.image, failure);
        ResourceCleanup.throwIfFailed(failure);
    }
}
