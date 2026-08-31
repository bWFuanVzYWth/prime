package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.RayConeParameters;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.NoisyPostProcessor;
import dev.prime.render.vulkan.dlss.DlssRrProfile;

final class NoisyBackend implements ReconstructionBackend {
    @Override
    public PostProcessingMode mode() {
        return PostProcessingMode.DISABLED;
    }

    @Override
    public Capability capability() {
        return Capability.supported();
    }

    @Override
    public ReconstructionExtent renderExtent(
            ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
        return new ReconstructionExtent(displayWidth, displayHeight);
    }

    @Override
    public PostProcessingMode fallbackMode() {
        return null;
    }

    @Override
    public TransparentGuideMode transparentGuideMode() {
        return TransparentGuideMode.DISABLED;
    }

    @Override
    public SubpixelJitter jitter(ReconstructionQualityMode quality, int frameIndex) {
        return DlssRrProfile.jitter(quality, frameIndex);
    }

    @Override
    public int jitterPhase(ReconstructionQualityMode quality, int frameIndex) {
        return DlssRrProfile.jitterPhase(quality, frameIndex);
    }

    @Override
    public RayConeParameters rayConeParameters(
            ReconstructionQualityMode quality,
            float projectionM00,
            float projectionM11,
            int width,
            int height) {
        return ReconstructionQualityMode.NATIVE_AA.rayConeParameters(
                projectionM00, projectionM11, width, height);
    }

    @Override
    public String executionLabel() {
        return "Prime native 1spp path tracing without post-processing";
    }

    @Override
    public VulkanReconstructionProcessor create(CreateInput input) {
        ResolvedReconstruction selection = input.selection();
        return NoisyPostProcessor.create(
                input.context(),
                input.atmosphere(),
                input.stableRadiance(),
                input.output(),
                selection.extent().width(),
                selection.extent().height(),
                selection.quality());
    }
}
