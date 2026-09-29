package dev.primept;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.primept.capture.CaptureInbox;
import dev.primept.capture.Packets;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.DynamicTextures;
import dev.primept.capture.BlockGeometryCache;
import dev.primept.capture.ExclusiveTerrainCapture;
import dev.primept.render.RendererSlot;
import dev.primept.render.FrameSequence;
import dev.primept.render.OfflineMode;
import dev.primept.settings.RenderSettings;
import dev.primept.settings.SettingsFile;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrimeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("PrimePT");
    public static final CaptureInbox CAPTURE = new CaptureInbox();
    private static final PrimeClient INSTANCE = new PrimeClient();
    private final boolean enabled = Boolean.getBoolean("primept.enabled");
    // Factories are resource-free; only RendererSlot may start a selected owner.
    private interface WorldRenderer extends RendererSlot.Backend {
        default boolean readyForCapture() {
            return false;
        }
        default void render(CameraRenderState camera, RenderTarget destination,
                            float solarHourAngle) {}
    }
    private final Map<String, Supplier<? extends WorldRenderer>> backends = new LinkedHashMap<>();
    private RendererSlot<String, WorldRenderer> slot;
    private String requested =
            enabled ? System.getProperty("primept.renderer", "path_trace") : "vanilla";
    private boolean unavailable;
    private CompletableFuture<Void> resourceReload;
    private final RenderProfile profile = new RenderProfile();
    private long worldRenderStart;
    private long sourceFrameSequence;
    private HostVulkanRenderer renderer;
    private long sentEpoch, sentAtlas;
    private final FrameSequence frames = new FrameSequence();
    private boolean reportedFrame;
    private boolean reportedProjectionWait;
    private boolean failed;
    private boolean skippedWorldRaster;
    private RenderSettings settings = RenderSettings.defaults();
    private RenderSettings.View diagnosticView = RenderSettings.View.OUTPUT;
    private final OfflineMode offline = new OfflineMode();
    private long frozenAtlasVersion;

    public static RenderSettings settings() {
        return INSTANCE.settings;
    }
    public static boolean controlsAvailable() {
        return INSTANCE.enabled;
    }
    public static RenderSettings.View diagnosticView() {
        return INSTANCE.diagnosticView;
    }
    public static void setDiagnosticView(RenderSettings.View view) {
        INSTANCE.diagnosticView = view;
    }
    public static boolean offlineRequested() {
        return INSTANCE.offline.requested();
    }
    public static boolean offlineActive() {
        return INSTANCE.offline.active();
    }
    public static void requestOffline(boolean value) {
        INSTANCE.offline.request(value && INSTANCE.enabled &&
                                 !INSTANCE.requested.equals("vanilla"));
    }
    public static void updateSettings(RenderSettings settings) {
        INSTANCE.settings = settings;
        INSTANCE.requested = INSTANCE.enabled && settings.pathTracing() ? "path_trace" : "vanilla";
        INSTANCE.failed = false;
        if (!settings.pathTracing())
            INSTANCE.offline.request(false);
    }
    public static void restoreSettings() {
        updateSettings(RenderSettings.defaults());
        INSTANCE.offline.request(false);
        INSTANCE.diagnosticView = RenderSettings.View.OUTPUT;
    }
    public static void saveSettings() {
        try {
            SettingsFile.save(
                    FabricLoader.getInstance().getConfigDir().resolve("primept.properties"),
                    INSTANCE.settings);
        } catch (java.io.IOException failure) {
            LOGGER.error("Cannot save Prime settings", failure);
        }
    }
    public static boolean offlineShortcut(InputConstants.Key key, boolean control) {
        if (key.getValue() != InputConstants.KEY_F2 || !control)
            return false;
        var client = Minecraft.getInstance();
        if (client.level == null || !INSTANCE.enabled || INSTANCE.requested.equals("vanilla"))
            return false;
        boolean alt = InputConstants.isKeyDown(InputConstants.KEY_LALT) ||
                      InputConstants.isKeyDown(InputConstants.KEY_RALT);
        return INSTANCE.offline.shortcut(true, control, alt);
    }

    public PrimeClient() {
        backends.put("vanilla", () -> new VanillaBackend(false));
        backends.put("path_trace", PrimeBackend::new);
    }

    @Override
    public void onInitializeClient() {
        var loaded = SettingsFile.load(
                FabricLoader.getInstance().getConfigDir().resolve("primept.properties"));
        INSTANCE.settings = loaded.settings();
        if (!loaded.resetReason().isEmpty())
            LOGGER.warn(loaded.resetReason());
        INSTANCE.requested =
                enabled ? System.getProperty("primept.renderer", INSTANCE.settings.pathTracing()
                                                                         ? "path_trace"
                                                                         : "vanilla")
                        : "vanilla";
        if (enabled && System.getProperty("primept.renderer") != null)
            INSTANCE.settings =
                    INSTANCE.settings.withPathTracing(!INSTANCE.requested.equals("vanilla"));
        LOGGER.info("Prime PT 26.3: Java thin capture → FFM → Rust → Vulkan/Slang; enabled={}",
                    enabled);
        LOGGER.info(
                "FRAPI geometry cache enabled={} (resource budget 4096 entries / 32MiB encoded mesh)",
                dev.primept.capture.BlockGeometryCache.enabled());
        if (enabled)
            LOGGER.warn(
                    "Section prototype: Rust owns radius/scheduling/model selection; one source request/response batch per frame. Temporary model/tint/fluid defaults are listed in PROTOTYPE_HACKS.md; visual parity is not claimed. Entity/block-entity/particle routing is retained.");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var command = literal("primept");
            var renderers = literal("renderer");
            for (String key : INSTANCE.backends.keySet())
                renderers.then(literal(key).executes(context -> {
                    if (!key.equals("vanilla") && !INSTANCE.enabled) {
                        context.getSource().sendError(Component.literal(
                                "Prime requires -Dprimept.enabled=true at startup to enable Vulkan ray tracing."));
                        return 0;
                    }
                    INSTANCE.requested = key;
                    INSTANCE.settings = INSTANCE.settings.withPathTracing(!key.equals("vanilla"));
                    if (key.equals("vanilla"))
                        INSTANCE.offline.request(false);
                    INSTANCE.failed = false;
                    context.getSource().sendFeedback(
                            Component.literal("Renderer requested: " + key));
                    return 1;
                }));
            dispatcher.register(command.then(renderers));
        });
    }

    public static void render(CameraRenderState camera, RenderTarget destination,
                              float solarHourAngle) {
        if (INSTANCE.slot != null && INSTANCE.slot.active() != null)
            INSTANCE.slot.active().render(camera, destination, solarHourAngle);
    }

    public static boolean captureResourcesEnabled() {
        return INSTANCE.enabled && !INSTANCE.failed && !INSTANCE.requested.equals("vanilla") &&
                (INSTANCE.slot == null || INSTANCE.slot.state() != RendererSlot.State.BLOCKED);
    }

    public static boolean captureEnabled() {
        return exclusiveFrameReady();
    }

    /** Ownership includes loading and failure: a retired vanilla renderer must never be called. */
    public static boolean ownsWorldRendering() {
        return INSTANCE.unavailable || ExclusiveTerrainCapture.vanillaSuspended();
    }

    /** This frame may omit known mechanical world output only while the selected PT backend owns it. */
    public static boolean exclusiveFrameReady() {
        return !INSTANCE.failed && !INSTANCE.offline.active() && INSTANCE.slot != null &&
                INSTANCE.slot.active() != null && INSTANCE.slot.active().readyForCapture();
    }

    /** Before extraction: the selected owner must prepare this frame's world state as well as render it. */
    public static long sourceFrameSequence() {
        return INSTANCE.sourceFrameSequence;
    }

    public static void beginFrame() {
        INSTANCE.sourceFrameSequence = Math.incrementExact(INSTANCE.sourceFrameSequence);
        INSTANCE.profile.beginExtraction();
        INSTANCE.advanceRenderer();
    }

    public static void endExtraction() {
        INSTANCE.profile.endExtraction();
    }

    private void advanceRenderer() {
        if (!enabled)
            return;
        if (Minecraft.getInstance().level == null)
            return;
        if (!HostVulkanRenderer.isVulkanHost()) {
            if (!failed) {
                failed = true;
                requested = "vanilla";
                CAPTURE.releaseSources();
                DynamicTextures.releaseSources();
                BlockGeometryCache.releaseToVanilla();
                LOGGER.error(
                        "Prime requires Minecraft's Vulkan backend; the current vanilla renderer remains active");
            }
            return;
        }
        if (slot == null)
            slot = new RendererSlot<>();
        if (slot.state() == RendererSlot.State.BLOCKED)
            return;
        try {
            if (slot.state() == RendererSlot.State.EMPTY &&
                !ExclusiveTerrainCapture.vanillaSuspended()) {
                // The host creates its initial world renderer before our first frame. Adopt it before retiring it.
                slot.select("vanilla", () -> new VanillaBackend(true));
            }
            Supplier<? extends WorldRenderer> factory = backends.get(requested);
            if (factory == null)
                throw new IllegalArgumentException("Unknown renderer: " + requested);
            if (!requested.equals(slot.key()) && !requested.equals("vanilla"))
                HostVulkanRenderer.requireAvailable();
            String previous = slot.key();
            slot.select(requested, factory);
            unavailable = false;
            if (!requested.equals(previous))
                LOGGER.info("Renderer switched: {} -> {} (old owner retired)", previous, requested);
            if (resourceReload != null && resourceReload.isDone()) {
                resourceReload.join();
                resourceReload = null;
                if (CAPTURE.atlas() == null)
                    throw new IllegalStateException(
                            "Resource reload produced no captured block atlas");
            }
            if (renderer != null && resourceReload == null)
                advancePrimeMode();
        } catch (Exception | LinkageError exception) {
            failRenderer(exception);
        }
    }

    private void advancePrimeMode() {
        var atlas = CAPTURE.atlas();
        if (offline.active() && (atlas == null || atlas.version() != frozenAtlasVersion))
            offline.request(
                    false); // Resource reload ends the old snapshot at the next frame boundary.
        boolean next = offline.desired(reportedFrame);
        if (next == offline.active()) {
            renderer.configure(settings, next, diagnosticView);
            return;
        }
        if (next) {
            frozenAtlasVersion = atlas.version();
            CAPTURE.disable();
            DynamicCapture.close();
            // Resource uploads remain observed for thaw; frozen native textures are not mutated.
            BlockGeometryCache.releaseToVanilla();
            ExclusiveTerrainCapture.release();
        }
        renderer.configure(settings, next, diagnosticView);
        if (!next) {
            // The world may have changed while frozen; start a complete new source epoch.
            CAPTURE.enable();
            ExclusiveTerrainCapture.acquire();
            resetFrameState();
        }
        frames.reset();
        profile.reset();
        offline.committed(next);
        LOGGER.info("Prime renderer mode: {} (previous exclusive resources retired)",
                    next ? "offline" : "realtime");
    }

    public static void beginWorldRender() {
        INSTANCE.skippedWorldRaster = ownsWorldRendering();
        INSTANCE.worldRenderStart = System.nanoTime();
    }

    public static boolean skipWorldRaster() {
        boolean ready = ownsWorldRendering();
        INSTANCE.skippedWorldRaster = ready;
        return ready;
    }

    static boolean skippedWorldRaster() {
        return INSTANCE.skippedWorldRaster;
    }

    /** Called before host extraction, so the native source owner can populate the same frame. */
    public static NativeBridge prepareNativeSources() {
        var owner = INSTANCE;
        if (!exclusiveFrameReady() || owner.renderer == null || owner.resourceReload != null)
            return null;
        var atlas = CAPTURE.atlas();
        if (atlas == null)
            return null;
        long epoch = CAPTURE.epoch();
        if (owner.sentEpoch != epoch) {
            owner.renderer.submit(Packets.reset(epoch));
            owner.sentEpoch = epoch;
            owner.sentAtlas = 0;
            owner.frames.reset();
            owner.reportedFrame = false;
            owner.renderer.resetReadiness();
        }
        if (owner.sentAtlas != atlas.version()) {
            owner.renderer.submit(
                    Packets.texture(epoch, atlas.width(), atlas.height(), atlas.rgba()));
            owner.sentAtlas = atlas.version();
            owner.frames.reset();
        }
        return owner.renderer.sourceBridge();
    }

    private void renderFrame(CameraRenderState camera, RenderTarget destination,
                             float solarHourAngle) {
        if (failed || resourceReload != null)
            return;
        try {
            if (offline.active()) {
                if (destination.width <= 0 || destination.height <= 0)
                    return;
                Packets.resizeFrozenFrame(renderer.frameBuffer(), destination.width,
                                          destination.height,
                                          frames.next(destination.width, destination.height));
                var timing = profile.begin(worldRenderStart);
                long started = System.nanoTime();
                renderer.record(destination);
                timing.nativeRender = System.nanoTime() - started;
                profile.finish(timing, CAPTURE, destination.width, destination.height, renderer);
                return;
            }
            RuntimeException terrainFailure = ExclusiveTerrainCapture.failure();
            if (terrainFailure != null)
                throw terrainFailure;
            RuntimeException captureFailure = CAPTURE.failure();
            if (captureFailure != null)
                throw captureFailure;
            if (!camera.initialized || destination.width <= 0 || destination.height <= 0) {
                frames.reset();
                return;
            }
            float fov = (float)(2 * Math.atan(1.0 / Math.abs(camera.projectionMatrix.m11())));
            // initialized describes the camera entity; its perspective matrix can still be zero during world entry.
            if (!Float.isFinite(fov) || fov < 0.01f || fov >= 3.0f) {
                if (!reportedProjectionWait)
                    LOGGER.info(
                            "Waiting for Minecraft's perspective camera before native rendering");
                reportedProjectionWait = true;
                return;
            }
            var atlas = CAPTURE.atlas();
            if (atlas == null)
                return;
            RenderProfile.Frame timing = profile.begin(worldRenderStart);
            long epoch = CAPTURE.epoch();
            if (sentEpoch != epoch) {
                submit(Packets.reset(epoch), timing);
                sentEpoch = epoch;
                sentAtlas = 0;
                frames.reset();
                reportedFrame = false;
                renderer.resetReadiness();
            }
            if (sentAtlas != atlas.version()) {
                submit(Packets.texture(epoch, atlas.width(), atlas.height(), atlas.rgba()), timing);
                sentAtlas = atlas.version();
                frames.reset();
            }
            timing.resourceSubmit = timing.submit;
            // Terrain is already synchronously published by the source request/response phase.
            int width = destination.width, height = destination.height;
            long phaseStart = System.nanoTime();
            renderer.submitDynamic(epoch);
            timing.dynamicSubmit = System.nanoTime() - phaseStart;
            var inverse = new Matrix4f(camera.viewRotationMatrix).invert();
            var forward = inverse.transformDirection(new Vector3f(0, 0, -1)).normalize();
            var right = inverse.transformDirection(new Vector3f(1, 0, 0)).normalize();
            var up = inverse.transformDirection(new Vector3f(0, 1, 0)).normalize();
            Packets.writeFrame(renderer.frameBuffer(), epoch, camera.pos.x, camera.pos.y,
                               camera.pos.z, components(forward), components(right), components(up),
                               fov, width, height, frames.next(width, height), solarHourAngle);
            phaseStart = System.nanoTime();
            renderer.record(destination);
            timing.nativeRender = System.nanoTime() - phaseStart;
            if (!reportedFrame) {
                LOGGER.info(
                        "Prime PT first world frame recorded: {}x{}, {} routed source sections, resource epoch {}",
                        width, height, ExclusiveTerrainCapture.routedSections(), epoch);
                reportedFrame = true;
                renderer.enableWorldReplacementAfterCompletion();
            }
            profile.finish(timing, CAPTURE, width, height, renderer);
        } catch (Exception | LinkageError exception) {
            failRenderer(exception);
        }
    }

    private void failRenderer(Throwable exception) {
        failed = true;
        CAPTURE.disable();
        requested = "vanilla";
        unavailable = true;
        if (slot != null && slot.active() instanceof VanillaBackend &&
            !ExclusiveTerrainCapture.vanillaSuspended()) {
            CAPTURE.releaseSources();
            DynamicTextures.releaseSources();
            BlockGeometryCache.releaseToVanilla();
            unavailable = false;
        }
        // Retirement is deferred to the next outer frame, never submitted from an active world render pass.
        LOGGER.error(
                "Prime renderer failed; vanilla restoration requires successful retirement at the next frame boundary",
                exception);
    }

    private final class VanillaBackend implements WorldRenderer {
        private final boolean adopt;
        VanillaBackend(boolean adopt) {
            this.adopt = adopt;
        }
        @Override
        public void start() {
            HostVulkanRenderer.requireRetirementResolved();
            if (!adopt || requested.equals("vanilla")) {
                CAPTURE.releaseSources();
                DynamicTextures.releaseSources();
                BlockGeometryCache.releaseToVanilla();
            }
            if (!adopt)
                ExclusiveTerrainCapture.resumeVanilla();
        }
        @Override
        public void close() {
            HostVulkanRenderer.retireHostResources(ExclusiveTerrainCapture::suspendVanilla);
        }
    }

    private final class PrimeBackend implements WorldRenderer {
        @Override
        public boolean readyForCapture() {
            return renderer != null && resourceReload == null && CAPTURE.atlas() != null &&
                    CAPTURE.failure() == null && DynamicCapture.healthy();
        }
        @Override
        public void render(CameraRenderState camera, RenderTarget destination,
                           float solarHourAngle) {
            renderFrame(camera, destination, solarHourAngle);
        }
        @Override
        public void start() throws Exception {
            HostVulkanRenderer.requireRetirementResolved();
            CAPTURE.enable();
            ExclusiveTerrainCapture.acquire();
            renderer = new HostVulkanRenderer();
            resetFrameState();
            LOGGER.info(
                    "Rust renderer attached to Minecraft's Vulkan device; direct storage-image output, no CPU readback");
            // Re-entering from vanilla captures actual uploads again; never reconstruct pixels from the GPU.
            if (CAPTURE.atlas() == null)
                resourceReload = Minecraft.getInstance().reloadResourcePacks();
        }
        @Override
        public void close() {
            HostVulkanRenderer.requireRetirementResolved();
            if (renderer != null)
                renderer.close();
            DynamicCapture.close();
            CAPTURE.disable();
            ExclusiveTerrainCapture.release();
            renderer = null;
            resourceReload = null;
            offline.reset();
        }
    }

    private void submit(byte[] packet, RenderProfile.Frame timing) {
        long start = System.nanoTime();
        renderer.submit(packet);
        timing.submit += System.nanoTime() - start;
        ++timing.packets;
        timing.bytes += packet.length;
    }

    private static float[] components(Vector3f vector) {
        return new float[] {vector.x, vector.y, vector.z};
    }

    public static void resetWorld() {
        INSTANCE.closeResources();
        INSTANCE.offline.reset();
        CAPTURE.reset();
        INSTANCE.resetFrameState();
    }
    private void resetFrameState() {
        profile.reset();
        sentEpoch = sentAtlas = worldRenderStart = 0;
        frames.reset();
        reportedProjectionWait = reportedFrame = false;
    }
    public static void close() {
        CAPTURE.disable();
        INSTANCE.closeResources();
        INSTANCE.profile.closeSamples();
    }

    private void closeResources() {
        try {
            if (slot != null)
                slot.close();
        } catch (Exception | LinkageError exception) {
            unavailable = true;
            LOGGER.error("Renderer shutdown failed; replacement remains blocked", exception);
        }
    }
}
