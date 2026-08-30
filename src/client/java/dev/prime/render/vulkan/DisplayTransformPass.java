package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.ReinhardGamutOutput;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Prime's common linear Rec.2020 HDR to selectable sRGB Rec.709 display boundary. */
public final class DisplayTransformPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 20;
    private static final int LOCAL_SIZE = 8;

    private final VulkanContext context;
    private final SharedComputeProgram program;
    private final AutoExposurePass autoExposure;
    private final VulkanBuffer exposureState;
    private final VulkanImage hdrOutput;
    private final long descriptorPool;
    private final long descriptorSet;
    private final int width;
    private final int height;
    private boolean destroyed;

    private DisplayTransformPass(
            VulkanContext context,
            SharedComputeProgram program,
            AutoExposurePass autoExposure,
            VulkanBuffer exposureState,
            VulkanImage hdrOutput,
            long descriptorPool,
            long descriptorSet,
            int width,
            int height) {
        this.context = context;
        this.program = program;
        this.autoExposure = autoExposure;
        this.exposureState = exposureState;
        this.hdrOutput = hdrOutput;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
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
                meteringGuide.materialClass(),
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
            VulkanImage materialClass,
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
        long descriptorPool = 0L;
        VulkanImage hdrOutput = null;
        AutoExposurePass autoExposure = frozenExposure == null
                ? AutoExposurePass.create(
                        context,
                        linearInput,
                        java.util.Objects.requireNonNull(albedo, "albedo"),
                        java.util.Objects.requireNonNull(materialClass, "materialClass"),
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
                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
                poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(1);
                poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
                poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
                descriptorPool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        poolSizes,
                        "create common display-transform descriptor pool");
                long descriptorSet = VulkanDescriptors.allocateSet(
                        context,
                        stack,
                        descriptorPool,
                        program.descriptorSetLayout(),
                        "allocate common display-transform descriptor set");
                VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(3, stack);
                imageInfos.get(0).imageView(linearInput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(1).imageView(displayOutput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(2).imageView(hdrOutput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer exposureInfo =
                        VkDescriptorBufferInfo.calloc(1, stack);
                exposureInfo.get(0)
                        .buffer(exposureState.handle())
                        .offset(0L)
                        .range(AutoExposurePass.EXPOSURE_STATE_SIZE);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
                writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(0).address(), 1));
                writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(1).address(), 1));
                writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(exposureInfo);
                writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(2).address(), 1));
                VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                return new DisplayTransformPass(
                        context,
                        program,
                        autoExposure,
                        exposureState,
                        hdrOutput,
                        descriptorPool,
                        descriptorSet,
                        displayOutput.width(),
                        displayOutput.height());
            }
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
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
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipeline(0));
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipelineLayout(),
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.program.pipelineLayout(), COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE),
                    1);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        this.program.release();
        this.hdrOutput.destroy();
        if (this.autoExposure != null) {
            this.autoExposure.destroy();
        }
    }
}
