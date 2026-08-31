package dev.prime.render.runtime;

import dev.prime.render.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.terrain.TerrainScene;
import dev.prime.render.runtime.terrain.TerrainStreamer;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.scene.vanilla.DynamicSceneMotion;
import dev.prime.render.scene.vanilla.VanillaLabPbrAtlas;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.FrozenExposureState;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanShaderModules;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class VulkanRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final RealtimeRenderer realtimeRenderer;
    private final OfflineRenderer offlineRenderer;
    private final StagingArena stagingArena;
    private final TerrainStreamer terrain;
    private final VanillaLabPbrAtlas labPbrSource = new VanillaLabPbrAtlas();
    private final MaterialTexturePages materialTextures;
    private final RendererDataMeasurementRecorder dataMeasurements;
    private final BlockPos.MutableBlockPos cameraBlockPosition = new BlockPos.MutableBlockPos();
    private final TraceBackend traceBackend;
    private AtmospherePipeline atmosphere;
    private BlockAtlasFrame blockAtlasFrame;
    private long blockAtlasTextureRevision;
    private List<TraceBackend.SceneTexture> sceneTextures = List.of();
    private DynamicSceneFrame publishedDynamicFrame;
    private Set<DynamicSceneFrame.CompatibilityIssue> dynamicCompatibilityIssues =
            Set.of();
    private FrameCamera camera;
    private AstronomyState astronomyState;
    private OfflineSession pendingOfflineSession;
    // Manual reload requests may arrive between frames; mutation remains render-thread owned.
    private volatile boolean shaderReloadRequested;
    private String shaderFingerprint;
    private SessionControls frameControls = SessionControls.defaults();
    private List<String> debugLines = List.of();
    private RendererModeLifecycle modeLifecycle = RendererModeLifecycle.initial();
    private boolean screenshotRequestRejected;
    private volatile boolean acceptsResourceReloadEffects = true;
    private boolean closed;
    public VulkanRenderer(VulkanContext context) {
        VulkanContext newContext = java.util.Objects.requireNonNull(context, "context");
        StagingArena newStagingArena = null;
        AtmospherePipeline newAtmosphere = null;
        TraceBackend newTraceBackend = null;
        RealtimeRenderer newRealtimeRenderer = null;
        OfflineRenderer newOfflineRenderer = null;
        TerrainStreamer newTerrain = null;
        MaterialTexturePages newMaterialTextures = null;
        DlssRrNative.Context newNgxContext = null;
        try {
            newContext.prewarmSharedPrograms();
            newStagingArena = new StagingArena(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newTraceBackend = new TraceBackend(newContext);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            newMaterialTextures = new MaterialTexturePages(newContext, newStagingArena);
            newNgxContext = DlssRrBootstrap.initialize(newContext).orElse(null);
            newRealtimeRenderer = new RealtimeRenderer(
                    newContext, newTraceBackend, newNgxContext);
            newOfflineRenderer = new OfflineRenderer(newContext, newTraceBackend);
            this.context = newContext;
            this.realtimeRenderer = newRealtimeRenderer;
            this.offlineRenderer = newOfflineRenderer;
            this.stagingArena = newStagingArena;
            this.traceBackend = newTraceBackend;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
            this.materialTextures = newMaterialTextures;
            this.dataMeasurements = RendererDataMeasurementRecorder.fromSystemProperties(
                    newContext.capabilities().deviceName());
            this.shaderFingerprint = VulkanShaderModules.fingerprint();
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(newOfflineRenderer, exception);
            ResourceCleanup.destroy(newRealtimeRenderer, exception);
            if (newRealtimeRenderer == null && newNgxContext != null) {
                DlssRrNative.Context failedNgxContext = newNgxContext;
                ResourceCleanup.run(
                        () -> DlssRrBootstrap.release(failedNgxContext), exception);
            }
            ResourceCleanup.close(newMaterialTextures, exception);
            ResourceCleanup.close(newTerrain, exception);
            ResourceCleanup.destroy(newTraceBackend, exception);
            ResourceCleanup.destroy(newAtmosphere, exception);
            ResourceCleanup.close(newStagingArena, exception);
            throw exception;
        }
    }

    public static boolean bootstrapResourcesReady(Minecraft minecraft) {
        try {
            TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            return VanillaLabPbrAtlas.hasStitchedSprites(atlas)
                    && atlas.getTextureView() instanceof VulkanGpuTextureView
                    && atlas.getSampler() instanceof VulkanGpuSampler;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /** Completes resource-dependent CPU translation and immutable GPU uploads before frame use. */
    public void bootstrap(Minecraft minecraft, RendererSettings settings) {
        if (!this.synchronizeLabPbr(minecraft)) {
            throw new IllegalStateException(
                    "Prime block textures are not ready for renderer bootstrap");
        }
        this.submitBootstrapResources(this.atmosphere, true, true);
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException(
                    "Prime display extent is unavailable during renderer bootstrap");
        }
        this.realtimeRenderer.prewarmResources(
                this.atmosphere,
                settings.postProcessingMode(),
                settings.reconstructionQuality(),
                width,
                height);
    }

    public boolean beginFrame(
            Minecraft minecraft,
            SessionControls controls,
            RendererSettings settings) {
        this.frameControls = java.util.Objects.requireNonNull(controls, "controls");
        java.util.Objects.requireNonNull(settings, "settings");
        boolean screenshotRequested = controls.screenshotRequested();
        if (this.screenshotRequestRejected) {
            screenshotRequested = false;
            this.screenshotRequestRejected = false;
        }
        this.reloadPipelineIfRequested();
        this.synchronizeLabPbr(minecraft);
        this.terrain.setSurfaceDetailMode(
                settings.surfaceDetailMode(),
                settings.voxelTextureSurfaceStrengthSteps());
        this.terrain.setWorkerPercentage(settings.terrainWorkerPercentage());
        screenshotRequested = this.updateOfflineSession(
                minecraft, screenshotRequested, settings);
        if (this.screenshotActive()) {
            return screenshotRequested;
        }
        if (this.pendingOfflineSession != null) {
            return screenshotRequested;
        }
        FrameCamera frameCamera = this.camera;
        if (frameCamera != null) {
            this.terrain.update(minecraft, frameCamera.x(), frameCamera.y(), frameCamera.z());
        } else if (minecraft.player != null) {
            this.terrain.update(
                    minecraft,
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ());
        } else {
            this.terrain.update(minecraft, 0.0, 0.0, 0.0);
        }
        return screenshotRequested;
    }

    private boolean synchronizeLabPbr(Minecraft minecraft) {
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        // Atlas objects exist before their GPU texture is uploaded. getTextureView() deliberately
        // throws during that short interval, which is normal startup state rather than a renderer
        // failure. The stitch map becomes non-empty in the same upload that creates the view.
        if (!VanillaLabPbrAtlas.hasStitchedSprites(atlas)) {
            this.blockAtlasFrame = null;
            return false;
        }
        if (!(atlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
                || !(atlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
            throw new IllegalStateException("Prime expected Vulkan block atlas resources");
        }
        if (atlasView.texture().getFormat() != com.mojang.blaze3d.GpuFormat.RGBA8_UNORM
                || atlasView.texture().getDepthOrLayers() != 1) {
            throw new IllegalStateException(
                    "Prime requires a two-dimensional RGBA8 block atlas for sRGB sampling");
        }
        dev.prime.render.vulkan.SrgbTextureView.imageView(atlasView);
        LabPbrAtlasFrame labPbrFrame = this.labPbrSource.ensure(minecraft, atlas);
        this.terrain.setLabPbrMaterials(
                this.materialTextures.ensure(labPbrFrame, atlasView.vkImageView()));
        long sourceGeneration = labPbrFrame.sourceGeneration();
        BlockAtlasFrame previous = this.blockAtlasFrame;
        boolean changed = previous == null
                || previous.view().vkImageView() != atlasView.vkImageView()
                || previous.sampler().vkSampler() != atlasSampler.vkSampler()
                || previous.sourceGeneration() != sourceGeneration;
        if (changed) {
            this.blockAtlasTextureRevision =
                    Math.incrementExact(this.blockAtlasTextureRevision);
        }
        this.blockAtlasFrame = new BlockAtlasFrame(
                atlasView,
                atlasSampler,
                sourceGeneration,
                this.blockAtlasTextureRevision);
        return true;
    }

    public void captureCamera(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians,
            RendererSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        OfflineSession pending = this.pendingOfflineSession;
        if (pending != null) {
            pending.updateProjection(baseProjection);
            return;
        }
        if (this.screenshotActive()) {
            if (this.offlineRenderer.updateProjection(baseProjection)) {
                this.modeLifecycle = this.modeLifecycle.releaseOfflineSized();
                PrimeInfo.LOGGER.info(
                        "Restarted Prime offline accumulation for a new aspect ratio");
            }
            return;
        }
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.astronomyState = AstronomyState.atSolarHourAngle(
                sunAngleRadians,
                settings.astronomy());
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        if (this.screenshotActive() || this.pendingOfflineSession != null) {
            return;
        }
        if (!frame.compatibilityIssues().equals(this.dynamicCompatibilityIssues)) {
            this.dynamicCompatibilityIssues = frame.compatibilityIssues();
            for (DynamicSceneFrame.CompatibilityIssue issue
                    : this.dynamicCompatibilityIssues) {
                PrimeInfo.LOGGER.warn(
                        "Prime dynamic scene compatibility: {}",
                        issue.description());
            }
        }
        ArrayList<TraceBackend.SceneTexture> textures =
                new ArrayList<>(frame.textures().size());
        for (DynamicSceneFrame.SceneTexture texture : frame.textures()) {
            if (!(texture.view() instanceof VulkanGpuTextureView view)
                    || !(texture.sampler() instanceof VulkanGpuSampler sampler)) {
                throw new IllegalStateException(
                        "Prime expected Vulkan dynamic scene textures");
            }
            textures.add(new TraceBackend.SceneTexture(
                    view.texture().vkImage(),
                    texture.sampling() == DynamicSceneFrame.Sampling.SRGB_COLOR
                            ? dev.prime.render.vulkan.SrgbTextureView.imageView(view)
                            : view.vkImageView(),
                    sampler.vkSampler()));
        }
        List<TraceBackend.SceneTexture> capturedTextures =
                List.copyOf(textures);
        DynamicSceneMotion motion = DynamicSceneMotion.prepare(
                frame, this.publishedDynamicFrame);
        this.dataMeasurements.recordDynamicMotion(motion);
        if (this.terrain.updateDynamic(motion)) {
            this.sceneTextures = capturedTextures;
            this.publishedDynamicFrame = frame;
        }
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.residentScene() != null;
    }

    public boolean screenshotActive() {
        return this.offlineRenderer.active();
    }

    public List<String> debugLines() {
        return this.debugLines;
    }

    public void render(RenderTarget mainTarget, RendererSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        BlockAtlasFrame atlas = this.blockAtlasFrame;
        if (atlas == null) {
            this.debugLines = List.of();
            return;
        }
        if (this.screenshotActive()) {
            this.debugLines = List.of();
            boolean sessionValid = this.offlineRenderer.render(
                    new OfflineRenderer.RenderInput(
                            mainTarget,
                            settings.display(),
                            this.atmosphere,
                            this.traceBackend.sunShadowPipeline(),
                            this.materialTextures,
                            atlas.view(),
                            atlas.sampler(),
                            atlas.textureRevision()));
            if (sessionValid) {
                if (this.offlineRenderer.hasSizedResources()) {
                    this.modeLifecycle = this.modeLifecycle.allocateOfflineSized();
                }
                this.debugLines = this.withRendererDiagnostics(settings);
                return;
            }
            this.cancelOfflineSession();
        }
        this.renderRealtime(mainTarget, atlas, settings);
    }

    private void renderRealtime(
            RenderTarget mainTarget,
            BlockAtlasFrame atlas,
            RendererSettings settings) {
        TerrainScene.ResidentSceneView scene = this.terrain.residentScene();
        FrameCamera frameCamera = this.camera;
        AstronomyState frameAstronomy = this.astronomyState;
        if (scene == null || frameCamera == null || frameAstronomy == null) {
            this.debugLines = this.withRendererDiagnostics(settings);
            return;
        }
        this.realtimeRenderer.render(
                new RealtimeRenderer.RenderInput(
                        mainTarget,
                        scene,
                        frameCamera,
                        frameAstronomy,
                        RealtimeRenderSettings.capture(settings),
                        this.frameControls,
                        this.isCameraInWater(Minecraft.getInstance(), frameCamera),
                        this.atmosphere,
                        this.traceBackend.sunShadowPipeline(),
                        this.materialTextures,
                        atlas.view(),
                        atlas.sampler(),
                        atlas.textureRevision(),
                        this.sceneTextures));
        this.debugLines = this.withRendererDiagnostics(settings);
        RealtimeRenderer.DiagnosticSnapshot diagnostic =
                this.realtimeRenderer.diagnosticSnapshot();
        if (diagnostic != null) {
            this.dataMeasurements.recordFrame(
                    this.context,
                    this.materialTextures.measurementSnapshot(),
                    scene.statistics(),
                    this.terrain.mediumIdStatistics(),
                    frameCamera,
                    diagnostic);
        }
        if (this.realtimeRenderer.hasSizedResources()) {
            this.modeLifecycle = this.modeLifecycle.allocateRealtimeSized();
        }
    }

    private List<String> withRendererDiagnostics(RendererSettings settings) {
        if (!this.frameControls.rendererDiagnostics()) {
            return List.of();
        }
        OfflineSession offlineSession = this.offlineRenderer.session();
        TerrainScene.ResidentSceneView scene = offlineSession == null
                ? this.terrain.residentScene()
                : offlineSession.scene();

        TerrainScene.CompactionStats stats = this.terrain.compactionStats();
        TerrainScene.SceneStatistics sceneStats = scene == null
                ? null
                : scene.statistics();
        long sourceBytes = Math.addExact(
                Math.addExact(stats.waitingSourceBytes(), stats.readySourceBytes()),
                stats.inFlightSourceBytes());
        OfflineRenderer.DiagnosticSnapshot offline =
                this.offlineRenderer.diagnosticSnapshot();
        RealtimeRenderer.DiagnosticSnapshot realtime = offline == null
                ? this.realtimeRenderer.diagnosticSnapshot()
                : null;

        ArrayList<String> lines = new ArrayList<>(14);
        lines.add("Prime renderer diagnostics");
        lines.add(String.format(
                Locale.ROOT,
                "Device: %s; SER: %s; opacity micromaps: %s",
                this.context.capabilities().deviceName(),
                this.context.capabilities().invocationReorderSupported()
                        ? "enabled"
                        : "unavailable",
                this.context.capabilities().opacityMicromapSupported()
                        ? "enabled"
                        : "unavailable"));
        lines.add(String.format(
                Locale.ROOT,
                "Path: %s; quality: %s",
                offline != null
                        ? "offline path-tracing accumulation"
                        : realtime != null
                                ? renderingPath(realtime.postProcessingMode())
                                : "n/a",
                realtime != null
                        ? reconstructionQuality(realtime.quality())
                        : "n/a"));
        lines.add(String.format(
                Locale.ROOT,
                "Resolution: render %s; display %s",
                offline != null
                        ? extent(offline.width(), offline.height())
                        : realtime != null
                                ? extent(realtime.renderWidth(), realtime.renderHeight())
                                : "n/a",
                offline != null
                        ? extent(offline.width(), offline.height())
                        : realtime != null
                                ? extent(realtime.displayWidth(), realtime.displayHeight())
                                : "n/a"));
        lines.add(String.format(
                Locale.ROOT,
                "Work: samples %s; integrator passes %s; integrator resources %s",
                offline != null
                        ? count(offline.accumulatedSamples())
                        : realtime != null
                                ? count(realtime.accumulatedSamples())
                                : "n/a",
                realtime != null ? count(realtime.integratorPassCount()) : "n/a",
                realtime != null ? bytes(realtime.integratorResourceBytes()) : "n/a"));
        lines.add(String.format(
                Locale.ROOT,
                "Scene: TLAS instances %s; area-light emitters %s; light-tree nodes %s",
                sceneStats == null ? "n/a" : count(sceneStats.tlasInstanceCount()),
                sceneStats == null ? "n/a" : count(sceneStats.areaLightEmitterCount()),
                sceneStats == null ? "n/a" : count(sceneStats.topLevelLightTreeNodeCount())));
        lines.add(String.format(
                Locale.ROOT,
                "Geometry: instanced triangle references %s; unique BLAS triangles %s",
                sceneStats == null ? "n/a" : count(sceneStats.instancedTriangleCount()),
                sceneStats == null ? "n/a" : count(sceneStats.uniqueBlasTriangleCount())));

        var exposure = offlineSession == null
                ? this.realtimeRenderer.exposureDiagnosticSnapshot()
                : offlineSession.exposure().diagnosticSnapshot();
        String exposureState = exposure == null
                ? "waiting for GPU readback"
                : !exposure.initialized()
                        ? "uninitialized"
                        : exposure.finite() ? "ready" : "non-finite";
        lines.add(String.format(
                Locale.ROOT,
                "Automatic exposure: current %s stops; target %s stops; metered log2 luminance %s; state %s",
                exposure == null ? "n/a" : scalar(exposure.automaticExposureEv()),
                exposure == null ? "n/a" : scalar(exposure.targetExposureEv()),
                exposure == null ? "n/a" : scalar(exposure.measuredLogBrightness()),
                exposureState));
        float manualExposure =
                settings.display().finalExposureQuarterSteps() * 0.25F;
        lines.add(String.format(
                Locale.ROOT,
                "Display exposure: manual %s stops; combined %s stops",
                scalar(manualExposure),
                exposure != null && exposure.finite()
                        ? scalar(exposure.automaticExposureEv() + manualExposure)
                        : "n/a"));
        lines.add(String.format(
                Locale.ROOT,
                "BLAS compaction: query pending %,d; ready %,d; retirement pending %,d; completed %,d",
                stats.waiting(),
                stats.ready(),
                stats.retiring(),
                stats.completedCount()));
        lines.add(String.format(
                Locale.ROOT,
                "Uncompacted backing: query pending %s; ready %s; retirement pending %s; total %s",
                bytes(stats.waitingSourceBytes()),
                bytes(stats.readySourceBytes()),
                bytes(stats.inFlightSourceBytes()),
                bytes(sourceBytes)));
        lines.add(String.format(
                Locale.ROOT,
                "Reclamation: known reclaimable %s; cumulatively reclaimed %s",
                bytes(stats.knownReclaimableBytes()),
                bytes(stats.reclaimedBytes())));
        lines.add(String.format(
                Locale.ROOT,
                "Compacted-target reservation: current %s; high-water %s",
                bytes(stats.reservedTargetBytes()),
                bytes(stats.highWaterTargetBytes())));
        return List.copyOf(lines);
    }

    static String extent(int width, int height) {
        return String.format(Locale.ROOT, "%,d x %,d", width, height);
    }

    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String bytes(long value) {
        return String.format(Locale.ROOT, "%,d bytes", value);
    }

    static String scalar(float value) {
        return Float.isFinite(value)
                ? String.format(Locale.ROOT, "%+.9g", value)
                : Float.toString(value);
    }

    private static String renderingPath(
            dev.prime.render.post.PostProcessingMode mode) {
        return switch (mode) {
            case DLSS_RR -> "DLSS Ray Reconstruction";
            case NRD_FSR ->
                    "NVIDIA Real-time Denoisers and FidelityFX Super Resolution 3.1.4";
            case DISABLED -> "native-resolution noisy path tracing";
        };
    }

    private static String reconstructionQuality(
            dev.prime.render.post.ReconstructionQualityMode quality) {
        return switch (quality) {
            case NATIVE_AA -> "native anti-aliasing";
            case QUALITY -> "quality";
            case BALANCED -> "balanced";
            case PERFORMANCE -> "performance";
            case ULTRA_PERFORMANCE -> "ultra performance";
        };
    }

    private boolean updateOfflineSession(
            Minecraft minecraft,
            boolean requested,
            RendererSettings settings) {
        OfflineSession current = this.offlineRenderer.session();
        OfflineSession tracked = current != null ? current : this.pendingOfflineSession;
        boolean worldChanged = tracked != null && !tracked.matchesWorld(minecraft.level);
        BlockAtlasFrame atlas = this.blockAtlasFrame;
        boolean resourcesChanged = tracked != null
                && (atlas == null
                        || !tracked.matchesAtlas(
                                atlas.view().vkImageView(),
                                atlas.sampler().vkSampler(),
                                atlas.textureRevision()));
        if (worldChanged || resourcesChanged) {
            requested = false;
        }
        if (this.screenshotActive()
                && (!requested || worldChanged || resourcesChanged)) {
            this.stopOfflineSession();
        }
        OfflineSession pending = this.pendingOfflineSession;
        if (pending != null && (!requested || worldChanged || resourcesChanged)) {
            this.pendingOfflineSession = null;
            pending.destroy();
            pending = null;
        }
        if (pending != null && pending.exposure().ready()) {
            this.context.awaitIdle();
            this.context.drainDeferredAfterIdle();
            this.realtimeRenderer.releaseSizedResourcesAfterIdle();
            this.modeLifecycle = this.modeLifecycle
                    .releaseRealtimeSized()
                    .enterOffline();
            this.pendingOfflineSession = null;
            this.offlineRenderer.begin(pending);
            PrimeInfo.LOGGER.info(
                    "Entered Prime screenshot mode at scene revision {}",
                    pending.scene().revision());
            return requested;
        }
        if (!this.screenshotActive()
                && pending == null
                && requested
                && minecraft.level != null
                && this.camera != null
                && this.astronomyState != null
                && this.terrain.residentScene() != null
                && this.realtimeRenderer.hasSizedResources()
                && this.blockAtlasFrame != null) {
            BlockAtlasFrame frozenAtlas = this.blockAtlasFrame;
            FrozenExposureState exposure = FrozenExposureState.capture(
                    this.context,
                    this.realtimeRenderer.displayExposureStateBuffer());
            OfflineSession session = null;
            try {
                session = new OfflineSession(
                        minecraft.level,
                        this.terrain.residentScene(),
                        this.camera,
                        this.astronomyState,
                        OfflineRenderSettings.capture(settings),
                        this.isCameraInWater(minecraft, this.camera),
                        frozenAtlas.view().vkImageView(),
                        frozenAtlas.sampler().vkSampler(),
                        frozenAtlas.textureRevision(),
                        this.sceneTextures,
                        exposure);
                this.pendingOfflineSession = session;
            } catch (RuntimeException exception) {
                if (session != null) {
                    throw ResourceCleanup.destroy(session, exception);
                }
                throw ResourceCleanup.destroy(exposure, exception);
            }
        }
        return requested;
    }

    private void stopOfflineSession() {
        if (!this.screenshotActive()) {
            return;
        }
        this.context.awaitIdle();
        this.context.drainDeferredAfterIdle();
        this.offlineRenderer.stopAfterIdle();
        this.modeLifecycle = this.modeLifecycle
                .releaseOfflineSized()
                .exitOffline();
        // Dirty notifications continue to accumulate while uploads are paused. A full resync on
        // exit also covers animation-driven or external changes that do not expose a precise
        // block range, without invalidating the frozen screenshot while it is converging.
        this.terrain.invalidateAll();
        PrimeInfo.LOGGER.info("Left Prime screenshot mode; scheduled a full terrain resync");
    }

    private void cancelOfflineSession() {
        this.screenshotRequestRejected = true;
        this.stopOfflineSession();
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        this.terrain.invalidateBlocks(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    public void invalidateAll() {
        this.terrain.invalidateAll();
    }

    public void requestRealtimeReset() {
        this.realtimeRenderer.requestReset();
    }

    public void requestShaderReload() {
        this.shaderReloadRequested = true;
    }

    public ResourceReload beginResourceReload() {
        return new ResourceReload(this, this.terrain.beginResourceReload());
    }

    public void finishResourceReload(
            ResourceReload reload, Minecraft minecraft, boolean reloadShaders) {
        TerrainStreamer.ResourceReload terrainReload = this.requireReload(reload);
        if (!this.acceptsResourceReloadEffects) {
            this.terrain.finishResourceReload(terrainReload);
            return;
        }
        this.labPbrSource.requestReload();
        if (!this.synchronizeLabPbr(minecraft)) {
            throw new IllegalStateException(
                    "Prime block textures are unavailable after resource reload");
        }
        this.submitBootstrapResources(this.atmosphere, true, true);
        if (reloadShaders) {
            this.requestShaderReload();
            this.reloadPipelineIfRequested();
        }
        this.terrain.finishResourceReload(terrainReload);
    }

    public void abortResourceReload(ResourceReload reload) {
        this.terrain.abortResourceReload(this.requireReload(reload));
    }

    private TerrainStreamer.ResourceReload requireReload(ResourceReload reload) {
        if (reload == null) {
            throw new NullPointerException("reload");
        }
        if (reload.owner != this) {
            throw new IllegalArgumentException("Resource reload belongs to another renderer");
        }
        return reload.terrainReload;
    }

    public void clearUiAlpha(RenderTarget mainTarget) {
        if (!StreamlineFrameGeneration.uiRecompositionActive()
                || !(mainTarget.getColorTexture() instanceof VulkanGpuTexture mainColor)
                || !(mainTarget.getColorTextureView() instanceof VulkanGpuTextureView mainColorView)) {
            return;
        }
        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        this.context.clearMainColorAlpha(
                commandBuffer,
                mainColor.vkImage(),
                mainColorView.vkImageView(),
                mainColor.getWidth(0),
                mainColor.getHeight(0));
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer),
                "end Prime UI alpha clear command buffer");
        encoder.execute(commandBuffer);
    }

    public void captureUiAlpha(RenderTarget mainTarget) {
        if (!StreamlineFrameGeneration.uiRecompositionActive()
                || !(mainTarget.getColorTexture() instanceof VulkanGpuTexture mainColor)
                || !(mainTarget.getColorTextureView() instanceof VulkanGpuTextureView mainColorView)) {
            return;
        }
        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        VulkanImage alpha = this.context.captureMainColorAlpha(
                commandBuffer,
                mainColor,
                mainColor.vkImage(),
                mainColorView.vkImageView(),
                mainColor.getWidth(0),
                mainColor.getHeight(0));
        if (alpha == null) {
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime UI alpha extraction command buffer");
            encoder.execute(commandBuffer);
            return;
        }
        if (!StreamlineFrameGeneration.prepareUiAlpha(
                commandBuffer,
                alpha,
                mainColor.getWidth(0),
                mainColor.getHeight(0))) {
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime UI alpha extraction command buffer");
            encoder.execute(commandBuffer);
            return;
        }
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer),
                "end Prime UI alpha extraction command buffer");
        encoder.execute(commandBuffer);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.acceptsResourceReloadEffects = false;
        this.dataMeasurements.finish(this.context);
        // A failed wait leaves every GPU child live and permits a later close attempt. After a
        // successful wait, every child is attempted once and failures are aggregated.
        this.context.awaitIdle();
        RuntimeException failure = null;
        failure = ResourceCleanup.run(this.context::drainDeferredAfterIdle, failure);
        failure = ResourceCleanup.destroy(this.pendingOfflineSession, failure);
        failure = ResourceCleanup.destroy(this.offlineRenderer, failure);
        failure = ResourceCleanup.destroy(this.realtimeRenderer, failure);
        failure = ResourceCleanup.close(this.terrain, failure);
        failure = ResourceCleanup.close(this.materialTextures, failure);
        failure = ResourceCleanup.destroy(this.traceBackend, failure);
        failure = ResourceCleanup.destroy(this.atmosphere, failure);
        failure = ResourceCleanup.close(this.stagingArena, failure);
        this.debugLines = List.of();
        this.pendingOfflineSession = null;
        this.closed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    private void reloadPipelineIfRequested() {
        if (!this.shaderReloadRequested) {
            return;
        }
        this.shaderReloadRequested = false;
        String replacementFingerprint = VulkanShaderModules.fingerprint();
        if (replacementFingerprint.equals(this.shaderFingerprint)) {
            PrimeInfo.LOGGER.debug("Prime shader resources are unchanged; skipped pipeline reload");
            return;
        }
        boolean offlineActive = this.screenshotActive();
        AtmospherePipeline replacementAtmosphere = null;
        SunShadowPipeline replacementSunShadow = null;
        boolean replacementAtmosphereSubmitted = false;
        try {
            this.context.invalidateSharedPrograms();
            this.context.prewarmSharedPrograms();
            replacementAtmosphere = new AtmospherePipeline(this.context);
            replacementSunShadow = this.traceBackend.prepareSunShadowReload();
            this.submitBootstrapResources(replacementAtmosphere, false, false);
            replacementAtmosphereSubmitted = true;
            this.offlineRenderer.reload();
            this.realtimeRenderer.reload(replacementAtmosphere);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementSunShadow, exception);
            if (replacementAtmosphereSubmitted) {
                AtmospherePipeline submittedAtmosphere = replacementAtmosphere;
                ResourceCleanup.run(
                        () -> this.context.defer(submittedAtmosphere), exception);
            } else {
                ResourceCleanup.destroy(replacementAtmosphere, exception);
            }
            PrimeInfo.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
            return;
        }
        SunShadowPipeline previousSunShadow =
                this.traceBackend.replaceSunShadowPipeline(replacementSunShadow);
        AtmospherePipeline previousAtmosphere = this.atmosphere;
        this.atmosphere = replacementAtmosphere;
        this.shaderFingerprint = replacementFingerprint;
        RuntimeException retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousSunShadow), null);
        retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousAtmosphere), retirementFailure);
        ResourceCleanup.throwIfFailed(retirementFailure);
        PrimeInfo.LOGGER.info(
                "Reloaded Prime {} ray tracing and atmosphere shaders",
                offlineActive ? "offline" : "realtime");
    }

    private void submitBootstrapResources(
            AtmospherePipeline targetAtmosphere,
            boolean prepareTraceBackend,
            boolean prepareMaterialTextures) {
        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        VulkanImageInitializationBatch initialization = new VulkanImageInitializationBatch();
        initialization.begin();
        long traceToken = 0L;
        boolean atmospherePending = false;
        MaterialTexturePages.FrameToken materialToken = null;
        boolean submitted = false;
        try {
            if (prepareTraceBackend) {
                traceToken = this.traceBackend.prepareStatic(commandBuffer, initialization);
            }
            atmospherePending = targetAtmosphere.prepareStatic(commandBuffer);
            if (prepareMaterialTextures) {
                materialToken = this.materialTextures.prepareInitial(commandBuffer);
            }
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime renderer bootstrap command buffer");
            encoder.execute(commandBuffer);
            submitted = true;
            initialization.submitted();
            if (traceToken != 0L) {
                this.traceBackend.submitted(traceToken);
            }
            if (atmospherePending) {
                targetAtmosphere.submittedStatic();
            }
            this.materialTextures.submitted(materialToken);
        } catch (RuntimeException exception) {
            if (submitted) {
                throw exception;
            }
            RuntimeException failure = exception;
            MaterialTexturePages.FrameToken abandonedMaterial = materialToken;
            if (abandonedMaterial != null) {
                failure = ResourceCleanup.run(
                        () -> this.materialTextures.abandon(abandonedMaterial), failure);
            }
            if (atmospherePending) {
                failure = ResourceCleanup.run(targetAtmosphere::abandonStatic, failure);
            }
            long abandonedTrace = traceToken;
            if (abandonedTrace != 0L) {
                failure = ResourceCleanup.run(
                        () -> this.traceBackend.abandon(abandonedTrace), failure);
            }
            failure = ResourceCleanup.run(initialization::abandon, failure);
            throw failure;
        }
    }

    private boolean isCameraInWater(Minecraft minecraft, FrameCamera camera) {
        if (minecraft.level == null) {
            return false;
        }
        BlockPos position = this.cameraBlockPosition.set(
                camera.x(), camera.y(), camera.z());
        var fluid = minecraft.level.getFluidState(position);
        // Match vanilla's height-aware camera test. A block-only check incorrectly puts the
        // camera in a medium while the eye is above shallow or flowing water in the same cell.
        return fluid.is(FluidTags.WATER)
                && camera.y() < position.getY() + fluid.getHeight(minecraft.level, position);
    }

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    /** One block-atlas snapshot is resolved and synchronized at the frame boundary. */
    private record BlockAtlasFrame(
            VulkanGpuTextureView view,
            VulkanGpuSampler sampler,
            long sourceGeneration,
            long textureRevision) {
    }

    public static final class ResourceReload {
        private final VulkanRenderer owner;
        private final TerrainStreamer.ResourceReload terrainReload;

        private ResourceReload(
                VulkanRenderer owner,
                TerrainStreamer.ResourceReload terrainReload) {
            this.owner = owner;
            this.terrainReload = terrainReload;
        }

        public CompletableFuture<Void> ready() {
            return this.terrainReload.ready();
        }
    }

}
