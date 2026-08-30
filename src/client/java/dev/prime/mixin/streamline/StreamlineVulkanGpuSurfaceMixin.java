package dev.prime.mixin.streamline;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.prime.PrimeClient;
import dev.prime.streamline.StreamlineReflex;
import java.nio.LongBuffer;
import java.util.Locale;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVulkan;
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
                    target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwCreateWindowSurface(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int prime$createHookedWindowSurface(
            VkInstance instance,
            long window,
            VkAllocationCallbacks allocator,
            LongBuffer surfacePointer) {
        if (!prime$usesStreamlineInterposer()) {
            return GLFWVulkan.glfwCreateWindowSurface(
                    instance, window, allocator, surfacePointer);
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        long hinstance = User32.GetWindowLongPtr(hwnd, User32.GWL_HINSTANCE);
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
            return JNI.callPPPPI(
                    instance.address(),
                    createInfo.address(),
                    0L,
                    MemoryUtil.memAddress(surfacePointer),
                    function);
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
