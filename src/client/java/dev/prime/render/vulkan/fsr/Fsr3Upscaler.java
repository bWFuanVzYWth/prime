package dev.prime.render.vulkan.fsr;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.fsr.FsrDispatchPlan;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanSync;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * FidelityFX FSR upscaling through AMD's signed Vulkan DLL.
 *
 * <p>The native library owns the complete FSR 3.1 pipeline and its temporal resources. Prime keeps
 * ownership of all external images and records the DLL dispatch into Minecraft's command buffer.
 * Only the final linear Rec.2020 to sRGB display transform remains a Prime shader, because that is
 * an application color-management boundary rather than part of FSR.
 */
public final class Fsr3Upscaler implements Destroyable {
    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final VulkanImage sceneColor;
    private final VulkanImage inputMotion;
    private final VulkanImage inputDepth;
    private final VulkanImage reactiveMask;
    private final VulkanImage transparencyCompositionMask;
    private final VulkanImage linearOutput;
    private final FsrNative.Instance nativeInstance;
    private final DisplayTransformPass displayPass;

    private boolean destroyed;

    private Fsr3Upscaler(
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            VulkanImage sceneColor,
            VulkanImage inputMotion,
            VulkanImage inputDepth,
            VulkanImage reactiveMask,
            VulkanImage transparencyCompositionMask,
            VulkanImage linearOutput,
            FsrNative.Instance nativeInstance,
            DisplayTransformPass displayPass) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.sceneColor = sceneColor;
        this.inputMotion = inputMotion;
        this.inputDepth = inputDepth;
        this.reactiveMask = reactiveMask;
        this.transparencyCompositionMask = transparencyCompositionMask;
        this.linearOutput = linearOutput;
        this.nativeInstance = nativeInstance;
        this.displayPass = displayPass;
    }

    public static Fsr3Upscaler create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            VulkanImage sceneColor,
            VulkanImage inputMotion,
            VulkanImage inputDepth,
            VulkanImage reactiveMask,
            VulkanImage transparencyCompositionMask,
            RawWavefrontFrame meteringGuide,
            VulkanImage displayOutput) {
        requireExtent(sceneColor, renderWidth, renderHeight, "scene color");
        requireExtent(inputMotion, renderWidth, renderHeight, "motion vectors");
        requireExtent(inputDepth, renderWidth, renderHeight, "depth");
        requireExtent(reactiveMask, renderWidth, renderHeight, "reactive mask");
        requireExtent(
                transparencyCompositionMask,
                renderWidth,
                renderHeight,
                "transparency/composition mask");
        requireExtent(displayOutput, displayWidth, displayHeight, "display output");

        VulkanImage linearOutput = null;
        FsrNative.Instance nativeInstance = null;
        DisplayTransformPass displayPass = null;
        try {
            linearOutput = context.createImage2D(
                    displayWidth,
                    displayHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                    "Prime FidelityFX linear HDR output");
            nativeInstance = FsrNative.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
            displayPass = DisplayTransformPass.createRealtime(
                    context, linearOutput, meteringGuide, displayOutput);
            return new Fsr3Upscaler(
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sceneColor,
                    inputMotion,
                    inputDepth,
                    reactiveMask,
                    transparencyCompositionMask,
                    linearOutput,
                    nativeInstance,
                    displayPass);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(displayPass, exception);
            ResourceCleanup.close(nativeInstance, exception);
            ResourceCleanup.destroy(linearOutput, exception);
            throw exception;
        }
    }

    public VulkanImage linearOutput() {
        return this.linearOutput;
    }

    public VulkanImage hdrDisplayOutput() {
        return this.displayPass.hdrOutput();
    }

    public long displayExposureStateBuffer() {
        return this.displayPass.exposureState().handle();
    }

    public FrameToken beginFrame(ReconstructionFrameParameters parameters) {
        this.requireOpen();
        return new FrameToken(this, new SubmittedFrame<>(parameters));
    }

    public void record(
            VkCommandBuffer commandBuffer,
            FrameToken token,
            VulkanImageInitializationBatch initialization) {
        this.requireOpen();
        if (token.owner != this) {
            throw new IllegalArgumentException("FSR frame token does not belong to this recording");
        }
        ReconstructionFrameParameters planned = token.parameters.plan();
        FsrDispatchPlan dispatchPlan = FsrDispatchPlan.create(
                planned.camera(),
                this.renderWidth,
                this.renderHeight,
                this.displayWidth,
                this.displayHeight,
                planned.jitter(),
                planned.deltaMilliseconds(),
                planned.reset());
        ReconstructionFrameParameters parameters = token.parameters.claimForExecution();
        if (parameters != planned) {
            throw new IllegalStateException(
                    "FSR temporal plan changed between planning and execution");
        }

        // The single NRD composite writes every imported input immediately before FSR.
        // The DLL owns its internal barriers, but this external producer/consumer dependency is
        // Prime's responsibility and must remain explicit.
        computeBarrier(commandBuffer);
        this.initializeLinearOutput(commandBuffer, initialization);
        this.nativeInstance.dispatch(
                commandBuffer,
                new FsrNative.Dispatch(
                        this.sceneColor,
                        this.inputDepth,
                        this.inputMotion,
                        this.reactiveMask,
                        this.transparencyCompositionMask,
                        this.linearOutput,
                        dispatchPlan));

        // FidelityFX restores imported resources to UNORDERED_ACCESS/GENERAL. Its output writes
        // still need an execution and memory dependency before Prime's display-transform shader
        // reads the same image.
        computeBarrier(commandBuffer);
        this.displayPass.record(
                commandBuffer,
                parameters.deltaMilliseconds() * 0.001F,
                parameters.reset(),
                false,
                parameters.display(),
                initialization);
    }

    /** Must be called immediately after the command buffer containing {@code token} is submitted. */
    public void submitted(FrameToken token) {
        this.requireOpen();
        if (token.owner != this) {
            throw new IllegalArgumentException("FSR frame token does not belong to this submission");
        }
        token.parameters.submitted();
    }

    public void abandon(FrameToken token) {
        this.requireOpen();
        if (token.owner != this) {
            throw new IllegalArgumentException(
                    "FSR frame token does not belong to this upscaler");
        }
        token.parameters.abandon();
    }

    private void initializeLinearOutput(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        if (initialization.prepare(this.linearOutput)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(this.linearOutput.image());
            barrier.get(0).subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void computeBarrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void requireExtent(
            VulkanImage image, int expectedWidth, int expectedHeight, String name) {
        Objects.requireNonNull(image, name);
        if (image.width() != expectedWidth || image.height() != expectedHeight) {
            throw new IllegalArgumentException(
                    "FSR "
                            + name
                            + " extent is "
                            + image.width()
                            + "x"
                            + image.height()
                            + ", expected "
                            + expectedWidth
                            + "x"
                            + expectedHeight);
        }
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("FSR upscaler has been destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.displayPass, failure);
        failure = ResourceCleanup.close(this.nativeInstance, failure);
        failure = ResourceCleanup.destroy(this.linearOutput, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    /** Immutable temporal inputs chosen before ray generation plus submission bookkeeping. */
    public static final class FrameToken {
        private final Fsr3Upscaler owner;
        private final SubmittedFrame<ReconstructionFrameParameters> parameters;

        private FrameToken(
                Fsr3Upscaler owner,
                SubmittedFrame<ReconstructionFrameParameters> parameters) {
            this.owner = owner;
            this.parameters = parameters;
        }

        public ReconstructionFrameParameters parameters() {
            return this.parameters.plan();
        }
    }
}
