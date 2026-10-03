package dev.primept;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Preserve driver swapchain results when the pinned SDK overwrites its aggregate VkResult. */
public final class StreamlinePresent {
    private StreamlinePresent() {}
    public static int invoke(VkQueue queue, VkPresentInfoKHR info, boolean nativeBridge) {
        if (!StreamlineBootstrap.installed())
            return nativeBridge ? NativeBridge.presentVulkan(queue.address(), info.address())
                                : KHRSwapchain.vkQueuePresentKHR(queue, info);
        if (info.swapchainCount() != 1)
            throw new IllegalStateException("Streamline requires one actual host swapchain");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var result = stack.ints(VK10.VK_ERROR_UNKNOWN);
            var copy = VkPresentInfoKHR.calloc(stack).set(info).pResults(result);
            int sdk = nativeBridge ? NativeBridge.presentVulkan(queue.address(), copy.address())
                                   : KHRSwapchain.vkQueuePresentKHR(queue, copy);
            int driver = result.get(0);
            if (info.pResults() != null)
                info.pResults().put(0, driver);
            if (sdk < VK10.VK_SUCCESS)
                return sdk;
            if (driver == VK10.VK_ERROR_UNKNOWN)
                throw new IllegalStateException(
                        "Interposed Present did not publish the actual swapchain result");
            return driver == VK10.VK_SUCCESS ? sdk : driver;
        }
    }
}
