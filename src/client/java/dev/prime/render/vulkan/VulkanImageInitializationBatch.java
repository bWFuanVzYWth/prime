// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import java.util.Arrays;
import java.util.Objects;

/**
 * Submission-scoped initialization state for images entering their owner-defined lifetime layout.
 *
 * <p>Recording only prepares a candidate. Images become initialized after the containing command
 * buffer is accepted by Minecraft's submission; abandoning the batch leaves committed state
 * unchanged. The reusable array adds no steady-state frame allocation.
 */
public final class VulkanImageInitializationBatch {
    private VulkanImage[] images = new VulkanImage[128];
    private int count;
    private boolean active;

    public void begin() {
        if (this.active) {
            throw new IllegalStateException(
                    "Vulkan image initialization batch is already active");
        }
        this.active = true;
    }

    /**
     * Returns whether {@code image} already has meaningful contents in its lifetime layout for
     * this command stream. The first use of a new image returns false; repeated uses in the same
     * batch return true.
     */
    public boolean prepare(VulkanImage image) {
        Objects.requireNonNull(image, "image");
        this.requireActive();
        if (image.initialized()) {
            return true;
        }
        for (int index = 0; index < this.count; index++) {
            if (this.images[index] == image) {
                return true;
            }
        }
        if (this.count == this.images.length) {
            this.images = Arrays.copyOf(
                    this.images, Math.multiplyExact(this.images.length, 2));
        }
        this.images[this.count++] = image;
        return false;
    }

    public void submitted() {
        this.requireActive();
        for (int index = 0; index < this.count; index++) {
            this.images[index].markInitialized();
        }
        this.finish();
    }

    public void abandon() {
        this.requireActive();
        this.finish();
    }

    private void finish() {
        Arrays.fill(this.images, 0, this.count, null);
        this.count = 0;
        this.active = false;
    }

    private void requireActive() {
        if (!this.active) {
            throw new IllegalStateException(
                    "Vulkan image initialization batch is not active");
        }
    }
}
