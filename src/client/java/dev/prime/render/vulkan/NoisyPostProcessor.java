package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Native-resolution 1 spp presentation path with no denoising or temporal filtering.
 *
 * <p>The shared history state controls only jitter identity and reset/submit semantics.
 */
public final class NoisyPostProcessor implements VulkanReconstructionProcessor {
    private final ReconstructionQualityMode quality;
    private final int width;
    private final int height;
    private final BasicRawWavefrontFrame rawFrame;
    private final NoisyCompositePass composite;
    private final DisplayTransformPass displayTransform;
    private final VulkanContext context;
    private final VulkanImage stableRadiance;
    private final VulkanImage displayOutput;
    private RendererImageDebugPass rendererDebugPass;
    private boolean destroyed;

    private NoisyPostProcessor(
            VulkanContext context,
            ReconstructionQualityMode quality,
            int width,
            int height,
            BasicRawWavefrontFrame rawFrame,
            NoisyCompositePass composite,
            DisplayTransformPass displayTransform,
            VulkanImage stableRadiance,
            VulkanImage displayOutput) {
        this.context = context;
        this.quality = quality;
        this.width = width;
        this.height = height;
        this.rawFrame = rawFrame;
        this.composite = composite;
        this.displayTransform = displayTransform;
        this.stableRadiance = stableRadiance;
        this.displayOutput = displayOutput;
    }

    public static NoisyPostProcessor create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage stableRadiance,
            VulkanImage displayOutput,
            int width,
            int height,
            ReconstructionQualityMode quality) {
        BasicRawWavefrontFrame rawFrame = null;
        NoisyCompositePass composite = null;
        DisplayTransformPass displayTransform = null;
        try {
            rawFrame = BasicRawWavefrontFrame.createRealtime(context, width, height);
            composite = NoisyCompositePass.create(
                    context, rawFrame, stableRadiance, atmosphere);
            displayTransform = DisplayTransformPass.createRealtime(
                    context, rawFrame.linearOutput(), rawFrame, displayOutput);
            return new NoisyPostProcessor(
                    context,
                    quality,
                    width,
                    height,
                    rawFrame,
                    composite,
                    displayTransform,
                    stableRadiance,
                    displayOutput);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(displayTransform, exception);
            ResourceCleanup.destroy(composite, exception);
            ResourceCleanup.destroy(rawFrame, exception);
            throw exception;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.DISABLED; }
    @Override public ReconstructionQualityMode quality() { return this.quality; }
    @Override public int renderWidth() { return this.width; }
    @Override public int renderHeight() { return this.height; }
    @Override public int displayWidth() { return this.width; }
    @Override public int displayHeight() { return this.height; }
    @Override public RawWavefrontFrame rawFrame() { return this.rawFrame; }
    @Override public VulkanImage linearHdrOutput() { return this.rawFrame.linearOutput(); }
    @Override public VulkanImage hdrDisplayOutput() { return this.displayTransform.hdrOutput(); }
    @Override public long displayExposureStateBuffer() {
        return this.displayTransform.exposureState().handle();
    }

    @Override
    public Frame beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings) {
        requireOpen();
        return new FrameToken(this, new SubmittedFrame<>(parameters));
    }

    @Override
    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        this.rawFrame.prepareForRayTrace(commandBuffer, initialization);
    }

    @Override
    public void captureRendererDiagnostic(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RendererImageView view) {
        if (view.active() && view != RendererImageView.DENOISED_OUTPUT) {
            this.rendererDebugPass().capture(commandBuffer, initialization, view);
        }
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization) {
        FrameToken token = requireFrame(frame);
        ReconstructionFrameParameters parameters = token.parameters.claimForExecution();
        this.composite.record(
                commandBuffer,
                parameters.camera(),
                parameters.sunDirection(),
                parameters.sunRadianceMultiplier());
        this.displayTransform.record(
                commandBuffer,
                parameters.deltaMilliseconds() * 0.001F,
                parameters.reset(),
                false,
                parameters.display(),
                initialization);
    }

    @Override
    public void presentRendererDiagnostic(
            VkCommandBuffer commandBuffer, RendererImageView view) {
        if (view.active()) this.rendererDebugPass().present(commandBuffer, view);
    }

    private RendererImageDebugPass rendererDebugPass() {
        if (this.rendererDebugPass == null) {
            this.rendererDebugPass = RendererImageDebugPass.create(
                    this.context,
                    this.rawFrame,
                    this.stableRadiance,
                    this.rawFrame.linearOutput(),
                    this.displayOutput,
                    this.displayTransform.hdrOutput());
        }
        return this.rendererDebugPass;
    }

    @Override
    public void submitted(Frame frame) {
        requireOpen();
        if (!(frame instanceof FrameToken token)
                || token.owner != this) {
            throw new IllegalArgumentException(
                    "Noisy frame was not recorded exactly once by this processor");
        }
        token.parameters.submitted();
    }

    @Override
    public void abandon(Frame frame) {
        requireOpen();
        if (!(frame instanceof FrameToken token)
                || token.owner != this) {
            throw new IllegalArgumentException(
                    "Noisy frame token does not belong to this processor");
        }
        token.parameters.abandon();
    }

    private FrameToken requireFrame(Frame frame) {
        requireOpen();
        if (!(frame instanceof FrameToken token)
                || token.owner != this) {
            throw new IllegalArgumentException("Noisy frame token does not belong to this processor");
        }
        return token;
    }

    private void requireOpen() {
        if (this.destroyed) throw new IllegalStateException("Noisy post-processor is destroyed");
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.rendererDebugPass, failure);
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.composite, failure);
        failure = ResourceCleanup.destroy(this.rawFrame, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    private static final class FrameToken implements Frame {
        private final NoisyPostProcessor owner;
        private final SubmittedFrame<ReconstructionFrameParameters> parameters;

        private FrameToken(
                NoisyPostProcessor owner,
                SubmittedFrame<ReconstructionFrameParameters> parameters) {
            this.owner = owner;
            this.parameters = parameters;
        }
    }
}
