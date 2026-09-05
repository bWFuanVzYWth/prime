package dev.prime.render.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.PrimaryRayTracingPipeline;
import dev.prime.render.vulkan.PrimaryRayFrameExecutor;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;

/** Independent flat-color realtime renderer with one primary-ray dispatch per frame. */
final class PrimaryRayRenderer implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private PrimaryRayTracingPipeline pipeline;
    private final PrimaryRayFrameExecutor executor;
    private boolean destroyed;

    PrimaryRayRenderer(VulkanContext context, TraceBackend backend) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.pipeline = new PrimaryRayTracingPipeline(context, backend);
        this.executor = new PrimaryRayFrameExecutor(context);
    }

    void render(RenderInput input) {
        Objects.requireNonNull(input, "input");
        if (!(input.mainTarget().getColorTexture() instanceof VulkanGpuTexture mainColor)) {
            throw new IllegalStateException("Prime expected a Vulkan main color texture");
        }
        if (mainColor.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Prime primary rays require an RGBA8_UNORM main target");
        }
        int width = mainColor.getWidth(0);
        int height = mainColor.getHeight(0);
        if (width <= 0
                || height <= 0
                || input.mainTarget().width != width
                || input.mainTarget().height != height) {
            return;
        }
        long invocationCount = (long) width * height;
        if (invocationCount
                > Integer.toUnsignedLong(
                        this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException(
                    "Primary-ray dimensions exceed the Vulkan ray dispatch limit");
        }
        VulkanImage output = ensureResources(
                width,
                height,
                input.scene(),
                input.atlasView(),
                input.atlasSampler(),
                input.sceneTextures(),
                input.materialTextures(),
                input.atmosphere());

        this.executor.execute(
                this.pipeline,
                input.scene(),
                input.camera(),
                input.materialTextures(),
                input.atlasView(),
                input.sceneTextures(),
                output,
                mainColor);
    }

    private VulkanImage ensureResources(
            int width,
            int height,
            TerrainScene.ResidentSceneView scene,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            MaterialTexturePages materialTextures,
            AtmospherePipeline atmosphere) {
        return this.pipeline.ensureResources(
                width,
                height,
                scene.tlas(),
                atlasView,
                atlasSampler,
                sceneTextures,
                materialTextures.binding(),
                scene.materialCore(),
                scene.surfaces(),
                scene.tintSamples(),
                atmosphere);
    }

    DiagnosticSnapshot diagnosticSnapshot() {
        return new DiagnosticSnapshot(
                this.pipeline.outputWidth(),
                this.pipeline.outputHeight(),
                this.pipeline.sizedResourceBytes());
    }

    void releaseSizedResourcesAfterIdle() {
        this.pipeline.releaseSizedResourcesAfterIdle();
    }

    void reload() {
        PrimaryRayTracingPipeline replacement =
                new PrimaryRayTracingPipeline(this.context, this.backend);
        PrimaryRayTracingPipeline previous = this.pipeline;
        this.pipeline = replacement;
        this.context.defer(previous);
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = dev.prime.infrastructure.ResourceCleanup.destroy(this.executor, failure);
        failure = dev.prime.infrastructure.ResourceCleanup.destroy(this.pipeline, failure);
        this.destroyed = true;
        dev.prime.infrastructure.ResourceCleanup.throwIfFailed(failure);
    }

    record RenderInput(
            RenderTarget mainTarget,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures) {
        RenderInput {
            Objects.requireNonNull(mainTarget, "mainTarget");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(materialTextures, "materialTextures");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(atlasSampler, "atlasSampler");
            sceneTextures = List.copyOf(sceneTextures);
        }
    }

    record DiagnosticSnapshot(int width, int height, long resourceBytes) {}
}
