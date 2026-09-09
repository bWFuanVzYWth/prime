// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import dev.prime.infrastructure.ResourceCleanup;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;

/** Owns one frozen native-resolution offline session and its display adapter. */
final class OfflineRenderResources implements Destroyable {
    final VulkanImage displayOutput;
    final VulkanImage runningMean;
    final DisplayTransformPass display;
    private boolean destroyed;

    private OfflineRenderResources(
            VulkanImage displayOutput,
            VulkanImage runningMean,
            DisplayTransformPass display) {
        this.displayOutput = displayOutput;
        this.runningMean = runningMean;
        this.display = display;
    }

    static OfflineRenderResources create(
            VulkanContext context,
            int width,
            int height,
            VulkanBuffer frozenExposure) {
        VulkanImage displayOutput = null;
        VulkanImage runningMean = null;
        DisplayTransformPass display = null;
        try {
            displayOutput = context.createOutputImage(width, height);
            runningMean = context.createAccumulationImage(width, height);
            display = DisplayTransformPass.createOffline(
                    context,
                    runningMean,
                    frozenExposure,
                    displayOutput);
            return new OfflineRenderResources(
                    displayOutput,
                    runningMean,
                    display);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(display, exception);
            ResourceCleanup.destroy(runningMean, exception);
            ResourceCleanup.destroy(displayOutput, exception);
            throw exception;
        }
    }

    boolean matches(int width, int height) {
        return this.displayOutput.width() == width
                && this.displayOutput.height() == height
                && this.runningMean.width() == width
                && this.runningMean.height() == height;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.display, failure);
        failure = ResourceCleanup.destroy(this.runningMean, failure);
        failure = ResourceCleanup.destroy(this.displayOutput, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
