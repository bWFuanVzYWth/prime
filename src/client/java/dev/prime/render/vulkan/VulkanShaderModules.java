// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/** Creates Vulkan shader modules from packaged SPIR-V or native-provided bytes. */
public final class VulkanShaderModules {
    private static final String MANIFEST = "/prime/shaders/manifest.sha256";
    private static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;

    private VulkanShaderModules() {
    }

    public static long create(VulkanContext context, String resourceName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return create(context, stack, resourceName);
        }
    }

    public static long create(
            VulkanContext context, MemoryStack stack, String resourceName) {
        byte[] bytes = readResource(resourceName, Integer.MAX_VALUE);
        return create(context, stack, bytes, resourceName);
    }

    /** Identifies the exact packaged SPIR-V set without loading every module. */
    public static String fingerprint() {
        byte[] manifest = readResource(MANIFEST, MAXIMUM_MANIFEST_BYTES);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(manifest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static long create(
            VulkanContext context,
            MemoryStack stack,
            byte[] bytes,
            String label) {
        // Keep large SPIR-V payloads off LWJGL's fixed per-thread stack.
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(
                            context.vkDevice(),
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(code),
                            null,
                            pointer),
                    "create " + label);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static byte[] readResource(String resourceName, int maximumBytes) {
        try (InputStream input = VulkanShaderModules.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            byte[] bytes = maximumBytes == Integer.MAX_VALUE
                    ? input.readAllBytes()
                    : input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IllegalStateException("Shader resource is too large " + resourceName);
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read shader resource " + resourceName, exception);
        }
    }
}
