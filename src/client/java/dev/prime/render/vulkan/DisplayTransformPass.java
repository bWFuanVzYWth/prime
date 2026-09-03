package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.ReinhardGamutOutput;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Prime's common linear Rec.2020 HDR to selectable sRGB Rec.709 display boundary. */
public final class DisplayTransformPass implements Destroyable {
    private static final int PUSH_SIZE = 20;
    private static final int LOCAL_SIZE = 8;

    private final SharedComputeProgram program;
    private final AutoExposurePass autoExposure;
    private final VulkanBuffer exposureState;
    private final VulkanImage hdrOutput;
    private final BoundSet descriptors;
    private final int width;
    private final int height;
    private boolean destroyed;

    private DisplayTransformPass(
            SharedComputeProgram program,
            AutoExposurePass autoExposure,
            VulkanBuffer exposureState,
            VulkanImage hdrOutput,
            BoundSet descriptors,
            int width,
            int height) {
        this.program = program;
        this.autoExposure = autoExposure;
        this.exposureState = exposureState;
        this.hdrOutput = hdrOutput;
        this.descriptors = descriptors;
        this.width = width;
        this.height = height;
    }

    public static DisplayTransformPass createRealtime(
            VulkanContext context,
            VulkanImage linearInput,
            RawWavefrontFrame meteringGuide,
            VulkanImage displayOutput) {
        return create(
                context,
                linearInput,
                meteringGuide.material(),
                meteringGuide.reconstructionControl(),
                displayOutput,
                false,
                null);
    }

    public static DisplayTransformPass createOffline(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanBuffer frozenExposure,
            VulkanImage displayOutput) {
        return create(
                context,
                linearInput,
                null,
                null,
                displayOutput,
                false,
                java.util.Objects.requireNonNull(frozenExposure, "frozenExposure"));
    }

    private static DisplayTransformPass create(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanImage albedo,
            VulkanImage reconstructionControl,
            VulkanImage displayOutput,
            boolean accumulatedMetering,
            VulkanBuffer frozenExposure) {
        if (linearInput.width() != displayOutput.width()
                || linearInput.height() != displayOutput.height()) {
            throw new IllegalArgumentException("Display transform input and output extents differ");
        }
        if (frozenExposure != null
                && frozenExposure.size() < AutoExposurePass.EXPOSURE_STATE_SIZE) {
            throw new IllegalArgumentException("Frozen exposure state is incomplete");
        }
        BoundSet descriptors = null;
        VulkanImage hdrOutput = null;
        AutoExposurePass autoExposure = frozenExposure == null
                ? AutoExposurePass.create(
                        context,
                        linearInput,
                        java.util.Objects.requireNonNull(albedo, "albedo"),
                        java.util.Objects.requireNonNull(
                                reconstructionControl, "reconstructionControl"),
                        accumulatedMetering)
                : null;
        VulkanBuffer exposureState = frozenExposure == null
                ? autoExposure.exposureState()
                : frozenExposure;
        SharedComputeProgram program = null;
        try {
            hdrOutput = context.createImage2D(
                    displayOutput.width(),
                    displayOutput.height(),
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime HDR display output");
            program = context.acquireDisplayTransformProgram();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                descriptors = VulkanDescriptors.bind(
                        context,
                        stack,
                        program.descriptorSetLayout(),
                        "display-transform",
                        VulkanDescriptors.image(
                                0, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                linearInput.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                displayOutput.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.buffer(
                                2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                exposureState.handle(), 0L,
                                AutoExposurePass.EXPOSURE_STATE_SIZE),
                        VulkanDescriptors.image(
                                3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                hdrOutput.view(), VK12.VK_IMAGE_LAYOUT_GENERAL));
                return new DisplayTransformPass(
                        program,
                        autoExposure,
                        exposureState,
                        hdrOutput,
                        descriptors,
                        displayOutput.width(),
                        displayOutput.height());
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(descriptors, exception);
            if (program != null) {
                program.release();
            }
            ResourceCleanup.destroy(hdrOutput, exception);
            ResourceCleanup.destroy(autoExposure, exception);
            throw exception;
        }
    }

    public VulkanBuffer exposureState() {
        return this.exposureState;
    }

    public VulkanImage hdrOutput() {
        return this.hdrOutput;
    }

    public void record(
            VkCommandBuffer commandBuffer,
            float deltaSeconds,
            boolean reset,
            boolean instant,
            DisplaySettings.Snapshot display,
            VulkanImageInitializationBatch initialization) {
        java.util.Objects.requireNonNull(display, "display");
        if (this.autoExposure == null) {
            throw new IllegalStateException("Frozen display transform cannot adapt exposure");
        }
        this.autoExposure.record(
                commandBuffer,
                this.width,
                this.height,
                deltaSeconds,
                reset,
                instant,
                display.autoExposureCompensation());
        this.recordDisplay(commandBuffer, display, initialization);
    }

    public void recordFrozen(
            VkCommandBuffer commandBuffer,
            DisplaySettings.Snapshot display,
            VulkanImageInitializationBatch initialization) {
        java.util.Objects.requireNonNull(display, "display");
        if (this.autoExposure != null) {
            throw new IllegalStateException("Adaptive display transform requires exposure update");
        }
        this.recordDisplay(commandBuffer, display, initialization);
    }

    private void recordDisplay(
            VkCommandBuffer commandBuffer,
            DisplaySettings.Snapshot display,
            VulkanImageInitializationBatch initialization) {
        java.util.Objects.requireNonNull(initialization, "initialization");
        VulkanImageTransitions.prepareOutputForComposite(
                commandBuffer, initialization, this.hdrOutput);
        ReinhardGamutOutput.Parameters reinhard =
                ReinhardGamutOutput.parameters(HdrOutput.activeHeadroom());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            push.putFloat(8, display.finalExposureMultiplier());
            push.putFloat(12, reinhard.outputPeak());
            push.putFloat(16, reinhard.curvePeak());
            this.program.dispatch(
                    commandBuffer,
                    stack,
                    this.descriptors.handle(),
                    push,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE));
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.descriptors.destroy();
        this.program.release();
        this.hdrOutput.destroy();
        if (this.autoExposure != null) {
            this.autoExposure.destroy();
        }
    }
}
