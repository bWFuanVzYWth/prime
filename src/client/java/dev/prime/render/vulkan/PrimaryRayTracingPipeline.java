package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.FrameCamera;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;

/** One-dispatch renderer that returns the base color of the nearest primary-ray hit. */
public final class PrimaryRayTracingPipeline implements Destroyable {
    static final int DESCRIPTOR_BINDING_COUNT = 1;
    private static final String[] FIXED_RESOURCES = {
        GeneratedShaderPrograms.resource("primary_ray_rmiss"),
        GeneratedShaderPrograms.resource("primary_ray_rmiss"),
        GeneratedShaderPrograms.resource("primary_ray_rchit"),
        GeneratedShaderPrograms.resource("primary_ray_rahit"),
        GeneratedShaderPrograms.resource("primary_ray_rahit"),
        GeneratedShaderPrograms.resource("primary_ray_rahit"),
        GeneratedShaderPrograms.resource("primary_ray_rchit")
    };

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final TraceProgram program;
    private VulkanDescriptors.BoundSet outputSet;
    private VulkanImage output;
    private boolean destroyed;

    public PrimaryRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
                VulkanDescriptors.layoutBinding(
                        bindings.get(0),
                        0,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        1,
                        KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                setLayout = VulkanDescriptors.createSetLayout(
                        context,
                        stack,
                        bindings,
                        "create Prime primary-ray output descriptor layout");
            }
            traceProgram = TraceProgram.create(
                    context,
                    GeneratedShaderPrograms.schedule("primary"),
                    FIXED_RESOURCES,
                    "Prime primary-ray pipeline",
                    "Prime primary-ray shader binding table",
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

    public VulkanImage ensureResources(
            int width,
            int height,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            MaterialTexturePages.Binding materialTextures,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.SurfaceBinding surfaces,
            TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                materialTextures,
                materialCore,
                surfaces,
                tintSamples,
                atmosphere);
        VulkanImage current = this.output;
        if (current != null && current.width() == width && current.height() == height) {
            return current;
        }
        VulkanImage replacement = this.context.createImage2D(
                width,
                height,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                "Prime primary-ray output");
        VulkanDescriptors.BoundSet replacementSet;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            replacementSet = VulkanDescriptors.bindStorageImages(
                    this.context,
                    stack,
                    this.descriptorSetLayout,
                    List.of(replacement),
                    "Prime primary-ray output");
        } catch (RuntimeException exception) {
            replacement.destroy();
            throw exception;
        }
        VulkanDescriptors.BoundSet previousSet = this.outputSet;
        this.outputSet = replacementSet;
        this.output = replacement;
        if (previousSet != null) {
            this.context.defer(previousSet);
        }
        if (current != null) {
            this.context.defer(current);
        }
        return replacement;
    }

    public void trace(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            TerrainScene.ResidentSceneView scene) {
        VulkanImage target = this.output;
        VulkanDescriptors.BoundSet descriptors = this.outputSet;
        if (target == null || descriptors == null) {
            throw new IllegalStateException("Primary-ray resources have not been initialized");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = PrimaryRayPushConstants.encode(
                    stack, camera, target.width(), target.height(), scene);
            this.program.bind(
                    commandBuffer,
                    stack,
                    pushConstants,
                    this.backend.bindings().descriptorSet(),
                    descriptors.handle());
            WavefrontCommands.trace(
                    commandBuffer, stack, this.program, target.width(), target.height(), 0);
        }
    }

    public int outputWidth() {
        return this.output == null ? 0 : this.output.width();
    }

    public int outputHeight() {
        return this.output == null ? 0 : this.output.height();
    }

    public long sizedResourceBytes() {
        return this.output == null ? 0L : (long) this.output.width() * this.output.height() * 4L;
    }

    public void releaseSizedResourcesAfterIdle() {
        VulkanDescriptors.BoundSet currentSet = this.outputSet;
        VulkanImage current = this.output;
        this.outputSet = null;
        this.output = null;
        if (currentSet != null) {
            currentSet.destroy();
        }
        if (current != null) {
            current.destroy();
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        RuntimeException failure = null;
        failure = dev.prime.infrastructure.ResourceCleanup.destroy(this.outputSet, failure);
        failure = dev.prime.infrastructure.ResourceCleanup.destroy(this.output, failure);
        failure = dev.prime.infrastructure.ResourceCleanup.destroy(this.program, failure);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        this.outputSet = null;
        this.output = null;
        dev.prime.infrastructure.ResourceCleanup.throwIfFailed(failure);
    }
}
