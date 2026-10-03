package dev.primept.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.primept.StartupOptions;
import java.util.Set;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Port of the old project's supported-extension negotiation before vkCreateInstance. */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @ModifyArgs(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"))
    private void
    primept$colorSpace(Args args) {
        Set<String> supported = args.get(2), enabled = args.get(3);
        if (StartupOptions.enabled() &&
            supported.contains(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME))
            enabled.add(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    }
}
