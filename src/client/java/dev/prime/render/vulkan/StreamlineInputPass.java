package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.FrameCamera;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Builds Streamline depth and motion without changing Prime's top-left image coordinates. */
public final class StreamlineInputPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE;
    private static final int HISTORY_VALID_OFFSET = 136;
    private static final int TRANSMISSIVE_HISTORY_EXACT_OFFSET = 140;

    private final SharedComputeProgram program;
    private final VulkanImage sourceDepth;
    private final VulkanImage sourceVisibleHistoryPosition;
    private final VulkanImage sourceControl;
    private final VulkanImage depth;
    private final VulkanImage motion;
    private final BoundSet descriptors;
    private final boolean exactTransmissiveHistory;
    private final Matrix4f currentClipToWorld = new Matrix4f();
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private final Matrix4f worldToViewScratch = new Matrix4f();
    private boolean guidesInitialized;
    private boolean destroyed;

    private StreamlineInputPass(
            SharedComputeProgram program,
            VulkanImage sourceDepth,
            VulkanImage sourceVisibleHistoryPosition,
            VulkanImage sourceControl,
            VulkanImage depth,
            VulkanImage motion,
            BoundSet descriptors,
            boolean exactTransmissiveHistory) {
        this.program = program;
        this.sourceDepth = sourceDepth;
        this.sourceVisibleHistoryPosition = sourceVisibleHistoryPosition;
        this.sourceControl = sourceControl;
        this.depth = depth;
        this.motion = motion;
        this.descriptors = descriptors;
        this.exactTransmissiveHistory = exactTransmissiveHistory;
    }

    public static StreamlineInputPass create(
            VulkanContext context,
            VulkanImage depth,
            VulkanImage visibleHistoryPosition,
            VulkanImage control,
            boolean exactTransmissiveHistory) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(visibleHistoryPosition, "visible history position");
        Objects.requireNonNull(control, "control");
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
        SharedComputeProgram program = null;
        VulkanImage streamlineDepth = null;
        VulkanImage streamlineMotion = null;
        BoundSet descriptors = null;
        try {
            program = context.acquireStreamlineInputProgram();
            streamlineDepth = context.createImage2D(
                    depth.width(),
                    depth.height(),
                    VK12.VK_FORMAT_R32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline reversed depth");
            streamlineMotion = context.createImage2D(
                    visibleHistoryPosition.width(),
                    visibleHistoryPosition.height(),
                    VK12.VK_FORMAT_R32G32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime Streamline top-left motion");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                descriptors = VulkanDescriptors.bind(
                        context,
                        stack,
                        program.descriptorSetLayout(),
                        "Streamline input",
                        VulkanDescriptors.image(
                                0, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                depth.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                1, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                visibleHistoryPosition.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                control.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                streamlineDepth.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.image(
                                4, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                streamlineMotion.view(), VK12.VK_IMAGE_LAYOUT_GENERAL));
                return new StreamlineInputPass(
                        program,
                        depth,
                        visibleHistoryPosition,
                        control,
                        streamlineDepth,
                        streamlineMotion,
                        descriptors,
                        exactTransmissiveHistory);
            }
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.destroy(descriptors, exception);
            failure = ResourceCleanup.destroy(streamlineMotion, failure);
            failure = ResourceCleanup.destroy(streamlineDepth, failure);
            SharedComputeProgram acquiredProgram = program;
            if (acquiredProgram != null) {
                failure = ResourceCleanup.run(acquiredProgram::release, failure);
            }
            throw failure;
        }
    }

    public VulkanImage depth() {
        return this.depth;
    }

    public VulkanImage motion() {
        return this.motion;
    }

    public boolean matches(
            VulkanImage depth,
            VulkanImage visibleHistoryPosition,
            VulkanImage control,
            boolean exactTransmissiveHistory) {
        return this.sourceDepth == depth
                && this.sourceVisibleHistoryPosition == visibleHistoryPosition
                && this.sourceControl == control
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
            this.program.dispatch(
                    commandBuffer,
                    stack,
                    this.descriptors.handle(),
                    push,
                    DispatchMath.divideRoundUp(this.depth.width(), LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.depth.height(), LOCAL_SIZE));
        }
        VulkanSync.memoryBarrier(
                commandBuffer,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT);
        this.guidesInitialized = true;
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

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Streamline input pass is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.descriptors.destroy();
        this.program.release();
        this.motion.destroy();
        this.depth.destroy();
    }
}
