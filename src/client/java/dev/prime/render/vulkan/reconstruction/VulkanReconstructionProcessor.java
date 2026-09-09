// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.reconstruction;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.RendererImageDebugPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Vulkan command, image, frame-token and lifetime boundary of a reconstruction backend. */
public abstract class VulkanReconstructionProcessor implements Destroyable {
    private final VulkanContext context;
    private final ResolvedReconstruction selection;
    private final VulkanImage stableRadiance;
    private final VulkanImage displayOutput;
    private RendererImageDebugPass rendererDebugPass;
    private boolean destroyed;

    protected VulkanReconstructionProcessor(
            VulkanContext context,
            ResolvedReconstruction selection,
            VulkanImage stableRadiance,
            VulkanImage displayOutput) {
        this.context = context;
        this.selection = selection;
        this.stableRadiance = stableRadiance;
        this.displayOutput = displayOutput;
    }

    public final ResolvedReconstruction selection() { return this.selection; }
    public final PostProcessingMode mode() { return this.selection.effectiveMode(); }
    public final ReconstructionQualityMode quality() { return this.selection.quality(); }
    public final int renderWidth() { return this.selection.extent().width(); }
    public final int renderHeight() { return this.selection.extent().height(); }
    public final int displayWidth() { return this.selection.displayExtent().width(); }
    public final int displayHeight() { return this.selection.displayExtent().height(); }

    protected final VulkanContext context() { return this.context; }
    protected final VulkanImage displayOutput() { return this.displayOutput; }

    protected final Frame newSubmittedFrame(ReconstructionFrameParameters parameters) {
        return new SubmittedFrameToken(this, parameters);
    }

    protected final ReconstructionFrameParameters claimSubmittedFrame(Frame frame) {
        return requireSubmittedFrame(frame).parameters.claimForExecution();
    }

    protected final void submittedFrame(Frame frame) {
        requireSubmittedFrame(frame).parameters.submitted();
    }

    protected final void abandonSubmittedFrame(Frame frame) {
        requireSubmittedFrame(frame).parameters.abandon();
    }

    private SubmittedFrameToken requireSubmittedFrame(Frame frame) {
        requireOpen();
        if (!(frame instanceof SubmittedFrameToken token) || token.owner != this) {
            throw new IllegalArgumentException(
                    "Frame token does not belong to this reconstruction processor");
        }
        return token;
    }

    public abstract RawWavefrontFrame rawFrame();

    public abstract VulkanImage linearHdrOutput();

    public abstract VulkanImage hdrDisplayOutput();

    public abstract long displayExposureStateBuffer();

    public abstract Frame beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings);

    public abstract void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization);

    public final void captureRendererDiagnostic(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization,
            RendererImageView view) {
        if (view.active() && view != RendererImageView.DENOISED_OUTPUT) {
            rendererDebugPass().capture(commandBuffer, initialization, view);
        }
    }

    public abstract void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            VulkanImageInitializationBatch initialization);

    public final void presentRendererDiagnostic(
            VkCommandBuffer commandBuffer, RendererImageView view) {
        if (view.active()) rendererDebugPass().present(commandBuffer, view);
    }

    public void abandon(Frame frame) {
        abandonSubmittedFrame(frame);
    }

    public void submitted(Frame frame) {
        submittedFrame(frame);
    }

    private RendererImageDebugPass rendererDebugPass() {
        if (this.rendererDebugPass == null) {
            this.rendererDebugPass = RendererImageDebugPass.create(
                    this.context,
                    rawFrame(),
                    this.stableRadiance,
                    linearHdrOutput(),
                    this.displayOutput,
                    hdrDisplayOutput());
        }
        return this.rendererDebugPass;
    }

    protected final void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Reconstruction processor is destroyed");
        }
    }

    protected final boolean destroyed() { return this.destroyed; }

    protected final RuntimeException destroyRendererDiagnostic(RuntimeException failure) {
        return ResourceCleanup.destroy(this.rendererDebugPass, failure);
    }

    protected final void finishDestroy(RuntimeException failure) {
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    public interface Frame { }

    protected static class SubmittedFrameToken implements Frame {
        private final VulkanReconstructionProcessor owner;
        private final SubmittedFrame<ReconstructionFrameParameters> parameters;

        protected SubmittedFrameToken(
                VulkanReconstructionProcessor owner,
                ReconstructionFrameParameters parameters) {
            this.owner = owner;
            this.parameters = new SubmittedFrame<>(parameters);
        }
    }
}
