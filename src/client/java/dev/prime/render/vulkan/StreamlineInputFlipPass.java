package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.FrameCamera;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Builds persistent NVIDIA-coordinate visible depth, motion and color inputs for Streamline. */
public final class StreamlineInputFlipPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE;
    private static final int HISTORY_VALID_OFFSET = 136;
    private static final int TRANSMISSIVE_HISTORY_EXACT_OFFSET = 140;

    private final VulkanContext context;
    private final SharedComputeProgram program;
    private final VulkanImage sourceDepth;
    private final VulkanImage sourceVisibleHistoryPosition;
    private final VulkanImage sourceControl;
    private final VulkanImage sourceColor;
    private final VulkanImage depth;
    private final VulkanImage motion;
    private final VulkanImage color;
    private final long descriptorPool;
    private final long descriptorSet;
    private final boolean exactTransmissiveHistory;
    private final Matrix4f currentClipToWorld = new Matrix4f();
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private final Matrix4f worldToViewScratch = new Matrix4f();
    private boolean guidesInitialized;
    private boolean colorInitialized;
    private boolean destroyed;

    private StreamlineInputFlipPass(
            VulkanContext context,
            SharedComputeProgram program,
            VulkanImage sourceDepth,
            VulkanImage sourceVisibleHistoryPosition,
            VulkanImage sourceControl,
            VulkanImage sourceColor,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage color,
            long descriptorPool,
            long descriptorSet,
            boolean exactTransmissiveHistory) {
        this.context = context;
        this.program = program;
        this.sourceDepth = sourceDepth;
        this.sourceVisibleHistoryPosition = sourceVisibleHistoryPosition;
        this.sourceControl = sourceControl;
        this.sourceColor = sourceColor;
        this.depth = depth;
        this.motion = motion;
        this.color = color;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.exactTransmissiveHistory = exactTransmissiveHistory;
    }

    public static StreamlineInputFlipPass create(
            VulkanContext context,
            VulkanImage depth,
            VulkanImage visibleHistoryPosition,
            VulkanImage control,
            VulkanImage color,
            boolean exactTransmissiveHistory) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(visibleHistoryPosition, "visible history position");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(color, "color");
        if (depth.width() != visibleHistoryPosition.width()
                || depth.height() != visibleHistoryPosition.height()
                || depth.width() != control.width()
                || depth.height() != control.height()) {
            throw new IllegalArgumentException("Streamline guide extents differ");
        }
        if ((depth.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0
                || (visibleHistoryPosition.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0
                || (control.usage() & VK12.VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Streamline guide images have incompatible Vulkan usage");
        }
        if (depth.format() != VK12.VK_FORMAT_R32_SFLOAT
                || visibleHistoryPosition.format()
                        != VK12.VK_FORMAT_R32G32B32A32_SFLOAT
                || control.format() != VK12.VK_FORMAT_R8_UINT) {
            throw new IllegalArgumentException("Streamline guide formats violate their contract");
        }
        requireTransferSource(color, "color");

        SharedComputeProgram program = null;
        VulkanImage flippedDepth = null;
        VulkanImage flippedMotion = null;
        VulkanImage flippedColor = null;
        long descriptorPool = 0L;
        try {
            program = context.acquireStreamlineInputProgram();
            flippedDepth = context.createImage2D(
                    depth.width(),
                    depth.height(),
                    VK12.VK_FORMAT_R32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline reversed depth");
            flippedMotion = context.createImage2D(
                    visibleHistoryPosition.width(),
                    visibleHistoryPosition.height(),
                    VK12.VK_FORMAT_R32G32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline top-left motion");
            flippedColor = createColorDestination(context, color);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(2);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(3);
                descriptorPool = VulkanDescriptors.createPool(
                        context,
                        stack,
                        1,
                        sizes,
                        "create Streamline input descriptor pool");
                long descriptorSet = VulkanDescriptors.allocateSet(
                        context,
                        stack,
                        descriptorPool,
                        program.descriptorSetLayout(),
                        "allocate Streamline input descriptor set");
                VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(5, stack);
                infos.get(0).imageView(depth.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(1)
                        .imageView(visibleHistoryPosition.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(2).imageView(control.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(3).imageView(flippedDepth.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                infos.get(4).imageView(flippedMotion.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
                for (int binding = 0; binding < 5; binding++) {
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(binding)
                            .descriptorCount(1)
                            .descriptorType(binding < 2
                                    ? VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                                    : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    infos.get(binding).address(), 1));
                }
                VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                return new StreamlineInputFlipPass(
                        context,
                        program,
                        depth,
                        visibleHistoryPosition,
                        control,
                        color,
                        flippedDepth,
                        flippedMotion,
                        flippedColor,
                        descriptorPool,
                        descriptorSet,
                        exactTransmissiveHistory);
            }
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            ResourceCleanup.destroy(flippedColor, exception);
            ResourceCleanup.destroy(flippedMotion, exception);
            ResourceCleanup.destroy(flippedDepth, exception);
            if (program != null) program.release();
            throw exception;
        }
    }

    public VulkanImage depth() {
        return this.depth;
    }

    public VulkanImage motion() {
        return this.motion;
    }

    public VulkanImage color() {
        return this.color;
    }

    public boolean matches(
            VulkanImage depth,
            VulkanImage visibleHistoryPosition,
            VulkanImage control,
            VulkanImage color,
            boolean exactTransmissiveHistory) {
        return this.sourceDepth == depth
                && this.sourceVisibleHistoryPosition == visibleHistoryPosition
                && this.sourceControl == control
                && this.sourceColor == color
                && this.exactTransmissiveHistory == exactTransmissiveHistory;
    }

    public void recordGuides(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            FrameCamera previousCamera,
            SubpixelJitter jitter,
            boolean historyValid) {
        requireOpen();
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(previousCamera, "previous camera");
        Objects.requireNonNull(jitter, "jitter");
        NrdCameraTransform.currentClipToWorld(camera, this.currentClipToWorld);
        NrdCameraTransform.previousWorldToClip(
                camera,
                previousCamera,
                this.previousWorldToClip,
                this.worldToViewScratch);
        prepareSampledSource(commandBuffer, this.sourceDepth);
        prepareSampledSource(commandBuffer, this.sourceVisibleHistoryPosition);
        prepareStorageSource(commandBuffer, this.sourceControl);
        prepareStorageOutput(commandBuffer, this.depth);
        prepareStorageOutput(commandBuffer, this.motion);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            writeReprojectionConstants(
                    push,
                    this.currentClipToWorld,
                    this.previousWorldToClip,
                    jitter,
                    historyValid,
                    this.exactTransmissiveHistory);
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
                    commandBuffer,
                    this.program.pipelineLayout(),
                    COMPUTE_STAGE,
                    0,
                    push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    DispatchMath.divideRoundUp(this.depth.width(), LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.depth.height(), LOCAL_SIZE),
                    1);
        }
        VulkanSync.memoryBarrier(
                commandBuffer,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
        this.guidesInitialized = true;
    }

    public void recordColor(VkCommandBuffer commandBuffer) {
        requireOpen();
        recordColorFlip(commandBuffer, this.sourceColor, this.color);
        this.colorInitialized = true;
    }

    private static VulkanImage createColorDestination(
            VulkanContext context, VulkanImage source) {
        return context.createImage2D(
                source.width(),
                source.height(),
                source.format(),
                VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                "Prime Streamline flipped HUD-less color");
    }

    private static void requireTransferSource(VulkanImage image, String name) {
        if ((image.usage() & VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Streamline " + name + " image is missing transfer-source usage");
        }
    }

    private static void prepareSampledSource(
            VkCommandBuffer commandBuffer, VulkanImage source) {
        VulkanSync.imageBarrier(
                commandBuffer,
                source.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void prepareStorageSource(
            VkCommandBuffer commandBuffer, VulkanImage source) {
        VulkanSync.imageBarrier(
                commandBuffer,
                source.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void writeReprojectionConstants(
            ByteBuffer target,
            Matrix4f currentClipToWorld,
            Matrix4f previousWorldToClip,
            SubpixelJitter jitter,
            boolean historyValid,
            boolean exactTransmissiveHistory) {
        currentClipToWorld.get(
                ShaderAbi.NRD_MOTION_PUSH_CURRENT_CLIP_TO_WORLD_OFFSET,
                target);
        previousWorldToClip.get(
                ShaderAbi.NRD_MOTION_PUSH_PREVIOUS_WORLD_TO_CLIP_OFFSET,
                target);
        int jitterOffset = ShaderAbi.NRD_MOTION_PUSH_CURRENT_JITTER_PIXELS_OFFSET;
        target.putFloat(jitterOffset, jitter.x());
        target.putFloat(jitterOffset + Float.BYTES, jitter.y());
        target.putInt(HISTORY_VALID_OFFSET, historyValid ? 1 : 0);
        target.putInt(
                TRANSMISSIVE_HISTORY_EXACT_OFFSET,
                exactTransmissiveHistory ? 1 : 0);
    }

    private void prepareStorageOutput(
            VkCommandBuffer commandBuffer, VulkanImage destination) {
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                this.guidesInitialized
                        ? VK12.VK_IMAGE_LAYOUT_GENERAL
                        : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                this.guidesInitialized
                        ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.guidesInitialized
                        ? VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT
                        : 0L,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void recordColorFlip(
            VkCommandBuffer commandBuffer, VulkanImage source, VulkanImage destination) {
        VulkanSync.imageBarrier(
                commandBuffer,
                source.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_READ_BIT);
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                this.colorInitialized
                        ? VK12.VK_IMAGE_LAYOUT_GENERAL
                        : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                this.colorInitialized
                        ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.colorInitialized ? VK12.VK_ACCESS_MEMORY_READ_BIT : 0L,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sourceOffsets = org.lwjgl.vulkan.VkOffset3D.calloc(2, stack);
            sourceOffsets.get(0).set(0, source.height(), 0);
            sourceOffsets.get(1).set(source.width(), 0, 1);
            var destinationOffsets = org.lwjgl.vulkan.VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, 0, 0);
            destinationOffsets.get(1).set(destination.width(), destination.height(), 1);
            var sourceLayers = org.lwjgl.vulkan.VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            var destinationLayers = org.lwjgl.vulkan.VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            var blit = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack)
                    .srcSubresource(sourceLayers)
                    .srcOffsets(sourceOffsets)
                    .dstSubresource(destinationLayers)
                    .dstOffsets(destinationOffsets);
            VK12.vkCmdBlitImage(
                    commandBuffer,
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    destination.image(),
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit,
                    VK12.VK_FILTER_NEAREST);
        }
        VulkanSync.imageBarrier(
                commandBuffer,
                destination.image(),
                VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Streamline input flip pass is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        this.program.release();
        this.color.destroy();
        this.motion.destroy();
        this.depth.destroy();
    }
}
