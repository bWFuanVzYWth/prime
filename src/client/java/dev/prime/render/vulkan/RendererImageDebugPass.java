package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.RendererImageView;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/** Captures renderer-owned images before reconstruction aliases them, using one lazy atlas. */
public final class RendererImageDebugPass implements Destroyable {
    private static final int NOISY_DIFFUSE = 0;
    private static final int NOISY_SPECULAR = 1;
    private static final int STABLE_RADIANCE = 2;
    private static final int SUN_LIGHTING = 3;
    private static final int SUN_PENUMBRA = 4;
    private static final int NORMAL_ROUGHNESS = 5;
    private static final int DIFFUSE_ALBEDO = 6;
    private static final int SPECULAR_ALBEDO = 7;
    private static final int PRIMARY_POSITION = 8;

    private final VulkanImage scratch;
    private final ImageDiagnosticPass capture;
    private final ImageDiagnosticPass sdr;
    private final ImageDiagnosticPass hdr;

    private RendererImageDebugPass(
            VulkanImage scratch,
            ImageDiagnosticPass capture,
            ImageDiagnosticPass sdr,
            ImageDiagnosticPass hdr) {
        this.scratch = scratch;
        this.capture = capture;
        this.sdr = sdr;
        this.hdr = hdr;
    }

    public static RendererImageDebugPass create(
            VulkanContext context,
            RawWavefrontFrame raw,
            VulkanImage stableRadiance,
            VulkanImage denoisedOutput,
            VulkanImage displayOutput,
            VulkanImage hdrOutput) {
        VulkanImage scratch = null;
        ImageDiagnosticPass capture = null;
        ImageDiagnosticPass sdr = null;
        ImageDiagnosticPass hdr = null;
        try {
            scratch = context.createImage2D(
                    displayOutput.width(),
                    displayOutput.height(),
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime renderer image-diagnostic atlas");
            capture = ImageDiagnosticPass.createHdr(
                    context,
                    scratch,
                    raw.noisyDiffuse(),
                    raw.noisySpecular(),
                    stableRadiance,
                    raw.sunLighting(),
                    raw.sunPenumbra(),
                    raw.normalRoughness(),
                    raw.material(),
                    raw.specularMaterial(),
                    raw.primaryPosition());
            sdr = ImageDiagnosticPass.createSdr(
                    context, displayOutput, scratch, denoisedOutput);
            hdr = ImageDiagnosticPass.createHdr(
                    context, hdrOutput, scratch, denoisedOutput);
            return new RendererImageDebugPass(scratch, capture, sdr, hdr);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(hdr, exception);
            ResourceCleanup.destroy(sdr, exception);
            ResourceCleanup.destroy(capture, exception);
            ResourceCleanup.destroy(scratch, exception);
            throw exception;
        }
    }

    public void capture(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RendererImageView view) {
        if (!view.active() || view == RendererImageView.DENOISED_OUTPUT) return;
        prepareScratch(commandBuffer, initialization);
        if (view == RendererImageView.GRID) {
            this.capture.recordGrid(commandBuffer, 4, List.of(
                    descriptor(RendererImageView.NOISY_DIFFUSE),
                    descriptor(RendererImageView.NOISY_DIFFUSE),
                    descriptor(RendererImageView.NOISY_SPECULAR),
                    descriptor(RendererImageView.STABLE_RADIANCE),
                    descriptor(RendererImageView.SUN_LIGHTING),
                    descriptor(RendererImageView.SUN_VISIBILITY),
                    descriptor(RendererImageView.SUN_PENUMBRA),
                    descriptor(RendererImageView.NORMAL),
                    descriptor(RendererImageView.ROUGHNESS),
                    descriptor(RendererImageView.DIFFUSE_ALBEDO),
                    descriptor(RendererImageView.SPECULAR_ALBEDO),
                    descriptor(RendererImageView.DIFFUSE_HIT_DISTANCE),
                    descriptor(RendererImageView.SPECULAR_HIT_DISTANCE),
                    descriptor(RendererImageView.PRIMARY_DEPTH)));
            return;
        }
        this.capture.recordFull(commandBuffer, descriptor(view));
    }

    public void present(VkCommandBuffer commandBuffer, RendererImageView view) {
        if (!view.active()) return;
        ImageDiagnosticPass.View denoised = new ImageDiagnosticPass.View(
                1, ImageDiagnosticPass.RADIANCE);
        if (view == RendererImageView.DENOISED_OUTPUT) {
            this.sdr.recordFull(commandBuffer, denoised);
            this.hdr.recordFull(commandBuffer, denoised);
            return;
        }
        ImageDiagnosticPass.View atlas = new ImageDiagnosticPass.View(
                0, ImageDiagnosticPass.RAW);
        this.sdr.recordFull(commandBuffer, atlas);
        this.hdr.recordFull(commandBuffer, atlas);
        if (view == RendererImageView.GRID) {
            this.sdr.recordCell(commandBuffer, 4, 0, denoised);
            this.hdr.recordCell(commandBuffer, 4, 0, denoised);
        }
    }

    private void prepareScratch(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean initialized = initialization.prepare(this.scratch);
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(initialized
                            ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(initialized
                            ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                            : 0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(initialized
                            ? VK12.VK_IMAGE_LAYOUT_GENERAL
                            : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(this.scratch.image());
            barrier.get(0).subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(barrier));
        }
    }

    private static ImageDiagnosticPass.View descriptor(RendererImageView view) {
        return switch (view) {
            case NOISY_DIFFUSE -> image(NOISY_DIFFUSE, ImageDiagnosticPass.RADIANCE);
            case NOISY_SPECULAR -> image(NOISY_SPECULAR, ImageDiagnosticPass.RADIANCE);
            case STABLE_RADIANCE -> image(STABLE_RADIANCE, ImageDiagnosticPass.RADIANCE);
            case SUN_LIGHTING -> image(SUN_LIGHTING, ImageDiagnosticPass.RADIANCE);
            case SUN_VISIBILITY -> image(SUN_LIGHTING, ImageDiagnosticPass.VISIBILITY_A);
            case SUN_PENUMBRA -> image(SUN_PENUMBRA, ImageDiagnosticPass.HIT_R);
            case NORMAL -> image(NORMAL_ROUGHNESS, ImageDiagnosticPass.NORMAL);
            case ROUGHNESS -> image(NORMAL_ROUGHNESS, ImageDiagnosticPass.ROUGHNESS);
            case DIFFUSE_ALBEDO -> image(DIFFUSE_ALBEDO, ImageDiagnosticPass.ALBEDO);
            case SPECULAR_ALBEDO -> image(SPECULAR_ALBEDO, ImageDiagnosticPass.ALBEDO);
            case DIFFUSE_HIT_DISTANCE -> image(NOISY_DIFFUSE, ImageDiagnosticPass.HIT_A);
            case SPECULAR_HIT_DISTANCE -> image(NOISY_SPECULAR, ImageDiagnosticPass.HIT_A);
            case PRIMARY_DEPTH -> image(PRIMARY_POSITION, ImageDiagnosticPass.HIT_A);
            case OFF, DENOISED_OUTPUT, GRID ->
                    throw new IllegalArgumentException("Renderer view has no raw image");
        };
    }

    private static ImageDiagnosticPass.View image(int source, int presentation) {
        return new ImageDiagnosticPass.View(source, presentation);
    }

    @Override
    public void destroy() {
        RuntimeException failure = ResourceCleanup.destroy(this.hdr, null);
        failure = ResourceCleanup.destroy(this.sdr, failure);
        failure = ResourceCleanup.destroy(this.capture, failure);
        failure = ResourceCleanup.destroy(this.scratch, failure);
        ResourceCleanup.throwIfFailed(failure);
    }
}
