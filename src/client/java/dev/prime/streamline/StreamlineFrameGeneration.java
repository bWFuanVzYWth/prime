package dev.prime.streamline;

import dev.prime.binding.streamline.BufferType;
import dev.prime.binding.streamline.Constants;
import dev.prime.binding.streamline.DlssgFlag;
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
    private static MemorySegment tokenOut;
    private static MemorySegment frameIndex;
    private static int maximumGeneratedFrameCount;
    private static int minimumWidthOrHeight;
    private static int swapchainFormat;
    private static int swapchainBackBufferCount;
    private static OptionsKey lastOptions;
    private static MemorySegment currentToken = MemorySegment.NULL;
    private static Frame frame;

    private StreamlineFrameGeneration() {
    }

    public static synchronized void initialize(Streamline instance) {
        shutdown();
        if (instance == null) {
            return;
        }
        try {
            Arena shared = Arena.ofShared();
            FrameGeneration loaded = FrameGeneration.load(instance, FrameGeneration.FEATURE_ID);
            tokenOut = shared.allocate(java.lang.foreign.ValueLayout.ADDRESS);
            frameIndex = shared.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
            streamline = instance;
            frameGeneration = loaded;
            arena = shared;
            refreshSupport();
        } catch (Throwable failure) {
            report("initialize", "Streamline DLSS-G initialization failed", failure);
            shutdown();
        }
    }

    public static synchronized boolean available() {
        return streamline != null && frameGeneration != null && maximumGeneratedFrameCount > 0;
    }

    public static synchronized int maximumMultiplier() {
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
    }

    public static synchronized boolean beginFrame(int logicalFrameIndex) {
        frame = null;
        currentToken = StreamlineReflex.currentToken();
        if (!available()
                || currentToken.equals(MemorySegment.NULL)
                || logicalFrameIndex != StreamlineReflex.currentFrameIndex()
                || !PrimeConfig.dlssFrameGenerationEnabled()
                || PrimeConfig.reflexMode() == dev.prime.binding.streamline.ReflexMode.OFF) {
            currentToken = MemorySegment.NULL;
            return false;
        }
        frameIndex.set(ValueLayout.JAVA_INT, 0, logicalFrameIndex);
        return true;
    }

    public static synchronized MemorySegment currentToken() {
        return currentToken;
    }

    public static synchronized void publish(
            int logicalFrameIndex,
            FrameCamera camera,
            RawWavefrontFrame rawFrame,
            VulkanImage color,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int backBufferCount) {
        if (!currentToken.equals(MemorySegment.NULL)
                && camera != null
                && rawFrame != null
                && color != null) {
            frame = new Frame(
                    logicalFrameIndex,
                    camera,
                    rawFrame,
                    color,
                    colorWidth,
                    colorHeight,
                    swapchainFormat != 0 ? swapchainFormat : colorFormat,
                    swapchainBackBufferCount > 0 ? swapchainBackBufferCount : backBufferCount);
        }
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
            fillConstants(constants, current.camera, current.logicalFrameIndex);
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
                        .flags(DlssgFlag.RETAIN_RESOURCES_WHEN_OFF.mask)
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
                if (frameGeneration.setOptions(
                        viewport, options)
                        != Streamline.RESULT_OK) {
                    return fail("set-options", "slDLSSGSetOptions failed", null);
                }
                lastOptions = desired;
            }
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
        lastOptions = null;
        frame = null;
        currentToken = MemorySegment.NULL;
        if (streamline == null || frameGeneration == null || arena == null) {
            return;
        }
        try (Arena callArena = Arena.ofConfined()) {
            DlssgOptions options = DlssgOptions.allocate(callArena)
                    .mode(DlssgMode.OFF)
                    .numFramesToGenerate(1)
                    .flags(DlssgFlag.RETAIN_RESOURCES_WHEN_OFF.mask)
                    .queueParallelismMode(DlssgQueueParallelismMode.BLOCK_PRESENTING_CLIENT_QUEUE)
                    .enableUserInterfaceRecomposition(SlBoolean.FALSE);
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            frameGeneration.setOptions(
                    viewport, options);
        } catch (Throwable failure) {
            report("disable", "Failed to disable Streamline DLSS-G", failure);
        }
    }

    public static synchronized void shutdown() {
        disable();
        streamline = null;
        frameGeneration = null;
        maximumGeneratedFrameCount = 0;
        minimumWidthOrHeight = 0;
        tokenOut = null;
        frameIndex = null;
        Arena shared = arena;
        arena = null;
        if (shared != null) {
            shared.close();
        }
    }

    private static void refreshSupport() {
        if (streamline == null || frameGeneration == null || arena == null) {
            return;
        }
        try (Arena callArena = Arena.ofConfined()) {
            DlssgState state = DlssgState.allocate(callArena);
            ViewportHandle viewport = ViewportHandle.allocate(callArena).value(VIEWPORT);
            int result = frameGeneration.getState(
                    viewport, state, null);
            if (result != Streamline.RESULT_OK) {
                report("support-" + result, "slDLSSGGetState support query failed", null);
                return;
            }
            maximumGeneratedFrameCount = Math.max(0, state.numFramesToGenerateMax());
            minimumWidthOrHeight = Math.max(0, state.minWidthOrHeight());
        } catch (Throwable failure) {
            report("support", "Failed to query Streamline DLSS-G support", failure);
        }
    }

    private static int currentFrameIndex() {
        return frameIndex == null ? -1 : frameIndex.get(ValueLayout.JAVA_INT, 0);
    }

    private static void fillConstants(Constants constants, FrameCamera camera, int logicalFrameIndex) {
        float[] projection = camera.projection().get(new float[16]);
        float[] inverse = new org.joml.Matrix4f(camera.projection()).invert().get(new float[16]);
        constants.cameraViewToClip(projection)
                .clipToCameraView(inverse)
                .clipToLensClip(new org.joml.Matrix4f().get(new float[16]))
                .clipToPrevClip(new org.joml.Matrix4f().get(new float[16]))
                .prevClipToClip(new org.joml.Matrix4f().get(new float[16]))
                .jitterOffset(0.0f, 0.0f)
                .mvecScale(1.0f, 1.0f)
                .cameraPinholeOffset(
                        (float) (camera.renderX() - camera.x()),
                        (float) (camera.renderY() - camera.y()))
                .cameraPos((float) camera.x(), (float) camera.y(), (float) camera.z())
                .cameraNear(0.1f)
                .cameraFar(1.0e7f)
                .cameraFOV((float) (2.0 * Math.atan(1.0 / Math.abs(camera.projection().m11()))))
                .cameraAspectRatio(Math.abs(camera.projection().m11() / camera.projection().m00()))
                .motionVectorsInvalidValue(0.0f)
                .depthInverted(SlBoolean.TRUE)
                .cameraMotionIncluded(SlBoolean.TRUE)
                .motionVectors3D(SlBoolean.FALSE)
                .reset(SlBoolean.FALSE)
                .orthographicProjection(SlBoolean.FALSE)
                .motionVectorsDilated(SlBoolean.FALSE)
                .motionVectorsJittered(SlBoolean.FALSE)
                .minRelativeLinearDepthObjectSeparation(40.0f);
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
            RawWavefrontFrame rawFrame,
            VulkanImage color,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int backBufferCount) {
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
