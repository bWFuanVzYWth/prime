package dev.primept.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.primept.HostVulkanRenderer;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/VkImageCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;")
            ,
            index = 0)
    private int
    primept$storageUsage(int usage) {
        var texture = (VulkanGpuTexture)(Object)this;
        return (texture.usage() & HostVulkanRenderer.USAGE_STORAGE) != 0
                ? usage | VK10.VK_IMAGE_USAGE_STORAGE_BIT
                : usage;
    }
}
