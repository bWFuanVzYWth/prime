package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.primept.VulkanBootstrap;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @ModifyExpressionValue(
            method =
                    "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(
                    value = "NEW",
                    target =
                            "(Ljava/lang/String;Ljava/util/Collection;)Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"))
    private FeatureSet
    primept$negotiate(FeatureSet vanilla, @Local VulkanPhysicalDevice physical) {
        var extensions = new HashSet<>(vanilla.extensions());
        var features = new HashSet<>(vanilla.features());
        VulkanBootstrap.negotiate(extensions, physical, features);
        // Both vkCreateDevice and the host device metadata receive this same negotiated set.
        return new FeatureSet(vanilla.name(), Set.copyOf(extensions), Set.copyOf(features),
                              vanilla.condition());
    }

    @Inject(method =
                    "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("RETURN"))
    private static void
    primept$created(FeatureSet features, VulkanPhysicalDevice physical,
                    CallbackInfoReturnable<VkDevice> callback) {
        VulkanBootstrap.deviceCreated(callback.getReturnValue(), features.extensions(),
                                      features.features());
    }
}
