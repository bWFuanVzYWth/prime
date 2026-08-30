package dev.prime.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.client.WindowsHdrDisplay;
import dev.prime.render.HdrOutput;
import dev.prime.render.vulkan.HdrPresentation;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.streamline.StreamlineFrameGeneration;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.IntBuffer;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkOffset3D;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Unique private static final int PRIME_MAX_SURFACE_FORMAT_QUERY_ATTEMPTS = 4;

    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long surface;
    @Shadow @Final private LongList swapchainImages;
    @Shadow @Final private long[] acquireSemaphores;
    @Shadow private int currentAcquireSemaphore;
    @Shadow private long[] presentSemaphores;
    @Shadow private int currentImageIndex;
    @Shadow private int swapchainWidth;
    @Shadow private int swapchainHeight;
    @Shadow private boolean swapchainOutOfDate;

    @Unique private long prime$window;
    @Unique private int prime$swapchainImageFormat;
    @Unique private int prime$swapchainColorSpace;

    @Shadow
    public abstract VkSurfaceFormatKHR pickSwapchainSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void prime$captureWindow(
            VulkanDevice ignoredDevice, long window, CallbackInfo callbackInfo) {
        this.prime$window = window;
    }

    @Inject(method = "configure", at = @At("HEAD"))
    private void prime$refreshSurfaceFormat(
            GpuSurface.Configuration configuration,
            CallbackInfo callbackInfo)
            throws SurfaceException {
        try {
            StreamlineFrameGeneration.beforeSwapchainReconfigure();
        } catch (RuntimeException exception) {
            throw new SurfaceException(exception);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            for (int attempt = 0;
                    attempt < PRIME_MAX_SURFACE_FORMAT_QUERY_ATTEMPTS;
                    attempt++) {
                count.put(0, 0);
                int countResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        this.device.vkDevice().getPhysicalDevice(),
                        this.surface,
                        count,
                        null);
                VulkanGpuSurface.throwIfFailure(
                        countResult, "Failed to get surface format count");
                if (countResult != VK12.VK_SUCCESS) {
                    throw new SurfaceException(
                            "Unexpected Vulkan result "
                                    + countResult
                                    + " while getting surface format count");
                }

                int capacity = count.get(0);
                if (capacity <= 0) {
                    throw new SurfaceException("Surface reported no supported formats");
                }
                VkSurfaceFormatKHR.Buffer formats =
                        VkSurfaceFormatKHR.calloc(capacity, stack);
                int formatsResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                        this.device.vkDevice().getPhysicalDevice(),
                        this.surface,
                        count,
                        formats);
                if (formatsResult == VK12.VK_INCOMPLETE) {
                    continue;
                }
                VulkanGpuSurface.throwIfFailure(
                        formatsResult, "Failed to enumerate surface formats");
                if (formatsResult != VK12.VK_SUCCESS) {
                    throw new SurfaceException(
                            "Unexpected Vulkan result "
                                    + formatsResult
                                    + " while enumerating surface formats");
                }

                int returned = count.get(0);
                if (returned <= 0 || returned > capacity) {
                    throw new SurfaceException(
                            "Surface returned an invalid format count " + returned);
                }
                formats.limit(returned);
                VkSurfaceFormatKHR hdr = null;
                for (int index = 0; index < returned; index++) {
                    VkSurfaceFormatKHR candidate = formats.get(index);
                    if (candidate.format() == VK12.VK_FORMAT_R16G16B16A16_SFLOAT
                            && candidate.colorSpace()
                                    == EXTSwapchainColorspace
                                            .VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
                        hdr = candidate;
                        break;
                    }
                }
                WindowsHdrDisplay.Snapshot display =
                        WindowsHdrDisplay.query(this.prime$window);
                boolean hdrSupported = hdr != null
                        && display.available()
                        && display.hdrActive();
                HdrOutput.updateCapability(
                        hdrSupported,
                        display.maximumNits(),
                        display.sdrWhiteNits());
                VkSurfaceFormatKHR selected;
                try {
                    selected = HdrOutput.requested()
                                    && hdrSupported
                                    && HdrPresentation.available()
                            ? hdr
                            : this.pickSwapchainSurfaceFormat(formats);
                } catch (IllegalStateException exception) {
                    throw new SurfaceException(exception);
                }
                this.prime$swapchainImageFormat = selected.format();
                this.prime$swapchainColorSpace = selected.colorSpace();
                return;
            }
        }
        throw new SurfaceException("Surface formats kept changing during enumeration");
    }

    @Inject(method = "configure", at = @At("TAIL"))
    private void prime$publishSwapchainConfiguration(
            GpuSurface.Configuration configuration,
            CallbackInfo callbackInfo) {
        StreamlineFrameGeneration.onSwapchainConfigured(
                this.prime$swapchainImageFormat,
                this.swapchainImages.size());
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageFormat(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
            index = 0)
    private int prime$useCurrentSurfaceFormat(int cachedFormat) {
        return this.prime$swapchainImageFormat;
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
            index = 0)
    private int prime$useCurrentColorSpace(int cachedColorSpace) {
        return this.prime$swapchainColorSpace;
    }

    @WrapMethod(method = "blitFromTexture")
    private void prime$presentHdr(
            CommandEncoderBackend encoderBackend,
            GpuTextureView source,
            Operation<Void> original) {
        if (this.prime$swapchainImageFormat != VK12.VK_FORMAT_R16G16B16A16_SFLOAT
                || this.prime$swapchainColorSpace
                        != EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
            original.call(encoderBackend, source);
            return;
        }
        if (this.swapchainOutOfDate) {
            throw new IllegalStateException("Attempt to use out of date HDR swapchain");
        }
        if (this.currentImageIndex < 0) {
            throw new IllegalStateException("HDR swapchain image has not been acquired");
        }
        if (!(encoderBackend instanceof VulkanCommandEncoder encoder)
                || !(source instanceof VulkanGpuTextureView vulkanSource)) {
            throw new IllegalArgumentException("HDR presentation requires Vulkan resources");
        }
        int width = Math.min(this.swapchainWidth, source.getWidth(0));
        int height = Math.min(this.swapchainHeight, source.getHeight(0));
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        VulkanImage hdrComposite = HdrPresentation.record(
                commandBuffer, vulkanSource, width, height);
        if (hdrComposite == null) {
            throw new IllegalStateException(
                    "HDR swapchain is active without a Prime presentation context");
        }
        long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
        this.prime$prepareSwapchainForBlit(commandBuffer, swapchainImage);
        this.prime$blitToSwapchain(
                commandBuffer, hdrComposite, swapchainImage, width, height);
        this.prime$prepareSwapchainForPresent(commandBuffer, swapchainImage);
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer),
                "end Prime HDR surface command buffer");
        encoder.waitSemaphore(
                this.acquireSemaphores[this.currentAcquireSemaphore],
                0L,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        encoder.execute(commandBuffer);
        encoder.signalSemaphore(
                this.presentSemaphores[this.currentImageIndex],
                0L,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
    }

    @Unique
    private void prime$prepareSwapchainForBlit(
            VkCommandBuffer commandBuffer, long swapchainImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(0L)
                    .srcAccessMask(0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchainImage);
            prime$colorRange(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barrier));
        }
    }

    @Unique
    private void prime$blitToSwapchain(
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            long swapchainImage,
            int width,
            int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkOffset3D.Buffer sourceOffsets = VkOffset3D.calloc(2, stack);
            sourceOffsets.get(0).set(0, 0, 0);
            sourceOffsets.get(1).set(width, height, 1);
            VkOffset3D.Buffer destinationOffsets = VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, height, 0);
            destinationOffsets.get(1).set(width, 0, 1);
            VkImageSubresourceLayers sourceLayers = VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkImageSubresourceLayers destinationLayers = VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack)
                    .srcSubresource(sourceLayers)
                    .srcOffsets(sourceOffsets)
                    .dstSubresource(destinationLayers)
                    .dstOffsets(destinationOffsets);
            VK12.vkCmdBlitImage(
                    commandBuffer,
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    swapchainImage,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit,
                    VK12.VK_FILTER_NEAREST);
        }
    }

    @Unique
    private void prime$prepareSwapchainForPresent(
            VkCommandBuffer commandBuffer, long swapchainImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer image = VkImageMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .dstAccessMask(0L)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(swapchainImage);
            prime$colorRange(image);
            VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .dstAccessMask(
                            VK12.VK_ACCESS_MEMORY_READ_BIT
                                    | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pMemoryBarriers(memory)
                            .pImageMemoryBarriers(image));
        }
    }

    @Unique
    private static void prime$colorRange(VkImageMemoryBarrier2.Buffer barrier) {
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }
}
