package dev.prime.render.vulkan;

import dev.prime.infrastructure.PrimeInfo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

/** Device-owned Vulkan pipeline cache with parallel private sessions and validated persistence. */
final class VulkanPipelineCache implements AutoCloseable {
    private static final int HEADER_SIZE = 32;
    private static final int MAXIMUM_BYTES = 64 * 1024 * 1024;

    private final VkDevice device;
    private final Path path;
    private final int vendorId;
    private final int deviceId;
    private final byte[] uuid;
    private final byte[] initialData;
    // Vulkan requires external synchronization for one cache handle. Pipeline compilation uses
    // private caches in parallel; this sole lock covers only their short merge and persistence.
    // It is never acquired while holding another Prime lock.
    private final Object mergeLock = new Object();
    private long handle;
    private boolean closed;

    private VulkanPipelineCache(VkDevice device, Path path, int vendorId, int deviceId, byte[] uuid,
        byte[] initialData, long handle) {
        this.device = device;
        this.path = path;
        this.vendorId = vendorId;
        this.deviceId = deviceId;
        this.uuid = uuid;
        this.initialData = initialData;
        this.handle = handle;
    }

    static VulkanPipelineCache create(VkDevice device, VkPhysicalDeviceProperties properties) {
        int vendorId = properties.vendorID();
        int deviceId = properties.deviceID();
        int driverVersion = properties.driverVersion();
        byte[] uuid = new byte[VK10.VK_UUID_SIZE];
        properties.pipelineCacheUUID().get(0, uuid);
        String name = "%08x-%08x-%08x-%s.bin".formatted(
            vendorId, deviceId, driverVersion, HexFormat.of().formatHex(uuid));
        Path path = FabricLoader.getInstance()
                        .getConfigDir()
                        .resolve("prime")
                        .resolve("pipeline_caches")
                        .resolve("vulkan")
                        .resolve(name);
        byte[] initialData = read(path, vendorId, deviceId, uuid);
        long handle = createHandle(device, initialData);
        if (handle == 0L && initialData != null) {
            PrimeInfo.LOGGER.warn(
                "Vulkan rejected persisted Prime pipeline cache {}; starting empty", path);
            handle = createHandle(device, null);
        }
        if (handle == 0L) {
            PrimeInfo.LOGGER.warn("Vulkan pipeline caching is unavailable for this session");
        }
        if (initialData != null) {
            PrimeInfo.LOGGER.info("Loaded {} KiB Prime Vulkan pipeline cache",
                Math.max(1, initialData.length / 1024));
        }
        return new VulkanPipelineCache(device, path, vendorId, deviceId, uuid, initialData, handle);
    }

    Session openSession() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan pipeline cache is closed");
        }
        if (this.handle == 0L) {
            return new Session(0L);
        }
        long sessionHandle = createHandle(this.device, this.initialData);
        if (sessionHandle == 0L) {
            sessionHandle = createHandle(this.device, null);
        }
        if (sessionHandle == 0L) {
            PrimeInfo.LOGGER.warn("Unable to create a temporary Vulkan pipeline cache");
        }
        return new Session(sessionHandle);
    }

    @Override
    public void close() {
        synchronized (this.mergeLock) {
            if (this.closed) {
                return;
            }
            if (this.handle != 0L) {
                try {
                    save();
                } catch (RuntimeException exception) {
                    PrimeInfo.LOGGER.warn(
                        "Unable to persist the Prime Vulkan pipeline cache", exception);
                }
                VK10.vkDestroyPipelineCache(this.device, this.handle, null);
                this.handle = 0L;
            }
            this.closed = true;
        }
    }

    private void save() {
        byte[] data;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            VulkanContext.check(VK10.vkGetPipelineCacheData(this.device, this.handle, size, null),
                "query Prime Vulkan pipeline cache size");
            long byteCount = size.get(0);
            if (byteCount < HEADER_SIZE || byteCount > MAXIMUM_BYTES) {
                PrimeInfo.LOGGER.warn(
                    "Ignoring unexpected Prime Vulkan pipeline cache size {}", byteCount);
                return;
            }
            ByteBuffer nativeData = MemoryUtil.memAlloc((int) byteCount);
            try {
                int result =
                    VK10.vkGetPipelineCacheData(this.device, this.handle, size, nativeData);
                if (result != VK10.VK_SUCCESS) {
                    throw new IllegalStateException(
                        "read Prime Vulkan pipeline cache failed with Vulkan result " + result);
                }
                int returnedBytes = Math.toIntExact(size.get(0));
                if (returnedBytes < HEADER_SIZE || returnedBytes > nativeData.capacity()) {
                    throw new IllegalStateException(
                        "Vulkan returned an invalid pipeline cache size " + returnedBytes);
                }
                data = new byte[returnedBytes];
                nativeData.get(0, data);
            } finally {
                MemoryUtil.memFree(nativeData);
            }
        }
        if (!matches(data, this.vendorId, this.deviceId, this.uuid)) {
            throw new IllegalStateException(
                "Vulkan returned a pipeline cache with an invalid header");
        }
        try {
            Files.createDirectories(this.path.getParent());
            Path temporary = Files.createTempFile(
                this.path.getParent(), this.path.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, data);
                try {
                    Files.move(temporary, this.path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, this.path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            PrimeInfo.LOGGER.info(
                "Saved {} KiB Prime Vulkan pipeline cache", Math.max(1, data.length / 1024));
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to persist Prime Vulkan pipeline cache " + this.path, exception);
        }
    }

    private static long createHandle(VkDevice device, byte[] initialData) {
        ByteBuffer nativeData =
            initialData == null ? null : MemoryUtil.memAlloc(initialData.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo createInfo =
                VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (nativeData != null) {
                nativeData.put(initialData).flip();
                createInfo.pInitialData(nativeData);
            }
            long[] pointer = new long[1];
            int result = VK10.vkCreatePipelineCache(device, createInfo, null, pointer);
            return result == VK10.VK_SUCCESS ? pointer[0] : 0L;
        } finally {
            if (nativeData != null) {
                MemoryUtil.memFree(nativeData);
            }
        }
    }

    private static byte[] read(Path path, int vendorId, int deviceId, byte[] uuid) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            long size = Files.size(path);
            if (size < HEADER_SIZE || size > MAXIMUM_BYTES) {
                PrimeInfo.LOGGER.warn("Ignoring invalid Prime Vulkan pipeline cache size {}", size);
                return null;
            }
            byte[] data = Files.readAllBytes(path);
            if (!matches(data, vendorId, deviceId, uuid)) {
                PrimeInfo.LOGGER.warn("Ignoring incompatible Prime Vulkan pipeline cache {}", path);
                return null;
            }
            return data;
        } catch (IOException exception) {
            PrimeInfo.LOGGER.warn("Unable to read Prime Vulkan pipeline cache {}", path, exception);
            return null;
        }
    }

    static boolean matches(byte[] data, int vendorId, int deviceId, byte[] uuid) {
        if (data.length < HEADER_SIZE || uuid.length != VK10.VK_UUID_SIZE) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        int headerSize = header.getInt(0);
        if (headerSize < HEADER_SIZE || headerSize > data.length
            || header.getInt(4) != VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE
            || header.getInt(8) != vendorId || header.getInt(12) != deviceId) {
            return false;
        }
        for (int index = 0; index < uuid.length; index++) {
            if (data[16 + index] != uuid[index]) {
                return false;
            }
        }
        return true;
    }

    final class Session implements AutoCloseable {
        private long sessionHandle;
        private boolean closed;

        private Session(long sessionHandle) {
            this.sessionHandle = sessionHandle;
        }

        long handle() {
            if (this.closed) {
                throw new IllegalStateException("Vulkan pipeline cache session is closed");
            }
            return this.sessionHandle;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            if (this.sessionHandle != 0L) {
                synchronized (VulkanPipelineCache.this.mergeLock) {
                    if (!VulkanPipelineCache.this.closed
                        && VulkanPipelineCache.this.handle != 0L) {
                    int result = VK10.vkMergePipelineCaches(VulkanPipelineCache.this.device,
                        VulkanPipelineCache.this.handle, new long[] {this.sessionHandle});
                    if (result != VK10.VK_SUCCESS) {
                            PrimeInfo.LOGGER.warn(
                                "Unable to merge Prime Vulkan pipeline cache: result {}", result);
                        }
                    }
                }
                VK10.vkDestroyPipelineCache(
                    VulkanPipelineCache.this.device, this.sessionHandle, null);
                this.sessionHandle = 0L;
            }
            this.closed = true;
        }
    }
}
