package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrPostProcessor;
import dev.prime.render.vulkan.dlss.DlssRrProfile;

final class DlssRrBackend implements ReconstructionBackend {
    private final DlssRrNative.Context ngxContext;
    private DlssRrNative.OptimalSettings optimalSettings;
    private ReconstructionQualityMode optimalQuality;
    private int optimalDisplayWidth;
    private int optimalDisplayHeight;

    DlssRrBackend(DlssRrNative.Context ngxContext) {
        this.ngxContext = ngxContext;
    }

    @Override
    public PostProcessingMode mode() {
        return PostProcessingMode.DLSS_RR;
    }

    @Override
    public Capability capability() {
        if (this.ngxContext == null || !DlssRrBootstrap.deviceReady()) {
            return Capability.unsupported(DlssRrBootstrap.unavailableReason());
        }
        return Capability.supported();
    }

    @Override
    public ReconstructionExtent renderExtent(
            ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
        if (!this.capability().available()) {
            throw new IllegalStateException("DLSS RR is unavailable");
        }
        if (this.optimalSettings == null
                || this.optimalDisplayWidth != displayWidth
                || this.optimalDisplayHeight != displayHeight
                || this.optimalQuality != quality) {
            this.optimalSettings = this.ngxContext.optimalSettings(
                    displayWidth, displayHeight, quality);
            this.optimalDisplayWidth = displayWidth;
            this.optimalDisplayHeight = displayHeight;
            this.optimalQuality = quality;
        }
        return new ReconstructionExtent(
                this.optimalSettings.renderWidth(),
                this.optimalSettings.renderHeight());
    }

    @Override
    public PostProcessingMode fallbackMode() {
        return PostProcessingMode.NRD_FSR;
    }

    @Override
    public TransparentGuideMode transparentGuideMode() {
        return TransparentGuideMode.TRANSMISSION_ONLY;
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
    public String executionLabel() {
        return "Prime 1spp path tracing and DLSS Ray Reconstruction";
    }

    @Override
    public VulkanReconstructionProcessor create(CreateInput input) {
        if (this.ngxContext == null) {
            throw new IllegalStateException(
                    "DLSS RR was selected without an initialized NGX context");
        }
        ResolvedReconstruction selection = input.selection();
        return DlssRrPostProcessor.create(
                input.context(),
                this.ngxContext,
                input.atmosphere(),
                input.stableRadiance(),
                input.output(),
                selection.extent().width(),
                selection.extent().height(),
                input.output().width(),
                input.output().height(),
                selection.quality());
    }
}
