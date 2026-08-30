package dev.prime.streamline;

import dev.prime.binding.streamline.BufferType;
import dev.prime.binding.streamline.Constants;
import dev.prime.binding.streamline.DlssgMode;
import dev.prime.binding.streamline.DlssgOptions;
import dev.prime.binding.streamline.DlssgQueueParallelismMode;
import dev.prime.binding.streamline.DlssgState;
import dev.prime.binding.streamline.FrameGeneration;
import dev.prime.binding.streamline.Resource;
import dev.prime.binding.streamline.ResourceLifecycle;
import dev.prime.binding.streamline.ResourceTag;
import dev.prime.binding.streamline.ResourceType;
import dev.prime.binding.streamline.SlBoolean;
import dev.prime.binding.streamline.Streamline;
import dev.prime.binding.streamline.ViewportHandle;
import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.FrameCamera;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.StreamlineInputFlipPass;
import dev.prime.render.vulkan.VulkanImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VK12;

public final class StreamlineFrameGeneration {
    private static final int VIEWPORT = 0;
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private static Streamline streamline;
    private static FrameGeneration frameGeneration;
    private static Arena arena;
    private static MemorySegment frameIndex;
    private static StreamlineFrameGenerationSupport support =
            StreamlineFrameGenerationSupport.unavailable();
    private static int swapchainFormat;
    private static int swapchainBackBufferCount;
    private static OptionsKey lastOptions;
    private static MemorySegment currentToken = MemorySegment.NULL;
    private static Frame frame;
    private static SubmittedFrame lastSubmittedFrame;
    private static int preparedFrameIndex = -1;
    private static boolean featureResourcesMayExist;

    private StreamlineFrameGeneration() {
    }

    public static synchronized void initialize(Streamline instance) {
        shutdown();
        if (instance == null) {
            return;
        }
        Arena shared = null;
        try {
            shared = Arena.ofShared();
            FrameGeneration loaded = FrameGeneration.load(instance, FrameGeneration.FEATURE_ID);
            MemorySegment loadedFrameIndex =
                    shared.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
            streamline = instance;
            frameGeneration = loaded;
            arena = shared;
            frameIndex = loadedFrameIndex;
            refreshSupport();
        } catch (Throwable failure) {
            if (shared != null && arena != shared) {
                shared.close();
            }
            report("initialize", "Streamline DLSS-G initialization failed", failure);
            shutdown();
        }
    }

    public static synchronized boolean available() {
        return streamline != null && frameGeneration != null && support.featureAvailable();
    }

    public static synchronized int maximumMultiplier() {
        int maximumGeneratedFrameCount = support.maximumGeneratedFrameCount();
        return maximumGeneratedFrameCount == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(2, maximumGeneratedFrameCount + 1);
    }

    public static synchronized int effectiveOutputMultiplier() {
        return Math.min(
                Math.max(2, PrimeConfig.dlssFrameGenerationMultiplier()),
                maximumMultiplier());
    }

    public static synchronized void onSwapchainConfigured(int format, int backBufferCount) {
        swapchainFormat = format;
        swapchainBackBufferCount = backBufferCount;
        lastOptions = null;
        lastSubmittedFrame = null;
        preparedFrameIndex = -1;
    }

    /** Releases every Streamline swapchain dependency before Minecraft destroys that swapchain. */
    public static synchronized void beforeSwapchainReconfigure() {
        disableInternal(true);
        lastSubmittedFrame = null;
    }

    public static synchronized boolean beginFrame(int logicalFrameIndex) {
        frame = null;
        preparedFrameIndex = -1;
        boolean requested = PrimeConfig.dlssFrameGenerationEnabled()
                && PrimeConfig.reflexMode() != dev.prime.binding.streamline.ReflexMode.OFF;
        if (!requested) {
            if (active()) disableInternal(false);
            currentToken = MemorySegment.NULL;
            return false;
        }
        if (!refreshSupport() || !support.runtimeCanRetry()) {
            if (active()) disableInternal(false);
            currentToken = MemorySegment.NULL;
            return false;
        }
        currentToken = StreamlineReflex.currentToken();
        if (currentToken.equals(MemorySegment.NULL)
                || logicalFrameIndex != StreamlineReflex.currentFrameIndex()) {
            currentToken = MemorySegment.NULL;
            return false;
        }
        frameIndex.set(ValueLayout.JAVA_INT, 0, logicalFrameIndex);
        return true;
    }

    public static synchronized MemorySegment currentToken() {
        return currentToken;
    }

    /** True only while the current Minecraft frame can actually feed DLSS-G. */
    public static synchronized boolean uiRecompositionActive() {
        return PrimeConfig.dlssFrameGenerationUiRecomposition()
                && !currentToken.equals(MemorySegment.NULL);
    }

    public static synchronized boolean publish(
            int logicalFrameIndex,
            FrameCamera camera,
            SubpixelJitter jitter,
            boolean reconstructionReset,
            RawWavefrontFrame rawFrame,
            VulkanImage color,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int backBufferCount) {
        if (currentToken.equals(MemorySegment.NULL)
                || camera == null
                || jitter == null
                || rawFrame == null
                || color == null
                || logicalFrameIndex != currentFrameIndex()) {
            return false;
        }
        if (!support.supportsFrame(
                rawFrame.viewZ().width(),
                rawFrame.viewZ().height(),
                rawFrame.transportMetadata().width(),
                rawFrame.transportMetadata().height(),
                colorWidth,
                colorHeight)) {
            if (active()) disableInternal(false);
            return false;
        }
        frame = new Frame(
                logicalFrameIndex,
                camera,
                jitter,
                reconstructionReset,
                colorWidth,
                colorHeight,
                swapchainFormat != 0 ? swapchainFormat : colorFormat,
                swapchainBackBufferCount > 0 ? swapchainBackBufferCount : backBufferCount);
        return true;
    }

    public static synchronized boolean prepare(
            VkCommandBuffer commandBuffer, StreamlineInputFlipPass flippedInputs) {
        Frame current = frame;
        if (current == null
                || flippedInputs == null
                || current.logicalFrameIndex != currentFrameIndex()) {
            return false;
        }
        try (Arena callArena = Arena.ofConfined()) {
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            Constants constants = Constants.allocate(callArena);
            SubmittedFrame history = lastSubmittedFrame;
            boolean reset = current.reconstructionReset
                    || history == null
                    || current.logicalFrameIndex != history.logicalFrameIndex + 1;
            FrameCamera previous = reset ? current.camera : history.camera;
            StreamlineFrameConstants.create(
                            current.camera,
                            previous,
                            current.jitter,
                            reset,
                            flippedInputs.motion().width(),
                            flippedInputs.motion().height())
                    .write(constants);
            if (streamline.setConstants(
                    constants, currentToken, viewport)
                    != Streamline.RESULT_OK) {
                return fail("set-constants", "slSetConstants failed", null);
            }
            VulkanImage depth = flippedInputs.depth();
            VulkanImage motion = flippedInputs.motion();
            VulkanImage color = flippedInputs.color();
            MemorySegment tags = ResourceTag.allocateArray(callArena, 3);
            tag(ResourceTag.wrap(tags, 0), depth, BufferType.DEPTH, callArena);
            tag(ResourceTag.wrap(tags, 1), motion, BufferType.MOTION_VECTORS, callArena);
            tag(ResourceTag.wrap(tags, 2), color, BufferType.HUD_LESS_COLOR, callArena);
            if (streamline.setTagForFrame(
                    currentToken,
                    viewport,
                    tags,
                    3,
                    MemorySegment.ofAddress(commandBuffer.address()))
                    != Streamline.RESULT_OK) {
                return fail("set-tag", "slSetTagForFrame failed", null);
            }
            int multiplier = Math.min(
                    PrimeConfig.dlssFrameGenerationMultiplier(), maximumMultiplier());
            OptionsKey desired = new OptionsKey(
                    multiplier - 1,
                    current.backBufferCount,
                    current.colorWidth,
                    current.colorHeight,
                    current.colorFormat,
                    motion.format(),
                    depth.format(),
                    color.format(),
                    PrimeConfig.dlssFrameGenerationUiRecomposition(),
                    PrimeConfig.dlssFrameGenerationUiRecomposition()
                            ? VK12.VK_FORMAT_R8_UNORM
                            : 0);
            if (!desired.equals(lastOptions)) {
                DlssgOptions options = DlssgOptions.allocate(callArena)
                        .mode(DlssgMode.ON)
                        .numFramesToGenerate(desired.generatedFrameCount)
                        .flags(0)
                        .numBackBuffers(desired.backBufferCount)
                        .mvecDepthWidth(motion.width())
                        .mvecDepthHeight(motion.height())
                        .colorWidth(desired.colorWidth)
                        .colorHeight(desired.colorHeight)
                        .colorBufferFormat(desired.colorFormat)
                        .mvecBufferFormat(desired.motionFormat)
                        .depthBufferFormat(desired.depthFormat)
                        .hudLessBufferFormat(desired.hudlessFormat)
                        .uiBufferFormat(desired.uiFormat)
                        .queueParallelismMode(DlssgQueueParallelismMode.BLOCK_PRESENTING_CLIENT_QUEUE)
                        .enableUserInterfaceRecomposition(
                                desired.uiRecomposition ? SlBoolean.TRUE : SlBoolean.FALSE)
                        .dynamicResWidth(0)
                        .dynamicResHeight(0)
                        .dynamicTargetFrameRate(0.0f);
                featureResourcesMayExist = true;
                if (frameGeneration.setOptions(viewport, options) != Streamline.RESULT_OK) {
                    return fail("set-options", "slDLSSGSetOptions failed", null);
                }
                lastOptions = desired;
            }
            if (!refreshSupport()
                    || !support.supportsFrame(
                            depth.width(),
                            depth.height(),
                            motion.width(),
                            motion.height(),
                            color.width(),
                            color.height())) {
                return fail(
                        "runtime-state-" + support.status(),
                        "DLSS-G runtime rejected the current frame (status 0x"
                                + Integer.toHexString(support.status()) + ")",
                        null);
            }
            preparedFrameIndex = current.logicalFrameIndex;
            return true;
        } catch (Throwable failure) {
            return fail("prepare", "DLSS-G frame preparation failed", failure);
        }
    }

    public static synchronized boolean prepareUiAlpha(
            VkCommandBuffer commandBuffer, VulkanImage alpha, int width, int height) {
        if (alpha == null
                || currentToken.equals(MemorySegment.NULL)
                || frame == null
                || frame.logicalFrameIndex != currentFrameIndex()
                || !PrimeConfig.dlssFrameGenerationUiRecomposition()
                || alpha.width() != width
                || alpha.height() != height
                || alpha.format() != VK12.VK_FORMAT_R8_UNORM) {
            return false;
        }
        try (Arena callArena = Arena.ofConfined()) {
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            MemorySegment tags = ResourceTag.allocateArray(callArena, 1);
            tag(ResourceTag.wrap(tags, 0), alpha, BufferType.UI_ALPHA, callArena);
            if (streamline.setTagForFrame(
                    currentToken,
                    viewport,
                    tags,
                    1,
                    MemorySegment.ofAddress(commandBuffer.address()))
                    != Streamline.RESULT_OK) {
                return fail("set-ui-alpha-tag", "slSetTagForFrame UI_ALPHA failed", null);
            }
            return true;
        } catch (Throwable failure) {
            return fail("ui-alpha", "DLSS-G UI alpha tagging failed", failure);
        }
    }

    public static synchronized void disable() {
        disableInternal(false);
    }

    public static synchronized void submitted(int logicalFrameIndex) {
        if (frame == null
                || preparedFrameIndex != logicalFrameIndex
                || frame.logicalFrameIndex != logicalFrameIndex) {
            return;
        }
        lastSubmittedFrame = new SubmittedFrame(logicalFrameIndex, frame.camera);
        preparedFrameIndex = -1;
    }

    public static synchronized void abandon(int logicalFrameIndex) {
        if (frame != null && frame.logicalFrameIndex == logicalFrameIndex) {
            finishFrame();
        }
    }

    private static void finishFrame() {
        frame = null;
        currentToken = MemorySegment.NULL;
        preparedFrameIndex = -1;
    }

    private static void disableInternal(boolean strict) {
        boolean enabled = active();
        lastOptions = null;
        finishFrame();
        if (streamline == null || frameGeneration == null || arena == null) {
            return;
        }
        RuntimeException failure = null;
        try (Arena callArena = Arena.ofConfined()) {
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            if (enabled) {
                DlssgOptions options = DlssgOptions.allocate(callArena)
                        .mode(DlssgMode.OFF)
                        .numFramesToGenerate(1)
                        .flags(0)
                        .queueParallelismMode(
                                DlssgQueueParallelismMode.BLOCK_PRESENTING_CLIENT_QUEUE)
                        .enableUserInterfaceRecomposition(SlBoolean.FALSE);
                int result = frameGeneration.setOptions(viewport, options);
                if (result != Streamline.RESULT_OK) {
                    failure = new IllegalStateException(
                            "slDLSSGSetOptions(OFF) failed with result " + result);
                }
                int freeResult = streamline.freeResources(
                        FrameGeneration.FEATURE_ID, viewport);
                if (freeResult != Streamline.RESULT_OK) {
                    IllegalStateException freeFailure = new IllegalStateException(
                            "slFreeResources(DLSS-G) failed with result " + freeResult);
                    if (failure == null) failure = freeFailure;
                    else failure.addSuppressed(freeFailure);
                }
                if (result == Streamline.RESULT_OK
                        && freeResult == Streamline.RESULT_OK) {
                    featureResourcesMayExist = false;
                }
            }
        } catch (Throwable throwable) {
            if (failure == null) failure = new RuntimeException(throwable);
            else failure.addSuppressed(throwable);
        }
        if (failure != null) {
            if (strict) throw failure;
            report("disable", "Failed to disable Streamline DLSS-G", failure);
        }
    }

    public static synchronized void shutdown() {
        disable();
        streamline = null;
        frameGeneration = null;
        support = StreamlineFrameGenerationSupport.unavailable();
        lastSubmittedFrame = null;
        featureResourcesMayExist = false;
        frameIndex = null;
        Arena shared = arena;
        arena = null;
        if (shared != null) {
            shared.close();
        }
    }

    private static boolean refreshSupport() {
        if (streamline == null || frameGeneration == null || arena == null) {
            support = StreamlineFrameGenerationSupport.unavailable();
            return false;
        }
        try (Arena callArena = Arena.ofConfined()) {
            DlssgState state = DlssgState.allocate(callArena);
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            int result = frameGeneration.getState(
                    viewport, state, null);
            if (result != Streamline.RESULT_OK) {
                report("support-" + result, "slDLSSGGetState support query failed", null);
                support = StreamlineFrameGenerationSupport.unavailable();
                return false;
            }
            support = new StreamlineFrameGenerationSupport(
                    state.status(),
                    state.numFramesToGenerateMax(),
                    state.minWidthOrHeight());
            return true;
        } catch (Throwable failure) {
            report("support", "Failed to query Streamline DLSS-G support", failure);
            support = StreamlineFrameGenerationSupport.unavailable();
            return false;
        }
    }

    private static int currentFrameIndex() {
        return frameIndex == null ? -1 : frameIndex.get(ValueLayout.JAVA_INT, 0);
    }

    private static void tag(
            ResourceTag tag, VulkanImage image, BufferType type, Arena arena) {
        Resource resource = Resource.allocate(arena)
                .type(ResourceType.TEX2D)
                .nativeHandle(MemorySegment.ofAddress(image.image()))
                .memory(MemorySegment.NULL)
                .view(MemorySegment.ofAddress(image.view()))
                .state(VK12.VK_IMAGE_LAYOUT_GENERAL)
                .width(image.width())
                .height(image.height())
                .nativeFormat(image.format())
                .mipLevels(image.mipLevels())
                .arrayLayers(1)
                .usage(image.usage());
        tag.resource(resource.segment())
                .type(type)
                .lifecycle(ResourceLifecycle.VALID_UNTIL_PRESENT)
                .extent(0, 0, image.width(), image.height());
    }

    private static boolean fail(String key, String message, Throwable failure) {
        report(key, message, failure);
        disable();
        return false;
    }

    private static void report(String key, String message, Throwable failure) {
        if (!REPORTED_FAILURES.add(key)) {
            return;
        }
        if (failure == null) {
            PrimeInfo.LOGGER.warn(message);
        } else {
            PrimeInfo.LOGGER.warn(message, failure);
        }
    }

    private record Frame(
            int logicalFrameIndex,
            FrameCamera camera,
            SubpixelJitter jitter,
            boolean reconstructionReset,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int backBufferCount) {
    }

    private static boolean active() {
        return lastOptions != null || featureResourcesMayExist;
    }

    private record SubmittedFrame(int logicalFrameIndex, FrameCamera camera) {
    }

    private record OptionsKey(
            int generatedFrameCount,
            int backBufferCount,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int motionFormat,
            int depthFormat,
            int hudlessFormat,
            boolean uiRecomposition,
            int uiFormat) {
    }
}
