package dev.prime.render.vulkan.fsr;

import dev.prime.render.fsr.FsrDispatchPlan;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.natives.NativeLibraries;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Narrow Java binding for AMD's signed FidelityFX Vulkan DLL.
 *
 * <p>The DLL owns the FSR context, private resources, pipelines and internal synchronization. Prime
 * supplies the existing Vulkan device, command buffer and external images. Every external image is
 * declared as {@code UNORDERED_ACCESS}: Prime keeps these compute images in
 * {@code VK_IMAGE_LAYOUT_GENERAL}, and FidelityFX restores imported resources to the declared
 * initial state before returning from {@code ffxDispatch}. This state/layout agreement is part of
 * the native boundary and must not be changed independently on either side.
 */
final class FsrNative {
    static final String EXPECTED_UPSCALER_VERSION = FsrSettings.UPSCALER_VERSION;

    private static final String WINDOWS_RESOURCE =
            "/prime/natives/windows-x86_64/amd_fidelityfx_vk.dll";

    private static final long CREATE_CONTEXT_UPSCALE = 0x0001_0000L;
    private static final long CREATE_BACKEND_VK = 0x0000_0003L;
    private static final long DISPATCH_UPSCALE = 0x0001_0001L;
    private static final long QUERY_GET_VERSIONS = 0x0000_0004L;
    private static final long QUERY_PROVIDER_VERSION = 0x0000_0006L;

    private static final int CREATE_UPSCALE_SIZE = 48;
    private static final int CREATE_BACKEND_SIZE = 40;
    private static final int GET_VERSIONS_SIZE = 56;
    private static final int PROVIDER_VERSION_SIZE = 32;
    private static final int DISPATCH_SIZE = 432;
    private static final int RESOURCE_SIZE = 48;

    private static final int CREATE_FLAG_HDR = 1 << 0;
    private static final int CREATE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int CREATE_FLAG_DEPTH_INFINITE = 1 << 4;
    private static final int CREATE_FLAGS = CREATE_FLAG_HDR
            | CREATE_FLAG_DEPTH_INVERTED
            | CREATE_FLAG_DEPTH_INFINITE;

    private static final int RESOURCE_TYPE_TEXTURE_2D = 2;
    private static final int RESOURCE_USAGE_READ_ONLY = 0;
    private static final int RESOURCE_USAGE_UAV = 1 << 1;
    private static final int RESOURCE_STATE_UNORDERED_ACCESS = 1 << 1;

    private final SharedLibrary library;
    private final long createFunction;
    private final long destroyFunction;
    private final long dispatchFunction;
    private final long queryFunction;

    private FsrNative() {
        SharedLibrary loaded = NativeLibraries.NATIVE_FFXFSR.getOrCreateLibrary();
        try {
            this.library = loaded;
            this.createFunction = requireFunction(loaded, "ffxCreateContext");
            this.destroyFunction = requireFunction(loaded, "ffxDestroyContext");
            this.dispatchFunction = requireFunction(loaded, "ffxDispatch");
            this.queryFunction = requireFunction(loaded, "ffxQuery");
            String availableVersion = this.queryAvailableVersion();
            if (!EXPECTED_UPSCALER_VERSION.equals(availableVersion)) {
                throw new IllegalStateException(
                        "Bundled FidelityFX upscaler version is "
                                + availableVersion
                                + "; expected "
                                + EXPECTED_UPSCALER_VERSION);
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

    static Instance create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        try {
            return Holder.INSTANCE.createInstance(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
        } catch (LinkageError error) {
            throw new IllegalStateException(
                    "Unable to load the bundled FidelityFX Vulkan library", error);
        }
    }

    static void verifyLibrary() {
        Holder.INSTANCE.getClass();
    }

    private Instance createInstance(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("FSR native extents must be positive");
        }
        ByteBuffer creationStorage = MemoryUtil.memCalloc(
                        CREATE_UPSCALE_SIZE + CREATE_BACKEND_SIZE)
                .order(ByteOrder.nativeOrder());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer create = creationStorage
                    .slice(0, CREATE_UPSCALE_SIZE)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer backend = creationStorage
                    .slice(CREATE_UPSCALE_SIZE, CREATE_BACKEND_SIZE)
                    .order(ByteOrder.nativeOrder());
            putHeader(backend, CREATE_BACKEND_VK, MemoryUtil.NULL);
            backend.putLong(16, context.vkDevice().address());
            backend.putLong(24, context.vkDevice().getPhysicalDevice().address());
            long getDeviceProcAddress = VK.getFunctionProvider()
                    .getFunctionAddress("vkGetDeviceProcAddr");
            if (getDeviceProcAddress == MemoryUtil.NULL) {
                throw new IllegalStateException("Vulkan loader does not expose vkGetDeviceProcAddr");
            }
            backend.putLong(32, getDeviceProcAddress);

            putHeader(create, CREATE_CONTEXT_UPSCALE, MemoryUtil.memAddress(backend));
            create.putInt(16, CREATE_FLAGS);
            putExtent(create, 20, renderWidth, renderHeight);
            putExtent(create, 28, displayWidth, displayHeight);
            create.putLong(40, MemoryUtil.NULL);

            ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            checkResult(
                    JNI.invokePPPI(
                            MemoryUtil.memAddress(contextPointer),
                            MemoryUtil.memAddress(create),
                            MemoryUtil.NULL,
                            this.createFunction),
                    "create FidelityFX upscaler context");
            long handle = contextPointer.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX returned a null upscaler context");
            }
            try {
                String version = this.queryVersion(stack, handle);
                if (!EXPECTED_UPSCALER_VERSION.equals(version)) {
                    throw new IllegalStateException(
                            "Unsupported FidelityFX upscaler version "
                                    + version
                                    + "; expected "
                                    + EXPECTED_UPSCALER_VERSION);
                }
                return new Instance(this, handle, version, creationStorage);
            } catch (RuntimeException exception) {
                try {
                    this.destroy(stack, handle);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } catch (RuntimeException exception) {
            MemoryUtil.memFree(creationStorage);
            throw exception;
        }
    }

    private String queryVersion(MemoryStack stack, long handle) {
        ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
        contextPointer.putLong(0, handle);
        ByteBuffer query = stack.calloc(PROVIDER_VERSION_SIZE).order(ByteOrder.nativeOrder());
        putHeader(query, QUERY_PROVIDER_VERSION, MemoryUtil.NULL);
        checkResult(
                JNI.invokePPI(
                        MemoryUtil.memAddress(contextPointer),
                        MemoryUtil.memAddress(query),
                        this.queryFunction),
                "query FidelityFX upscaler version");
        long name = query.getLong(24);
        if (name == MemoryUtil.NULL) {
            throw new IllegalStateException("FidelityFX returned a null provider version name");
        }
        return MemoryUtil.memUTF8(name);
    }

    private String queryAvailableVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer count = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            ByteBuffer versionId = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            ByteBuffer versionName = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            count.putLong(0, 1L);
            ByteBuffer query = stack.calloc(GET_VERSIONS_SIZE).order(ByteOrder.nativeOrder());
            putHeader(query, QUERY_GET_VERSIONS, MemoryUtil.NULL);
            query.putLong(16, CREATE_CONTEXT_UPSCALE);
            query.putLong(24, MemoryUtil.NULL);
            query.putLong(32, MemoryUtil.memAddress(count));
            query.putLong(40, MemoryUtil.memAddress(versionId));
            query.putLong(48, MemoryUtil.memAddress(versionName));
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.NULL,
                            MemoryUtil.memAddress(query),
                            this.queryFunction),
                    "query available FidelityFX upscaler version");
            if (count.getLong(0) != 1L || versionId.getLong(0) == 0L) {
                throw new IllegalStateException("FidelityFX DLL does not expose exactly one upscaler provider");
            }
            long name = versionName.getLong(0);
            if (name == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX returned a null available-version name");
            }
            return MemoryUtil.memUTF8(name);
        }
    }

    private void dispatch(
            long handle,
            VkCommandBuffer commandBuffer,
            Dispatch dispatch) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer description = stack.calloc(DISPATCH_SIZE).order(ByteOrder.nativeOrder());
            putHeader(description, DISPATCH_UPSCALE, MemoryUtil.NULL);
            description.putLong(16, commandBuffer.address());
            putResource(description, 24, dispatch.color(), false);
            putResource(description, 72, dispatch.depth(), false);
            putResource(description, 120, dispatch.motion(), false);
            // A null exposure selects FidelityFX's internal 1.0 texture. Prime's working-space and
            // display contracts both use exposure 1.0, so no extra external image is required.
            putNullResource(description, 168);
            putResource(description, 216, dispatch.reactive(), false);
            putResource(description, 264, dispatch.transparencyComposition(), false);
            putResource(description, 312, dispatch.output(), true);

            FsrDispatchConstants.write(description, dispatch.plan());

            ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            contextPointer.putLong(0, handle);
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.memAddress(contextPointer),
                            MemoryUtil.memAddress(description),
                            this.dispatchFunction),
                    "dispatch FidelityFX upscaler");
        }
    }

    private void destroy(MemoryStack stack, long handle) {
        ByteBuffer contextPointer = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
        contextPointer.putLong(0, handle);
        checkResult(
                JNI.invokePPI(
                        MemoryUtil.memAddress(contextPointer),
                        MemoryUtil.NULL,
                        this.destroyFunction),
                "destroy FidelityFX upscaler context");
    }

    private static void putHeader(ByteBuffer buffer, long type, long next) {
        buffer.putLong(0, type);
        buffer.putLong(8, next);
    }

    private static void putExtent(ByteBuffer buffer, int offset, int width, int height) {
        buffer.putInt(offset, width);
        buffer.putInt(offset + Integer.BYTES, height);
    }

    private static void putNullResource(ByteBuffer buffer, int offset) {
        for (int byteOffset = 0; byteOffset < RESOURCE_SIZE; byteOffset += Long.BYTES) {
            buffer.putLong(offset + byteOffset, 0L);
        }
    }

    private static void putResource(
            ByteBuffer buffer, int offset, VulkanImage image, boolean writable) {
        buffer.putLong(offset, image.image());
        buffer.putInt(offset + 8, RESOURCE_TYPE_TEXTURE_2D);
        buffer.putInt(offset + 12, surfaceFormat(image.format()));
        buffer.putInt(offset + 16, image.width());
        buffer.putInt(offset + 20, image.height());
        buffer.putInt(offset + 24, 1);
        buffer.putInt(offset + 28, image.mipLevels());
        buffer.putInt(offset + 32, 0);
        buffer.putInt(offset + 36, writable ? RESOURCE_USAGE_UAV : RESOURCE_USAGE_READ_ONLY);
        buffer.putInt(offset + 40, RESOURCE_STATE_UNORDERED_ACCESS);
    }

    private static int surfaceFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK12.VK_FORMAT_R32G32B32A32_SFLOAT -> 3;
            case VK12.VK_FORMAT_R16G16B16A16_SFLOAT -> 4;
            case VK12.VK_FORMAT_R32G32_SFLOAT -> 6;
            case VK12.VK_FORMAT_R8G8B8A8_UNORM -> 10;
            case VK12.VK_FORMAT_R16G16_SFLOAT -> 18;
            case VK12.VK_FORMAT_R8_UNORM -> 25;
            case VK12.VK_FORMAT_R32_SFLOAT -> 28;
            default -> throw new IllegalArgumentException(
                    "Unsupported FidelityFX Vulkan image format " + vkFormat);
        };
    }

    static boolean isSupportedPlatform() {
        return NativeLibraries.isWindowsX64();
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        return NativeLibraries.isWindowsX64(osName, architecture);
    }

    private static long requireFunction(SharedLibrary library, String name) {
        return NativeLibraries.requireFunction(
                library, name, "The FidelityFX native library");
    }

    private static void checkResult(int result, String operation) {
        NativeLibraries.checkResult(result, operation);
    }

    record Dispatch(
            VulkanImage color,
            VulkanImage depth,
            VulkanImage motion,
            VulkanImage reactive,
            VulkanImage transparencyComposition,
            VulkanImage output,
            FsrDispatchPlan plan) {
    }

    static final class Instance implements AutoCloseable {
        private final FsrNative api;
        private final String version;
        private final ByteBuffer creationStorage;
        private long handle;

        private Instance(
                FsrNative api, long handle, String version, ByteBuffer creationStorage) {
            this.api = api;
            this.handle = handle;
            this.version = version;
            this.creationStorage = creationStorage;
        }

        String version() {
            return this.version;
        }

        void dispatch(VkCommandBuffer commandBuffer, Dispatch dispatch) {
            this.api.dispatch(this.requireOpen(), commandBuffer, dispatch);
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("FidelityFX upscaler context has been destroyed");
            }
            return this.handle;
        }

        @Override
        public void close() {
            long instance = this.handle;
            if (instance == MemoryUtil.NULL) {
                return;
            }
            this.handle = MemoryUtil.NULL;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                try {
                    this.api.destroy(stack, instance);
                } finally {
                    MemoryUtil.memFree(this.creationStorage);
                }
            }
        }
    }

    private static final class Holder {
        private static final FsrNative INSTANCE = new FsrNative();
    }
}
