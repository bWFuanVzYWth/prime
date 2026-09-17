// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

public final class VulkanImage implements Destroyable {
    private final long allocator;
    private final VkDevice device;
    private final long image;
    private final long allocation;
    private final long view;
    private final int mipLevels;
    private final int format;
    private final int usage;
    private final int width;
    private final int height;
    private final int depth;
    private boolean initialized;
    private boolean destroyed;

    VulkanImage(
            long allocator,
            VkDevice device,
            long image,
            long allocation,
            long view,
            int mipLevels,
            int format,
            int usage,
            int width,
            int height,
            int depth) {
        this.allocator = allocator;
        this.device = device;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.mipLevels = mipLevels;
        this.format = format;
        this.usage = usage;
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public long image() {
        return this.image;
    }

    public long view() {
        return this.view;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    public int width() {
        return this.width;
    }

    public int format() {
        return this.format;
    }

    public int usage() {
        return this.usage;
    }

    public int height() {
        return this.height;
    }

    public int depth() {
        return this.depth;
    }

    boolean initialized() {
        return this.initialized;
    }

    void markInitialized() {
        this.initialized = true;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroyImageView(this.device, this.view, null);
            Vma.vmaDestroyImage(this.allocator, this.image, this.allocation);
        }
    }
}
