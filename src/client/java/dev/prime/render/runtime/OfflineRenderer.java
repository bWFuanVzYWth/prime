package dev.prime.render.runtime;

import dev.prime.render.*;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.OfflineRayTracingPipeline;
import dev.prime.render.vulkan.OfflineFrameExecutor;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import java.util.Objects;
import org.joml.Matrix4fc;

/** Owns the offline pipeline, frozen session, scheduler, and native-sized resources. */
final class OfflineRenderer implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private final OfflineFrameExecutor executor;
    private OfflineRayTracingPipeline pipeline;
    private OfflineRenderResources resources;
    private OfflineSession session;
    private boolean destroyed;

    OfflineRenderer(VulkanContext context, TraceBackend backend) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = new OfflineFrameExecutor(context);
        this.pipeline = new OfflineRayTracingPipeline(context, backend);
    }

    boolean active() {
        return this.session != null;
    }

    OfflineSession session() {
        return this.session;
    }

    void begin(OfflineSession value) {
        if (this.session != null) {
            throw new IllegalStateException("Offline renderer is already active");
        }
        if (this.resources != null) {
            throw new IllegalStateException("Inactive offline renderer retained sized resources");
        }
        this.session = Objects.requireNonNull(value, "value");
    }

    void stopAfterIdle() {
        OfflineSession previous = this.session;
        this.session = null;
        OfflineRenderResources previousResources = this.resources;
        this.resources = null;
        RuntimeException failure = ResourceCleanup.destroy(previousResources, null);
        if (this.pipeline != null) {
            failure = ResourceCleanup.run(
                    this.pipeline::releaseSizedResourcesAfterIdle, failure);
        }
        failure = ResourceCleanup.destroy(previous, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    boolean updateProjection(Matrix4fc projection) {
        OfflineSession current = this.session;
        if (current == null || !current.updateProjection(projection)) {
            return false;
        }
        OfflineRenderResources previous = this.resources;
        this.resources = null;
        if (previous != null) {
            this.context.defer(previous);
        }
        return true;
    }

    OfflineRayTracingPipeline pipeline() {
        return this.pipeline;
    }

    OfflineFrameExecutor executor() {
        return this.executor;
    }

    OfflineRenderResources resources() {
        return this.resources;
    }

    boolean hasSizedResources() {
        return this.resources != null;
    }

    DiagnosticSnapshot diagnosticSnapshot() {
        OfflineSession currentSession = this.session;
        if (currentSession == null) {
            return null;
        }
        OfflineRenderResources currentResources = this.resources;
        int width = currentResources == null
                ? 0
                : currentResources.displayOutput.width();
        int height = currentResources == null
                ? 0
                : currentResources.displayOutput.height();
        return new DiagnosticSnapshot(
                width, height, currentSession.sampleCount());
    }

    OfflineRenderResources ensureResources(int width, int height) {
        if (this.session == null) {
            throw new IllegalStateException("Offline resources require an active session");
        }
        OfflineRenderResources current = this.resources;
        if (current != null && current.matches(width, height)) {
            return current;
        }
        OfflineRenderResources replacement =
                OfflineRenderResources.create(
                        this.context,
                        width,
                        height,
                        this.session.exposure().buffer());
        this.resources = replacement;
        this.session.resetAccumulation();
        if (current != null) {
            this.context.defer(current);
        }
        return replacement;
    }

    /** Renders one frozen-scene sample; false means the frozen atlas is no longer valid. */
    boolean render(RenderInput input) {
        Objects.requireNonNull(input, "input");
        OfflineSession current = this.session;
        if (current == null) {
            return false;
        }
        if (!(input.mainTarget().getColorTexture() instanceof VulkanGpuTexture mainColor)) {
            throw new IllegalStateException("Prime expected a Vulkan main color texture");
        }
        if (mainColor.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Prime requires an RGBA8_UNORM main target");
        }
        int width = mainColor.getWidth(0);
        int height = mainColor.getHeight(0);
        if (width <= 0
                || height <= 0
                || input.mainTarget().width != width
                || input.mainTarget().height != height) {
            return true;
        }
        this.requireRayDispatchCapacity(width, height);
        if (!current.matchesAtlas(
                input.atlasView().vkImageView(),
                input.atlasSampler().vkSampler(),
                input.textureRevision())) {
            return false;
        }

        OfflineRenderResources images = this.ensureResources(width, height);
        OfflineRayTracingPipeline activePipeline = this.pipeline();
        activePipeline.ensureDescriptors(
                current.scene().tlas(),
                images.runningMean,
                input.atlasView(),
                input.atlasSampler(),
                current.sceneTextures(),
                input.materialTextures().baseColorPages(),
                input.materialTextures().normalPages(),
                input.materialTextures().opticalPages(),
                input.materialTextures().textureRecords(),
                current.scene().materialCore(),
                current.scene().tintSamples(),
                input.atmosphere());
        OfflineFramePlan framePlan = new OfflineFrameInput(
                current.camera(),
                width,
                height,
                current.scene().revision(),
                current.textureRevision(),
                current.astronomy(),
                current.cameraInWater(),
                current.settings().lighting(),
                current.settings().material(),
                current.settings().maximumBounces(),
                current.sampleCount(),
                input.display()).plan();
        this.executor.execute(
                activePipeline,
                input.sunShadow(),
                input.atmosphere(),
                input.materialTextures(),
                current.scene(),
                framePlan,
                images.displayOutput,
                images.runningMean,
                images.display,
                input.atlasView(),
                current.sceneTextures(),
                current.textureRevision(),
                mainColor);
        current.commitSample();
        if (current.sampleCount() > 0L
                && (current.sampleCount() & (current.sampleCount() - 1L)) == 0L) {
            PrimeInfo.LOGGER.info(
                    "Prime screenshot accumulation reached {} samples",
                    current.sampleCount());
        }
        return true;
    }

    private void requireRayDispatchCapacity(int width, int height) {
        long invocationCount = (long) width * height;
        if (invocationCount
                > Integer.toUnsignedLong(
                        this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException(
                    "Render dimensions exceed the Vulkan ray dispatch limit");
        }
    }

    record RenderInput(
            RenderTarget mainTarget,
            DisplaySettings.Snapshot display,
            AtmospherePipeline atmosphere,
            SunShadowPipeline sunShadow,
            MaterialTexturePages materialTextures,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            long textureRevision) {
        RenderInput {
            Objects.requireNonNull(mainTarget, "mainTarget");
            Objects.requireNonNull(display, "display");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(materialTextures, "materialTextures");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(atlasSampler, "atlasSampler");
            if (textureRevision < 0L) {
                throw new IllegalArgumentException(
                        "Offline texture revision must be non-negative");
            }
        }
    }

    record DiagnosticSnapshot(int width, int height, long accumulatedSamples) {}

    void reload() {
        OfflineRayTracingPipeline replacementPipeline = null;
        OfflineRenderResources replacementResources = null;
        try {
            replacementPipeline = new OfflineRayTracingPipeline(this.context, this.backend);
            OfflineRenderResources current = this.resources;
            if (current != null) {
                if (this.session == null) {
                    throw new IllegalStateException(
                            "Offline resources exist without a session");
                }
                replacementResources = OfflineRenderResources.create(
                        this.context,
                        current.displayOutput.width(),
                        current.displayOutput.height(),
                        this.session.exposure().buffer());
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementResources, exception);
            ResourceCleanup.destroy(replacementPipeline, exception);
            throw exception;
        }
        OfflineRayTracingPipeline previousPipeline = this.pipeline;
        OfflineRenderResources previousResources = this.resources;
        this.pipeline = replacementPipeline;
        this.resources = replacementResources;
        if (this.session != null) {
            this.session.resetAccumulation();
        }
        if (previousPipeline != null) {
            this.context.defer(previousPipeline);
        }
        if (previousResources != null) {
            this.context.defer(previousResources);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.resources, failure);
        failure = ResourceCleanup.destroy(this.pipeline, failure);
        failure = ResourceCleanup.destroy(this.session, failure);
        this.resources = null;
        this.session = null;
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
