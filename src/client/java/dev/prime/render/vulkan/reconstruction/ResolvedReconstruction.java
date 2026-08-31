package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.RayConeParameters;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import java.util.Objects;
import java.util.Optional;

/** Requested product mode resolved to one executable built-in backend and render extent. */
public record ResolvedReconstruction(
        PostProcessingMode requestedMode,
        PostProcessingMode effectiveMode,
        ReconstructionQualityMode quality,
        ReconstructionExtent extent,
        ReconstructionExtent displayExtent,
        ReconstructionBackend backend,
        Optional<String> fallbackReason) {
    public ResolvedReconstruction {
        requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
        effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
        quality = Objects.requireNonNull(quality, "quality");
        extent = Objects.requireNonNull(extent, "extent");
        displayExtent = Objects.requireNonNull(displayExtent, "displayExtent");
        backend = Objects.requireNonNull(backend, "backend");
        fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
        if (backend.mode() != effectiveMode) {
            throw new IllegalArgumentException(
                    "Resolved reconstruction backend disagrees with its effective mode");
        }
        if ((requestedMode == effectiveMode) != fallbackReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resolved reconstruction fallback reason is inconsistent");
        }
    }

    public boolean fellBack() {
        return this.requestedMode != this.effectiveMode;
    }

    public TransparentGuideMode transparentGuideMode() {
        return this.backend.transparentGuideMode();
    }

    public SubpixelJitter jitter(int frameIndex) {
        return this.backend.jitter(this.quality, frameIndex);
    }

    public int jitterPhase(int frameIndex) {
        return this.backend.jitterPhase(this.quality, frameIndex);
    }

    public RayConeParameters rayConeParameters(
            float projectionM00, float projectionM11) {
        return this.backend.rayConeParameters(
                this.quality,
                projectionM00,
                projectionM11,
                this.extent.width(),
                this.extent.height());
    }

    public String executionLabel() {
        return this.backend.executionLabel();
    }

}
