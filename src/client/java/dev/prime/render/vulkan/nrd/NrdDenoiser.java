package dev.prime.render.vulkan.nrd;

import dev.prime.render.vulkan.GeneratedShaderPrograms;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.post.nrd.NrdFrameInput;
import dev.prime.render.post.nrd.NrdFramePlan;
import dev.prime.render.post.SubmittedFrame;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.AerialEpipolarMapping;
import dev.prime.render.FrameCamera;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.SunDirection;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.ParallelPipelineCreation;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanShaderModules;
import dev.prime.render.vulkan.VulkanSync;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * Vulkan realization of NRD Core's API-independent dispatch descriptions.
 *
 * <p>NRD never owns or sees a Vulkan handle. Prime creates every image, pipeline, descriptor and
 * constant buffer, records all dispatches on the existing Minecraft command buffer and retires
 * frame bindings at the real queue completion point. This boundary is intentionally generic so a
 * later wavefront path scheduler can replace raygen without changing denoiser ownership.
 */
public final class NrdDenoiser implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    static final int MOTION_NRD_BINDING = 0;
    static final int MOTION_FSR_BINDING = 22;
    static final int MOTION_BINDING_COUNT = 23;
    // Wavefront resolve writes 65504 for a sky view-Z. Keep the valid range below that sentinel while
    // remaining far beyond Minecraft's usable terrain and Prime's 16,000-block aerial volume.
    private static final float DENOISING_RANGE = 60_000.0f;
    final VulkanContext context;
    private final int width;
    private final int height;
    private final NrdNative.Instance nativeInstance;
    final NrdNative.Description description;
    private final NrdImages images;
    private final RawWavefrontFrame rawFrame;
    private final PreparedNrdFrame preparedFrame;
    private final NrdCompositeFrame compositeFrame;
    private final AtmospherePipeline atmosphere;
    private final long nearestSampler;
    private final long linearSampler;
    final ComputePipeline[] pipelines;
    private final NrdInputPreparationPass inputPreparationPipeline;
    private final NrdCompositePass composite;
    private final Matrix4f currentNrdProjection = new Matrix4f();
    private final Matrix4f previousNrdProjection = new Matrix4f();
    private final Matrix4f previousWorldToView = new Matrix4f();
    private final NrdFrameBindingPool bindingPool;

    private boolean destroyed;

    private NrdDenoiser(
            VulkanContext context,
            int width,
            int height,
            NrdNative.Instance nativeInstance,
            NrdImages images,
            VulkanImage output,
            AtmospherePipeline atmosphere,
            long nearestSampler,
            long linearSampler,
            ComputePipeline[] pipelines,
            NrdInputPreparationPass inputPreparationPipeline,
            NrdCompositePass composite) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.nativeInstance = nativeInstance;
        this.description = nativeInstance.description();
        this.images = images;
        this.rawFrame = new RawSignals(images);
        this.preparedFrame = new PreparedNrdFrame(
                new PreparedNrdFrame.Branch(
                        images.motion,
                        images.normalRoughness,
                        images.viewZ,
                        images.noisyDiffuse,
                        images.noisySpecular,
                        images.noisyDiffuseSh1,
                        images.noisySpecularSh1),
                new PreparedNrdFrame.Branch(
                        images.reflectionMotion,
                        images.reflectionNormalRoughness,
                        images.reflectionViewZ,
                        images.reflectionNoisyDiffuse,
                        images.reflectionNoisySpecular,
                        images.reflectionNoisyDiffuseSh1,
                        images.reflectionNoisySpecularSh1),
                images.sunPenumbra,
                images.fsrDepth,
                images.fsrMotion);
        this.compositeFrame = new NrdCompositeFrame(
                output,
                images.fsrReactiveMask,
                images.fsrTransparencyCompositionMask);
        this.atmosphere = atmosphere;
        this.nearestSampler = nearestSampler;
        this.linearSampler = linearSampler;
        this.pipelines = pipelines;
        this.inputPreparationPipeline = inputPreparationPipeline;
        this.composite = composite;
        this.bindingPool = new NrdFrameBindingPool(this);
    }

    static <T> void validateMotionBindings(
            T[] descriptorImages, T nrdMotion, T fsrMotion) {
        if (descriptorImages.length != MOTION_BINDING_COUNT
                || descriptorImages[MOTION_NRD_BINDING] != nrdMotion
                || descriptorImages[MOTION_FSR_BINDING] != fsrMotion
                || nrdMotion == fsrMotion) {
            throw new IllegalStateException(
                    "NRD and FSR motion outputs must use distinct ABI bindings");
        }
    }

    public static NrdDenoiser create(
            VulkanContext context,
            int width,
            int height,
            VulkanImage output,
            VulkanImage stableAccumulation,
            AtmospherePipeline atmosphere) {
        String debugPrefix = "Prime NRD";
        NrdNative.Instance nativeInstance = NrdNative.create(width, height);
        NrdImages images = null;
        long nearestSampler = 0L;
        long linearSampler = 0L;
        ComputePipeline[] pipelines = null;
        NrdInputPreparationPass inputPreparationPipeline = null;
        NrdCompositePass composite = null;
        try {
            NrdNative.Description description = nativeInstance.description();
            validateNativeContract(description);
            images = NrdImages.create(
                    context,
                    width,
                    height,
                    description,
                    debugPrefix);
            nearestSampler = createSampler(context, false, debugPrefix + " nearest-clamp sampler");
            linearSampler = createSampler(context, true, debugPrefix + " linear-clamp sampler");
            pipelines = createPipelines(context, description, nearestSampler, linearSampler);
            inputPreparationPipeline = NrdInputPreparationPass.create(
                    context,
                    images,
                    GeneratedShaderPrograms.resource("nrd_motion"),
                    debugPrefix);
            composite = NrdCompositePass.create(
                    context, output, stableAccumulation, images, atmosphere);
            return new NrdDenoiser(
                    context,
                    width,
                    height,
                    nativeInstance,
                    images,
                    output,
                    atmosphere,
                    nearestSampler,
                    linearSampler,
                    pipelines,
                    inputPreparationPipeline,
                    composite);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(composite, exception);
            ResourceCleanup.destroy(inputPreparationPipeline, exception);
            destroyPipelines(pipelines, exception);
            if (linearSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), linearSampler, null);
            }
            if (nearestSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), nearestSampler, null);
            }
            ResourceCleanup.destroy(images, exception);
            ResourceCleanup.close(nativeInstance, exception);
            throw exception;
        }
    }

    public NrdCompositeFrame compositeFrame() {
        this.requireOpen();
        return this.compositeFrame;
    }

    public RawWavefrontFrame rawFrame() {
        return this.rawFrame;
    }

    public VulkanImage fsrMotion() {
        return this.images.fsrMotion;
    }

    public VulkanImage fsrDepth() {
        return this.images.fsrDepth;
    }

    public VulkanImage fsrReactiveMask() {
        return this.images.fsrReactiveMask;
    }

    public VulkanImage fsrTransparencyCompositionMask() {
        return this.images.fsrTransparencyCompositionMask;
    }

    /**
     * Makes the raw signal images writable by raygen and all NRD-owned images available to the
     * preparation and denoising compute passes. The GENERAL layout is stable for their complete
     * lifetime; only explicit availability and visibility dependencies change between stages.
     */
    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        this.requireOpen();
        VulkanImage[] allImages = this.images.allImages();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(allImages.length, stack);
            for (int index = 0; index < allImages.length; index++) {
                VulkanImage image = allImages[index];
                boolean initialized = initialization.prepare(image);
                barriers.get(index)
                        .sType$Default()
                        .srcStageMask(initialized ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT : 0L)
                        .dstStageMask(
                                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(index).subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
            }
            issueImageBarrier(commandBuffer, stack, barriers);
        }
    }

    /**
     * Records only Prime's raygen-to-NRD adapter and returns its typed output boundary.
     *
     * <p>The returned state is not history until {@link #submitted(FrameToken)} is called.
     */
    public PreparedFrame prepareInputs(
            VkCommandBuffer commandBuffer,
            SubmittedFrame<NrdFramePlan> frame) {
        this.requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        // Preparation may fail after commands are emitted. Never retry one semantic version.
        NrdFramePlan plan = Objects.requireNonNull(frame, "frame")
                .claimForExecution();
        NrdFrameInput input = plan.input();
        rayTraceToComputeBarrier(commandBuffer);
        PreparedNrdFrame prepared = this.inputPreparationPipeline.record(
                commandBuffer,
                input.camera(),
                plan.historyCamera(),
                this.width,
                this.height,
                input.cameraJitterX(),
                input.cameraJitterY(),
                this.preparedFrame);
        return new PreparedFrame(
                this,
                frame,
                prepared);
    }

    /** Records native NRD dispatches and Prime's composite from one prepared input version. */
    public FrameToken recordReconstruction(
            VkCommandBuffer commandBuffer,
            PreparedFrame frame,
            float sunRadianceMultiplier) {
        this.requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (frame.owner != this || frame.consumed) {
            throw new IllegalArgumentException(
                    "Prepared NRD frame does not belong to this reconstruction");
        }
        // A failure can occur after commands or native state have been emitted. Never allow the
        // same logical version to be recorded into another command buffer.
        frame.consumed = true;
        NrdFramePlan plan = frame.planned.plan();
        NrdFrameInput input = plan.input();
        this.nativeInstance.setFrameSettings(createFrameSettings(
                input.camera(),
                plan.historyCamera(),
                input.cameraJitterX(),
                input.cameraJitterY(),
                plan.historyJitterX(),
                plan.historyJitterY(),
                this.width,
                this.height,
                plan.frameIndex(),
                plan.restart(),
                plan.deltaMilliseconds(),
                false,
                input.sunDirection()));
        NrdNative.DispatchList dispatches = this.nativeInstance.getDispatches();
        NrdFrameBindings bindings = this.bindingPool.acquire(dispatches.size());
        try {
            computeToComputeBarrier(commandBuffer);
            bindings.prepare(dispatches, this, frame.inputs);
            for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                if (dispatchIndex != 0) {
                    computeToComputeBarrier(commandBuffer);
                }
                ComputePipeline pipeline = this.pipelines[dispatches.pipelineIndex(dispatchIndex)];
                VK12.vkCmdBindPipeline(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipeline.pipeline);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VK12.vkCmdBindDescriptorSets(
                            commandBuffer,
                            VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                            pipeline.pipelineLayout,
                            0,
                            stack.longs(
                                    bindings.resourceDescriptorSet(dispatchIndex),
                                    bindings.constantsDescriptorSet(dispatchIndex)),
                            null);
                }
                VK12.vkCmdDispatch(
                        commandBuffer,
                        dispatches.gridWidth(dispatchIndex),
                        dispatches.gridHeight(dispatchIndex),
                        1);
            }
            computeToComputeBarrier(commandBuffer);
            AerialEpipolarMapping.Epipole epipole =
                    this.atmosphere.aerialEpipole(
                            input.camera(),
                            input.sunDirection());
            this.composite.record(
                    commandBuffer,
                    this.width,
                    this.height,
                    sunRadianceMultiplier,
                    input.cameraJitterX(),
                    input.cameraJitterY(),
                    epipole.x(),
                    epipole.y());
            return new FrameToken(
                    this,
                    bindings,
                    frame.planned);
        } catch (RuntimeException exception) {
            this.bindingPool.recycle(bindings);
            throw exception;
        }
    }

    /** Must be called immediately after the command buffer containing {@code token} is submitted. */
    public SubmittedFrame<NrdFramePlan> submitted(FrameToken token) {
        this.requireOpen();
        if (token.owner != this || token.submitted || token.abandoned) {
            throw new IllegalArgumentException("NRD frame token does not belong to this submission");
        }
        token.submitted = true;
        this.context.afterSubmission(() -> this.bindingPool.recycle(token.bindings));
        return token.planned;
    }

    /** Returns bindings for reconstruction commands that were recorded but never submitted. */
    public void abandon(FrameToken token) {
        this.requireOpen();
        if (token.owner != this || token.submitted || token.abandoned) {
            throw new IllegalArgumentException(
                    "NRD frame token does not belong to this denoiser");
        }
        token.abandoned = true;
        this.bindingPool.recycle(token.bindings);
    }

    VulkanImage resolveResource(
            PreparedNrdFrame prepared,
            int resourceType,
            int indexInPool,
            int identifier) {
        boolean reflection = identifier == 2;
        return switch (resourceType) {
            case NrdNative.RESOURCE_IN_MV,
                    NrdNative.RESOURCE_IN_NORMAL_ROUGHNESS,
                    NrdNative.RESOURCE_IN_VIEWZ,
                    NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST,
                    NrdNative.RESOURCE_IN_SPEC_RADIANCE_HITDIST,
                    NrdNative.RESOURCE_IN_DIFF_SH0,
                    NrdNative.RESOURCE_IN_DIFF_SH1,
                    NrdNative.RESOURCE_IN_SPEC_SH0,
                    NrdNative.RESOURCE_IN_SPEC_SH1,
                    NrdNative.RESOURCE_IN_PENUMBRA ->
                    prepared.resolveInput(resourceType, identifier);
            case NrdNative.RESOURCE_OUT_DIFF_RADIANCE_HITDIST -> reflection ? this.images.reflectionDenoisedDiffuse : this.images.denoisedDiffuse;
            case NrdNative.RESOURCE_OUT_SPEC_RADIANCE_HITDIST -> reflection ? this.images.reflectionDenoisedSpecular : this.images.denoisedSpecular;
            case NrdNative.RESOURCE_OUT_DIFF_SH0 -> reflection ? this.images.reflectionDenoisedDiffuse : this.images.denoisedDiffuse;
            case NrdNative.RESOURCE_OUT_DIFF_SH1 -> reflection ? this.images.reflectionDenoisedDiffuseSh1 : this.images.denoisedDiffuseSh1;
            case NrdNative.RESOURCE_OUT_SPEC_SH0 -> reflection ? this.images.reflectionDenoisedSpecular : this.images.denoisedSpecular;
            case NrdNative.RESOURCE_OUT_SPEC_SH1 -> reflection ? this.images.reflectionDenoisedSpecularSh1 : this.images.denoisedSpecularSh1;
            case NrdNative.RESOURCE_OUT_SHADOW_TRANSLUCENCY -> this.images.sunShadow;
            case NrdNative.RESOURCE_TRANSIENT_POOL -> checkedPoolImage(
                    this.images.transientPool, indexInPool, "transient");
            case NrdNative.RESOURCE_PERMANENT_POOL -> checkedPoolImage(
                    this.images.permanentPool, indexInPool, "permanent");
            default -> throw new IllegalStateException(
                    "NRD requested unsupported resource type "
                            + resourceType);
        };
    }

    private static VulkanImage checkedPoolImage(VulkanImage[] pool, int index, String name) {
        if (index < 0 || index >= pool.length) {
            throw new IllegalStateException("NRD " + name + " pool index is out of range: " + index);
        }
        return pool[index];
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        // Submission-completion callbacks may race teardown. Publish terminal ownership first;
        // the pool lock then makes each late recycle destroy rather than requeue its binding.
        this.destroyed = true;
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.bindingPool, failure);
        failure = ResourceCleanup.destroy(this.composite, failure);
        failure = ResourceCleanup.destroy(this.inputPreparationPipeline, failure);
        failure = destroyPipelines(this.pipelines, failure);
        VK12.vkDestroySampler(this.context.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.context.vkDevice(), this.nearestSampler, null);
        failure = ResourceCleanup.destroy(this.images, failure);
        failure = ResourceCleanup.close(this.nativeInstance, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("NRD denoiser is destroyed");
        }
    }

    private static void validateNativeContract(NrdNative.Description description) {
        if (description.nrdVersion() != NrdNative.EXPECTED_NRD_VERSION
                || description.samplerOffset() != 0
                || description.textureOffset() != 20
                || description.constantBufferOffset() != 2
                || description.storageTextureOffset() != 3
                || description.constantBufferAndSamplersSpaceIndex() != 1
                || description.resourcesSpaceIndex() != 0
                || description.samplersBaseRegisterIndex() != 0
                || description.resourcesBaseRegisterIndex() != 0
                || !description.samplers().equals(List.of(0, 1))
                || !"main".equals(description.shaderEntryPoint())) {
            throw new IllegalStateException("Bundled NRD library does not match Prime's Vulkan ABI contract");
        }
    }

    private NrdNative.FrameSettings createFrameSettings(
            FrameCamera camera,
            FrameCamera previous,
            float cameraJitterX,
            float cameraJitterY,
            float previousCameraJitterX,
            float previousCameraJitterY,
            int width,
            int height,
            int frameIndex,
            boolean restart,
            float deltaMilliseconds,
            boolean enableValidation,
            SunDirection sunDirection) {
        NrdCameraTransform.projectionForNrd(camera.projection(), this.currentNrdProjection);
        NrdCameraTransform.projectionForNrd(previous.projection(), this.previousNrdProjection);
        NrdCameraTransform.previousWorldToView(camera, previous, this.previousWorldToView);
        return new NrdNative.FrameSettings(
                this.currentNrdProjection,
                this.previousNrdProjection,
                camera.viewRotation(),
                this.previousWorldToView,
                cameraJitterX,
                cameraJitterY,
                previousCameraJitterX,
                previousCameraJitterY,
                width,
                height,
                width,
                height,
                frameIndex,
                restart,
                deltaMilliseconds,
                DENOISING_RANGE,
                enableValidation,
                sunDirection.x(),
                sunDirection.y(),
                sunDirection.z());
    }

    private static long createSampler(VulkanContext context, boolean linear, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int filter = linear ? VK12.VK_FILTER_LINEAR : VK12.VK_FILTER_NEAREST;
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                    "create " + label);
            long sampler = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(), VK12.VK_OBJECT_TYPE_SAMPLER, sampler, label);
            return sampler;
        }
    }

    private static ComputePipeline[] createPipelines(
            VulkanContext context,
            NrdNative.Description description,
            long nearestSampler,
            long linearSampler) {
        ComputePipeline[] pipelines = new ComputePipeline[description.pipelines().size()];
        try {
            ParallelPipelineCreation.run(
                    "NRD compute pipelines",
                    pipelines.length,
                    index -> pipelines[index] = ComputePipeline.create(
                            context,
                            description,
                            description.pipelines().get(index),
                            nearestSampler,
                            linearSampler,
                            index));
            return pipelines;
        } catch (RuntimeException exception) {
            destroyPipelines(pipelines, exception);
            throw exception;
        }
    }

    private static RuntimeException destroyPipelines(
            ComputePipeline[] pipelines, RuntimeException failure) {
        if (pipelines == null) {
            return failure;
        }
        for (int index = pipelines.length - 1; index >= 0; index--) {
            failure = ResourceCleanup.destroy(pipelines[index], failure);
        }
        return failure;
    }

    private static void rayTraceToComputeBarrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void computeToComputeBarrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void issueImageBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VkImageMemoryBarrier2.Buffer barriers) {
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
    }

    /** One command-stream version after input preparation and before native reconstruction. */
    public static final class PreparedFrame {
        private final NrdDenoiser owner;
        private final SubmittedFrame<NrdFramePlan> planned;
        private final PreparedNrdFrame inputs;
        private boolean consumed;

        private PreparedFrame(
                NrdDenoiser owner,
                SubmittedFrame<NrdFramePlan> planned,
                PreparedNrdFrame inputs) {
            this.owner = owner;
            this.planned = planned;
            this.inputs = inputs;
        }

        public PreparedNrdFrame inputs() {
            return this.inputs;
        }

        public FrameCamera camera() {
            return this.planned.plan().input().camera();
        }

        public FrameCamera historyCamera() {
            return this.planned.plan().historyCamera();
        }

        public float currentJitterX() {
            return this.planned.plan().input().cameraJitterX();
        }

        public float currentJitterY() {
            return this.planned.plan().input().cameraJitterY();
        }

        public float historyJitterX() {
            return this.planned.plan().historyJitterX();
        }

        public float historyJitterY() {
            return this.planned.plan().historyJitterY();
        }

        public int frameIndex() {
            return this.planned.plan().frameIndex();
        }

        public boolean restart() {
            return this.planned.plan().restart();
        }

        public float deltaMilliseconds() {
            return this.planned.plan().deltaMilliseconds();
        }

        public SunDirection sunDirection() {
            return this.planned.plan().input().sunDirection();
        }

    }

    public static final class FrameToken {
        private final NrdDenoiser owner;
        private final NrdFrameBindings bindings;
        private final SubmittedFrame<NrdFramePlan> planned;
        private boolean submitted;
        private boolean abandoned;

        private FrameToken(
                NrdDenoiser owner,
                NrdFrameBindings bindings,
                SubmittedFrame<NrdFramePlan> planned) {
            this.owner = owner;
            this.bindings = bindings;
            this.planned = planned;
        }
    }

    /** Raw raygen view kept separate from the in-place prepared NRD view. */
    private static final class RawSignals implements RawWavefrontFrame {
        private final NrdImages images;

        private RawSignals(NrdImages images) {
            this.images = images;
        }

        @Override public VulkanImage noisyDiffuse() { return this.images.noisyDiffuse; }
        @Override public VulkanImage noisySpecular() { return this.images.noisySpecular; }
        @Override public VulkanImage diffuseDirection() { return this.images.noisyDiffuseSh1; }
        @Override public VulkanImage specularDirection() { return this.images.noisySpecularSh1; }
        @Override public VulkanImage normalRoughness() { return this.images.normalRoughness; }
        @Override public VulkanImage viewZ() { return this.images.viewZ; }
        @Override public VulkanImage transportScratch() { return this.images.fsrMotion; }
        @Override public VulkanImage visibleMotion() { return this.images.fsrMotion; }
        @Override public VulkanImage material() { return this.images.material; }
        @Override public VulkanImage specularMaterial() { return this.images.specularMaterial; }
        @Override public VulkanImage materialClass() { return this.images.materialClass; }
        @Override public VulkanImage primaryPosition() { return this.images.primaryPosition; }
        @Override public VulkanImage sunLighting() { return this.images.sunLighting; }
        @Override public VulkanImage sunPenumbra() { return this.images.sunPenumbra; }
        @Override public VulkanImage reflectionNoisyDiffuse() {
            return this.images.reflectionNoisyDiffuse;
        }
        @Override public VulkanImage reflectionNoisySpecular() {
            return this.images.reflectionNoisySpecular;
        }
        @Override public VulkanImage reflectionNormalRoughness() {
            return this.images.reflectionNormalRoughness;
        }
        @Override public VulkanImage reflectionMaterial() {
            return this.images.reflectionMaterial;
        }
        @Override public VulkanImage reflectionSpecularMaterial() {
            return this.images.reflectionSpecularMaterial;
        }
        @Override public VulkanImage reflectionPosition() {
            return this.images.reflectionPosition;
        }
        @Override public VulkanImage reflectionDiffuseDirection() {
            return this.images.reflectionNoisyDiffuseSh1;
        }
        @Override public VulkanImage reflectionSpecularDirection() {
            return this.images.reflectionNoisySpecularSh1;
        }
        @Override public VulkanImage displayPosition() { return this.images.displayPosition; }
        @Override public boolean usesShInputs() { return true; }
    }

    static final class ComputePipeline implements Destroyable {
        private final VulkanContext context;
        final long resourceDescriptorSetLayout;
        final long constantsDescriptorSetLayout;
        private final long pipelineLayout;
        private final long pipeline;
        private boolean destroyed;

        private ComputePipeline(
                VulkanContext context,
                long resourceDescriptorSetLayout,
                long constantsDescriptorSetLayout,
                long pipelineLayout,
                long pipeline) {
            this.context = context;
            this.resourceDescriptorSetLayout = resourceDescriptorSetLayout;
            this.constantsDescriptorSetLayout = constantsDescriptorSetLayout;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
        }

        private static ComputePipeline create(
                VulkanContext context,
                NrdNative.Description description,
                NrdNative.Pipeline pipelineDescription,
                long nearestSampler,
                long linearSampler,
                int pipelineIndex) {
            long resourceDescriptorSetLayout = 0L;
            long constantsDescriptorSetLayout = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                resourceDescriptorSetLayout = createNrdResourceDescriptorSetLayout(
                        context, stack, description, pipelineDescription);
                constantsDescriptorSetLayout = createNrdConstantsDescriptorSetLayout(
                        context,
                        stack,
                        description,
                        pipelineDescription,
                        nearestSampler,
                        linearSampler);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(
                                resourceDescriptorSetLayout,
                                constantsDescriptorSetLayout));
                LongBuffer layoutPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(context.vkDevice(), layoutInfo, null, layoutPointer),
                        "create Prime NRD pipeline layout " + pipelineIndex);
                pipelineLayout = layoutPointer.get(0);
                long shaderModule = VulkanShaderModules.create(
                        context, stack, pipelineDescription.spirv(), "Prime NRD shader " + pipelineIndex);
                try {
                    VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(COMPUTE_STAGE)
                            .module(shaderModule)
                            .pName(stack.UTF8(description.shaderEntryPoint()));
                    VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                    createInfo.get(0)
                            .sType$Default()
                            .stage(stage)
                            .layout(pipelineLayout);
                    LongBuffer pipelinePointer = stack.mallocLong(1);
                    context.createComputePipeline(
                            createInfo,
                            pipelinePointer,
                            "Prime NRD " + pipelineDescription.identifier());
                    pipeline = pipelinePointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
                }
                return new ComputePipeline(
                        context,
                        resourceDescriptorSetLayout,
                        constantsDescriptorSetLayout,
                        pipelineLayout,
                        pipeline);
            } catch (RuntimeException exception) {
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                if (pipelineLayout != 0L) {
                    VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
                }
                if (constantsDescriptorSetLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(
                            context.vkDevice(), constantsDescriptorSetLayout, null);
                }
                if (resourceDescriptorSetLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(
                            context.vkDevice(), resourceDescriptorSetLayout, null);
                }
                throw exception;
            }
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
                VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
                VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.constantsDescriptorSetLayout, null);
                VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.resourceDescriptorSetLayout, null);
            }
        }
    }

    private static long createNrdResourceDescriptorSetLayout(
            VulkanContext context,
            MemoryStack stack,
            NrdNative.Description description,
            NrdNative.Pipeline pipeline) {
        int resourceCount = pipeline.ranges().stream()
                .mapToInt(NrdNative.PipelineRange::descriptorsNum)
                .sum();
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(resourceCount, stack);
        int bindingIndex = 0;
        int textureIndex = 0;
        int storageIndex = 0;
        for (NrdNative.PipelineRange range : pipeline.ranges()) {
            for (int descriptorIndex = 0; descriptorIndex < range.descriptorsNum(); descriptorIndex++) {
                int binding;
                int descriptorType;
                if (range.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                    binding = description.textureOffset()
                            + description.resourcesBaseRegisterIndex()
                            + textureIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                } else if (range.descriptorType() == NrdNative.DESCRIPTOR_STORAGE_TEXTURE) {
                    binding = description.storageTextureOffset()
                            + description.resourcesBaseRegisterIndex()
                            + storageIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                } else {
                    throw new IllegalStateException("Unknown NRD descriptor range type " + range.descriptorType());
                }
                bindings.get(bindingIndex++)
                        .binding(binding)
                        .descriptorType(descriptorType)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
            }
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime NRD resource descriptor set layout");
        return pointer.get(0);
    }

    private static long createNrdConstantsDescriptorSetLayout(
            VulkanContext context,
            MemoryStack stack,
            NrdNative.Description description,
            NrdNative.Pipeline pipeline,
            long nearestSampler,
            long linearSampler) {
        int bindingCount = description.samplers().size() + (pipeline.hasConstantData() ? 1 : 0);
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
        int bindingIndex = 0;
        long[] immutableSamplers = new long[] {nearestSampler, linearSampler};
        if (description.samplers().size() != immutableSamplers.length) {
            throw new IllegalStateException("Prime expects NRD's nearest and linear samplers");
        }
        for (int samplerIndex = 0; samplerIndex < description.samplers().size(); samplerIndex++) {
            bindings.get(bindingIndex++)
                    .binding(description.samplerOffset()
                            + description.samplersBaseRegisterIndex()
                            + samplerIndex)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE)
                    .pImmutableSamplers(stack.longs(immutableSamplers[samplerIndex]));
        }
        if (pipeline.hasConstantData()) {
            bindings.get(bindingIndex)
                    .binding(description.constantBufferOffset()
                            + description.constantBufferRegisterIndex())
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime NRD constants descriptor set layout");
        return pointer.get(0);
    }

}
