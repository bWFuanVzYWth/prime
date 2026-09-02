package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.RayConeParameters;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.dlss.DlssRrProfile;
import java.util.Objects;
import java.util.Optional;

/** Requested product mode resolved to one executable built-in backend and render extent. */
public record ResolvedReconstruction(
        PostProcessingMode requestedMode,
        PostProcessingMode effectiveMode,
        ReconstructionQualityMode quality,
        ReconstructionExtent extent,
        ReconstructionExtent displayExtent,
        Optional<String> fallbackReason) {
    public ResolvedReconstruction {
        requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
        effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
        quality = Objects.requireNonNull(quality, "quality");
        extent = Objects.requireNonNull(extent, "extent");
        displayExtent = Objects.requireNonNull(displayExtent, "displayExtent");
        fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
        if ((requestedMode == effectiveMode) != fallbackReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resolved reconstruction fallback reason is inconsistent");
        }
    }

    public boolean fellBack() {
        return this.requestedMode != this.effectiveMode;
    }

    public TransparentGuideMode transparentGuideMode() {
        return switch (this.effectiveMode) {
            case NRD_FSR -> TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
            case DLSS_RR -> TransparentGuideMode.TRANSMISSION_ONLY;
            case DISABLED -> TransparentGuideMode.DISABLED;
        };
    }

    public SubpixelJitter jitter(int frameIndex) {
        return this.effectiveMode == PostProcessingMode.NRD_FSR
                ? this.quality.jitter(frameIndex)
                : DlssRrProfile.jitter(this.quality, frameIndex);
    }

    public int jitterPhase(int frameIndex) {
        return this.effectiveMode == PostProcessingMode.NRD_FSR
                ? this.quality.jitterPhase(frameIndex)
                : DlssRrProfile.jitterPhase(this.quality, frameIndex);
    }

    public RayConeParameters rayConeParameters(
            float projectionM00, float projectionM11) {
        ReconstructionQualityMode coneQuality = this.effectiveMode == PostProcessingMode.DISABLED
                ? ReconstructionQualityMode.NATIVE_AA
                : this.quality;
        return coneQuality.rayConeParameters(
                projectionM00, projectionM11, this.extent.width(), this.extent.height());
    }

    public String executionLabel() {
        return switch (this.effectiveMode) {
            case NRD_FSR -> "Prime 1spp path tracing, NRD, and FidelityFX FSR 3.1.4";
            case DLSS_RR -> "Prime 1spp path tracing and DLSS Ray Reconstruction";
            case DISABLED -> "Prime native 1spp path tracing without post-processing";
        };
    }
}
