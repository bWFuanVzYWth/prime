package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.RayConeParameters;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.util.Objects;

/** Closed internal contract implemented only by Prime's three built-in backends. */
interface ReconstructionBackend {
    PostProcessingMode mode();

    Capability capability();

    ReconstructionExtent renderExtent(
            ReconstructionQualityMode quality, int displayWidth, int displayHeight);

    PostProcessingMode fallbackMode();

    TransparentGuideMode transparentGuideMode();

    SubpixelJitter jitter(ReconstructionQualityMode quality, int frameIndex);

    int jitterPhase(ReconstructionQualityMode quality, int frameIndex);

    default RayConeParameters rayConeParameters(
            ReconstructionQualityMode quality,
            float projectionM00,
            float projectionM11,
            int width,
            int height) {
        return quality.rayConeParameters(projectionM00, projectionM11, width, height);
    }

    String executionLabel();

    VulkanReconstructionProcessor create(CreateInput input);

    record Capability(boolean available, String unavailableReason) {
        public Capability {
            if (available && unavailableReason != null) {
                throw new IllegalArgumentException(
                        "Available reconstruction capability cannot have a failure reason");
            }
            if (!available && (unavailableReason == null || unavailableReason.isBlank())) {
                throw new IllegalArgumentException(
                        "Unavailable reconstruction capability requires a reason");
            }
        }

        public static Capability supported() {
            return new Capability(true, null);
        }

        public static Capability unsupported(String reason) {
            return new Capability(false, reason);
        }
    }

    record CreateInput(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage stableRadiance,
            VulkanImage output,
            ResolvedReconstruction selection) {
        public CreateInput {
            context = Objects.requireNonNull(context, "context");
            atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
            stableRadiance = Objects.requireNonNull(stableRadiance, "stableRadiance");
            output = Objects.requireNonNull(output, "output");
            selection = Objects.requireNonNull(selection, "selection");
        }
    }
}
