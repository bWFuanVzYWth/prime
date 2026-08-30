package dev.prime.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Allows RGBA8 storage to be viewed as either encoded UNORM data or filtered sRGB color. */
@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;flags(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"),
            index = 0)
    private int prime$allowSrgbView(int flags) {
        VulkanGpuTexture texture = (VulkanGpuTexture) (Object) this;
        return texture.getFormat() == GpuFormat.RGBA8_UNORM
                ? flags | VK12.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT
                : flags;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"),
            index = 0)
    private int prime$allowMainColorStorage(int usage) {
        VulkanGpuTexture texture = (VulkanGpuTexture) (Object) this;
        return (texture.usage() & 32) != 0
                ? usage | VK12.VK_IMAGE_USAGE_STORAGE_BIT
                : usage;
    }
}
