package dev.prime.streamline;

import dev.prime.binding.streamline.HotKey;
import dev.prime.binding.streamline.Pcl;
import dev.prime.binding.streamline.PclMarker;
import dev.prime.binding.streamline.Reflex;
import dev.prime.binding.streamline.ReflexMode;
import dev.prime.binding.streamline.ReflexOptions;
import dev.prime.binding.streamline.Streamline;
import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Drives NVIDIA Reflex through the Streamline FFM binding, mirroring the call sequence of
 * Super Resolution's StreamlineReflexProvider. Render thread only; every public method is a
 * no-op until {@link #initialize} succeeds and the game has finished loading.
 */
public final class StreamlineReflex {
    private static final int PACING_WARMUP_FRAMES = 3;

    private static Reflex reflex;
    private static Pcl pcl;
    private static Streamline streamline;
    private static Arena arena;
    private static MemorySegment tokenOut;
    private static MemorySegment frameIndexSegment;
    private static ReflexOptions options;

    private static MemorySegment currentToken = MemorySegment.NULL;
    private static int frameCounter;
    private static boolean gameLoaded;
    private static int pacingWarmupRemaining;
    private static OptionsKey lastAppliedOptions;

    private StreamlineReflex() {
    }

    public static void initialize(Streamline instance) {
        try {
            Reflex loadedReflex = Reflex.load(instance, Reflex.FEATURE_ID);
            Pcl loadedPcl = Pcl.load(instance, Pcl.FEATURE_ID);
            Arena sharedArena = Arena.ofShared();
            streamline = instance;
            reflex = loadedReflex;
            pcl = loadedPcl;
            arena = sharedArena;
            tokenOut = sharedArena.allocate(ADDRESS);
            frameIndexSegment = sharedArena.allocate(JAVA_INT);
            options = ReflexOptions.allocate(sharedArena);
        } catch (Throwable failure) {
            PrimeInfo.LOGGER.warn(
                    "Streamline Reflex/PCL feature functions unavailable; low latency disabled",
                    failure);
            streamline = null;
            reflex = null;
            pcl = null;
            arena = null;
            tokenOut = null;
            frameIndexSegment = null;
            options = null;
        }
    }

    public static boolean available() {
        return reflex != null && pcl != null;
    }

    public static boolean pclAvailable() {
        return available() && gameLoaded && !currentToken.equals(MemorySegment.NULL);
    }

    public static MemorySegment currentToken() {
        return currentToken;
    }

    public static int currentFrameIndex() {
        return frameCounter;
    }

    public static void onGameLoadFinished() {
        gameLoaded = true;
    }

    public static void beginFrame() {
        if (!available() || !gameLoaded) {
            return;
        }
        if (PrimeConfig.reflexMode() == ReflexMode.OFF) {
            currentToken = MemorySegment.NULL;
            return;
        }
        frameIndexSegment.set(JAVA_INT, 0, ++frameCounter);
        if (streamline.getNewFrameToken(tokenOut, frameIndexSegment) != Streamline.RESULT_OK) {
            currentToken = MemorySegment.NULL;
            return;
        }
        currentToken = tokenOut.get(ADDRESS, 0);
        if (currentToken.equals(MemorySegment.NULL)) {
            return;
        }
        refreshOptions();
        sleep();
        setMarker(PclMarker.SIMULATION_START);
    }

    public static void endSimulation() {
        setMarker(PclMarker.SIMULATION_END);
    }

    public static void beginRenderSubmission() {
        setMarker(PclMarker.RENDER_SUBMIT_START);
    }

    public static void endRenderSubmission() {
        setMarker(PclMarker.RENDER_SUBMIT_END);
    }

    public static void beginPresent() {
        setMarker(PclMarker.PRESENT_START);
    }

    public static void endPresent() {
        setMarker(PclMarker.PRESENT_END);
    }

    public static void onLatencyPing(boolean pressed) {
        if (pressed) {
            setMarker(PclMarker.PC_LATENCY_PING);
        }
    }

    public static void onTriggerFlash() {
        setMarker(PclMarker.TRIGGER_FLASH);
    }

    /** Swapchain rebuilds invalidate the pacer; skip a few sleeps and re-apply options. */
    public static void invalidatePacing() {
        pacingWarmupRemaining = PACING_WARMUP_FRAMES;
        lastAppliedOptions = null;
    }

    public static boolean shouldSkipVanillaFrameLimiter() {
        return available()
                && gameLoaded
                && PrimeConfig.reflexMode() != ReflexMode.OFF
                && frameLimitUs() != 0;
    }

    /** FPS cap in microseconds; 0 disables. DLSS-FG must scale this by its output multiplier. */
    static int frameLimitUs() {
        int limit = Minecraft.getInstance().options.framerateLimit().get();
        if (limit <= 0 || limit >= 260) {
            return 0;
        }
        int frameLimitUs = (1_000_000 + limit - 1) / limit;
        if (PrimeConfig.dlssFrameGenerationEnabled()) {
            frameLimitUs = Math.max(
                    1,
                    frameLimitUs * StreamlineFrameGeneration.effectiveOutputMultiplier());
        }
        return frameLimitUs;
    }

    public static void release() {
        if (!available()) {
            lastAppliedOptions = null;
            return;
        }
        applyOptions(
                new OptionsKey(
                        ReflexMode.OFF,
                        frameLimitUs(),
                        HotKey.VK_F13,
                        Win32ThreadId.current(),
                        false),
                true);
        lastAppliedOptions = null;
        currentToken = MemorySegment.NULL;
    }

    public static void shutdown() {
        release();
        Arena sharedArena = arena;
        streamline = null;
        reflex = null;
        pcl = null;
        arena = null;
        tokenOut = null;
        frameIndexSegment = null;
        options = null;
        gameLoaded = false;
        currentToken = MemorySegment.NULL;
        if (sharedArena != null) {
            sharedArena.close();
        }
    }

    private static void sleep() {
        if (pacingWarmupRemaining > 0) {
            pacingWarmupRemaining--;
            return;
        }
        reflex.sleep(currentToken);
    }

    private static void refreshOptions() {
        applyOptions(
                new OptionsKey(
                        PrimeConfig.reflexMode(),
                        frameLimitUs(),
                        HotKey.VK_F13,
                        Win32ThreadId.current(),
                        false),
                false);
    }

    private static void applyOptions(OptionsKey desired, boolean force) {
        if (!force && desired.equals(lastAppliedOptions)) {
            return;
        }
        options.mode(desired.mode())
                .frameLimitUs(desired.frameLimitUs())
                .virtualKey(desired.virtualKey())
                .idThread(desired.threadId())
                .useMarkersToOptimize(desired.useMarkersToOptimize());
        if (reflex.setOptions(options) == Streamline.RESULT_OK) {
            lastAppliedOptions = desired;
        }
    }

    private static void setMarker(PclMarker marker) {
        if (!available()
                || !gameLoaded
                || PrimeConfig.reflexMode() == ReflexMode.OFF
                || currentToken.equals(MemorySegment.NULL)) {
            return;
        }
        pcl.setMarker(marker, currentToken);
    }

    private record OptionsKey(
            ReflexMode mode,
            int frameLimitUs,
            HotKey virtualKey,
            int threadId,
            boolean useMarkersToOptimize) {
    }
}
