package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.fsr.Fsr3Upscaler;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import dev.prime.render.post.nrd.NrdFramePlan;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Existing REBLUR/SIGMA + FidelityFX FSR 3.1.4 implementation of the shared boundary. */
public final class NrdFsrPostProcessor extends VulkanReconstructionProcessor {
    private final VulkanImage sceneColor;
    private final NrdDenoiser denoiser;
    private final Fsr3Upscaler upscaler;
    private NrdInputDebugPass nrdDebugPresent;

    private NrdFsrPostProcessor(
            VulkanContext context,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            VulkanImage sceneColor,
            NrdDenoiser denoiser,
            Fsr3Upscaler upscaler,
            VulkanImage displayOutput,
            VulkanImage stableRadiance) {
        super(
                context,
                PostProcessingMode.NRD_FSR,
                quality,
                renderWidth,
                renderHeight,
                displayWidth,
                displayHeight,
                stableRadiance,
                displayOutput);
        this.sceneColor = sceneColor;
        this.denoiser = denoiser;
        this.upscaler = upscaler;
    }

    public static NrdFsrPostProcessor create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage accumulation,
            VulkanImage displayOutput,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode quality) {
        VulkanImage sceneColor = null;
        NrdDenoiser denoiser = null;
        Fsr3Upscaler upscaler = null;
        try {
            sceneColor = context.createImage2D(
                    renderWidth,
                    renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime NRD-FSR linear HDR scene color");
            denoiser = NrdDenoiser.create(
                    context, renderWidth, renderHeight, sceneColor, accumulation, atmosphere);
            upscaler = Fsr3Upscaler.create(
                    context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sceneColor,
                    denoiser.fsrMotion(),
                    denoiser.fsrDepth(),
                    denoiser.fsrReactiveMask(),
                    denoiser.fsrTransparencyCompositionMask(),
                    denoiser.rawFrame(),
                    displayOutput);
            return new NrdFsrPostProcessor(
                    context,
                    quality,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sceneColor,
                    denoiser,
                    upscaler,
                    displayOutput,
                    accumulation);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(upscaler, exception);
            ResourceCleanup.destroy(denoiser, exception);
            ResourceCleanup.destroy(sceneColor, exception);
            throw exception;
        }
    }

    @Override public RawWavefrontFrame rawFrame() { return this.denoiser.rawFrame(); }
    @Override public VulkanImage linearHdrOutput() { return this.upscaler.linearOutput(); }
    @Override public VulkanImage hdrDisplayOutput() { return this.upscaler.hdrDisplayOutput(); }
    @Override public long displayExposureStateBuffer() {
        return this.upscaler.displayExposureStateBuffer();
    }

    @Override
    public FrameToken beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings) {
        requireOpen();
        Fsr3Upscaler.FrameToken fsr = this.upscaler.beginFrame(parameters);
        NrdFramePlan nrd = NrdFramePlan.from(parameters, quality());
        return new FrameToken(this, fsr, nrd, debugSettings);
    }

    @Override
    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        VulkanSync.prepareImage(commandBuffer, initialization, this.sceneColor,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        this.denoiser.prepareForRayTrace(commandBuffer, initialization);
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization) {
        FrameToken token = requireFrame(frame);
        if (token.recorded) {
            throw new IllegalArgumentException("NRD-FSR frame was already recorded");
        }
        token.recorded = true;
        // Recording can fail after emitting commands; such a token must never be retried into the
        // same or another command buffer.
        token.nrdPrepared =
                this.denoiser.prepareInputs(commandBuffer, token.nrdPlan);
        token.nrd = this.denoiser.recordReconstruction(
                commandBuffer,
                token.nrdPrepared,
                token.fsr.parameters().sunRadianceMultiplier());
        this.upscaler.record(
                commandBuffer,
                token.fsr,
                initialization);
        NrdInputView diagnostic = token.debugSettings.images().nrd();
        if (diagnostic.active()) {
            this.nrdDebugPresent(token.nrdPrepared.inputs()).record(commandBuffer, diagnostic);
        }
    }

    @Override
    public void submitted(Frame frame) {
        requireOpen();
        FrameToken token = requireFrame(frame);
        if (token.nrd == null) {
            throw new IllegalArgumentException("NRD-FSR frame was not recorded exactly once");
        }
        RuntimeException failure = null;
        try {
            this.denoiser.submitted(token.nrd);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        failure = ResourceCleanup.run(
                () -> this.upscaler.submitted(token.fsr), failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    @Override
    public void abandon(Frame frame) {
        requireOpen();
        FrameToken token = requireFrame(frame);
        RuntimeException failure = null;
        if (token.nrd != null) {
            failure = ResourceCleanup.run(
                    () -> this.denoiser.abandon(token.nrd), failure);
        }
        failure = ResourceCleanup.run(
                () -> this.upscaler.abandon(token.fsr), failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private FrameToken requireFrame(Frame frame) {
        if (!(frame instanceof FrameToken token) || token.owner != this) {
            throw new IllegalArgumentException("NRD-FSR frame token does not belong to this processor");
        }
        return token;
    }

    private NrdInputDebugPass nrdDebugPresent(
            dev.prime.render.vulkan.nrd.PreparedNrdFrame prepared) {
        if (this.nrdDebugPresent == null) {
            this.nrdDebugPresent = NrdInputDebugPass.create(
                    context(),
                    this.sceneColor,
                    prepared,
                    displayOutput(),
                    this.upscaler.hdrDisplayOutput());
        }
        return this.nrdDebugPresent;
    }

    @Override
    public void destroy() {
        if (destroyed()) return;
        RuntimeException failure = null;
        failure = destroyRendererDiagnostic(failure);
        failure = ResourceCleanup.destroy(this.nrdDebugPresent, failure);
        failure = ResourceCleanup.destroy(this.upscaler, failure);
        failure = ResourceCleanup.destroy(this.denoiser, failure);
        failure = ResourceCleanup.destroy(this.sceneColor, failure);
        finishDestroy(failure);
    }

    public static final class FrameToken implements Frame {
        private final NrdFsrPostProcessor owner;
        private final Fsr3Upscaler.FrameToken fsr;
        private final NrdFramePlan nrdPlan;
        private final ReconstructionDebugSettings debugSettings;
        private boolean recorded;
        private NrdDenoiser.PreparedFrame nrdPrepared;
        private NrdDenoiser.FrameToken nrd;

        private FrameToken(
                NrdFsrPostProcessor owner,
                Fsr3Upscaler.FrameToken fsr,
                NrdFramePlan nrdPlan,
                ReconstructionDebugSettings debugSettings) {
            this.owner = owner;
            this.fsr = fsr;
            this.nrdPlan = nrdPlan;
            this.debugSettings = debugSettings;
        }
    }
}
