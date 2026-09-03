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
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.terrain.TerrainScene;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayExposureDiagnostics;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.RealtimeFrameExecutor;
import dev.prime.render.vulkan.RealtimeRayTracingPipeline;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.reconstruction.ReconstructionBackendRegistry;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.ResolvedReconstruction;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionResources;
import java.util.List;
import java.util.Objects;

/** Owns the complete interactive pipeline, scheduler state, and sized resources. */
final class RealtimeRenderer implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private final RealtimeFrameExecutor executor;
    private final DisplayExposureDiagnostics exposureDiagnostics;
    private final DlssRrNative.Context ngxContext;
    private final ReconstructionBackendRegistry reconstructionRegistry;
    private RealtimeRayTracingPipeline pipeline;
    private VulkanReconstructionResources resources;
    private RealtimeSampleState sampleState = RealtimeSampleState.initial();
    private MaterialSettings.Snapshot materialSettings;
    private TransparentNeeMode transparentNeeMode;
    private boolean destroyed;

    RealtimeRenderer(
            VulkanContext context,
            TraceBackend backend,
            DlssRrNative.Context ngxContext) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ngxContext = ngxContext;
        this.reconstructionRegistry = new ReconstructionBackendRegistry(context, ngxContext);
        this.pipeline = new RealtimeRayTracingPipeline(context, backend);
        this.executor = new RealtimeFrameExecutor(context);
        this.exposureDiagnostics = new DisplayExposureDiagnostics(context);
    }

    RealtimeRayTracingPipeline pipeline() {
        return this.pipeline;
    }

    RealtimeFrameExecutor executor() {
        return this.executor;
    }

    VulkanReconstructionResources resources() {
        return this.resources;
    }

    boolean hasSizedResources() {
        return this.resources != null;
    }

    long displayExposureStateBuffer() {
        VulkanReconstructionResources current = this.resources;
        if (current == null) {
            throw new IllegalStateException("Realtime exposure requires sized resources");
        }
        return current.processor().displayExposureStateBuffer();
    }

    RealtimeSampleState.Plan planSample(RealtimeSampleState.Input input) {
        return this.sampleState.plan(input);
    }

    void commitSample(RealtimeSampleState.Plan plan) {
        this.sampleState = plan.committedState();
    }

    int sampleIndex() {
        return this.sampleState.sampleIndex();
    }

    DiagnosticSnapshot diagnosticSnapshot() {
        VulkanReconstructionResources current = this.resources;
        if (current == null) {
            return null;
        }
        return new DiagnosticSnapshot(
                current.selection().effectiveMode(),
                current.selection().quality(),
                current.stableRadiance().width(),
                current.stableRadiance().height(),
                current.output().width(),
                current.output().height(),
                this.sampleIndex(),
                this.pipeline.passCount(),
                this.pipeline.sizedResourceBytes(),
                this.exposureDiagnostics.latest());
    }

    DisplayExposureDiagnostics.Snapshot exposureDiagnosticSnapshot() {
        return this.exposureDiagnostics.latest();
    }

    void setExposureDiagnosticsEnabled(boolean enabled) {
        this.exposureDiagnostics.setEnabled(enabled);
    }

    boolean ensureResources(
            AtmospherePipeline atmosphere,
            MaterialTexturePages materialTextures,
            ResolvedReconstruction selection,
            TerrainScene.ResidentSceneView scene,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures) {
        VulkanReconstructionResources current = this.resources;
        boolean resourcesMatch = current != null && current.matches(selection);
        if (resourcesMatch) {
            this.pipeline.ensureDescriptors(
                    scene.tlas(),
                    current.stableRadiance(),
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    materialTextures.baseColorPages(),
                    materialTextures.normalPages(),
                    materialTextures.opticalPages(),
                    materialTextures.textureRecords(),
                    scene.materialCore(),
                    scene.tintSamples(),
                    atmosphere,
                    current.processor().rawFrame());
            return false;
        }
        VulkanReconstructionResources replacementResources = null;
        try {
            replacementResources =
                    this.reconstructionRegistry.createResources(atmosphere, selection);
            this.requireRayDispatchCapacity(
                    replacementResources.selection().extent().width(),
                    replacementResources.selection().extent().height());
            this.pipeline.ensureDescriptors(
                    scene.tlas(),
                    replacementResources.stableRadiance(),
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    materialTextures.baseColorPages(),
                    materialTextures.normalPages(),
                    materialTextures.opticalPages(),
                    materialTextures.textureRecords(),
                    scene.materialCore(),
                    scene.tintSamples(),
                    atmosphere,
                    replacementResources.processor().rawFrame());
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementResources, exception);
            throw exception;
        }
        this.resources = replacementResources;
        this.sampleState = this.sampleState.invalidated();
        if (current != null) {
            this.context.defer(current);
        }
        return true;
    }

    void prewarmResources(
            AtmospherePipeline atmosphere,
            PostProcessingMode mode,
            ReconstructionQualityMode quality,
            int displayWidth,
            int displayHeight) {
        if (this.resources != null) {
            throw new IllegalStateException("Realtime resources are already prepared");
        }
        ResolvedReconstruction selection = this.reconstructionRegistry.resolve(
                mode, quality, displayWidth, displayHeight);
        this.requireRayDispatchCapacity(
                selection.extent().width(), selection.extent().height());
        this.resources = this.reconstructionRegistry.createResources(
                atmosphere, selection);
        this.sampleState = this.sampleState.invalidated();
    }

    void render(RenderInput input) {
        Objects.requireNonNull(input, "input");
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
            return;
        }

        RendererSettings settings = input.settings();
        PostProcessingMode requestedMode = input.controls().rawOutput()
                ? PostProcessingMode.DISABLED
                : settings.postProcessingMode();
        ResolvedReconstruction requestedSelection = this.reconstructionRegistry.resolve(
                requestedMode,
                settings.reconstructionQuality(),
                width,
                height);
        this.requireRayDispatchCapacity(
                requestedSelection.extent().width(), requestedSelection.extent().height());
        boolean resized = this.ensureResources(
                input.atmosphere(),
                input.materialTextures(),
                requestedSelection,
                input.scene(),
                input.atlasView(),
                input.atlasSampler(),
                input.sceneTextures());
        boolean materialChanged = !settings.material().equals(this.materialSettings);
        this.materialSettings = settings.material();
        boolean transparentNeeChanged = settings.lighting().transparentNeeMode()
                != this.transparentNeeMode;
        this.transparentNeeMode = settings.lighting().transparentNeeMode();
        boolean reconfigured = resized || materialChanged || transparentNeeChanged;
        VulkanReconstructionResources images = this.resources;
        if (images == null) {
            return;
        }
        ResolvedReconstruction selection = images.selection();
        int renderWidth = selection.extent().width();
        int renderHeight = selection.extent().height();
        if (reconfigured) {
            PrimeInfo.LOGGER.debug(
                    "Reconfigured Prime realtime path at display {}x{}, render {}x{}, {} {} "
                            + "(output image={}, view={}; accumulation image={}, view={}; "
                            + "atlas image={}, view={}, sampler={})",
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    selection.effectiveMode().id(),
                    selection.quality().id(),
                    hex(images.output().image()),
                    hex(images.output().view()),
                    hex(images.stableRadiance().image()),
                    hex(images.stableRadiance().view()),
                    hex(input.atlasView().texture().vkImage()),
                    hex(input.atlasView().vkImageView()),
                    hex(input.atlasSampler().vkSampler()));
        }

        VulkanImage target = images.output();
        VulkanImage history = images.stableRadiance();
        VulkanReconstructionProcessor processor = images.processor();
        long frameTimeNanos = System.nanoTime();
        RealtimeSampleState.Plan sampleFrame = this.planSample(new RealtimeSampleState.Input(
                input.camera(), frameTimeNanos, input.scene().resetRevision(), reconfigured));
        var jitter = selection.jitter(sampleFrame.frameIndex());
        ReconstructionFrameParameters postParameters = new ReconstructionFrameParameters(
                sampleFrame.camera(),
                sampleFrame.historyCamera(),
                sampleFrame.frameIndex(),
                jitter,
                sampleFrame.reset(),
                sampleFrame.deltaMilliseconds(),
                input.astronomy().sunDirection(),
                settings.lighting().sunMultiplier(),
                settings.display());
        ReconstructionDebugSettings debugSettings =
                new ReconstructionDebugSettings(
                        input.controls().imageDiagnostics(),
                        input.controls().rrResponsivity());
        VulkanReconstructionProcessor.Frame postFrame =
                processor.beginFrame(postParameters, debugSettings);
        IntegratorFrameInput integrator;
        try {
            integrator = new IntegratorFrameInput(
                    input.camera(),
                    renderWidth,
                    renderHeight,
                    input.astronomy(),
                    selection.rayConeParameters(
                            input.camera().projection().m00(),
                            input.camera().projection().m11()),
                    settings.additionalSpecularBounces(),
                    settings.minimumBounces(),
                    settings.maximumBounces(),
                    sampleFrame.sampleIndex(),
                    sampleFrame.epoch(),
                    selection.jitterPhase(sampleFrame.frameIndex()),
                    input.cameraInWater(),
                    selection.effectiveMode(),
                    selection.transparentGuideMode(),
                    settings.lighting(),
                    settings.material(),
                    processor.rawFrame().usesShInputs(),
                    !sampleFrame.reset());
        } catch (RuntimeException exception) {
            throw ResourceCleanup.run(() -> processor.abandon(postFrame), exception);
        }
        this.executor.execute(
                selection.executionLabel(),
                this.pipeline(),
                input.sunShadow(),
                input.atmosphere(),
                input.materialTextures(),
                input.scene(),
                integrator,
                postParameters,
                processor,
                postFrame,
                target,
                history,
                input.controls().imageDiagnostics(),
                this.exposureDiagnostics,
                input.atlasView(),
                input.sceneTextures(),
                mainColor);
        this.commitSample(sampleFrame);
        int accumulatedSamples = this.sampleIndex();
        if (accumulatedSamples >= 16
                && (accumulatedSamples & (accumulatedSamples - 1)) == 0) {
            PrimeInfo.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSamples,
                    input.scene().revision());
        }
    }

    void requestReset() {
        this.sampleState = this.sampleState.invalidated();
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

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    record RenderInput(
            RenderTarget mainTarget,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            AstronomyState astronomy,
            RendererSettings settings,
            SessionControls controls,
            boolean cameraInWater,
            AtmospherePipeline atmosphere,
            SunShadowPipeline sunShadow,
            MaterialTexturePages materialTextures,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures) {
        RenderInput {
            Objects.requireNonNull(mainTarget, "mainTarget");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(astronomy, "astronomy");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(controls, "controls");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(materialTextures, "materialTextures");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(atlasSampler, "atlasSampler");
            sceneTextures = List.copyOf(sceneTextures);
        }
    }

    record DiagnosticSnapshot(
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            int accumulatedSamples,
            int integratorPassCount,
            long integratorResourceBytes,
            DisplayExposureDiagnostics.Snapshot exposure) {}

    void releaseSizedResourcesAfterIdle() {
        VulkanReconstructionResources current = this.resources;
        this.resources = null;
        if (current != null) {
            current.destroy();
        }
        this.pipeline.releaseSizedResourcesAfterIdle();
        this.sampleState = this.sampleState.invalidated();
    }

    void reload(AtmospherePipeline atmosphere) {
        RealtimeRayTracingPipeline replacementPipeline = null;
        VulkanReconstructionResources replacementResources = null;
        try {
            replacementPipeline = new RealtimeRayTracingPipeline(this.context, this.backend);
            VulkanReconstructionResources current = this.resources;
            if (current != null) {
                replacementResources = this.reconstructionRegistry.createResources(
                        atmosphere, current.selection());
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementResources, exception);
            ResourceCleanup.destroy(replacementPipeline, exception);
            throw exception;
        }
        RealtimeRayTracingPipeline previousPipeline = this.pipeline;
        VulkanReconstructionResources previousResources = this.resources;
        this.pipeline = replacementPipeline;
        this.resources = replacementResources;
        this.sampleState = this.sampleState.invalidated();
        this.context.defer(previousPipeline);
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
        failure = ResourceCleanup.destroy(this.executor, failure);
        failure = ResourceCleanup.destroy(this.exposureDiagnostics, failure);
        failure = ResourceCleanup.destroy(this.resources, failure);
        failure = ResourceCleanup.destroy(this.pipeline, failure);
        if (this.ngxContext != null) {
            failure = ResourceCleanup.run(
                    () -> DlssRrBootstrap.release(this.ngxContext), failure);
        }
        this.resources = null;
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
