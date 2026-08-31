package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Offline-only full-path pipeline with a four-stage per-bounce wavefront. */
public final class OfflineRayTracingPipeline implements Destroyable {
    static final int RAYGEN_GROUP_COUNT = OfflineGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = OfflineGroups.MODULE_COUNT;
    static int dispatchCount(int maximumBounces) {
        dev.prime.render.MaximumBounceSettings.validateCount(maximumBounces);
        return 4 * maximumBounces + 1;
    }
    static final int DESCRIPTOR_BINDING_COUNT = 3;
    private static final WavefrontLayout WAVEFRONT_LAYOUT = new WavefrontLayout(
            ShaderAbi.OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL,
            ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL,
            ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL,
            ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE,
            ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE,
            ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT,
            ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE,
            ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_INDEX_SIZE,
            "Offline");

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private Bindings bindings;
    private boolean destroyed;

    public OfflineRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        long layout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
                layout = TracePipelineLayouts.create(
                        context,
                        stack,
                        backend.bindings().descriptorSetLayout(),
                        setLayout,
                        "offline");
            }
            String suffix = context.capabilities().wavefrontShaderSuffix();
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    OfflineGroups.schedule(suffix),
                    "Prime offline ray tracing pipeline",
                    "Prime offline shader binding table");
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
        } catch (RuntimeException exception) {
            if (traceProgram != null) {
                traceProgram.destroy();
            }
            if (layout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), layout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            }
            throw exception;
        }
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage runningMean,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            List<VulkanImage> materialBaseColorPages,
            List<VulkanImage> materialNormalPages,
            List<VulkanImage> materialOpticalPages,
            VulkanBuffer textureRecords,
            TerrainScene.TintOperatorBinding tintOperators,
            AtmospherePipeline atmosphere) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialBaseColorPages,
                materialNormalPages,
                materialOpticalPages,
                textureRecords,
                tintOperators,
                atmosphere);
        int width = runningMean.width();
        int height = runningMean.height();
        long requiredBytes = wavefrontBytes(width, height);
        validateRanges(width, height, this.context.maxStorageBufferRange());
        validateDispatch(
                width,
                height,
                this.context.capabilities().maxRayDispatchInvocationCount());
        VulkanBuffer candidate = this.wavefront;
        boolean replaces = candidate == null || candidate.size() != requiredBytes;
        if (replaces) {
            candidate = this.context.createBuffer(
                    requiredBytes,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime offline wavefront slots");
        }
        if (this.bindings != null
                && this.bindings.matches(runningMean.view(), candidate.handle())) {
            return;
        }
        Bindings replacement;
        try {
            replacement = Bindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    runningMean,
                    candidate,
                    queueOffset(width, height));
        } catch (RuntimeException exception) {
            if (replaces) {
                candidate.destroy();
            }
            throw exception;
        }
        Bindings previousBindings = this.bindings;
        VulkanBuffer previousWavefront = this.wavefront;
        this.bindings = replacement;
        this.wavefront = candidate;
        if (previousBindings != null) {
            this.context.defer(previousBindings);
        }
        if (replaces && previousWavefront != null) {
            this.context.defer(previousWavefront);
        }
    }

    /** Releases descriptor bindings and wavefront backing after the device has become idle. */
    public void releaseSizedResourcesAfterIdle() {
        if (this.bindings != null) {
            this.bindings.destroy();
            this.bindings = null;
        }
        if (this.wavefront != null) {
            this.wavefront.destroy();
            this.wavefront = null;
        }
    }

    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        int width = input.width();
        int height = input.height();
        if (this.wavefront == null || this.wavefront.size() != wavefrontBytes(width, height)) {
            throw new IllegalStateException("Offline wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.bind(commandBuffer, stack, RayTracingPushConstants.encode(stack, input, scene));
            long commandOffset = queueCommandOffset(width, height);
            this.initializeQueues(commandBuffer, stack, commandOffset);
            this.trace(
                    commandBuffer,
                    stack,
                    width,
                    height,
                    OfflineGroups.CAMERA_TRACE);
            this.wavefrontBarrier(commandBuffer, stack);
            this.recordShadingRound(commandBuffer, stack, commandOffset, 0);
            int sourceQueue = 1;
            for (int bounce = 1; bounce < input.maximumBounces(); bounce++) {
                this.recordRound(commandBuffer, stack, commandOffset, sourceQueue);
                sourceQueue ^= 1;
            }
            this.trace(
                    commandBuffer,
                    stack,
                    width,
                    height,
                    OfflineGroups.SAMPLE_RESOLVE);
        }
    }

    private void recordRound(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset,
            int sourceQueue) {
        this.traceIndirect(
                commandBuffer,
                stack,
                OfflineGroups.bridgeTrace(sourceQueue),
                commandOffset,
                sourceQueue);
        this.wavefrontBarrier(commandBuffer, stack);
        this.recordShadingRound(
                commandBuffer, stack, commandOffset, sourceQueue);
    }

    private void recordShadingRound(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset,
            int sourceQueue) {
        this.traceIndirect(
                commandBuffer,
                stack,
                OfflineGroups.lightSelect(sourceQueue),
                commandOffset,
                sourceQueue);
        this.wavefrontBarrier(commandBuffer, stack);
        this.traceIndirect(
                commandBuffer,
                stack,
                OfflineGroups.direct(sourceQueue),
                commandOffset,
                sourceQueue);
        this.wavefrontBarrier(commandBuffer, stack);
        this.traceIndirect(
                commandBuffer,
                stack,
                OfflineGroups.scatter(sourceQueue),
                commandOffset,
                sourceQueue);
        this.wavefrontBarrier(commandBuffer, stack);
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants) {
        if (this.bindings == null) {
            throw new IllegalStateException("Offline descriptors have not been initialized");
        }
        VK12.vkCmdBindPipeline(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.program.pipeline);
        VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.pipelineLayout,
                0,
                stack.longs(this.backend.bindings().descriptorSet(), this.bindings.descriptorSet),
                null);
        VK12.vkCmdPushConstants(
                commandBuffer,
                this.pipelineLayout,
                TracePipelineLayouts.ALL_RT_STAGES,
                0,
                pushConstants);
    }

    private void trace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int width,
            int height,
            int group) {
        WavefrontCommands.trace(
                commandBuffer, stack, this.program, width, height, group);
    }

    private void traceIndirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int group,
            long commandOffset,
            int sourceQueue) {
        WavefrontCommands.traceIndirect(
                commandBuffer,
                stack,
                this.program,
                this.wavefront,
                group,
                commandOffset,
                sourceQueue,
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset) {
        WavefrontCommands.initializeQueues(
                commandBuffer,
                stack,
                this.wavefront,
                commandOffset,
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private void wavefrontBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        WavefrontCommands.wavefrontBarrier(commandBuffer, stack, this.wavefront);
    }

    static long wavefrontBytes(int width, int height) {
        return WAVEFRONT_LAYOUT.wavefrontBytes(width, height);
    }

    static int raygenModule(int group) {
        return OfflineGroups.module(group);
    }

    static int raygenControl(int group) {
        return OfflineGroups.control(group);
    }

    static long queueOffset(int width, int height) {
        return WAVEFRONT_LAYOUT.queueOffset(width, height);
    }

    static long queueBytes(int width, int height) {
        return WAVEFRONT_LAYOUT.queueBytes(width, height);
    }

    static long queueCommandOffset(int width, int height) {
        return WAVEFRONT_LAYOUT.queueCommandOffset(width, height);
    }

    static void validateRanges(int width, int height, long maximumRange) {
        WAVEFRONT_LAYOUT.validateRanges(width, height, maximumRange);
    }

    static void validateDispatch(int width, int height, int maximumInvocations) {
        WAVEFRONT_LAYOUT.validateDispatch(width, height, maximumInvocations);
    }

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        bindings.get(0)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(2)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(), info, null, pointer),
                "create offline trace descriptor layout");
        return pointer.get(0);
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.bindings != null) {
                this.bindings.destroy();
                this.bindings = null;
            }
            if (this.wavefront != null) {
                this.wavefront.destroy();
                this.wavefront = null;
            }
            this.program.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static final class Bindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long runningMean;
        private final long wavefront;
        private boolean destroyed;

        private Bindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long runningMean,
                long wavefront) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.runningMean = runningMean;
            this.wavefront = wavefront;
        }

        private static Bindings create(
                VulkanContext context,
                long layout,
                VulkanImage runningMean,
                VulkanBuffer wavefront,
                long queueOffset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create offline trace descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocation =
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(layout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    context.vkDevice(), allocation, setPointer),
                            "allocate offline trace descriptor set");
                    long set = setPointer.get(0);
                    VkDescriptorImageInfo imageInfo = VkDescriptorImageInfo.calloc(stack)
                            .imageView(runningMean.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorBufferInfo.Buffer bufferInfos =
                            VkDescriptorBufferInfo.calloc(2, stack);
                    bufferInfos.get(0)
                            .buffer(wavefront.handle())
                            .offset(0L)
                            .range(queueOffset);
                    bufferInfos.get(1)
                            .buffer(wavefront.handle())
                            .offset(queueOffset)
                            .range(wavefront.size() - queueOffset);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
                    writes.get(0)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfo.address(), 1));
                    writes.get(1)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(2)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new Bindings(
                            context,
                            pool,
                            set,
                            runningMean.view(),
                            wavefront.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(long candidateRunningMean, long candidateWavefront) {
            return this.runningMean == candidateRunningMean
                    && this.wavefront == candidateWavefront;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(
                        this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }
}
