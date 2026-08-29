package dev.prime.render.runtime;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.RendererSettings;
import dev.prime.render.vulkan.HdrPresentation;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Sole client-thread lifecycle owner of the Vulkan context and renderer state machine. */
public final class RendererLifecycle {
    private final RuntimeStateMachine states = new RuntimeStateMachine();
    private String failureReason = "Prime has not initialized";
    private boolean initialized;
    private boolean shuttingDown;
    // Reload preparation only observes attachment. Renderer mutation remains client-thread owned.
    private volatile VulkanRenderer renderer;
    private VulkanContext context;
    // Failed renderers retire at the next frame boundary after host command ownership is settled.
    private VulkanRenderer retiringRenderer;
    private ClientLevel world;

    public void initialize(RendererSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        if (this.initialized) {
            throw new IllegalStateException("Prime renderer lifecycle is already initialized");
        }
        this.initialized = true;
        if (!settings.pathTracingEnabled()) {
            this.states.disabled();
        }
    }

    public boolean initialized() {
        return this.initialized;
    }

    public RuntimeState state() {
        return this.states.current();
    }

    public String failureReason() {
        return this.failureReason;
    }

    public void setFailureReason(String reason) {
        this.failureReason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public VulkanRenderer renderer() {
        return this.renderer;
    }

    /** Creates only the shared Vulkan boundary required by HDR presentation. */
    public boolean tryInitializePresentation() {
        if (this.context != null) {
            return true;
        }
        if (this.shuttingDown || this.states.current() == RuntimeState.FAILED) {
            return false;
        }
        VulkanBootstrap.Snapshot bootstrap = VulkanBootstrap.snapshot();
        VulkanCapabilities capabilities = bootstrap.capabilities();
        VulkanDevice device = bootstrap.device();
        if (!capabilities.available() || device == null) {
            return false;
        }
        VulkanContext created = null;
        try {
            created = new VulkanContext(device, capabilities);
            HdrPresentation.attach(created);
            this.context = created;
            return true;
        } catch (RuntimeException exception) {
            HdrPresentation.detach(created);
            ResourceCleanup.close(created, exception);
            PrimeInfo.LOGGER.warn(
                    "Prime HDR presentation initialization failed; retaining SDR output",
                    exception);
            return false;
        }
    }

    public void tryInitialize(
            Minecraft minecraft, TerrainOwnership terrain, RendererSettings settings) {
        if (this.renderer != null
                || this.retiringRenderer != null
                || this.shuttingDown
                || this.states.current() == RuntimeState.FAILED) {
            return;
        }
        VulkanBootstrap.Snapshot bootstrap = VulkanBootstrap.snapshot();
        VulkanCapabilities capabilities = bootstrap.capabilities();
        VulkanDevice device = bootstrap.device();
        if (!capabilities.available() || device == null) {
            this.failureReason = capabilities.unavailableReason();
            this.states.unavailable();
            terrain.restore(minecraft, true);
            return;
        }
        VulkanRenderer createdRenderer = null;
        try {
            if (this.context == null) {
                this.context = new VulkanContext(device, capabilities);
                HdrPresentation.attach(this.context);
            }
            if (!VulkanRenderer.bootstrapResourcesReady(minecraft)) {
                this.states.rendererReady();
                return;
            }
            if (minecraft.level != null && minecraft.player != null) {
                terrain.acquire(minecraft);
            }
            createdRenderer = new VulkanRenderer(this.context);
            createdRenderer.bootstrap(minecraft, settings);
            this.renderer = createdRenderer;
            this.failureReason = "";
            this.states.rendererReady();
        } catch (RuntimeException exception) {
            this.failureReason = RuntimeFailureSummary.describe(
                    exception, "Vulkan initialization");
            this.states.fail();
            terrain.restore(minecraft, true);
            ResourceCleanup.close(createdRenderer, exception);
            VulkanContext failedContext = this.context;
            this.context = null;
            HdrPresentation.detach(failedContext);
            ResourceCleanup.close(failedContext, exception);
            PrimeInfo.LOGGER.error("Prime Vulkan initialization failed", exception);
        }
    }

    public void observeWorld(ClientLevel currentWorld, boolean rendererReady) {
        if (this.world != currentWorld) {
            this.world = currentWorld;
            this.states.worldChanged();
        }
        if (currentWorld == null) {
            this.states.worldAbsent();
        } else {
            this.states.worldStreaming(rendererReady);
        }
    }

    public void retireFailed(Minecraft minecraft, TerrainOwnership terrain) {
        VulkanRenderer failedRenderer = this.retiringRenderer;
        if (failedRenderer == null) {
            return;
        }
        try {
            failedRenderer.close();
            if (this.retiringRenderer == failedRenderer) {
                this.retiringRenderer = null;
            }
            terrain.restore(minecraft, true);
        } catch (RuntimeException exception) {
            PrimeInfo.LOGGER.error("Failed to retire Prime Vulkan resources", exception);
        }
    }

    public void disable(Minecraft minecraft, TerrainOwnership terrain) {
        VulkanRenderer activeRenderer = this.renderer;
        this.world = null;
        this.states.disabled();
        this.renderer = null;
        RuntimeException failure = null;
        try {
            terrain.restore(minecraft, true);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (activeRenderer != null) {
            try {
                activeRenderer.close();
            } catch (RuntimeException exception) {
                this.retiringRenderer = activeRenderer;
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            PrimeInfo.LOGGER.error(
                    "Failed to stop Prime after path tracing was disabled", failure);
        }
    }

    /** Any failure after host acceptance retires the renderer before another frame can reuse it. */
    public void fail(Throwable failure) {
        this.failureReason = RuntimeFailureSummary.describe(failure);
        this.states.fail();
        VulkanRenderer failedRenderer = this.renderer;
        this.world = null;
        if (failedRenderer != null) {
            if (this.renderer == failedRenderer) {
                this.renderer = null;
            }
            this.retiringRenderer = failedRenderer;
        }
        PrimeInfo.LOGGER.error(
                "Prime ray tracing failed; returning to vanilla rendering", failure);
    }

    public ResourceReload beginResourceReload() {
        VulkanRenderer activeRenderer = this.renderer;
        return activeRenderer == null
                ? ResourceReload.inactive()
                : new ResourceReload(
                        activeRenderer, activeRenderer.beginResourceReload());
    }

    public boolean finishResourceReload(
            ResourceReload reload, Minecraft minecraft, boolean reloadShaders) {
        java.util.Objects.requireNonNull(reload, "reload");
        if (reload.renderer == null) {
            return false;
        }
        reload.renderer.finishResourceReload(
                reload.rendererReload, minecraft, reloadShaders);
        return reloadShaders && this.renderer == reload.renderer;
    }

    public void abortResourceReload(ResourceReload reload) {
        java.util.Objects.requireNonNull(reload, "reload");
        if (reload.renderer != null) {
            reload.renderer.abortResourceReload(reload.rendererReload);
        }
    }

    public void shutdown() {
        this.shuttingDown = true;
        VulkanRenderer activeRenderer = this.renderer;
        VulkanRenderer failedRenderer = this.retiringRenderer;
        VulkanContext activeContext = this.context;
        this.world = null;
        RuntimeException failure = null;
        try {
            if (activeRenderer != null) {
                failure = ResourceCleanup.close(activeRenderer, failure);
                if (this.renderer == activeRenderer) {
                    this.renderer = null;
                }
            }
            if (failedRenderer != null && failedRenderer != activeRenderer) {
                failure = ResourceCleanup.close(failedRenderer, failure);
                if (this.retiringRenderer == failedRenderer) {
                    this.retiringRenderer = null;
                }
            }
            if (activeContext != null) {
                HdrPresentation.detach(activeContext);
                failure = ResourceCleanup.close(activeContext, failure);
                if (this.context == activeContext) {
                    this.context = null;
                }
            }
        } finally {
            this.states.shutdown();
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class ResourceReload {
        private final VulkanRenderer renderer;
        private final VulkanRenderer.ResourceReload rendererReload;

        private ResourceReload(
                VulkanRenderer renderer,
                VulkanRenderer.ResourceReload rendererReload) {
            this.renderer = renderer;
            this.rendererReload = rendererReload;
        }

        private static ResourceReload inactive() {
            return new ResourceReload(null, null);
        }

        public CompletableFuture<Void> ready() {
            return this.rendererReload == null
                    ? CompletableFuture.completedFuture(null)
                    : this.rendererReload.ready();
        }
    }
}
