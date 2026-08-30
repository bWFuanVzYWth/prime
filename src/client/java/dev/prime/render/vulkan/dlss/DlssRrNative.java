package dev.prime.render.vulkan.dlss;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.natives.NativeLibrary;
import dev.prime.render.vulkan.natives.NativeLibraries;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Stable, fixed-width Java binding for Prime's private DLSS Ray Reconstruction bridge. */
public final class DlssRrNative {
    public static final int ABI_VERSION = 10;
    static final int RENDER_PRESET_F = 6;
    static final int EXTENSION_QUERY_SIZE = 56;
    static final int INIT_DESCRIPTION_SIZE = 56;
    static final int OPTIMAL_SETTINGS_SIZE = 32;
    static final int FEATURE_DESCRIPTION_SIZE = 48;
    static final int IMAGE_SIZE = 32;
    static final int IMAGE_COUNT = 10;
    static final int EVALUATE_DESCRIPTION_SIZE = 176 + IMAGE_COUNT * IMAGE_SIZE;
    private static final int EXTENSION_CAPACITY = 64;
    private static final int EXTENSION_NAME_STRIDE = 256;
    private final Path featureDirectory;
    private final Path applicationDataDirectory;
    private final String engineVersion;
    private final long instanceExtensionsFunction;
    private final long deviceExtensionsFunction;
    private final long initializeFunction;
    private final long optimalSettingsFunction;
    private final long createFeatureFunction;
    private final long evaluateFunction;
    private final long releaseFeatureFunction;
    private final long shutdownFunction;

    private DlssRrNative() {
        ExtractedRuntime runtime = extractRuntime();
        this.featureDirectory = runtime.directory();
        this.applicationDataDirectory = createApplicationDataDirectory();
        this.engineVersion = FabricLoader.getInstance()
                .getModContainer(PrimeInfo.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        if (this.engineVersion.isBlank()) {
            throw new IllegalStateException("Prime's NGX engine version must not be blank");
        }
        SharedLibrary loaded = runtime.bridge.getOrCreateLibrary();
        try {
            BridgeFunctions functions = validateBridge(loaded);
            this.instanceExtensionsFunction = functions.instanceExtensions();
            this.deviceExtensionsFunction = functions.deviceExtensions();
            this.initializeFunction = functions.initialize();
            this.optimalSettingsFunction = functions.optimalSettings();
            this.createFeatureFunction = functions.createFeature();
            this.evaluateFunction = functions.evaluate();
            this.releaseFeatureFunction = functions.releaseFeature();
            this.shutdownFunction = functions.shutdown();
        } catch (RuntimeException | Error exception) {
            try {
                loaded.free();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public static boolean isSupportedPlatform() {
        return NativeLibraries.isWindowsX64();
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        return NativeLibraries.isWindowsX64(osName, architecture);
    }

    public static List<String> instanceExtensions() {
        return Holder.INSTANCE.queryExtensions(0L, 0L, Holder.INSTANCE.instanceExtensionsFunction);
    }

    public static List<String> deviceExtensions(long instance, long physicalDevice) {
        if (instance == 0L || physicalDevice == 0L) {
            throw new IllegalArgumentException("Vulkan instance and physical-device handles are required");
        }
        return Holder.INSTANCE.queryExtensions(
                instance, physicalDevice, Holder.INSTANCE.deviceExtensionsFunction);
    }

    public static Context initialize(VulkanContext context) {
        return Holder.INSTANCE.initializeContext(context);
    }

    static void verifyLibrary() {
        ExtractedRuntime runtime = extractRuntime();
        SharedLibrary loaded = runtime.bridge().getOrCreateLibrary();
        try {
            validateBridge(loaded);
        } catch (RuntimeException | Error exception) {
            try {
                loaded.free();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        loaded.free();
    }

    private List<String> queryExtensions(long instance, long physicalDevice, long function) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer names = stack.calloc(EXTENSION_CAPACITY * EXTENSION_NAME_STRIDE);
            ByteBuffer query = stack.calloc(EXTENSION_QUERY_SIZE).order(ByteOrder.nativeOrder());
            query.putLong(0, instance);
            query.putLong(8, physicalDevice);
            query.putInt(16, EXTENSION_CAPACITY);
            query.putLong(24, MemoryUtil.memAddress(names));
            query.putLong(32, MemoryUtil.memAddress(stack.UTF16(this.featureDirectory.toString())));
            query.putLong(40, MemoryUtil.memAddress(stack.UTF16(this.applicationDataDirectory.toString())));
            query.putLong(48, MemoryUtil.memAddress(stack.UTF8(this.engineVersion)));
            checkResult(JNI.invokePI(MemoryUtil.memAddress(query), function), "query DLSS RR Vulkan extensions");
            int count = query.getInt(20);
            if (count < 0 || count > EXTENSION_CAPACITY) {
                throw new IllegalStateException("DLSS RR returned an invalid extension count " + count);
            }
            LinkedHashSet<String> extensions = new LinkedHashSet<>(count);
            long namesAddress = MemoryUtil.memAddress(names);
            for (int index = 0; index < count; index++) {
                long address = namesAddress + (long) index * EXTENSION_NAME_STRIDE;
                String extension = MemoryUtil.memUTF8(address);
                if (extension.isBlank() || !extensions.add(extension)) {
                    throw new IllegalStateException(
                            "DLSS RR returned an invalid Vulkan extension list");
                }
            }
            return List.copyOf(extensions);
        }
    }

    private Context initializeContext(VulkanContext context) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer description = stack.calloc(INIT_DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
            description.putLong(0, context.vkDevice().getPhysicalDevice().getInstance().address());
            description.putLong(8, context.vkDevice().getPhysicalDevice().address());
            description.putLong(16, context.vkDevice().address());
            description.putLong(24, MemoryUtil.memAddress(stack.UTF16(this.featureDirectory.toString())));
            description.putLong(32, MemoryUtil.memAddress(stack.UTF16(this.applicationDataDirectory.toString())));
            description.putLong(40, MemoryUtil.memAddress(stack.UTF8(this.engineVersion)));
            ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            description.putLong(48, MemoryUtil.memAddress(output));
            checkResult(
                    JNI.invokePI(MemoryUtil.memAddress(description), this.initializeFunction),
                    "initialize NVIDIA NGX DLSS RR");
            long handle = output.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR returned a null context");
            }
            return new Context(this, handle);
        }
    }

    private static BridgeFunctions validateBridge(SharedLibrary library) {
        long getAbiVersion = requireFunction(library, "primeDlssRrGetAbiVersion");
        long getRenderPreset = requireFunction(library, "primeDlssRrGetRenderPreset");
        BridgeFunctions functions = new BridgeFunctions(
                requireFunction(library, "primeDlssRrGetInstanceExtensions"),
                requireFunction(library, "primeDlssRrGetDeviceExtensions"),
                requireFunction(library, "primeDlssRrInitialize"),
                requireFunction(library, "primeDlssRrGetOptimalSettings"),
                requireFunction(library, "primeDlssRrCreateFeature"),
                requireFunction(library, "primeDlssRrEvaluate"),
                requireFunction(library, "primeDlssRrReleaseFeature"),
                requireFunction(library, "primeDlssRrShutdown"));
        int abiVersion = JNI.invokeI(getAbiVersion);
        if (abiVersion != ABI_VERSION) {
            throw new IllegalStateException(
                    "Prime DLSS RR bridge ABI mismatch: expected "
                            + ABI_VERSION
                            + ", found "
                            + abiVersion);
        }
        int renderPreset = JNI.invokeI(getRenderPreset);
        if (renderPreset != RENDER_PRESET_F) {
            throw new IllegalStateException(
                    "Prime DLSS RR render preset mismatch: expected F ("
                            + RENDER_PRESET_F
                            + "), found "
                            + renderPreset);
        }
        return functions;
    }

    private static long requireFunction(SharedLibrary library, String name) {
        return NativeLibraries.requireFunction(library, name, "The DLSS RR bridge");
    }

    private static ExtractedRuntime extractRuntime() {
        if (!isSupportedPlatform()) {
            throw new IllegalStateException("DLSS RR currently supports Windows x86-64 only");
        }
        NativeLibraries.NATIVE_DLSSRR_BRIDGE.tryToExtract();
        NativeLibraries.NATIVE_DLSSRR_FEATURE.tryToExtract();
        return new ExtractedRuntime(
                NativeLibraries.extractedNativePath(),
                NativeLibraries.NATIVE_DLSSRR_BRIDGE
        );
    }

    private static Path createApplicationDataDirectory() {
        Path path = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("prime")
                .resolve("ngx")
                .toAbsolutePath();
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Prime's NGX application-data directory", exception);
        }
        return path;
    }

    private static void checkResult(int result, String operation) {
        NativeLibraries.checkResult(result, operation);
    }

    static void putMatrixForNgx(ByteBuffer target, int offset, Matrix4fc matrix) {
        // NGX specifies row-major matrices multiplied from the left. JOML's column-major memory
        // for M is byte-identical to a row-major representation of transpose(M), which is the
        // equivalent transform under row-vector multiplication.
        matrix.get(offset, target);
    }

    public static final class Context implements AutoCloseable {
        private final DlssRrNative nativeApi;
        private long handle;

        private Context(DlssRrNative nativeApi, long handle) {
            this.nativeApi = nativeApi;
            this.handle = handle;
        }

        public OptimalSettings optimalSettings(
                int outputWidth, int outputHeight, ReconstructionQualityMode quality) {
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalArgumentException("DLSS RR output dimensions must be positive");
            }
            Objects.requireNonNull(quality, "quality");
            long context = requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer settings = stack.calloc(OPTIMAL_SETTINGS_SIZE).order(ByteOrder.nativeOrder());
                settings.putLong(0, context);
                settings.putInt(8, outputWidth);
                settings.putInt(12, outputHeight);
                settings.putInt(16, DlssRrProfile.ngxPerfQualityValue(quality));
                checkResult(
                        JNI.invokePI(
                                MemoryUtil.memAddress(settings),
                                this.nativeApi.optimalSettingsFunction),
                        "query DLSS RR optimal settings");
                int width = settings.getInt(20);
                int height = settings.getInt(24);
                if (width <= 0
                        || height <= 0
                        || width > outputWidth
                        || height > outputHeight) {
                    throw new IllegalStateException("DLSS RR returned invalid optimal dimensions");
                }
                return new OptimalSettings(width, height);
            }
        }

        Feature createFeature(
                VkCommandBuffer commandBuffer,
                int renderWidth,
                int renderHeight,
                int outputWidth,
                int outputHeight,
                ReconstructionQualityMode quality) {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(quality, "quality");
            if (renderWidth <= 0
                    || renderHeight <= 0
                    || outputWidth <= 0
                    || outputHeight <= 0
                    || renderWidth > outputWidth
                    || renderHeight > outputHeight) {
                throw new IllegalArgumentException("DLSS RR feature dimensions are invalid");
            }
            long context = requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer description = stack.calloc(FEATURE_DESCRIPTION_SIZE)
                        .order(ByteOrder.nativeOrder());
                description.putLong(0, context);
                description.putLong(8, commandBuffer.address());
                description.putInt(16, renderWidth);
                description.putInt(20, renderHeight);
                description.putInt(24, outputWidth);
                description.putInt(28, outputHeight);
                description.putInt(32, DlssRrProfile.ngxPerfQualityValue(quality));
                ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
                description.putLong(40, MemoryUtil.memAddress(output));
                checkResult(
                        JNI.invokePI(
                                MemoryUtil.memAddress(description),
                                this.nativeApi.createFeatureFunction),
                        "create DLSS RR feature");
                long feature = output.getLong(0);
                if (feature == MemoryUtil.NULL) {
                    throw new IllegalStateException("DLSS RR returned a null feature");
                }
                return new Feature(
                        this.nativeApi,
                        feature,
                        renderWidth,
                        renderHeight,
                        outputWidth,
                        outputHeight);
            }
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR context is closed");
            }
            return this.handle;
        }

        @Override
        public void close() {
            long context = this.handle;
            if (context != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                checkResult(
                        JNI.invokePI(context, this.nativeApi.shutdownFunction),
                        "shut down NVIDIA NGX");
            }
        }
    }

    static final class Feature implements AutoCloseable {
        private final DlssRrNative nativeApi;
        private final int renderWidth;
        private final int renderHeight;
        private final int outputWidth;
        private final int outputHeight;
        private long handle;

        private Feature(
                DlssRrNative nativeApi,
                long handle,
                int renderWidth,
                int renderHeight,
                int outputWidth,
                int outputHeight) {
            this.nativeApi = nativeApi;
            this.handle = handle;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
        }

        void evaluate(VkCommandBuffer commandBuffer, Evaluation evaluation) {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("DLSS RR feature is closed");
            }
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(evaluation, "evaluation");
            if (evaluation.renderWidth() != this.renderWidth
                    || evaluation.renderHeight() != this.renderHeight
                    || evaluation.outputColor().width() != this.outputWidth
                    || evaluation.outputColor().height() != this.outputHeight) {
                throw new IllegalArgumentException(
                        "DLSS RR evaluation dimensions do not match its feature");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer description = stack.calloc(EVALUATE_DESCRIPTION_SIZE)
                        .order(ByteOrder.nativeOrder());
                description.putLong(0, this.handle);
                description.putLong(8, commandBuffer.address());
                description.putInt(16, evaluation.renderWidth());
                description.putInt(20, evaluation.renderHeight());
                description.putFloat(24, ngxJitterOffset(evaluation.sampleJitterX()));
                description.putFloat(28, ngxJitterOffset(evaluation.sampleJitterY()));
                description.putFloat(32, evaluation.motionScaleX());
                description.putFloat(36, evaluation.motionScaleY());
                description.putInt(40, evaluation.reset() ? 1 : 0);
                description.putFloat(44, evaluation.frameTimeMilliseconds());
                putMatrixForNgx(description, 48, evaluation.worldToView());
                putMatrixForNgx(description, 112, evaluation.viewToClip());
                putImage(description, 176, evaluation.diffuseAlbedo());
                putImage(description, 176 + IMAGE_SIZE, evaluation.specularAlbedo());
                putImage(description, 176 + 2 * IMAGE_SIZE, evaluation.normalRoughness());
                putImage(description, 176 + 3 * IMAGE_SIZE, evaluation.inputColor());
                putImage(description, 176 + 4 * IMAGE_SIZE, evaluation.outputColor());
                putImage(description, 176 + 5 * IMAGE_SIZE, evaluation.linearDepth());
                putImage(description, 176 + 6 * IMAGE_SIZE, evaluation.motionVectors());
                putImage(description, 176 + 7 * IMAGE_SIZE, evaluation.specularMotionVectors());
                putImage(description, 176 + 8 * IMAGE_SIZE, evaluation.specularHitDistance());
                putImage(description, 176 + 9 * IMAGE_SIZE, evaluation.responsivity());
                checkResult(
                        JNI.invokePI(MemoryUtil.memAddress(description), this.nativeApi.evaluateFunction),
                        "evaluate DLSS RR");
            }
        }

        private static void putImage(ByteBuffer target, int offset, VulkanImage image) {
            target.putLong(offset, image.image());
            target.putLong(offset + 8, image.view());
            target.putInt(offset + 16, image.format());
            target.putInt(offset + 20, image.width());
            target.putInt(offset + 24, image.height());
        }

        @Override
        public void close() {
            long feature = this.handle;
            if (feature != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                checkResult(
                        JNI.invokePI(feature, this.nativeApi.releaseFeatureFunction),
                        "release DLSS RR feature");
            }
        }
    }

    public record OptimalSettings(int renderWidth, int renderHeight) {}

    static float ngxJitterOffset(float sampleJitter) {
        return -sampleJitter;
    }

    /** One complete low-resolution RR evaluation and its full-resolution output. */
    record Evaluation(
            int renderWidth,
            int renderHeight,
            float sampleJitterX,
            float sampleJitterY,
            float motionScaleX,
            float motionScaleY,
            boolean reset,
            float frameTimeMilliseconds,
            Matrix4fc worldToView,
            Matrix4fc viewToClip,
            VulkanImage diffuseAlbedo,
            VulkanImage specularAlbedo,
            VulkanImage normalRoughness,
            VulkanImage inputColor,
            VulkanImage outputColor,
            VulkanImage linearDepth,
            VulkanImage motionVectors,
            VulkanImage specularMotionVectors,
            VulkanImage specularHitDistance,
            VulkanImage responsivity) {
        public Evaluation {
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalArgumentException("DLSS RR render dimensions must be positive");
            }
            if (!Float.isFinite(sampleJitterX)
                    || !Float.isFinite(sampleJitterY)
                    || Math.abs(sampleJitterX) > 0.5F
                    || Math.abs(sampleJitterY) > 0.5F) {
                throw new IllegalArgumentException("DLSS RR sample jitter must be finite pixel offsets");
            }
            if (motionScaleX != renderWidth || motionScaleY != renderHeight) {
                throw new IllegalArgumentException("DLSS RR motion scale must match the render extent");
            }
            if (!Float.isFinite(frameTimeMilliseconds) || frameTimeMilliseconds < 0.0F) {
                throw new IllegalArgumentException("DLSS RR frame time must be finite and non-negative");
            }
            worldToView = Objects.requireNonNull(worldToView, "worldToView");
            viewToClip = Objects.requireNonNull(viewToClip, "viewToClip");
            if (!worldToView.isFinite() || !viewToClip.isFinite()) {
                throw new IllegalArgumentException("DLSS RR matrices must be finite");
            }
            requireInput(diffuseAlbedo, "diffuse albedo", DlssRrTargets.ALBEDO_FORMAT,
                    renderWidth, renderHeight);
            requireInput(specularAlbedo, "specular albedo", DlssRrTargets.ALBEDO_FORMAT,
                    renderWidth, renderHeight);
            requireInput(normalRoughness, "normal/roughness",
                    DlssRrTargets.NORMAL_ROUGHNESS_FORMAT, renderWidth, renderHeight);
            requireInput(inputColor, "input color", DlssRrTargets.COLOR_FORMAT,
                    renderWidth, renderHeight);
            requireOutput(outputColor);
            requireInput(linearDepth, "linear depth", DlssRrTargets.LINEAR_DEPTH_FORMAT,
                    renderWidth, renderHeight);
            requireInput(motionVectors, "motion vectors", DlssRrTargets.MOTION_FORMAT,
                    renderWidth, renderHeight);
            requireInput(specularMotionVectors, "specular motion vectors",
                    DlssRrTargets.SPECULAR_MOTION_FORMAT, renderWidth, renderHeight);
            requireInput(specularHitDistance, "specular hit distance",
                    DlssRrTargets.SPECULAR_HIT_DISTANCE_FORMAT, renderWidth, renderHeight);
            requireInput(responsivity, "responsivity", DlssRrTargets.RESPONSIVITY_FORMAT,
                    renderWidth, renderHeight);
        }

        private static void requireInput(
                VulkanImage image, String name, int format, int width, int height) {
            Objects.requireNonNull(image, name);
            if (image.format() != format || image.width() != width || image.height() != height) {
                throw new IllegalArgumentException("Invalid DLSS RR " + name + " image");
            }
        }

        private static void requireOutput(VulkanImage image) {
            Objects.requireNonNull(image, "outputColor");
            if (image.format() != DlssRrTargets.COLOR_FORMAT
                    || image.width() <= 0
                    || image.height() <= 0) {
                throw new IllegalArgumentException("Invalid DLSS RR output color image");
            }
        }
    }

    private record BridgeFunctions(
            long instanceExtensions,
            long deviceExtensions,
            long initialize,
            long optimalSettings,
            long createFeature,
            long evaluate,
            long releaseFeature,
            long shutdown) {}

    private record ExtractedRuntime(Path directory, NativeLibrary bridge) {}

    private static final class Holder {
        private static final DlssRrNative INSTANCE = new DlssRrNative();
    }
}
