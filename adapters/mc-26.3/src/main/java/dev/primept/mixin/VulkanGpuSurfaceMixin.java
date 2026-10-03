package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.*;
import dev.primept.*;
import dev.primept.display.*;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.IOException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Actual after-HUD colors, swapchain metadata and one real Present remain host events. */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long surface;
    @Shadow @Final private LongList swapchainImages;
    @Shadow @Final private long[] acquireSemaphores;
    @Shadow private long[] presentSemaphores;
    @Shadow private int currentAcquireSemaphore, currentImageIndex, swapchainWidth, swapchainHeight;
    @Shadow private long swapchain;
    @Shadow private boolean swapchainOutOfDate;
    @Shadow
    public abstract VkSurfaceFormatKHR
    pickSwapchainSurfaceFormat(VkSurfaceFormatKHR.Buffer formats);
    @Unique private long primept$window, primept$monitor;
    @Unique private int primept$format, primept$colorSpace, primept$usage;
    @Unique private boolean primept$requestedHdr, primept$requestedFg, primept$fgUsage;
    @Unique private long[] primept$views;
    @Unique private HdrSurfaceBridge primept$fallback;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void primept$window(VulkanDevice device, long window, CallbackInfo ci) {
        primept$window = window;
    }

    @WrapMethod(method = "configure")
    private void primept$configure(GpuSurface.Configuration configuration, Operation<Void> original)
            throws SurfaceException {
        primept$chooseFormat();
        try {
            original.call(configuration);
            primept$createViews();
            HdrOutput.surfaceConfigured(primept$hdr());
        } catch (RuntimeException | Error failure) {
            HdrOutput.surfaceConfigured(false);
            throw failure;
        }
    }

    @Unique
    private void primept$chooseFormat() throws SurfaceException {
        StreamlineFrames.suspend();
        primept$requestedHdr = HdrOutput.requested();
        primept$requestedFg = PrimeClient.settings().frameGeneration();
        primept$monitor = HostHdrDisplay.monitorIdentity(primept$window);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.callocInt(1);
            for (int attempt = 0; attempt < 4; attempt++) {
                primept$check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        device.vkDevice().getPhysicalDevice(), surface, count, null));
                if (count.get(0) <= 0)
                    throw new SurfaceException("Surface has no formats");
                var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
                int status = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        device.vkDevice().getPhysicalDevice(), surface, count, formats);
                if (status == VK10.VK_INCOMPLETE)
                    continue;
                primept$check(status);
                formats.limit(count.get(0));
                var capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
                primept$check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                        device.vkDevice().getPhysicalDevice(), surface, capabilities));
                int availableUsage = capabilities.supportedUsageFlags();
                VkSurfaceFormatKHR hdr = null;
                for (int i = 0; i < formats.limit(); i++) {
                    var candidate = formats.get(i);
                    if (candidate.format() == VK10.VK_FORMAT_R16G16B16A16_SFLOAT &&
                        candidate.colorSpace() ==
                                EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT)
                        hdr = candidate;
                }
                var display = HostHdrDisplay.queryWindow(primept$window);
                boolean hdrSupported = StartupOptions.enabled() && hdr != null &&
                                       display.available() && display.hdrActive() &&
                                       (availableUsage & VK10.VK_IMAGE_USAGE_STORAGE_BIT) != 0;
                HdrOutput.updateCapability(hdrSupported, display.maximumNits(),
                                           display.sdrWhiteNits());
                var selected = HdrOutput.requestedCalibration().active()
                                       ? hdr
                                       : pickSwapchainSurfaceFormat(formats);
                primept$format = selected.format();
                primept$colorSpace = selected.colorSpace();
                primept$usage = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                int fg = VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                         VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                primept$fgUsage = StreamlineBootstrap.installed() && (availableUsage & fg) == fg;
                if (primept$fgUsage)
                    primept$usage |= fg;
                if (primept$hdr())
                    primept$usage |= VK10.VK_IMAGE_USAGE_STORAGE_BIT;
                return;
            }
        }
        throw new SurfaceException("Surface formats changed during enumeration");
    }
    @Unique
    private static void primept$check(int result) throws SurfaceException {
        if (result != VK10.VK_SUCCESS)
            throw new SurfaceException("Vulkan surface operation failed: " + result);
    }
    @Unique
    private boolean primept$hdr() {
        return primept$format == VK10.VK_FORMAT_R16G16B16A16_SFLOAT &&
                primept$colorSpace ==
                        EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT;
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageFormat(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
    private int
    primept$format(int ignored) {
        return primept$format;
    }
    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
    private int
    primept$colorSpace(int ignored) {
        return primept$colorSpace;
    }
    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
    private int
    primept$usage(int usage) {
        return usage | primept$usage;
    }

    @Inject(method = "isSuboptimal", at = @At("RETURN"), cancellable = true)
    private void primept$displayChanged(CallbackInfoReturnable<Boolean> ci) {
        if (swapchain != 0 && (primept$requestedHdr != HdrOutput.requested() ||
                               primept$requestedFg != PrimeClient.settings().frameGeneration() ||
                               primept$monitor != HostHdrDisplay.monitorIdentity(primept$window)))
            ci.setReturnValue(true);
    }

    @Unique
    private void primept$createViews() {
        if (!primept$hdr() && !primept$fgUsage)
            return;
        primept$views = new long[swapchainImages.size()];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocLong(1);
            for (int i = 0; i < primept$views.length; i++) {
                var create = VkImageViewCreateInfo.calloc(stack)
                                     .sType$Default()
                                     .image(swapchainImages.getLong(i))
                                     .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                                     .format(primept$format);
                create.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1)
                        .layerCount(1);
                int status = VK10.vkCreateImageView(device.vkDevice(), create, null, pointer);
                if (status != VK10.VK_SUCCESS)
                    throw new IllegalStateException("Cannot create present image view: " + status);
                primept$views[i] = pointer.get(0);
            }
        }
    }

    @Inject(method = "destroySwapchain", at = @At("HEAD"))
    private void primept$retire(CallbackInfo ci) {
        if (primept$views != null || primept$fallback != null) {
            HostVulkanRenderer.retireSurfaceResources(device.createCommandEncoder(), () -> {
                if (primept$fallback != null) {
                    primept$fallback.close();
                    primept$fallback = null;
                }
                if (primept$views != null) {
                    for (long view : primept$views)
                        if (view != 0)
                            VK10.vkDestroyImageView(device.vkDevice(), view, null);
                    primept$views = null;
                }
            });
        }
        HdrOutput.surfaceConfigured(false);
    }

    @WrapMethod(method = "blitFromTexture")
    private void primept$afterHud(CommandEncoderBackend backend, GpuTextureView source,
                                  Operation<Void> original) {
        boolean hdr = primept$hdr();
        if (!hdr && !(primept$fgUsage && StreamlineFrames.active())) {
            original.call(backend, source);
            return;
        }
        if (swapchainOutOfDate || currentImageIndex < 0 ||
            !(backend instanceof VulkanCommandEncoder encoder) ||
            !(source instanceof VulkanGpuTextureView ui))
            throw new IllegalStateException("Invalid Prime surface frame");
        int width = Math.min(swapchainWidth, source.getWidth(0)),
            height = Math.min(swapchainHeight, source.getHeight(0));
        long image = swapchainImages.getLong(currentImageIndex),
             view = primept$views[currentImageIndex];
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        long serial = ((VulkanCommandEncoderAccessor)(Object)encoder).primept$currentSubmitIndex();
        try {
            if (hdr)
                primept$transition(command, image, false);
            boolean world = HostVulkanRenderer.presentSurface(
                    encoder, command.address(), ui.texture().vkImage(), ui.vkImageView(), image,
                    view, serial, width, height, hdr, swapchainImages.size(), primept$format);
            if (hdr && !world) {
                if (primept$fallback == null) {
                    var access = (VulkanCommandEncoderAccessor)(Object)encoder;
                    primept$fallback = new HdrSurfaceBridge(
                            device.instance().vkInstance().address(),
                            device.vkDevice().getPhysicalDevice().address(),
                            device.vkDevice().address(), device.graphicsQueue().vkQueue().address(),
                            access.primept$submitSemaphore(),
                            device.graphicsQueue().queueFamilyIndex());
                }
                primept$fallback.record(command.address(), ui.texture().vkImage(), ui.vkImageView(),
                                        image, view, serial, width, height,
                                        HdrOutput.activeCalibration());
            }
            if (hdr)
                primept$transition(command, image, true);
            int status = VK10.vkEndCommandBuffer(command);
            if (status != VK10.VK_SUCCESS)
                throw new IllegalStateException("Cannot finish Prime surface command: " + status);
            if (hdr)
                encoder.waitSemaphore(acquireSemaphores[currentAcquireSemaphore], 0,
                                      VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            encoder.execute(command);
            if (hdr)
                encoder.signalSemaphore(presentSemaphores[currentImageIndex], 0,
                                        VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            else
                original.call(backend, source);
        } catch (IOException failure) {
            HostVulkanRenderer.surfaceFailed(failure);
            HostVulkanRenderer.submissionFailed(encoder, failure);
            throw new IllegalStateException("Cannot initialize HDR surface conversion", failure);
        } catch (RuntimeException | Error failure) {
            HostVulkanRenderer.surfaceFailed(failure);
            HostVulkanRenderer.submissionFailed(encoder, failure);
            throw failure;
        }
    }

    @Unique
    private void primept$transition(VkCommandBuffer command, long image, boolean present) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack)
                                  .sType$Default()
                                  .image(image)
                                  .oldLayout(present ? VK10.VK_IMAGE_LAYOUT_GENERAL
                                                     : VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                                  .newLayout(present ? KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                                                     : VK10.VK_IMAGE_LAYOUT_GENERAL)
                                  .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                  .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                  .srcAccessMask(present ? VK10.VK_ACCESS_SHADER_WRITE_BIT : 0)
                                  .dstAccessMask(present ? 0 : VK10.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .layerCount(1);
            VK10.vkCmdPipelineBarrier(command,
                                      present ? VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                                              : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                      present ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                              : VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                      0, null, null, barrier);
        }
    }

    @ModifyArg(
            method = "blitFromTexture",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;signalSemaphore(JJJ)V")
            ,
            index = 2)
    private long
    primept$presentStage(long stage) {
        return StreamlineFrames.active() ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : stage;
    }

    @Redirect(
            method = "present",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int
    primept$present(VkQueue queue, VkPresentInfoKHR presentInfo) {
        StreamlineFrames.beforePresent();
        return StreamlinePresent.invoke(
                queue, presentInfo,
                NativeBridge.hasVulkanPresent() &&
                        (!StreamlineBootstrap.installed() || StreamlineFrames.active()));
    }
}
