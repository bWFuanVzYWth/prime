package dev.prime.render.vulkan.reconstruction;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Vulkan command, image, frame-token and lifetime boundary of a reconstruction backend. */
public interface VulkanReconstructionProcessor extends Destroyable {
    PostProcessingMode mode();

    ReconstructionQualityMode quality();

    int renderWidth();

    int renderHeight();

    int displayWidth();

    int displayHeight();

    RawWavefrontFrame rawFrame();

    VulkanImage linearHdrOutput();

    VulkanImage hdrDisplayOutput();

    long displayExposureStateBuffer();

    Frame beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings);

    void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization);

    void captureRendererDiagnostic(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RendererImageView view);

    void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization);

    void presentRendererDiagnostic(
            VkCommandBuffer commandBuffer, RendererImageView view);

    void abandon(Frame frame);

    void submitted(Frame frame);

    interface Frame { }
}
