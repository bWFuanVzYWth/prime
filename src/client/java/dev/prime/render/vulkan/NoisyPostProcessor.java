package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.ResolvedReconstruction;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Native-resolution 1 spp presentation path with no denoising or temporal filtering.
 *
 * <p>The shared history state controls only jitter identity and reset/submit semantics.
 */
public final class NoisyPostProcessor extends VulkanReconstructionProcessor {
    private final BasicRawWavefrontFrame rawFrame;
    private final NoisyCompositePass composite;
    private final DisplayTransformPass displayTransform;

    private NoisyPostProcessor(
            VulkanContext context,
            ResolvedReconstruction selection,
            BasicRawWavefrontFrame rawFrame,
            NoisyCompositePass composite,
            DisplayTransformPass displayTransform,
            VulkanImage stableRadiance,
            VulkanImage displayOutput) {
        super(
                context,
                selection,
                stableRadiance,
                displayOutput);
        this.rawFrame = rawFrame;
        this.composite = composite;
        this.displayTransform = displayTransform;
    }

    public static NoisyPostProcessor create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage stableRadiance,
            VulkanImage displayOutput,
            ResolvedReconstruction selection) {
        int width = selection.extent().width();
        int height = selection.extent().height();
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
                    selection,
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
        return newSubmittedFrame(parameters);
    }

    @Override
    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        this.rawFrame.prepareForRayTrace(commandBuffer, initialization);
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization) {
        ReconstructionFrameParameters parameters = claimSubmittedFrame(frame);
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
    public void destroy() {
        if (destroyed()) return;
        RuntimeException failure = null;
        failure = destroyRendererDiagnostic(failure);
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.composite, failure);
        failure = ResourceCleanup.destroy(this.rawFrame, failure);
        finishDestroy(failure);
    }
}
