package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
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

/** Realtime ray-tracing pipeline and its wavefront resources. */
public final class RealtimeRayTracingPipeline implements Destroyable {
    static final int RAYGEN_GROUP_COUNT = RealtimeStandardGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeStandardGroups.MODULE_COUNT;
    private static final int STORAGE_IMAGE_DESCRIPTOR_COUNT = imageBindings().length;
    static final int DESCRIPTOR_BINDING_COUNT = STORAGE_IMAGE_DESCRIPTOR_COUNT + 2;
    private static final WavefrontLayout WAVEFRONT_LAYOUT = new WavefrontLayout(
            ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL,
            ShaderAbi.WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL,
            ShaderAbi.WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL,
            ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_QUEUE_COUNT,
            ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE,
            ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE,
            "Realtime");

    /** Barrier resources are physical even when several semantic bindings alias one image. */
    static long[] uniqueImageHandles(long[] images) {
        return Arrays.stream(images).distinct().toArray();
    }

    static long[] selectUniqueImageHandles(long[] images, int... indices) {
        return Arrays.stream(indices)
                .mapToLong(index -> images[index])
                .distinct()
                .toArray();
    }

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private OutputBindings bindings;
    private int lastRecordedPassCount;
    private boolean destroyed;

    static int dispatchCount(int minimumBounces) {
        dev.prime.render.MinimumBounceSettings.validateCount(minimumBounces);
        // Landing owns the primary-surface bounce. Every additional minimum bounce has four
        // narrow stages; admission and the register tail replace all remaining dispatches.
        return 4 * (minimumBounces - 1) + 14;
    }

    static int[] primaryDirectInputImageIndices() {
        return new int[] {1, 2};
    }

    static int[] primaryInputImageIndices() {
        return new int[] {0, 1, 2, 4, 6, 7, 8, 9, 10, 20, 21};
    }

    static int[] nextStepInputImageIndices() {
        // Guide images participate even when the next stage only writes them: the fallback and
        // first-owned guide stores require an explicit WAW dependency.
        return new int[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 21
        };
    }

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
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
                        "realtime");
            }
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    RealtimeStandardGroups.standardSchedule(
                            context.capabilities().wavefrontShaderSuffix()),
                    "Prime realtime ray tracing pipeline",
                    "Prime realtime shader binding table");
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
            this.lastRecordedPassCount = dispatchCount(
                    dev.prime.render.MinimumBounceSettings.MAXIMUM_COUNT);
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

    public int passCount() {
        return this.lastRecordedPassCount;
    }

    public long sizedResourceBytes() {
        return this.wavefront == null ? 0L : this.wavefront.size();
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            List<VulkanImage> materialBaseColorPages,
            List<VulkanImage> materialNormalPages,
            List<VulkanImage> materialOpticalPages,
            VulkanBuffer textureRecords,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialBaseColorPages,
                materialNormalPages,
                materialOpticalPages,
                textureRecords,
                materialCore,
                tintSamples,
                atmosphere);
        int width = signals.noisyDiffuse().width();
        int height = signals.noisyDiffuse().height();
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
                    "Prime realtime wavefront slots");
        }
        if (this.bindings != null
                && this.bindings.matches(
                        stableRadiance,
                        signals,
                        candidate.handle())) {
            return;
        }
        OutputBindings replacement;
        try {
            replacement = OutputBindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    stableRadiance,
                    signals,
                    candidate,
                    queueOffset(width, height));
        } catch (RuntimeException exception) {
            if (replaces) {
                candidate.destroy();
            }
            throw exception;
        }
        OutputBindings previousBindings = this.bindings;
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
        if (this.wavefront == null
                || this.wavefront.size() != wavefrontBytes(width, height)) {
            throw new IllegalStateException("Realtime wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = RayTracingPushConstants.encode(stack, input, scene);
            long commandOffset = queueCommandOffset(width, height);
            this.bind(commandBuffer, stack, pushConstants);
            this.initializeQueues(
                    commandBuffer, stack, commandOffset, input.additionalSpecularBounces());
            this.lastRecordedPassCount = this.recordTransport(
                    commandBuffer, stack, input, commandOffset);
        }
    }

    private int recordTransport(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            IntegratorFrameInput input,
            long commandOffset) {
        this.traceDirect(
                commandBuffer,
                stack,
                input.width(),
                input.height(),
                RealtimePrimaryGroups.CAMERA_TRACE);
        this.recordPrimaryPrefix(commandBuffer, stack, commandOffset);
        int minimumBounces = input.minimumBounces();
        boolean sourceOne = false;
        this.queueBarrier(commandBuffer, stack);
        for (int round = 1; round < minimumBounces; round++) {
            int sourceQueue = sourceOne
                    ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                    : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
            this.traceQueued(
                    commandBuffer,
                    stack,
                    RealtimeStandardGroups.bridgeTrace(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.queueBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    RealtimeStandardGroups.lightSelect(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    RealtimeStandardGroups.direct(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    RealtimeStandardGroups.scatter(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.queueBarrier(commandBuffer, stack);
            sourceOne = !sourceOne;
        }
        int tailSourceQueue = sourceOne
                ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimeStandardGroups.tailAdmission(sourceOne),
                commandOffset,
                tailSourceQueue);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimeStandardGroups.TAIL,
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.resolveInputBarrier(commandBuffer, stack);
        this.recordOutputTail(
                commandBuffer,
                stack,
                commandOffset,
                input.width(),
                input.height(),
                RealtimeStandardGroups.BRANCH_RESOLVE,
                RealtimeStandardGroups.NOISY_OUTPUT_RESOLVE);
        return dispatchCount(minimumBounces);
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants) {
        if (this.bindings == null) {
            throw new IllegalStateException("Realtime descriptors have not been initialized");
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

    private void traceDirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int width,
            int height,
            int group) {
        WavefrontCommands.trace(
                commandBuffer, stack, this.program, width, height, group);
    }

    /** Records groups 1..9, from visible-primary work through secondary-queue publication. */
    private void recordPrimaryPrefix(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset) {
        this.primaryDirectInputBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.VISIBLE_DIRECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.primaryInputBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.SURFACE_SPLIT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.DELTA_WALK_0,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRACE_QUEUE_0);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.GUIDE_DELTA_WALK_0,
                commandOffset,
                ShaderAbi.WAVEFRONT_GUIDE_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.DELTA_WALK_1,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRACE_QUEUE_1);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.GUIDE_DELTA_WALK_1,
                commandOffset,
                ShaderAbi.WAVEFRONT_GUIDE_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.LANDING_LIGHT_SELECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.LANDING_DIRECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                RealtimePrimaryGroups.LANDING_SCATTER,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
    }

    private void recordOutputTail(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset,
            int width,
            int height,
            int branchResolveGroup,
            int outputResolveGroup) {
        this.traceQueued(
                commandBuffer,
                stack,
                branchResolveGroup,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
        this.traceDirect(
                commandBuffer,
                stack,
                width,
                height,
                outputResolveGroup);
    }

    private void traceQueued(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int group,
            long commandOffset,
            int commandQueue) {
        WavefrontCommands.traceIndirect(
                commandBuffer,
                stack,
                this.program,
                this.wavefront,
                group,
                commandOffset,
                commandQueue,
                ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset,
            int additionalSpecularBounces) {
        WavefrontCommands.initializeQueues(
                commandBuffer,
                stack,
                this.wavefront,
                commandOffset,
                ShaderAbi.WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE,
                additionalSpecularBounces);
    }

    private void queueBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        WavefrontCommands.wavefrontBarrier(commandBuffer, stack, this.wavefront);
    }

    private void primaryDirectInputBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.wavefrontResourceBarrier(
                commandBuffer,
                stack,
                this.bindings.primaryDirectInputImages);
    }

    private void primaryInputBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.wavefrontResourceBarrier(
                commandBuffer,
                stack,
                this.bindings.primaryInputImages);
    }

    private void nextStepBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.wavefrontResourceBarrier(
                commandBuffer,
                stack,
                this.bindings.nextStepInputImages);
    }

    private void resolveInputBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.wavefrontResourceBarrier(
                commandBuffer,
                stack,
                this.bindings.allImages);
    }

    private void wavefrontResourceBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long[] images) {
        long sourceStage =
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        long destinationStage = sourceStage | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
        long destinationAccess = VK12.VK_ACCESS_SHADER_READ_BIT
                | VK12.VK_ACCESS_SHADER_WRITE_BIT
                | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        VulkanSync.resourceBarrier(
                commandBuffer,
                stack,
                this.wavefront,
                images,
                sourceStage,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                destinationStage,
                destinationAccess);
    }

    static long wavefrontBytes(int width, int height) {
        return WAVEFRONT_LAYOUT.wavefrontBytes(width, height);
    }

    static int raygenModule(int group) {
        return RealtimeStandardGroups.module(group);
    }

    static int raygenControl(int group) {
        return RealtimeStandardGroups.control(group);
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
        int cursor = 0;
        int[] imageBindings = imageBindings();
        for (int binding : imageBindings) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(cursor)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
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
                "create realtime trace descriptor layout");
        return pointer.get(0);
    }

    private static int[] imageBindings() {
        return new int[] {
            ShaderAbi.DESCRIPTOR_STABLE_RADIANCE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
            ShaderAbi.DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA,
            ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING,
            ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA,
            ShaderAbi.DESCRIPTOR_RECONSTRUCTION_CONTROL
        };
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

    private static final class OutputBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long stableRadiance;
        private final long[] images;
        private final long[] allImages;
        private final long[] primaryDirectInputImages;
        private final long[] primaryInputImages;
        private final long[] nextStepInputImages;
        private final long wavefront;
        private boolean destroyed;

        private OutputBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long stableRadiance,
                long[] images,
                long[] allImages,
                long wavefront) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.stableRadiance = stableRadiance;
            this.images = images.clone();
            this.allImages = uniqueImageHandles(allImages);
            this.primaryDirectInputImages = selectUniqueImageHandles(
                    allImages,
                    RealtimeRayTracingPipeline.primaryDirectInputImageIndices());
            this.primaryInputImages = selectUniqueImageHandles(
                    allImages,
                    RealtimeRayTracingPipeline.primaryInputImageIndices());
            this.nextStepInputImages = selectUniqueImageHandles(
                    allImages,
                    RealtimeRayTracingPipeline.nextStepInputImageIndices());
            this.wavefront = wavefront;
        }

        private static OutputBindings create(
                VulkanContext context,
                long layout,
                VulkanImage stableRadiance,
                RawWavefrontFrame signals,
                VulkanBuffer wavefront,
                long queueOffset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes =
                        VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(STORAGE_IMAGE_DESCRIPTOR_COUNT);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(2);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create realtime trace descriptor pool");
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
                            "allocate realtime trace descriptor set");
                    long set = setPointer.get(0);
                    VulkanImage[] images = outputImages(stableRadiance, signals);
                    long[] views = new long[images.length];
                    long[] imageHandles = new long[images.length];
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(images.length, stack);
                    for (int index = 0; index < images.length; index++) {
                        views[index] = images[index].view();
                        imageHandles[index] = images[index].image();
                        imageInfos.get(index)
                                .imageView(views[index])
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
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
                    int[] imageBindings = imageBindings();
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(imageBindings.length + 2, stack);
                    for (int index = 0; index < imageBindings.length; index++) {
                        writes.get(index)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(imageBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index).address(), 1));
                    }
                    writes.get(imageBindings.length)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(imageBindings.length + 1)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new OutputBindings(
                            context,
                            pool,
                            set,
                            stableRadiance.view(),
                            views,
                            imageHandles,
                            wavefront.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                VulkanImage candidateStableRadiance,
                RawWavefrontFrame signals,
                long candidateWavefront) {
            if (this.stableRadiance != candidateStableRadiance.view()
                    || this.wavefront != candidateWavefront) {
                return false;
            }
            VulkanImage[] candidates = outputImages(candidateStableRadiance, signals);
            if (this.images.length != candidates.length) {
                return false;
            }
            for (int index = 0; index < candidates.length; index++) {
                if (this.images[index] != candidates[index].view()) {
                    return false;
                }
            }
            return true;
        }

        private static VulkanImage[] outputImages(
                VulkanImage stableRadiance, RawWavefrontFrame signals) {
            return new VulkanImage[] {
                stableRadiance,
                signals.noisyDiffuse(),
                signals.noisySpecular(),
                signals.normalRoughness(),
                signals.viewZ(),
                signals.transportScratch(),
                signals.material(),
                signals.specularMaterial(),
                signals.primaryPosition(),
                signals.diffuseDirection(),
                signals.specularDirection(),
                signals.reflectionNoisyDiffuse(),
                signals.reflectionNoisySpecular(),
                signals.reflectionNormalRoughness(),
                signals.reflectionMaterial(),
                signals.reflectionSpecularMaterial(),
                signals.reflectionPosition(),
                signals.reflectionDiffuseDirection(),
                signals.reflectionSpecularDirection(),
                signals.displayPosition(),
                signals.sunLighting(),
                signals.sunPenumbra(),
                signals.reconstructionControl()
            };
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
