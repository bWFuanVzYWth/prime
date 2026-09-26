package dev.primept.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.primept.VulkanBootstrap;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @Inject(method =
                    "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void
    primept$negotiate(Collection<String> extensions, VulkanPhysicalDevice physical,
                      Set<VulkanFeature> features, CallbackInfoReturnable<VkDevice> callback) {
        VulkanBootstrap.negotiate(extensions, physical, features);
    }

    @Inject(method =
                    "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("RETURN"))
    private static void
    primept$created(Collection<String> extensions, VulkanPhysicalDevice physical,
                    Set<VulkanFeature> features, CallbackInfoReturnable<VkDevice> callback) {
        VulkanBootstrap.deviceCreated(callback.getReturnValue(), extensions, features);
    }
}
