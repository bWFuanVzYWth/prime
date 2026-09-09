// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;

/** Lazily materialized sRGB reinterpretation owned by a Minecraft Vulkan texture view. */
public interface SrgbTextureView {
    long prime$srgbImageView();

    static long imageView(VulkanGpuTextureView view) {
        if (!(view instanceof SrgbTextureView srgb)) {
            throw new IllegalStateException("Prime sRGB Vulkan view mixin was not applied");
        }
        return srgb.prime$srgbImageView();
    }
}
