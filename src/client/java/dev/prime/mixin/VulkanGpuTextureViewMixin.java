// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.prime.render.vulkan.SrgbTextureView;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns the color-space companion view for one exact two-dimensional subresource range. */
@Mixin(VulkanGpuTextureView.class)
public abstract class VulkanGpuTextureViewMixin implements SrgbTextureView {
    @Shadow @Final private VulkanDevice device;

    @Unique private long prime$srgbImageView;

    @Override
    public long prime$srgbImageView() {
        if (this.prime$srgbImageView != 0L) {
            return this.prime$srgbImageView;
        }
        VulkanGpuTextureView view = (VulkanGpuTextureView) (Object) this;
        VulkanGpuTexture texture = view.texture();
        if (texture.getFormat() != GpuFormat.RGBA8_UNORM
                || texture.getDepthOrLayers() != 1
                || (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            throw new IllegalArgumentException(
                    "sRGB color sampling requires a two-dimensional RGBA8_UNORM texture");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(texture.vkImage())
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK12.VK_FORMAT_R8G8B8A8_SRGB);
            info.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(view.baseMipLevel())
                    .levelCount(view.mipLevels())
                    .baseArrayLayer(0)
                    .layerCount(1);
            LongBuffer pointer = stack.mallocLong(1);
            int result = VK12.vkCreateImageView(
                    this.device.vkDevice(), info, null, pointer);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "Could not create Prime sRGB companion image view: Vulkan result "
                                + result);
            }
            this.prime$srgbImageView = pointer.get(0);
            return this.prime$srgbImageView;
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void prime$destroySrgbView(CallbackInfo callbackInfo) {
        if (this.prime$srgbImageView != 0L) {
            VK12.vkDestroyImageView(
                    this.device.vkDevice(), this.prime$srgbImageView, null);
            this.prime$srgbImageView = 0L;
        }
    }
}
