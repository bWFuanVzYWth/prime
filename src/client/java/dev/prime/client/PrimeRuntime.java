package dev.prime.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import dev.prime.PrimeClient;
import dev.prime.mixin.MinecraftAccessor;
import dev.prime.render.HdrOutput;
import dev.prime.render.RendererSettings;
import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.diagnostic.RrResponsivity;
import dev.prime.render.runtime.RendererLifecycle;
import dev.prime.render.runtime.RuntimeDiagnostics;
import dev.prime.render.runtime.RuntimeState;
import dev.prime.render.runtime.SessionControls;
import dev.prime.render.runtime.TerrainOwnership;
import dev.prime.render.runtime.VulkanRenderer;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.vulkan.HdrPresentation;
import java.util.List;

import dev.prime.streamline.StreamlineReflex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;

public final class PrimeRuntime {
    private static final PrimeRuntime INSTANCE = new PrimeRuntime();
    private final RendererLifecycle lifecycle = new RendererLifecycle();
    private final TerrainOwnership terrain = new TerrainOwnership();
    private final RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();
    private boolean screenshotRequested;
    private boolean rendererDiagnostics;
    private boolean rawOutput;
    private ImageDiagnosticSelection imageDiagnostics = ImageDiagnosticSelection.off();
    private float rrResponsivity = RrResponsivity.DEFAULT;
    private SessionControls sessionSnapshot = SessionControls.defaults();
    private boolean previousEscape;
    private RendererSettings frameSettings;

    private PrimeRuntime() {
    }

    public static PrimeRuntime instance() {
        return INSTANCE;
    }

    public void initialize(RendererSettings settings) {
        this.lifecycle.initialize(settings);
        this.frameSettings = java.util.Objects.requireNonNull(settings, "settings");
    }

    public RuntimeState state() {
        return this.lifecycle.state();
    }

    public boolean shouldReplaceWorld() {
        return this.lifecycle.state() == RuntimeState.ACTIVE;
    }

    public boolean shouldMaintainVanillaTerrain() {
        return !this.terrain.primeOwned();
    }

    public int vanillaTerrainDistance(int configuredDistance) {
        return this.terrain.vanillaDistance(configuredDistance);
    }

    public boolean shouldCaptureDynamicScene() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer != null
                && this.lifecycle.state() == RuntimeState.ACTIVE
                && !activeRenderer.screenshotActive();
    }

    public void beginFrame(Minecraft minecraft, RendererSettings settings) {
        HdrPresentation.beginFrame();
        this.frameSettings = java.util.Objects.requireNonNull(settings, "settings");
        this.lifecycle.retireFailed(minecraft, this.terrain);
        if (!this.lifecycle.initialized()) {
            return;
        }
        this.updateSessionShortcuts(minecraft);
        boolean presentationWasAvailable = HdrPresentation.available();
        if (HdrOutput.requested()) {
            this.lifecycle.tryInitializePresentation();
            if (!presentationWasAvailable && HdrPresentation.available()) {
                ((MinecraftAccessor) minecraft)
                        .prime$setWindowSurfaceNeedsReconfiguring(true);
            }
        }
        if (!settings.pathTracingEnabled()) {
            this.lifecycle.disable(minecraft, this.terrain);
            return;
        }
        this.lifecycle.tryInitialize(minecraft, this.terrain, settings);
        this.lifecycle.setFailureReason(this.diagnostics.finalizeUnavailableReason(
                this.lifecycle.failureReason(), this.lifecycle.state()));
        this.diagnostics.showFailureOnce(
                minecraft, this.lifecycle.state(), this.lifecycle.failureReason());
        if (this.lifecycle.state() == RuntimeState.FAILED) {
            return;
        }
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null) {
            return;
        }
        try {
            ClientLevel currentWorld = minecraft.level;
            if (currentWorld != null && minecraft.player != null) {
                this.terrain.acquire(minecraft);
            }
            SessionControls frameControls = this.sessionControls();
            boolean screenshotRequested = activeRenderer.beginFrame(
                    minecraft, frameControls, settings);
            if (screenshotRequested != frameControls.screenshotRequested()) {
                this.screenshotRequested = screenshotRequested;
                this.sessionSnapshot = null;
            }
            this.lifecycle.observeWorld(
                    currentWorld,
                    currentWorld != null
                            && minecraft.player != null
                            && activeRenderer.isReady());
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void captureCamera(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        RendererSettings settings = this.frameSettings;
        if (activeRenderer != null
                && settings != null
                && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.captureCamera(
                    renderedProjection,
                    baseProjection,
                    viewRotation,
                    x,
                    y,
                    z,
                    sunAngleRadians,
                    settings);
        }
    }

    public void renderWorld(RenderTarget mainTarget) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        RendererSettings settings = this.frameSettings;
        if (activeRenderer == null
                || settings == null
                || this.lifecycle.state() != RuntimeState.ACTIVE) {
            return;
        }
        try {
            activeRenderer.render(mainTarget, settings);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void clearUiAlpha(RenderTarget mainTarget) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null
                || this.lifecycle.state() != RuntimeState.ACTIVE
                || activeRenderer.screenshotActive()) {
            return;
        }
        try {
            activeRenderer.clearUiAlpha(mainTarget);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void captureUiAlpha(RenderTarget mainTarget) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null
                || this.lifecycle.state() != RuntimeState.ACTIVE
                || activeRenderer.screenshotActive()) {
            return;
        }
        try {
            activeRenderer.captureUiAlpha(mainTarget);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null
                || this.lifecycle.state() != RuntimeState.ACTIVE
                || activeRenderer.screenshotActive()) {
            return;
        }
        try {
            activeRenderer.captureDynamicScene(frame);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public boolean handleScreenshotShortcut(
            Minecraft minecraft, InputConstants.Key key, boolean controlDown) {
        long window = minecraft.getWindow().handle();
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        if (!controlDown
                || !alt
                || !minecraft.options.keyScreenshot.matches(key)
                || minecraft.level == null) {
            return false;
        }
        this.requestScreenshot(!this.screenshotRequested);
        return true;
    }

    public boolean screenshotRequested() {
        return this.screenshotRequested;
    }

    public void pathTracingChanged(boolean enabled) {
        if (!enabled) {
            this.requestScreenshot(false);
        } else if (this.lifecycle.state() == RuntimeState.DISABLED) {
            // Enabling after an explicit stop is a fresh initialization attempt.
            this.diagnostics.resetAvailabilityNotifications();
        }
    }

    public void surfaceDetailModeChanged() {
        this.invalidateAll();
    }

    public void voxelTextureSurfaceStrengthChanged(boolean enabled, int strengthSteps) {
        dev.prime.render.terrain.VoxelSurfaceSettings.maximumHeight(strengthSteps);
        if (enabled) {
            this.invalidateAll();
        }
    }

    public void requestScreenshot(boolean enabled) {
        if (this.screenshotRequested != enabled) {
            this.screenshotRequested = enabled;
            this.sessionSnapshot = null;
        }
    }

    public boolean screenshotActive() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer != null && activeRenderer.screenshotActive();
    }

    public boolean rendererDiagnostics() {
        return this.rendererDiagnostics;
    }

    public void setRendererDiagnostics(boolean value) {
        if (this.rendererDiagnostics != value) {
            this.rendererDiagnostics = value;
            this.sessionSnapshot = null;
        }
    }

    public boolean rawOutput() {
        return this.rawOutput;
    }

    public void setRawOutput(boolean value) {
        if (this.rawOutput != value) {
            this.rawOutput = value;
            this.sessionSnapshot = null;
            this.requestRealtimeReset();
        }
    }

    public RendererImageView rendererImageView() {
        return this.imageDiagnostics.renderer();
    }

    public void setRendererImageView(RendererImageView value) {
        ImageDiagnosticSelection replacement = this.imageDiagnostics.withRenderer(value);
        if (replacement != this.imageDiagnostics) {
            this.imageDiagnostics = replacement;
            this.sessionSnapshot = null;
        }
    }

    public RrInputView rrInputView() {
        return this.imageDiagnostics.rr();
    }

    public void setRrInputView(RrInputView value) {
        ImageDiagnosticSelection replacement = this.imageDiagnostics.withRr(value);
        if (replacement != this.imageDiagnostics) {
            this.imageDiagnostics = replacement;
            this.sessionSnapshot = null;
        }
    }

    public float rrResponsivity() {
        return this.rrResponsivity;
    }

    public void setRrResponsivity(float value) {
        value = RrResponsivity.requireValid(value);
        if (this.rrResponsivity != value) {
            this.rrResponsivity = value;
            this.sessionSnapshot = null;
        }
    }

    public NrdInputView nrdInputView() {
        return this.imageDiagnostics.nrd();
    }

    public void setNrdInputView(NrdInputView value) {
        ImageDiagnosticSelection replacement = this.imageDiagnostics.withNrd(value);
        if (replacement != this.imageDiagnostics) {
            this.imageDiagnostics = replacement;
            this.sessionSnapshot = null;
        }
    }

    public void restoreSessionDefaults() {
        this.screenshotRequested = false;
        this.rendererDiagnostics = false;
        this.rawOutput = false;
        this.imageDiagnostics = ImageDiagnosticSelection.off();
        this.rrResponsivity = RrResponsivity.DEFAULT;
        this.sessionSnapshot = SessionControls.defaults();
    }

    public List<String> debugLines() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer == null ? List.of() : activeRenderer.debugLines();
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.invalidateBlocks(
                    minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
        }
    }

    public void invalidateAll() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.invalidateAll();
        }
    }

    private void requestRealtimeReset() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.requestRealtimeReset();
        }
    }

    /** Retires the exact renderer resource epoch observed by the prepare executor. */
    public RendererLifecycle.ResourceReload beginResourceReload() {
        return this.lifecycle.beginResourceReload();
    }

    /** Applies only the renderer epoch captured by {@link #beginResourceReload()}. */
    public void finishResourceReload(
            RendererLifecycle.ResourceReload reload, boolean reloadShaders) {
        if (this.lifecycle.finishResourceReload(
                reload, Minecraft.getInstance(), reloadShaders)) {
            this.requestScreenshot(false);
        }
    }

    /** Reopens the retired owner after a failed or cancelled Minecraft reload. */
    public void abortResourceReload(RendererLifecycle.ResourceReload reload) {
        this.lifecycle.abortResourceReload(reload);
    }

    public void shutdown() {
        this.restoreSessionDefaults();
        this.frameSettings = null;
        this.lifecycle.shutdown();
    }

    public void fail(Throwable failure) {
        this.lifecycle.fail(failure);
        this.restoreSessionDefaults();
    }

    /** Prevents reuse of histories advanced into a host submission that later failed. */
    public void minecraftHostSubmissionFailed(RuntimeException failure) {
        if (this.lifecycle.renderer() != null) {
            fail(failure);
        }
    }

    private void updateSessionShortcuts(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        boolean escape = pressed(window, GLFW.GLFW_KEY_ESCAPE);
        if (escape
                && !this.previousEscape
                && (this.screenshotRequested || this.screenshotActive())) {
            this.screenshotRequested = false;
            this.sessionSnapshot = null;
        }
        this.previousEscape = escape;
    }

    private SessionControls sessionControls() {
        if (this.sessionSnapshot == null) {
            this.sessionSnapshot = new SessionControls(
                    this.screenshotRequested,
                    this.rendererDiagnostics,
                    this.rawOutput,
                    this.imageDiagnostics,
                    this.rrResponsivity);
        }
        return this.sessionSnapshot;
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
