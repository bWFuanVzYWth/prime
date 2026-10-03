package dev.primept;

import org.lwjgl.vulkan.*;

/** The native process owner handles synchronous and asynchronous SDK Present results. */
public final class StreamlinePresent {
    private StreamlinePresent() {}
    public static int invoke(VkQueue queue, VkPresentInfoKHR info, boolean nativeBridge) {
        if (StreamlineBootstrap.installed())
            return StreamlineBootstrap.present(queue.address(), info.address());
        return nativeBridge ? NativeBridge.presentVulkan(queue.address(), info.address())
                            : KHRSwapchain.vkQueuePresentKHR(queue, info);
    }
}
