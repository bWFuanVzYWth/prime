package dev.primept.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.primept.NativeBridge;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Streamline advances its frame bookkeeping around the host's one real presentation. */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Redirect(
            method = "present",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int
    primept$present(VkQueue queue, VkPresentInfoKHR presentInfo) {
        return NativeBridge.hasVulkanPresent()
                ? NativeBridge.presentVulkan(queue.address(), presentInfo.address())
                : KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
    }
}
