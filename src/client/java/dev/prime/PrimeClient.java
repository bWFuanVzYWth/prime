package dev.prime;

import dev.prime.binding.streamline.EngineType;
import dev.prime.binding.streamline.FrameGeneration;
import dev.prime.binding.streamline.Pcl;
import dev.prime.binding.streamline.PreferenceFlag;
import dev.prime.binding.streamline.Preferences;
import dev.prime.binding.streamline.Reflex;
import dev.prime.binding.streamline.RenderApi;
import dev.prime.binding.streamline.Streamline;
import dev.prime.client.PrimeRuntime;
import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.runtime.RendererLifecycle;
import dev.prime.render.scene.vanilla.ItemFrameModelFallback;
import dev.prime.render.vulkan.natives.NativeLibraries;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.lwjgl.system.SharedLibrary;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class PrimeClient implements ClientModInitializer {
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(
            PrimeInfo.MOD_ID, "ray_tracing_resources");

    public static Streamline streamline() {
        return streamlineInstance;
    }

    private static Streamline streamlineInstance;
    // Native bootstrap initializes this owner once; Minecraft closes it before Vulkan teardown.
    private static SharedLibrary[] streamlineLibraries = new SharedLibrary[0];

    public static boolean initializeStreamline(Path interposerPath) {
        if (streamlineInstance != null) {
            return true;
        }
        SharedLibrary[] libraries = new SharedLibrary[6];
        int loadedCount = 0;
        Streamline instance = null;
        try {
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_COMMON.getOrCreateLibrary();
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_PCL.getOrCreateLibrary();
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_REFLEX.getOrCreateLibrary();
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_DLSSG.getOrCreateLibrary();
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_DLSSG_FEATURE.getOrCreateLibrary();
            libraries[loadedCount++] =
                    NativeLibraries.NATIVE_STREAMLINE_LOWLATENCY_FEATURE.getOrCreateLibrary();
            instance = Streamline.open(interposerPath);
            Path logDir = createStreamlineLogDirectory();
            int initResult;
            try (Arena arena = Arena.ofConfined()) {
                var preferences = Preferences.allocate(arena);
                MemorySegment pluginPath = arena.allocateFrom(
                        NativeLibraries.extractedNativePath().toString(),
                        StandardCharsets.UTF_16LE);
                preferences.pathsToPlugins(arena.allocateFrom(ADDRESS, pluginPath));
                preferences.numPathsToPlugins(1);
                preferences.pathToLogsAndData(
                        arena.allocateFrom(logDir.toString(), StandardCharsets.UTF_16LE));
                preferences.featuresToLoad(arena.allocateFrom(
                        JAVA_INT,
                        FrameGeneration.FEATURE_ID,
                        Pcl.FEATURE_ID,
                        Reflex.FEATURE_ID));
                preferences.numFeaturesToLoad(3);
                preferences.flags(
                        PreferenceFlag.DISABLE_CL_STATE_TRACKING.mask
                                | PreferenceFlag.USE_FRAME_BASED_RESOURCE_TAGGING.mask);
                preferences.renderApi(RenderApi.VULKAN);
                preferences.engine(EngineType.CUSTOM);
                preferences.engineVersion(arena.allocateFrom("0.1.0"));
                preferences.projectId(
                        arena.allocateFrom("07210721-0721-4E6F-A8C1-1145142D0A3C"));
                preferences.showConsole(false);
                initResult = instance.init(preferences);
            }
            if (initResult != Streamline.RESULT_OK) {
                throw new IllegalStateException(
                        "Streamline slInit failed with result " + initResult);
            }
            streamlineInstance = instance;
            streamlineLibraries = libraries;
            PrimeInfo.LOGGER.info("Streamline base runtime initialized");
            return true;
        } catch (RuntimeException | LinkageError failure) {
            closeStreamline(instance, libraries, loadedCount, false, failure);
            PrimeInfo.LOGGER.warn(
                    "Streamline initialization failed; Reflex and DLSS-G are disabled",
                    failure);
            return false;
        }
    }

    public static void shutdownStreamline() {
        Streamline instance = streamlineInstance;
        SharedLibrary[] libraries = streamlineLibraries;
        streamlineInstance = null;
        streamlineLibraries = new SharedLibrary[0];
        Throwable failure = closeStreamline(
                instance, libraries, libraries.length, true, null);
        if (failure != null) {
            PrimeInfo.LOGGER.warn("Streamline shutdown was incomplete", failure);
        }
    }

    private static Path createStreamlineLogDirectory() {
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("prime")
                .resolve("streamline")
                .toAbsolutePath();
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to create the Streamline log directory", exception);
        }
        return path;
    }

    private static Throwable closeStreamline(
            Streamline instance,
            SharedLibrary[] libraries,
            int loadedCount,
            boolean shutdown,
            Throwable failure) {
        if (instance != null && shutdown) {
            try {
                int result = instance.shutdown();
                if (result != Streamline.RESULT_OK) {
                    throw new IllegalStateException(
                            "Streamline slShutdown failed with result " + result);
                }
            } catch (RuntimeException | LinkageError exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (instance != null) {
            try {
                instance.close();
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        for (int index = loadedCount - 1; index >= 0; index--) {
            SharedLibrary library = libraries[index];
            if (library == null) {
                continue;
            }
            try {
                library.free();
            } catch (RuntimeException | LinkageError exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    private static Throwable appendFailure(Throwable failure, Throwable cleanupFailure) {
        if (failure == null) {
            return cleanupFailure;
        }
        if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    @Override
    public void onInitializeClient() {
        PrimeConfig.load();
        if (PrimeConfig.dlssFrameGenerationEnabled()) {
            PrimeInfo.LOGGER.warn(
                    "DLSS Frame Generation is a high-risk experiment with a known unrecoverable NVIDIA Vulkan device-lost crash");
        }
        PrimeInfo.LOGGER.info("Initializing Prime ray tracing framework");
        ItemFrameModelFallback.register();
        PrimeRuntime.instance().initialize(PrimeConfig.rendererSettings());
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resourceLoader.registerReloadListener(RELOAD_LISTENER_ID, new PreparableReloadListener() {
            private boolean initialReload = true;

            @Override
            public CompletableFuture<Void> reload(
                    SharedState state,
                    Executor preparationExecutor,
                    PreparationBarrier preparationBarrier,
                    Executor applyExecutor) {
                PrimeRuntime runtime = PrimeRuntime.instance();
                AtomicReference<RendererLifecycle.ResourceReload> reload =
                        new AtomicReference<>();
                CompletableFuture<RendererLifecycle.ResourceReload> retired =
                        CompletableFuture.supplyAsync(() -> {
                            RendererLifecycle.ResourceReload ticket =
                                    runtime.beginResourceReload();
                            reload.set(ticket);
                            return ticket;
                        }, preparationExecutor);
                CompletableFuture<Void> applied = retired
                        .thenCompose(ticket -> ticket.ready().thenApply(ignored -> ticket))
                        .thenCompose(preparationBarrier::wait)
                        .thenComposeAsync(ticket -> CompletableFuture.runAsync(() -> {
                            boolean reloadShaders = !this.initialReload;
                            runtime.finishResourceReload(ticket, reloadShaders);
                            this.initialReload = false;
                        }, Minecraft.getInstance()), applyExecutor);
                return applied.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        return;
                    }
                    RendererLifecycle.ResourceReload ticket = reload.get();
                    if (ticket == null) {
                        return;
                    }
                    try {
                        runtime.abortResourceReload(ticket);
                    } catch (RuntimeException abortFailure) {
                        failure.addSuppressed(abortFailure);
                    }
                });
            }
        });
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, RELOAD_LISTENER_ID);
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, RELOAD_LISTENER_ID);

    }
}
