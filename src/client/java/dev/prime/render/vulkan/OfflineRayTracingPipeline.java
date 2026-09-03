package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

/** Offline-only full-path pipeline with a four-stage per-bounce wavefront. */
public final class OfflineRayTracingPipeline implements Destroyable {
    static int dispatchCount(int maximumBounces) {
        dev.prime.render.MaximumBounceSettings.validateCount(maximumBounces);
        return 4 * maximumBounces + 1;
    }
    static final int DESCRIPTOR_BINDING_COUNT = 3;
    static final WavefrontLayout LAYOUT = new WavefrontLayout(
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
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private Bindings bindings;
    private boolean destroyed;

    public OfflineRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
            }
            String suffix = context.capabilities().wavefrontShaderSuffix();
            traceProgram = TraceProgram.create(
                    context,
                    OfflineGroups.schedule(suffix),
                    "Prime offline ray tracing pipeline",
                    "Prime offline shader binding table",
                    backend.bindings().descriptorSetLayout(),
                    setLayout);
            this.descriptorSetLayout = setLayout;
            this.program = traceProgram;
        } catch (RuntimeException exception) {
            if (traceProgram != null) {
                traceProgram.destroy();
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
            MaterialTexturePages.Binding materialTextures,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialTextures,
                materialCore,
                tintSamples,
                atmosphere);
        int width = runningMean.width();
        int height = runningMean.height();
        long requiredBytes = LAYOUT.wavefrontBytes(width, height);
        LAYOUT.validateRanges(width, height, this.context.maxStorageBufferRange());
        LAYOUT.validateDispatch(
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
                    LAYOUT.queueOffset(width, height));
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
        if (this.wavefront == null || this.wavefront.size() != LAYOUT.wavefrontBytes(width, height)) {
            throw new IllegalStateException("Offline wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.bind(commandBuffer, stack, RayTracingPushConstants.encode(stack, input, scene));
            long commandOffset = LAYOUT.queueCommandOffset(width, height);
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
        this.program.bind(
                commandBuffer,
                stack,
                pushConstants,
                this.backend.bindings().descriptorSet(),
                this.bindings.descriptorSet);
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

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        VulkanDescriptors.layoutBinding(
                bindings.get(0), ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VulkanDescriptors.layoutBinding(
                bindings.get(1), ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VulkanDescriptors.layoutBinding(
                bindings.get(2), ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        return VulkanDescriptors.createSetLayout(
                context,
                stack,
                bindings,
                "create offline trace descriptor layout");
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.releaseSizedResourcesAfterIdle();
            this.program.destroy();
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static final class Bindings implements Destroyable {
        private final BoundSet descriptors;
        private final long descriptorSet;
        private final long runningMean;
        private final long wavefront;

        private Bindings(
                BoundSet descriptors,
                long runningMean,
                long wavefront) {
            this.descriptors = descriptors;
            this.descriptorSet = descriptors.handle();
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
                BoundSet descriptors = VulkanDescriptors.bind(
                        context, stack, layout, "offline trace",
                        VulkanDescriptors.image(
                                ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN,
                                VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                runningMean.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                        VulkanDescriptors.buffer(
                                ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                wavefront.handle(), 0L, queueOffset),
                        VulkanDescriptors.buffer(
                                ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE,
                                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                wavefront.handle(), queueOffset,
                                wavefront.size() - queueOffset));
                return new Bindings(
                        descriptors, runningMean.view(), wavefront.handle());
            }
        }

        private boolean matches(long candidateRunningMean, long candidateWavefront) {
            return this.runningMean == candidateRunningMean
                    && this.wavefront == candidateWavefront;
        }

        @Override
        public void destroy() {
            this.descriptors.destroy();
        }
    }
}
