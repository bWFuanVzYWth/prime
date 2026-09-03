package dev.prime.render.vulkan.reconstruction;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanImage;
import java.util.Objects;

/** Size-dependent images and processor owned as one reconstruction unit. */
public final class VulkanReconstructionResources implements Destroyable {
    private final VulkanImage output;
    private final VulkanImage stableRadiance;
    private final VulkanReconstructionProcessor processor;
    private boolean destroyed;

    VulkanReconstructionResources(
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanReconstructionProcessor processor) {
        this.output = Objects.requireNonNull(output, "output");
        this.stableRadiance = Objects.requireNonNull(stableRadiance, "stableRadiance");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public VulkanImage output() {
        return this.output;
    }

    public VulkanImage stableRadiance() {
        return this.stableRadiance;
    }

    public VulkanReconstructionProcessor processor() {
        return this.processor;
    }

    public ResolvedReconstruction selection() {
        return this.processor.selection();
    }

    public boolean matches(ResolvedReconstruction candidate) {
        ResolvedReconstruction current = this.processor.selection();
        return current.requestedMode() == candidate.requestedMode()
                && current.effectiveMode() == candidate.effectiveMode()
                && current.quality() == candidate.quality()
                && current.extent().equals(candidate.extent())
                && current.displayExtent().equals(candidate.displayExtent());
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.processor, failure);
        failure = ResourceCleanup.destroy(this.stableRadiance, failure);
        failure = ResourceCleanup.destroy(this.output, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
