package dev.prime.render.vulkan.nrd;

import dev.prime.render.vulkan.NativeLibraries;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joml.Matrix4fc;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Stable Java binding for Prime's narrow NRD Core bridge.
 *
 * <p>The bundled native library contains NRD's API-independent scheduler and SPIR-V only. It never
 * receives a Vulkan handle: resource ownership, command recording and synchronization remain on
 * the Java side. The private ABI consists exclusively of fixed-width integers, pointers and byte
 * arrays and is versioned independently from NRD's C++ structures.
 */
public final class NrdNative {
    static final int ABI_VERSION = 11;
    static final int EXPECTED_NRD_VERSION = 4 << 24 | 17 << 16 | 4;
    static final int EXPECTED_NORMAL_ENCODING = 4;

    public static final int DESCRIPTOR_TEXTURE = 0;
    public static final int DESCRIPTOR_STORAGE_TEXTURE = 1;

    public static final int RESOURCE_IN_MV = 0;
    public static final int RESOURCE_IN_NORMAL_ROUGHNESS = 1;
    public static final int RESOURCE_IN_VIEWZ = 2;
    public static final int RESOURCE_IN_DIFF_RADIANCE_HITDIST = 6;
    public static final int RESOURCE_IN_SPEC_RADIANCE_HITDIST = 7;
    public static final int RESOURCE_IN_DIFF_SH0 = 11;
    public static final int RESOURCE_IN_DIFF_SH1 = 12;
    public static final int RESOURCE_IN_SPEC_SH0 = 13;
    public static final int RESOURCE_IN_SPEC_SH1 = 14;
    public static final int RESOURCE_IN_PENUMBRA = 15;
    public static final int RESOURCE_OUT_DIFF_RADIANCE_HITDIST = 18;
    public static final int RESOURCE_OUT_SPEC_RADIANCE_HITDIST = 19;
    public static final int RESOURCE_OUT_DIFF_SH0 = 20;
    public static final int RESOURCE_OUT_DIFF_SH1 = 21;
    public static final int RESOURCE_OUT_SPEC_SH0 = 22;
    public static final int RESOURCE_OUT_SPEC_SH1 = 23;
    public static final int RESOURCE_OUT_SHADOW_TRANSLUCENCY = 27;
    public static final int RESOURCE_OUT_VALIDATION = 29;
    public static final int RESOURCE_TRANSIENT_POOL = 30;
    public static final int RESOURCE_PERMANENT_POOL = 31;

    private static final int CREATE_DESCRIPTION_SIZE = 12;
    private static final int DESCRIPTION_SIZE = 136;
    private static final int PIPELINE_SIZE = 288;
    private static final int PIPELINE_RANGE_SIZE = 8;
    private static final int TEXTURE_INFO_SIZE = 8;
    private static final int DISPATCH_SIZE = 48;
    private static final int RESOURCE_SIZE = 16;
    private static final int DISPATCH_LIST_SIZE = 16;
    private static final String WINDOWS_RESOURCE = "/prime/natives/windows-x86_64/prime_nrd.dll";

    private final SharedLibrary library;
    private final long createFunction;
    private final long getDescriptionFunction;
    private final long setFrameSettingsFunction;
    private final long getDispatchesFunction;
    private final long destroyFunction;

    private NrdNative() {
        SharedLibrary loaded = loadLibrary();
        try {
            this.library = loaded;
            long getAbiVersionFunction = requireFunction(loaded, "primeNrdGetAbiVersion");
            this.createFunction = requireFunction(loaded, "primeNrdCreate");
            this.getDescriptionFunction = requireFunction(loaded, "primeNrdGetDescription");
            this.setFrameSettingsFunction = requireFunction(loaded, "primeNrdSetFrameSettings");
            this.getDispatchesFunction = requireFunction(loaded, "primeNrdGetDispatches");
            this.destroyFunction = requireFunction(loaded, "primeNrdDestroy");
            int abiVersion = JNI.invokeI(getAbiVersionFunction);
            if (abiVersion != ABI_VERSION) {
                throw new IllegalStateException(
                        "Prime NRD bridge ABI mismatch: expected "
                                + ABI_VERSION
                                + ", found "
                                + abiVersion);
            }
        } catch (RuntimeException | Error exception) {
            try {
                loaded.free();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public static Instance create(int width, int height) {
        if (width <= 0 || height <= 0 || width > 65_535 || height > 65_535) {
            throw new IllegalArgumentException("NRD dimensions must be in [1, 65535]");
        }
        try {
            return Holder.INSTANCE.createInstance(width, height);
        } catch (LinkageError error) {
            // Static native loading failures are Errors by default and would bypass Prime's
            // RuntimeException-based vanilla fallback. Normalize them at this private boundary.
            throw new IllegalStateException("Unable to load the bundled NRD native library", error);
        }
    }

    private Instance createInstance(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer createDesc = stack.calloc(CREATE_DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
            createDesc.putInt(0, width);
            createDesc.putInt(4, height);
            createDesc.putInt(8, 0);
            ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.memAddress(createDesc),
                            MemoryUtil.memAddress(output),
                            this.createFunction),
                    "create NRD instance");
            long handle = output.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("NRD returned a null instance");
            }
            try {
                Description description = this.readDescription(stack, handle);
                return new Instance(this, handle, description);
            } catch (RuntimeException exception) {
                JNI.invokePV(handle, this.destroyFunction);
                throw exception;
            }
        }
    }

    static boolean isSupportedPlatform() {
        return NativeLibraries.isWindowsX64();
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        return NativeLibraries.isWindowsX64(osName, architecture);
    }

    private Description readDescription(MemoryStack stack, long handle) {
        ByteBuffer output = stack.calloc(DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
        checkResult(
                JNI.invokePPI(handle, MemoryUtil.memAddress(output), this.getDescriptionFunction),
                "read NRD instance description");
        int abiVersion = output.getInt(0);
        int nrdVersion = output.getInt(4);
        int normalEncoding = output.getInt(100);
        if (abiVersion != ABI_VERSION
                || nrdVersion != EXPECTED_NRD_VERSION
                || normalEncoding != EXPECTED_NORMAL_ENCODING) {
            throw new IllegalStateException(
                    "Unsupported NRD native description: ABI "
                            + abiVersion
                            + ", NRD "
                            + formatVersion(nrdVersion)
                            + ", normal encoding "
                            + normalEncoding);
        }

        int samplersNum = output.getInt(48);
        int pipelinesNum = output.getInt(52);
        int permanentPoolSize = output.getInt(64);
        int transientPoolSize = output.getInt(68);
        List<Integer> samplers = readIntegers(output.getLong(40), samplersNum);
        List<Pipeline> pipelines = readPipelines(output.getLong(56), pipelinesNum);
        List<TextureInfo> permanentPool = readTexturePool(output.getLong(72), permanentPoolSize);
        List<TextureInfo> transientPool = readTexturePool(output.getLong(80), transientPoolSize);
        String entryPoint = readFixedUtf8(MemoryUtil.memAddress(output) + 104L, 32);
        return new Description(
                nrdVersion,
                output.getInt(8),
                output.getInt(12),
                output.getInt(16),
                output.getInt(20),
                output.getInt(24),
                output.getInt(28),
                output.getInt(32),
                output.getInt(36),
                output.getInt(88),
                output.getInt(92),
                output.getInt(96),
                normalEncoding,
                entryPoint,
                samplers,
                pipelines,
                permanentPool,
                transientPool);
    }

    private static List<Integer> readIntegers(long address, int count) {
        requireArray(address, count, Integer.BYTES, "NRD sampler array");
        ArrayList<Integer> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(MemoryUtil.memGetInt(address + (long) index * Integer.BYTES));
        }
        return List.copyOf(values);
    }

    private static List<Pipeline> readPipelines(long address, int count) {
        requireArray(address, count, PIPELINE_SIZE, "NRD pipeline array");
        ArrayList<Pipeline> pipelines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long pipelineAddress = address + (long) index * PIPELINE_SIZE;
            long spirvAddress = MemoryUtil.memGetLong(pipelineAddress);
            long spirvSize = MemoryUtil.memGetLong(pipelineAddress + 8L);
            if (spirvAddress == MemoryUtil.NULL || spirvSize <= 0L || spirvSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("NRD returned invalid SPIR-V bytecode");
            }
            byte[] spirv = new byte[(int) spirvSize];
            MemoryUtil.memByteBuffer(spirvAddress, spirv.length).get(spirv);
            spirv = NrdSpirv.expandThreeComponentStorageWrites(spirv);
            long rangesAddress = MemoryUtil.memGetLong(pipelineAddress + 16L);
            int rangesNum = MemoryUtil.memGetInt(pipelineAddress + 24L);
            requireArray(rangesAddress, rangesNum, PIPELINE_RANGE_SIZE, "NRD pipeline ranges");
            ArrayList<PipelineRange> ranges = new ArrayList<>(rangesNum);
            for (int rangeIndex = 0; rangeIndex < rangesNum; rangeIndex++) {
                long rangeAddress = rangesAddress + (long) rangeIndex * PIPELINE_RANGE_SIZE;
                ranges.add(new PipelineRange(
                        MemoryUtil.memGetInt(rangeAddress),
                        MemoryUtil.memGetInt(rangeAddress + 4L)));
            }
            pipelines.add(new Pipeline(
                    spirv,
                    List.copyOf(ranges),
                    MemoryUtil.memGetInt(pipelineAddress + 28L) != 0,
                    readFixedUtf8(pipelineAddress + 32L, 256)));
        }
        return List.copyOf(pipelines);
    }

    private static List<TextureInfo> readTexturePool(long address, int count) {
        requireArray(address, count, TEXTURE_INFO_SIZE, "NRD texture pool");
        ArrayList<TextureInfo> textures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long textureAddress = address + (long) index * TEXTURE_INFO_SIZE;
            textures.add(new TextureInfo(
                    MemoryUtil.memGetInt(textureAddress),
                    MemoryUtil.memGetInt(textureAddress + 4L)));
        }
        return List.copyOf(textures);
    }

    private static void requireArray(long address, int count, int stride, String name) {
        if (count < 0 || count > 65_536 || (count != 0 && address == MemoryUtil.NULL)) {
            throw new IllegalStateException(name + " is invalid");
        }
        Math.multiplyExact(count, stride);
    }

    private static String readFixedUtf8(long address, int maximumLength) {
        int length = 0;
        while (length < maximumLength && MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        return MemoryUtil.memUTF8(address, length);
    }

    private static SharedLibrary loadLibrary() {
        if (!isSupportedPlatform()) {
            throw new IllegalStateException("The bundled NRD native library currently supports Windows x86-64 only");
        }
        return NativeLibraries.loadBundled(
                "prime-nrd", WINDOWS_RESOURCE, "prime_nrd.dll", "NRD native library");
    }

    private static long requireFunction(SharedLibrary library, String name) {
        return NativeLibraries.requireFunction(library, name, "The NRD native library");
    }

    private static void checkResult(int result, String operation) {
        NativeLibraries.checkResult(result, operation);
    }

    private static String formatVersion(int packedVersion) {
        return ((packedVersion >>> 24) & 0xff)
                + "."
                + ((packedVersion >>> 16) & 0xff)
                + "."
                + (packedVersion & 0xffff);
    }

    public static final class Instance implements AutoCloseable {
        private final NrdNative nativeApi;
        private final Description description;
        private final DispatchList dispatches = new DispatchList();
        private long handle;

        private Instance(NrdNative nativeApi, long handle, Description description) {
            this.nativeApi = nativeApi;
            this.handle = handle;
            this.description = description;
        }

        public Description description() {
            return this.description;
        }

        public void setFrameSettings(FrameSettings settings) {
            long instance = this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer input = stack.calloc(
                                NrdFrameSettingsConstants.SIZE)
                        .order(ByteOrder.nativeOrder());
                NrdFrameSettingsConstants.write(input, settings);
                checkResult(
                        JNI.invokePPI(
                                instance,
                                MemoryUtil.memAddress(input),
                                this.nativeApi.setFrameSettingsFunction),
                        "set NRD frame settings");
            }
        }

        /**
         * Returns a reusable read-only view of NRD's current native dispatch storage.
         *
         * <p>The view remains valid until the next call to this method on the same instance. It is
         * deliberately not materialized as Java records and lists: this path runs once per frame
         * for every denoiser, while the native bridge already owns a complete immutable snapshot.
         */
        public DispatchList getDispatches() {
            long instance = this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer output = stack.calloc(DISPATCH_LIST_SIZE).order(ByteOrder.nativeOrder());
                checkResult(
                        JNI.invokePPI(
                                instance,
                                MemoryUtil.memAddress(output),
                                this.nativeApi.getDispatchesFunction),
                        "get NRD dispatches");
                this.dispatches.reset(output.getLong(0), output.getInt(8));
                return this.dispatches;
            }
        }

        @Override
        public void close() {
            long instance = this.handle;
            if (instance != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                JNI.invokePV(instance, this.nativeApi.destroyFunction);
            }
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("NRD instance is closed");
            }
            return this.handle;
        }

    }

    public record Description(
            int nrdVersion,
            int samplerOffset,
            int textureOffset,
            int constantBufferOffset,
            int storageTextureOffset,
            int constantBufferRegisterIndex,
            int samplersBaseRegisterIndex,
            int resourcesBaseRegisterIndex,
            int constantBufferMaxDataSize,
            int setsMaxNum,
            int constantBufferAndSamplersSpaceIndex,
            int resourcesSpaceIndex,
            int normalEncoding,
            String shaderEntryPoint,
            List<Integer> samplers,
            List<Pipeline> pipelines,
            List<TextureInfo> permanentPool,
            List<TextureInfo> transientPool) {}

    public record Pipeline(
            byte[] spirv,
            List<PipelineRange> ranges,
            boolean hasConstantData,
            String identifier) {}

    public record PipelineRange(int descriptorType, int descriptorsNum) {}

    public record TextureInfo(int format, int downsampleFactor) {}

    /** Allocation-free view over the native bridge's current dispatch snapshot. */
    public static final class DispatchList {
        private long address;
        private int size;

        private DispatchList() {}

        private void reset(long address, int size) {
            requireArray(address, size, DISPATCH_SIZE, "NRD dispatch array");
            this.address = address;
            this.size = size;
            for (int dispatchIndex = 0; dispatchIndex < size; dispatchIndex++) {
                long dispatch = this.dispatchAddress(dispatchIndex);
                int resourceCount = MemoryUtil.memGetInt(dispatch + 24L);
                requireArray(
                        MemoryUtil.memGetLong(dispatch + 8L),
                        resourceCount,
                        RESOURCE_SIZE,
                        "NRD dispatch resources");
                int constantSize = MemoryUtil.memGetInt(dispatch + 28L);
                long constantAddress = MemoryUtil.memGetLong(dispatch + 16L);
                if (constantSize < 0
                        || (constantSize != 0 && constantAddress == MemoryUtil.NULL)) {
                    throw new IllegalStateException("NRD returned invalid constant data");
                }
            }
        }

        public int size() {
            return this.size;
        }

        public boolean isEmpty() {
            return this.size == 0;
        }

        public int pipelineIndex(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 32L);
        }

        public int gridWidth(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 36L);
        }

        public int gridHeight(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 40L);
        }

        public int identifier(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 44L);
        }

        public int resourceCount(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 24L);
        }

        public int resourceDescriptorType(int dispatchIndex, int resourceIndex) {
            return MemoryUtil.memGetInt(this.resourceAddress(dispatchIndex, resourceIndex));
        }

        public int resourceType(int dispatchIndex, int resourceIndex) {
            return MemoryUtil.memGetInt(this.resourceAddress(dispatchIndex, resourceIndex) + 4L);
        }

        public int resourceIndexInPool(int dispatchIndex, int resourceIndex) {
            return MemoryUtil.memGetInt(this.resourceAddress(dispatchIndex, resourceIndex) + 8L);
        }

        public int constantDataSize(int dispatchIndex) {
            return MemoryUtil.memGetInt(this.dispatchAddress(dispatchIndex) + 28L);
        }

        long constantDataAddress(int dispatchIndex) {
            return MemoryUtil.memGetLong(this.dispatchAddress(dispatchIndex) + 16L);
        }

        private long dispatchAddress(int dispatchIndex) {
            if (dispatchIndex < 0 || dispatchIndex >= this.size) {
                throw new IndexOutOfBoundsException(dispatchIndex);
            }
            return this.address + (long) dispatchIndex * DISPATCH_SIZE;
        }

        private long resourceAddress(int dispatchIndex, int resourceIndex) {
            long dispatch = this.dispatchAddress(dispatchIndex);
            int resourceCount = MemoryUtil.memGetInt(dispatch + 24L);
            if (resourceIndex < 0 || resourceIndex >= resourceCount) {
                throw new IndexOutOfBoundsException(resourceIndex);
            }
            return MemoryUtil.memGetLong(dispatch + 8L) + (long) resourceIndex * RESOURCE_SIZE;
        }
    }

    public record FrameSettings(
            Matrix4fc viewToClip,
            Matrix4fc viewToClipPrevious,
            Matrix4fc worldToView,
            Matrix4fc worldToViewPrevious,
            float cameraJitterX,
            float cameraJitterY,
            float previousCameraJitterX,
            float previousCameraJitterY,
            int width,
            int height,
            int previousWidth,
            int previousHeight,
            int frameIndex,
            boolean restart,
            float timeDeltaMilliseconds,
            float denoisingRange,
            boolean enableValidation,
            float sunDirectionX,
            float sunDirectionY,
            float sunDirectionZ) {
        public FrameSettings {
            Objects.requireNonNull(viewToClip, "viewToClip");
            Objects.requireNonNull(
                    viewToClipPrevious, "viewToClipPrevious");
            Objects.requireNonNull(worldToView, "worldToView");
            Objects.requireNonNull(
                    worldToViewPrevious, "worldToViewPrevious");
            if (!viewToClip.isFinite()
                    || !viewToClipPrevious.isFinite()
                    || !worldToView.isFinite()
                    || !worldToViewPrevious.isFinite()) {
                throw new IllegalArgumentException(
                        "NRD frame matrices must be finite");
            }
            if (!validJitter(cameraJitterX)
                    || !validJitter(cameraJitterY)
                    || !validJitter(previousCameraJitterX)
                    || !validJitter(previousCameraJitterY)) {
                throw new IllegalArgumentException(
                        "NRD jitter must remain inside one source pixel");
            }
            if (width <= 0
                    || height <= 0
                    || previousWidth <= 0
                    || previousHeight <= 0
                    || width > 65_535
                    || height > 65_535
                    || previousWidth > 65_535
                    || previousHeight > 65_535
                    || frameIndex < 0) {
                throw new IllegalArgumentException(
                        "NRD extents or frame index are invalid");
            }
            if (!Float.isFinite(timeDeltaMilliseconds)
                    || timeDeltaMilliseconds < 0.0F
                    || timeDeltaMilliseconds
                            > dev.prime.render.FrameTime
                                    .MAXIMUM_DELTA_MILLISECONDS
                    || !Float.isFinite(denoisingRange)
                    || denoisingRange <= 0.0F) {
                throw new IllegalArgumentException(
                        "NRD time delta or denoising range is invalid");
            }
            float sunLengthSquared = sunDirectionX * sunDirectionX
                    + sunDirectionY * sunDirectionY
                    + sunDirectionZ * sunDirectionZ;
            if (!Float.isFinite(sunLengthSquared)
                    || Math.abs(sunLengthSquared - 1.0F) > 1.0e-4F) {
                throw new IllegalArgumentException(
                        "NRD sun direction must be finite and unit length");
            }
        }

        private static boolean validJitter(float value) {
            return Float.isFinite(value) && Math.abs(value) <= 0.5F;
        }
    }

    private static final class Holder {
        private static final NrdNative INSTANCE = new NrdNative();

        private Holder() {}
    }
}
