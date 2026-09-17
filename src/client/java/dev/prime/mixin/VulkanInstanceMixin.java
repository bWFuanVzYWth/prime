// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import java.util.Set;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @Shadow @Final private Set<String> enabledExtensions;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"))
    private void prime$enableNgxInstanceExtensions(
            int debugVerbosity,
            boolean enableDebugLabels,
            boolean enableValidation,
            CallbackInfo ci) {
        DlssRrBootstrap.enableRequiredInstanceExtensions(this.enabledExtensions);
    }

    @ModifyArgs(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"))
    private void prime$enableSwapchainColorSpace(Args args) {
        Set<String> supported = args.get(2);
        Set<String> enabled = args.get(3);
        if (supported.contains(
                EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME)) {
            enabled.add(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
        }
    }
}
