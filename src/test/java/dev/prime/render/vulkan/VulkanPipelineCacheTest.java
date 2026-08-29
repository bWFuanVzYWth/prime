package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

final class VulkanPipelineCacheTest {
    private static final int VENDOR = 0x10de;
    private static final int DEVICE = 0x2684;

    @Test
    void acceptsOnlyTheCurrentDeviceHeader() {
        byte[] uuid = uuid();
        byte[] cache = cache(uuid);

        assertTrue(VulkanPipelineCache.matches(cache, VENDOR, DEVICE, uuid));
        assertFalse(VulkanPipelineCache.matches(cache, VENDOR + 1, DEVICE, uuid));
        assertFalse(VulkanPipelineCache.matches(cache, VENDOR, DEVICE + 1, uuid));

        byte[] anotherUuid = uuid.clone();
        anotherUuid[anotherUuid.length - 1] ^= 1;
        assertFalse(VulkanPipelineCache.matches(cache, VENDOR, DEVICE, anotherUuid));
    }

    @Test
    void rejectsTruncatedOrImpossibleHeaders() {
        byte[] uuid = uuid();
        assertFalse(VulkanPipelineCache.matches(new byte[31], VENDOR, DEVICE, uuid));

        byte[] oversizedHeader = cache(uuid);
        ByteBuffer.wrap(oversizedHeader)
                .order(ByteOrder.nativeOrder())
                .putInt(0, oversizedHeader.length + 1);
        assertFalse(VulkanPipelineCache.matches(oversizedHeader, VENDOR, DEVICE, uuid));
    }

    @Test
    @Tag("artifact")
    void shaderManifestProvidesAStableSha256Identity() {
        assertTrue(VulkanShaderModules.fingerprint().matches("[0-9a-f]{64}"));
    }

    private static byte[] cache(byte[] uuid) {
        byte[] cache = new byte[32];
        ByteBuffer header = ByteBuffer.wrap(cache).order(ByteOrder.nativeOrder());
        header.putInt(0, 32);
        header.putInt(4, VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE);
        header.putInt(8, VENDOR);
        header.putInt(12, DEVICE);
        System.arraycopy(uuid, 0, cache, 16, uuid.length);
        return cache;
    }

    private static byte[] uuid() {
        byte[] uuid = new byte[VK10.VK_UUID_SIZE];
        for (int index = 0; index < uuid.length; index++) {
            uuid[index] = (byte) index;
        }
        return uuid;
    }
}
