package dev.prime.render.vulkan;

import static dev.prime.render.vulkan.GeneratedShaderPrograms.*;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

/** Realtime ray-tracing pipeline and its wavefront resources. */
public final class RealtimeRayTracingPipeline implements Destroyable {
    private static final int PRIMARY_DIRECT_INPUT = 1;
    private static final int PRIMARY_INPUT = 2;
    private static final int NEXT_STEP_INPUT = 4;
    private static final ImageBinding[] IMAGE_BINDINGS = ImageBinding.values();
    private static final int STORAGE_IMAGE_DESCRIPTOR_COUNT = IMAGE_BINDINGS.length;
    static final int DESCRIPTOR_BINDING_COUNT = STORAGE_IMAGE_DESCRIPTOR_COUNT + 2;
    static final WavefrontLayout LAYOUT = new WavefrontLayout(
            ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL,
            ShaderAbi.WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL,
            ShaderAbi.WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL,
            ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE,
            ShaderAbi.WAVEFRONT_QUEUE_COUNT,
            ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE,
            ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE,
            "Realtime");

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private OutputBindings bindings;
    private int lastRecordedPassCount;
    private boolean destroyed;

    static int dispatchCount(int minimumBounces) {
        dev.prime.render.BounceSettings.validateFixedCount(minimumBounces);
        // Landing owns the primary-surface bounce. Every additional minimum bounce has four
        // narrow stages; the register tail replaces all remaining dispatches.
        return 4 * (minimumBounces - 1) + 13;
    }

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
            }
            traceProgram = TraceProgram.create(
                    context,
                    GeneratedShaderPrograms.schedule(
                            "realtime.standard",
                            context.capabilities().wavefrontShaderSuffix()),
                    "Prime realtime ray tracing pipeline",
                    "Prime realtime shader binding table",
                    backend.bindings().descriptorSetLayout(),
                    setLayout);
            this.descriptorSetLayout = setLayout;
            this.program = traceProgram;
            this.lastRecordedPassCount = dispatchCount(
                    dev.prime.render.BounceSettings.MAXIMUM_FIXED_COUNT);
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
            MaterialTexturePages.Binding materialTextures,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialTextures,
                materialCore,
                tintSamples,
                atmosphere);
        int width = signals.noisyDiffuse().width();
        int height = signals.noisyDiffuse().height();
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
                    LAYOUT.queueOffset(width, height));
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
                || this.wavefront.size() != LAYOUT.wavefrontBytes(width, height)) {
            throw new IllegalStateException("Realtime wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = RayTracingPushConstants.encode(stack, input, scene);
            long commandOffset = LAYOUT.queueCommandOffset(width, height);
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
                REALTIME_STANDARD_CAMERA_TRACE);
        this.recordPrimaryPrefix(commandBuffer, stack, commandOffset);
        int minimumBounces = input.minimumBounces();
        boolean sourceOne = false;
        for (int round = 1; round < minimumBounces; round++) {
            WavefrontCommands.wavefrontBarrier(commandBuffer, stack, this.wavefront);
            int sourceQueue = sourceOne
                    ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                    : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
            this.traceQueued(
                    commandBuffer,
                    stack,
                    queuedGroup(
                            sourceOne,
                            REALTIME_STANDARD_BRIDGE_TRACE_0,
                            REALTIME_STANDARD_BRIDGE_TRACE_1),
                    commandOffset,
                    sourceQueue);
            WavefrontCommands.wavefrontBarrier(commandBuffer, stack, this.wavefront);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    queuedGroup(
                            sourceOne,
                            REALTIME_STANDARD_LIGHT_SELECT_0,
                            REALTIME_STANDARD_LIGHT_SELECT_1),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    queuedGroup(
                            sourceOne,
                            REALTIME_STANDARD_DIRECT_0,
                            REALTIME_STANDARD_DIRECT_1),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    queuedGroup(
                            sourceOne,
                            REALTIME_STANDARD_SCATTER_0,
                            REALTIME_STANDARD_SCATTER_1),
                    commandOffset,
                    sourceQueue);
            sourceOne = !sourceOne;
        }
        int tailSourceQueue = sourceOne
                ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                queuedGroup(
                        sourceOne,
                        REALTIME_STANDARD_TAIL_0,
                        REALTIME_STANDARD_TAIL_1),
                commandOffset,
                tailSourceQueue);
        this.resolveInputBarrier(commandBuffer, stack);
        this.recordOutputTail(
                commandBuffer,
                stack,
                commandOffset,
                input.width(),
                input.height(),
                REALTIME_STANDARD_BRANCH_RESOLVE,
                REALTIME_STANDARD_NOISY_OUTPUT_RESOLVE);
        return dispatchCount(minimumBounces);
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants) {
        if (this.bindings == null) {
            throw new IllegalStateException("Realtime descriptors have not been initialized");
        }
        this.program.bind(
                commandBuffer,
                stack,
                pushConstants,
                this.backend.bindings().descriptorSet(),
                this.bindings.descriptorSet);
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
                REALTIME_STANDARD_VISIBLE_DIRECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.primaryInputBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_SURFACE_SPLIT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_DELTA_WALK_0,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRACE_QUEUE_0);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_GUIDE_DELTA_WALK_0,
                commandOffset,
                ShaderAbi.WAVEFRONT_GUIDE_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_DELTA_WALK_1,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRACE_QUEUE_1);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_GUIDE_DELTA_WALK_1,
                commandOffset,
                ShaderAbi.WAVEFRONT_GUIDE_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_LANDING_LIGHT_SELECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_LANDING_DIRECT,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                REALTIME_STANDARD_LANDING_SCATTER,
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

    private static int queuedGroup(boolean sourceOne, int first, int second) {
        return sourceOne ? second : first;
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

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        int cursor = 0;
        for (ImageBinding binding : IMAGE_BINDINGS) {
            VulkanDescriptors.layoutBinding(
                    bindings.get(cursor++), binding.descriptor,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor++), ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VulkanDescriptors.layoutBinding(
                bindings.get(cursor), ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE,
                VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                1, KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        return VulkanDescriptors.createSetLayout(
                context,
                stack,
                bindings,
                "create realtime trace descriptor layout");
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

    private static final class OutputBindings implements Destroyable {
        private final VulkanDescriptors.BoundSet descriptors;
        private final long descriptorSet;
        private final long stableRadiance;
        private final long[] images;
        private final long[] allImages;
        private final long[] primaryDirectInputImages;
        private final long[] primaryInputImages;
        private final long[] nextStepInputImages;
        private final long wavefront;

        private OutputBindings(
                VulkanDescriptors.BoundSet descriptors,
                long stableRadiance,
                long[] images,
                long[] allImages,
                long wavefront) {
            this.descriptors = descriptors;
            this.descriptorSet = descriptors.handle();
            this.stableRadiance = stableRadiance;
            this.images = images;
            this.allImages = Arrays.stream(allImages).distinct().toArray();
            this.primaryDirectInputImages = phaseImages(allImages, PRIMARY_DIRECT_INPUT);
            this.primaryInputImages = phaseImages(allImages, PRIMARY_INPUT);
            this.nextStepInputImages = phaseImages(allImages, NEXT_STEP_INPUT);
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
                VulkanImage[] images = outputImages(stableRadiance, signals);
                long[] views = new long[images.length];
                long[] imageHandles = new long[images.length];
                VulkanDescriptors.Binding[] bindings =
                        new VulkanDescriptors.Binding[images.length + 2];
                for (int index = 0; index < images.length; index++) {
                    views[index] = images[index].view();
                    imageHandles[index] = images[index].image();
                    bindings[index] = VulkanDescriptors.image(
                            IMAGE_BINDINGS[index].descriptor,
                            VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            views[index],
                            VK12.VK_IMAGE_LAYOUT_GENERAL);
                }
                bindings[images.length] = VulkanDescriptors.buffer(
                        ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        wavefront.handle(),
                        0L,
                        queueOffset);
                bindings[images.length + 1] = VulkanDescriptors.buffer(
                        ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        wavefront.handle(),
                        queueOffset,
                        wavefront.size() - queueOffset);
                VulkanDescriptors.BoundSet descriptors = VulkanDescriptors.bind(
                        context,
                        stack,
                        layout,
                        "realtime trace",
                        bindings);
                return new OutputBindings(
                        descriptors,
                        stableRadiance.view(),
                        views,
                        imageHandles,
                        wavefront.handle());
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
            for (int index = 0; index < IMAGE_BINDINGS.length; index++) {
                if (this.images[index]
                        != IMAGE_BINDINGS[index].image(candidateStableRadiance, signals).view()) {
                    return false;
                }
            }
            return true;
        }

        private static VulkanImage[] outputImages(
                VulkanImage stableRadiance, RawWavefrontFrame signals) {
            VulkanImage[] images = new VulkanImage[IMAGE_BINDINGS.length];
            for (int index = 0; index < images.length; index++) {
                images[index] = IMAGE_BINDINGS[index].image(stableRadiance, signals);
            }
            return images;
        }

        /** Guide outputs need the WAW dependency even before their first read. */
        private static long[] phaseImages(long[] images, int phase) {
            return java.util.stream.IntStream.range(0, IMAGE_BINDINGS.length)
                    .filter(index -> (IMAGE_BINDINGS[index].phases & phase) != 0)
                    .mapToLong(index -> images[index])
                    .distinct()
                    .toArray();
        }

        @Override
        public void destroy() {
            this.descriptors.destroy();
        }
    }

    private enum ImageBinding {
        STABLE(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE, PRIMARY_INPUT | NEXT_STEP_INPUT),
        NOISY_DIFFUSE(ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE, PRIMARY_DIRECT_INPUT | PRIMARY_INPUT | NEXT_STEP_INPUT),
        NOISY_SPECULAR(ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR, PRIMARY_DIRECT_INPUT | PRIMARY_INPUT | NEXT_STEP_INPUT),
        NORMAL_ROUGHNESS(ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS, NEXT_STEP_INPUT),
        VIEW_Z(ShaderAbi.DESCRIPTOR_NRD_VIEW_Z, PRIMARY_INPUT | NEXT_STEP_INPUT),
        TRANSPORT_METADATA(ShaderAbi.DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA, NEXT_STEP_INPUT),
        MATERIAL(ShaderAbi.DESCRIPTOR_NRD_MATERIAL, PRIMARY_INPUT | NEXT_STEP_INPUT),
        SPECULAR_MATERIAL(ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL, PRIMARY_INPUT | NEXT_STEP_INPUT),
        PRIMARY_POSITION(ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION, PRIMARY_INPUT | NEXT_STEP_INPUT),
        DIFFUSE_DIRECTION(ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION, PRIMARY_INPUT | NEXT_STEP_INPUT),
        SPECULAR_DIRECTION(ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION, PRIMARY_INPUT | NEXT_STEP_INPUT),
        REFLECTION_NOISY_DIFFUSE(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_DIFFUSE, NEXT_STEP_INPUT),
        REFLECTION_NOISY_SPECULAR(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_SPECULAR, NEXT_STEP_INPUT),
        REFLECTION_NORMAL_ROUGHNESS(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NORMAL_ROUGHNESS, NEXT_STEP_INPUT),
        REFLECTION_MATERIAL(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_MATERIAL, NEXT_STEP_INPUT),
        REFLECTION_SPECULAR_MATERIAL(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_MATERIAL, NEXT_STEP_INPUT),
        REFLECTION_POSITION(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_POSITION, NEXT_STEP_INPUT),
        REFLECTION_DIFFUSE_DIRECTION(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_DIFFUSE_DIRECTION, NEXT_STEP_INPUT),
        REFLECTION_SPECULAR_DIRECTION(ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_DIRECTION, NEXT_STEP_INPUT),
        DISPLAY_POSITION(ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION, 0),
        SUN_LIGHTING(ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING, PRIMARY_INPUT),
        SUN_PENUMBRA(ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA, PRIMARY_INPUT | NEXT_STEP_INPUT),
        RECONSTRUCTION_CONTROL(ShaderAbi.DESCRIPTOR_RECONSTRUCTION_CONTROL, 0);

        final int descriptor;
        final int phases;

        ImageBinding(int descriptor, int phases) {
            this.descriptor = descriptor;
            this.phases = phases;
        }

        VulkanImage image(VulkanImage stableRadiance, RawWavefrontFrame raw) {
            return switch (this) {
                case STABLE -> stableRadiance;
                case NOISY_DIFFUSE -> raw.noisyDiffuse();
                case NOISY_SPECULAR -> raw.noisySpecular();
                case NORMAL_ROUGHNESS -> raw.normalRoughness();
                case VIEW_Z -> raw.viewZ();
                case TRANSPORT_METADATA -> raw.transportScratch();
                case MATERIAL -> raw.material();
                case SPECULAR_MATERIAL -> raw.specularMaterial();
                case PRIMARY_POSITION -> raw.primaryPosition();
                case DIFFUSE_DIRECTION -> raw.diffuseDirection();
                case SPECULAR_DIRECTION -> raw.specularDirection();
                case REFLECTION_NOISY_DIFFUSE -> raw.reflectionNoisyDiffuse();
                case REFLECTION_NOISY_SPECULAR -> raw.reflectionNoisySpecular();
                case REFLECTION_NORMAL_ROUGHNESS -> raw.reflectionNormalRoughness();
                case REFLECTION_MATERIAL -> raw.reflectionMaterial();
                case REFLECTION_SPECULAR_MATERIAL -> raw.reflectionSpecularMaterial();
                case REFLECTION_POSITION -> raw.reflectionPosition();
                case REFLECTION_DIFFUSE_DIRECTION -> raw.reflectionDiffuseDirection();
                case REFLECTION_SPECULAR_DIRECTION -> raw.reflectionSpecularDirection();
                case DISPLAY_POSITION -> raw.displayPosition();
                case SUN_LIGHTING -> raw.sunLighting();
                case SUN_PENUMBRA -> raw.sunPenumbra();
                case RECONSTRUCTION_CONTROL -> raw.reconstructionControl();
            };
        }
    }

}
