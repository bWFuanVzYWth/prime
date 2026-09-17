// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin.streamline;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.prime.PrimeClient;
import dev.prime.streamline.StreamlineReflex;
import java.nio.LongBuffer;
import java.util.Locale;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.User32;
import org.lwjgl.vulkan.KHRWin32Surface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(VulkanGpuSurface.class)
public class StreamlineVulkanGpuSurfaceMixin {
    @Unique
    private static boolean prime$usesStreamlineInterposer() {
        return System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT)
                        .contains("windows")
                && PrimeClient.streamline() != null;
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/sdl/SDLVulkan;SDL_Vulkan_CreateSurface(JLorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)Z"))
    private boolean prime$createHookedWindowSurface(
            long window,
            VkInstance instance,
            VkAllocationCallbacks allocator,
            LongBuffer surfacePointer) {
        if (!prime$usesStreamlineInterposer()) {
            return SDLVulkan.SDL_Vulkan_CreateSurface(
                    window, instance, allocator, surfacePointer);
        }
        int properties = SDLVideo.SDL_GetWindowProperties(window);
        long hwnd = properties == 0 ? 0L : SDLProperties.SDL_GetPointerProperty(
                properties, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        long hinstance = hwnd == 0L ? 0L : User32.GetWindowLongPtr(hwnd, User32.GWL_HINSTANCE);
        if (hwnd == 0L || hinstance == 0L) {
            throw new IllegalStateException(
                    "Failed to resolve Win32 handles for the Vulkan presentation window");
        }
        long function = VK10.vkGetInstanceProcAddr(instance, "vkCreateWin32SurfaceKHR");
        if (function == 0L) {
            throw new IllegalStateException("vkCreateWin32SurfaceKHR is unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWin32SurfaceCreateInfoKHR createInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
                    .sType(KHRWin32Surface.VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                    .hinstance(hinstance)
                    .hwnd(hwnd);
            int result = JNI.callPPPPI(
                    instance.address(),
                    createInfo.address(),
                    MemoryUtil.memAddressSafe(allocator),
                    MemoryUtil.memAddress(surfacePointer),
                    function);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateWin32SurfaceKHR failed: " + result);
            }
            return true;
        }
    }

    @Inject(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;",
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void prime$addStreamlineImageUsage(
            GpuSurface.Configuration config,
            CallbackInfo ci,
            @Local(name = "surfaceCapabilities") VkSurfaceCapabilitiesKHR capabilities,
            @Local(name = "swapchainCreateInfo") VkSwapchainCreateInfoKHR createInfo)
            throws SurfaceException {
        if (!prime$usesStreamlineInterposer()) {
            return;
        }
        int required = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        int unsupported = required & ~capabilities.supportedUsageFlags();
        if (unsupported != 0) {
            throw new SurfaceException(
                    "Streamline swapchain usage is unsupported: 0x"
                            + Integer.toHexString(unsupported));
        }
        createInfo.imageUsage(createInfo.imageUsage() | required);
    }

    @Inject(method = "configure", at = @At("HEAD"))
    private void prime$invalidateReflexPacing(
            GpuSurface.Configuration config, CallbackInfo ci) {
        StreamlineReflex.invalidatePacing();
    }

    @Inject(method = "present", at = @At("HEAD"))
    private void prime$reflexPresentBegin(CallbackInfo ci) {
        StreamlineReflex.endRenderSubmission();
        StreamlineReflex.beginPresent();
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void prime$reflexPresentEnd(CallbackInfo ci) {
        StreamlineReflex.endPresent();
    }
}
