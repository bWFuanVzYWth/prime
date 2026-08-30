package dev.prime.mixin.streamline;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.prime.streamline.StreamlineReflex;
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
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.nio.LongBuffer;
import java.util.Locale;

@Mixin(VulkanGpuSurface.class)
public class StreamlineVulkanGpuSurfaceMixin {

    @Unique
    private static boolean prime$isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwCreateWindowSurface(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
            )
    )
    private int prime$createHookedWindowSurface(VkInstance instance, long window, VkAllocationCallbacks allocator, LongBuffer surfacePointer) {
        if (!prime$isWindows()) {
            return GLFWVulkan.glfwCreateWindowSurface(instance, window, allocator, surfacePointer);
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        long hinstance = User32.GetWindowLongPtr(hwnd, User32.GWL_HINSTANCE);
        if (hwnd == 0L || hinstance == 0L) {
            throw new IllegalStateException("Failed to resolve Win32 handles for the Vulkan presentation window");
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
                    function
            );
        }
    }

    // Fix this

    // [22-22-37][streamline][error][tid:82288][17s:951ms:106us]vulkan.cpp:1043[debugUtilsMessengerCallback]
    // vkCreateImageView(): pCreateInfo->image (VkImage 0xdb00000000db) was created with
    // VK_IMAGE_USAGE_2_TRANSFER_SRC_BIT_KHR|VK_IMAGE_USAGE_2_TRANSFER_DST_BIT_KHR but
    // requires VK_IMAGE_USAGE_2_SAMPLED_BIT_KHR|VK_IMAGE_USAGE_2_STORAGE_BIT_KHR|
    // VK_IMAGE_USAGE_2_COLOR_ATTACHMENT_BIT_KHR|VK_IMAGE_USAGE_2_DEPTH_STENCIL_ATTACHMENT_BIT_KHR|
    // VK_IMAGE_USAGE_2_TRANSIENT_ATTACHMENT_BIT_KHR|VK_IMAGE_USAGE_2_INPUT_ATTACHMENT_BIT_KHR|
    // VK_IMAGE_USAGE_2_FRAGMENT_SHADING_RATE_ATTACHMENT_BIT_KHR|VK_IMAGE_USAGE_2_FRAGMENT_DENSITY_MAP_BIT_EXT|
    // VK_IMAGE_USAGE_2_VIDEO_DECODE_DST_BIT_KHR|VK_IMAGE_USAGE_2_VIDEO_DECODE_DPB_BIT_KHR|
    // VK_IMAGE_USAGE_2_VIDEO_ENCODE_SRC_BIT_KHR|VK_IMAGE_USAGE_2_VIDEO_ENCODE_DPB_BIT_KHR|
    // VK_IMAGE_USAGE_2_SAMPLE_WEIGHT_BIT_QCOM|VK_IMAGE_USAGE_2_SAMPLE_BLOCK_MATCH_BIT_QCOM|
    // VK_IMAGE_USAGE_2_VIDEO_ENCODE_QUANTIZATION_DELTA_MAP_BIT_KHR|VK_IMAGE_USAGE_2_VIDEO_ENCODE_EMPHASIS_MAP_BIT_KHR.
    // The Vulkan spec states: image must have been created with a usage value containing at least one of the
    // following: VK_IMAGE_USAGE_SAMPLED_BIT VK_IMAGE_USAGE_STORAGE_BIT VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    // VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT
    // VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT VK_IMAGE_USAGE_FRAGMENT_SHADING_RATE_ATTACHMENT_BIT_KHR
    // VK_IMAGE_USAGE_FRAGMENT_DENSITY_MAP_BIT_EXT VK_IMAGE_USAGE_VIDEO_DECODE_DST_BIT_KHR
    // VK_IMAGE_USAGE_VIDEO_DECODE_DPB_BIT_KHR VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR
    // VK_IMAGE_USAGE_VIDEO_ENCODE_DPB_BIT_KHR VK_IMAGE_USAGE_SAMPLE_WEIGHT_BIT_QCOM
    // VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM VK_IMAGE_USAGE_VIDEO_ENCODE_QUANTIZATION_DELTA_MAP_BIT_KHR
    // VK_IMAGE_USAGE_VIDEO_ENCODE_EMPHASIS_MAP_BIT_KHR
    // (https://docs.vulkan.org/spec/latest/chapters/resources.html#VUID-VkImageViewCreateInfo-image-04441)
    // [22-22-37][streamline][error][tid:82288][17s:951ms:229us]vulkan.cpp:3556[getSwapChainBuffer]
    // m_ddt.CreateImageView(m_device, &texViewCreateInfo, 0, &imageView) failed - error -1000011001
    // [22-22-37][streamline][error][tid:82288][17s:951ms:757us]dlfgSwapchain.cpp:91[cloneFakeBuffers]
    // ctx.compute->getSwapChainBuffer(ctx.swapChainData.getSwapChain(), i, ctx.swapChainData.getBackbufferRef(i)) failed 1 (Error)

    @Inject(method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;",
                    shift = At.Shift.AFTER
            ),
            locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void overrideImageUsage(
            GpuSurface.Configuration config,
            CallbackInfo ci,
            @Local(name = "swapchainCreateInfo")
            VkSwapchainCreateInfoKHR infoKHR
    ) {
        infoKHR.imageUsage(
                VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                        VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
        );
    }

    @Inject(method = "configure", at = @At("HEAD"))
    private void prime$invalidateReflexPacing(GpuSurface.Configuration config, CallbackInfo ci) {
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

    @Redirect(method = "present",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanUtils;crashIfFailure(Lcom/mojang/blaze3d/vulkan/VulkanDevice;ILjava/lang/String;)V"
            )
    )
    private void skipPresentCheck(
            VulkanDevice device, int result, String message) {
        /*
        Fix this:
        [22:34:32] [Render thread/ERROR] (Minecraft) vkQueueSubmit(): pSubmits[0] command buffer VkCommandBuffer
         0x20e3141a8d0[nv.sl.dlss_g.cmdCtx.pacer.command-buffer] expects
          VkImage 0xea00000000ea[nv.sl.dlss_g.tex2d.fake-swapchain-buffer] (subresource: aspectMask =
          VK_IMAGE_ASPECT_COLOR_BIT, mipLevel = 0, arrayLayer = 0) to be in layout
           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL--instead, current layout is VK_IMAGE_LAYOUT_PRESENT_SRC_KHR.
        The Vulkan spec states: If a descriptor with type equal to any of
        VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM, VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
        VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, or
        VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT is accessed as a result of this command, all image
         subresources identified by that descriptor must be in the image layout identified when the
          descriptor was written (https://docs.vulkan.org/spec/latest/chapters/drawing.html#VUID-vkCmdDraw-None-09600)
        knot//com.mojang.blaze3d.vulkan.VulkanGpuSurface.present(VulkanGpuSurface.java:413)
        knot//com.mojang.blaze3d.systems.GpuSurface.present(GpuSurface.java:98)
        knot//net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1399)
        knot//net.minecraft.client.Minecraft.runTick(Minecraft.java:1269)
        knot//net.minecraft.client.Minecraft.run(Minecraft.java:959)
        [22:34:32] [Render thread/ERROR] (Minecraft) vkQueuePresentKHR(): pPresentInfo images passed
        to present must be in layout VK_IMAGE_LAYOUT_PRESENT_SRC_KHR or VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR
         but VkImage 0xdb00000000db is in VK_IMAGE_LAYOUT_UNDEFINED.
        The Vulkan spec states: Each element of pImageIndices must be the index of a presentable image
        acquired from the swapchain specified by the corresponding element of the pSwapchains array,
         and the presented image subresource must be in the VK_IMAGE_LAYOUT_PRESENT_SRC_KHR or
         VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR layout at the time the operation is executed on a VkDevice
          (https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html#VUID-VkPresentInfoKHR-pImageIndices-01430)
        knot//com.mojang.blaze3d.vulkan.VulkanGpuSurface.present(VulkanGpuSurface.java:413)
        knot//com.mojang.blaze3d.systems.GpuSurface.present(GpuSurface.java:98)
        knot//net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1399)
        knot//net.minecraft.client.Minecraft.runTick(Minecraft.java:1269)
        knot//net.minecraft.client.Minecraft.run(Minecraft.java:959)

        Streamline's fake swapchain owns these internal layout transitions.
        See NVIDIA-RTX/Streamline issues 84 and 112.
*/
    }

    @Redirect(method = "acquireNextTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanUtils;crashIfFailure(Lcom/mojang/blaze3d/vulkan/VulkanDevice;ILjava/lang/String;)V"
            )
    )
    private void skipPresentCheck_v2(
            VulkanDevice device, int result, String message) {
        /*
        Fix this:
        [22:34:32] [Render thread/ERROR] (Minecraft) vkQueueSubmit(): pSubmits[0] command buffer VkCommandBuffer
         0x20e3141a8d0[nv.sl.dlss_g.cmdCtx.pacer.command-buffer] expects
          VkImage 0xea00000000ea[nv.sl.dlss_g.tex2d.fake-swapchain-buffer] (subresource: aspectMask =
          VK_IMAGE_ASPECT_COLOR_BIT, mipLevel = 0, arrayLayer = 0) to be in layout
           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL--instead, current layout is VK_IMAGE_LAYOUT_PRESENT_SRC_KHR.
        The Vulkan spec states: If a descriptor with type equal to any of
        VK_DESCRIPTOR_TYPE_SAMPLE_WEIGHT_IMAGE_QCOM, VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
        VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, or
        VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT is accessed as a result of this command, all image
         subresources identified by that descriptor must be in the image layout identified when the
          descriptor was written (https://docs.vulkan.org/spec/latest/chapters/drawing.html#VUID-vkCmdDraw-None-09600)
        knot//com.mojang.blaze3d.vulkan.VulkanGpuSurface.present(VulkanGpuSurface.java:413)
        knot//com.mojang.blaze3d.systems.GpuSurface.present(GpuSurface.java:98)
        knot//net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1399)
        knot//net.minecraft.client.Minecraft.runTick(Minecraft.java:1269)
        knot//net.minecraft.client.Minecraft.run(Minecraft.java:959)
        [22:34:32] [Render thread/ERROR] (Minecraft) vkQueuePresentKHR(): pPresentInfo images passed
        to present must be in layout VK_IMAGE_LAYOUT_PRESENT_SRC_KHR or VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR
         but VkImage 0xdb00000000db is in VK_IMAGE_LAYOUT_UNDEFINED.
        The Vulkan spec states: Each element of pImageIndices must be the index of a presentable image
        acquired from the swapchain specified by the corresponding element of the pSwapchains array,
         and the presented image subresource must be in the VK_IMAGE_LAYOUT_PRESENT_SRC_KHR or
         VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR layout at the time the operation is executed on a VkDevice
          (https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html#VUID-VkPresentInfoKHR-pImageIndices-01430)
        knot//com.mojang.blaze3d.vulkan.VulkanGpuSurface.present(VulkanGpuSurface.java:413)
        knot//com.mojang.blaze3d.systems.GpuSurface.present(GpuSurface.java:98)
        knot//net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1399)
        knot//net.minecraft.client.Minecraft.runTick(Minecraft.java:1269)
        knot//net.minecraft.client.Minecraft.run(Minecraft.java:959)

        Streamline's fake swapchain owns these internal layout transitions.
        See NVIDIA-RTX/Streamline issues 84 and 112.
*/
    }
}
