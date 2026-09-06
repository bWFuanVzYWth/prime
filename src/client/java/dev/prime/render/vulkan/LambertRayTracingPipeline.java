package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.BounceSettings;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** One-path Lambert transport. The frame owner alone replaces descriptors and sized resources. */
public final class LambertRayTracingPipeline implements RealtimeTracePipeline {
    static final WavefrontLayout LAYOUT = new WavefrontLayout(1, 1, 3,
            ShaderAbi.LAMBERT_RECORD_SIZE, ShaderAbi.LAMBERT_SCRATCH_RECORD_SIZE, ShaderAbi.LAMBERT_QUEUE_COUNT,
            ShaderAbi.LAMBERT_COMMAND_STRIDE, ShaderAbi.LAMBERT_INDEX_SIZE, "Lambert");
    private static final RealtimeRayTracingPipeline.ImageBinding[] IMAGE_BINDINGS =
            RealtimeRayTracingPipeline.ImageBinding.values();
    private static final String[] FIXED_RESOURCES = {
        GeneratedShaderPrograms.resource("lambert_world_rmiss"),
        GeneratedShaderPrograms.resource("lambert_shadow_rmiss"),
        GeneratedShaderPrograms.resource("lambert_world_rchit"),
        GeneratedShaderPrograms.resource("lambert_world_rahit"),
        GeneratedShaderPrograms.resource("lambert_shadow_opaque_rahit"),
        GeneratedShaderPrograms.resource("lambert_shadow_rahit"),
        GeneratedShaderPrograms.resource("lambert_shadow_rchit")
    };
    private final VulkanContext context;
    private final TraceBackend backend;
    private final long layout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private VulkanDescriptors.BoundSet descriptors;
    private long[] views;
    private long[] imageHandles;
    private boolean destroyed;
    private int lastRecordedPassCount = dispatchCount(BounceSettings.DEFAULT_FIXED_COUNT, BounceSettings.DEFAULT_COUNT);

    public LambertRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = backend;
        long candidateLayout = 0L;
        TraceProgram candidateProgram = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // The image formats/aliases are the reconstruction ABI, independent of transport.
            candidateLayout = RealtimeRayTracingPipeline.createDescriptorSetLayout(context, stack);
            boolean subgroup = context.capabilities().wavefrontSubgroupSupported();
            boolean ser = subgroup && context.capabilities().invocationReorderSupported();
            candidateProgram = TraceProgram.create(context, schedule(subgroup, ser),
                    fixedResources(ser), "Prime Lambert wavefront", "Prime Lambert SBT",
                    backend.bindings().descriptorSetLayout(), candidateLayout);
            this.layout = candidateLayout;
            this.program = candidateProgram;
        } catch (RuntimeException failure) {
            ResourceCleanup.destroy(candidateProgram, failure);
            if (candidateLayout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), candidateLayout, null);
            throw failure;
        }
    }

    static RaygenSchedule schedule(boolean subgroupSupported, boolean serSupported) {
        return GeneratedShaderPrograms.schedule(subgroupSupported && serSupported ? "lambert.ser"
                : subgroupSupported ? "lambert.subgroup" : "lambert");
    }

    static String[] fixedResources(boolean ser) {
        String[] resources = FIXED_RESOURCES.clone();
        if (ser) resources[3] = GeneratedShaderPrograms.resource("lambert_world_rahit_ser");
        return resources;
    }

    static int bounceLimit(int minimum, int maximum) {
        return Math.max(BounceSettings.validateFixedCount(minimum), BounceSettings.validateCount(maximum));
    }
    static int dispatchCount(int minimum, int maximum) { return 2 * bounceLimit(minimum, maximum) + 4; }
    static int queueMetadata(int minimum, int delta) {
        return BounceSettings.validateFixedCount(minimum) | (BounceSettings.validateCount(delta) << 8);
    }
    @Override public int passCount() { return this.lastRecordedPassCount; }
    @Override public long sizedResourceBytes() { return this.wavefront == null ? 0L : this.wavefront.size(); }

    @Override
    public void ensureDescriptors(long tlas, VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView, VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures, MaterialTexturePages.Binding materialTextures,
            TerrainScene.MaterialCoreBinding materialCore, TerrainScene.SurfaceBinding surfaces,
            TerrainScene.TintSampleBinding tintSamples, AtmospherePipeline atmosphere, RawWavefrontFrame signals) {
        this.backend.ensureSceneDescriptors(tlas, atlasView, atlasSampler, sceneTextures,
                materialTextures, materialCore, surfaces, tintSamples, atmosphere);
        int width = stableRadiance.width();
        int height = stableRadiance.height();
        LAYOUT.validateRanges(width, height, this.context.maxStorageBufferRange());
        LAYOUT.validateDispatch(width, height, this.context.capabilities().maxRayDispatchInvocationCount());
        long bytes = LAYOUT.wavefrontBytes(width, height);
        VulkanImage[] images = Arrays.stream(IMAGE_BINDINGS)
                .map(binding -> binding.image(stableRadiance, signals)).toArray(VulkanImage[]::new);
        long[] newViews = Arrays.stream(images).mapToLong(VulkanImage::view).toArray();
        boolean replace = this.wavefront == null || this.wavefront.size() != bytes;
        if (!replace && Arrays.equals(this.views, newViews)) return;
        VulkanBuffer candidate = replace ? this.context.createBuffer(bytes,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                        | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "Prime Lambert paths and queues") : this.wavefront;
        VulkanDescriptors.BoundSet newDescriptors;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanDescriptors.Binding[] bindings = new VulkanDescriptors.Binding[images.length + 2];
            for (int i = 0; i < images.length; ++i) bindings[i] = VulkanDescriptors.image(
                    IMAGE_BINDINGS[i].descriptor, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    images[i].view(), VK12.VK_IMAGE_LAYOUT_GENERAL);
            long queueOffset = LAYOUT.queueOffset(width, height);
            bindings[images.length] = VulkanDescriptors.buffer(ShaderAbi.LAMBERT_PATH_BINDING,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, candidate.handle(), 0L, queueOffset);
            bindings[images.length + 1] = VulkanDescriptors.buffer(ShaderAbi.LAMBERT_QUEUE_BINDING,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, candidate.handle(), queueOffset, bytes - queueOffset);
            newDescriptors = VulkanDescriptors.bind(this.context, stack, this.layout, "Lambert wavefront", bindings);
        } catch (RuntimeException failure) {
            if (replace) ResourceCleanup.destroy(candidate, failure);
            throw failure;
        }
        VulkanDescriptors.BoundSet previous = this.descriptors;
        VulkanBuffer oldBuffer = this.wavefront;
        this.descriptors = newDescriptors;
        this.wavefront = candidate;
        this.views = newViews;
        this.imageHandles = Arrays.stream(images).mapToLong(VulkanImage::image).distinct().toArray();
        if (previous != null) this.context.defer(previous);
        if (replace && oldBuffer != null) this.context.defer(oldBuffer);
    }

    @Override
    public void trace(VkCommandBuffer command, IntegratorFrameInput input, TerrainScene.ResidentSceneView scene) {
        if (this.wavefront == null || this.wavefront.size() != LAYOUT.wavefrontBytes(input.width(), input.height()))
            throw new IllegalStateException("Lambert resources do not match the frame extent");
        if (!this.backend.bindings().ready()) throw new IllegalStateException("Trace backend is not ready");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.program.bind(command, stack, RayTracingPushConstants.encode(stack, input, scene),
                    this.backend.bindings().descriptorSet(), this.descriptors.handle());
            long commands = LAYOUT.queueCommandOffset(input.width(), input.height());
            WavefrontCommands.initializeQueues(command, stack, this.wavefront, commands,
                    ShaderAbi.LAMBERT_QUEUE_COUNT, ShaderAbi.LAMBERT_COMMAND_STRIDE,
                    queueMetadata(input.minimumBounces(), input.additionalSpecularBounces()));
            WavefrontCommands.trace(command, stack, this.program, input.width(), input.height(),
                    GeneratedShaderPrograms.LAMBERT_CAMERA);
            this.outputBarrier(command, stack);
            WavefrontCommands.traceIndirect(command, stack, this.program, this.wavefront,
                    GeneratedShaderPrograms.LAMBERT_GUIDE, commands, 2, ShaderAbi.LAMBERT_COMMAND_STRIDE);
            this.outputBarrier(command, stack);
            WavefrontCommands.traceIndirect(command, stack, this.program, this.wavefront,
                    GeneratedShaderPrograms.LAMBERT_FIRST, commands, 0, ShaderAbi.LAMBERT_COMMAND_STRIDE);
            int limit = bounceLimit(input.minimumBounces(), input.maximumBounces());
            for (int bounce = 1; bounce <= limit; ++bounce) {
                int queue = bounce & 1;
                WavefrontCommands.wavefrontBarrier(command, stack, this.wavefront);
                WavefrontCommands.traceIndirect(command, stack, this.program, this.wavefront,
                        queue == 0 ? GeneratedShaderPrograms.LAMBERT_TRACE_0 : GeneratedShaderPrograms.LAMBERT_TRACE_1,
                        commands, queue, ShaderAbi.LAMBERT_COMMAND_STRIDE);
                WavefrontCommands.wavefrontBarrier(command, stack, this.wavefront);
                int shade = bounce == limit
                        ? (queue == 0 ? GeneratedShaderPrograms.LAMBERT_TERMINAL_0 : GeneratedShaderPrograms.LAMBERT_TERMINAL_1)
                        : (queue == 0 ? GeneratedShaderPrograms.LAMBERT_SHADE_0 : GeneratedShaderPrograms.LAMBERT_SHADE_1);
                WavefrontCommands.traceIndirect(command, stack, this.program, this.wavefront,
                        shade,
                        commands, queue, ShaderAbi.LAMBERT_COMMAND_STRIDE);
            }
            this.outputBarrier(command, stack);
            WavefrontCommands.trace(command, stack, this.program, input.width(), input.height(),
                    GeneratedShaderPrograms.LAMBERT_RESOLVE);
            this.lastRecordedPassCount = dispatchCount(input.minimumBounces(), input.maximumBounces());
        }
    }

    private void outputBarrier(VkCommandBuffer command, MemoryStack stack) {
        long rt = KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        VulkanSync.resourceBarrier(command, stack, this.wavefront, this.imageHandles, rt,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, rt | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    @Override public void releaseSizedResourcesAfterIdle() {
        RuntimeException failure = ResourceCleanup.destroy(this.descriptors, null);
        failure = ResourceCleanup.destroy(this.wavefront, failure);
        this.descriptors = null;
        this.wavefront = null;
        this.views = null;
        this.imageHandles = null;
        ResourceCleanup.throwIfFailed(failure);
    }

    @Override public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        RuntimeException failure = ResourceCleanup.run(this::releaseSizedResourcesAfterIdle, null);
        failure = ResourceCleanup.destroy(this.program, failure);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.layout, null);
        ResourceCleanup.throwIfFailed(failure);
    }
}
