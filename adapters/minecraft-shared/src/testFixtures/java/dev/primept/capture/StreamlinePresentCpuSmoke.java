package dev.primept.capture;

import dev.primept.NativeBridge;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import sun.misc.Unsafe;

/** Executes the transformed host present path with a fake synchronous native function and no GPU. */
public final class StreamlinePresentCpuSmoke {
    private static int calls;
    private StreamlinePresentCpuSmoke() {}

    public static void run(String className) throws Exception {
        Class<?> surfaceType = Class.forName(className);
        if (Arrays.stream(surfaceType.getDeclaredMethods())
                    .noneMatch(method -> method.getName().contains("primept$present")))
            throw new AssertionError("Streamline present redirect was not applied");
        var unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        var queue = (VkQueue)unsafe.allocateInstance(VkQueue.class);
        field(Pointer.Default.class, "address").setLong(queue, 17);
        Object surface = unsafe.allocateInstance(surfaceType);
        field(surfaceType, "presentQueue").set(surface, queue);
        field(surfaceType, "swapchain").setLong(surface, 23);
        field(surfaceType, "presentSemaphores").set(surface, new long[] {29});
        field(surfaceType, "currentImageIndex").setInt(surface, 0);
        Field binding = field(NativeBridge.class, "vulkanPresent");
        Object previous = binding.get(null);
        calls = 0;
        try {
            binding.set(null, MethodHandles.lookup().findStatic(
                                      StreamlinePresentCpuSmoke.class, "present",
                                      MethodType.methodType(int.class, long.class, long.class)));
            surfaceType.getMethod("present").invoke(surface);
            if (calls != 1 || !field(surfaceType, "swapchainSuboptimal").getBoolean(surface) ||
                field(surfaceType, "currentImageIndex").getInt(surface) != -1)
                throw new AssertionError("The host must consume the actual result of one present");
        } finally {
            binding.set(null, previous);
        }
        System.out.println(
                "PRIME_STREAMLINE_PRESENT_CPU_OK: actual transformed host present, borrowed descriptor, one native call, preserved suboptimal result; no window or GPU");
    }

    private static int present(long queue, long info) {
        var descriptor = VkPresentInfoKHR.create(info);
        if (queue != 17 || descriptor.swapchainCount() != 1 ||
            descriptor.pSwapchains().get(0) != 23 || descriptor.pImageIndices().get(0) != 0 ||
            descriptor.waitSemaphoreCount() != 1 || descriptor.pWaitSemaphores().get(0) != 29)
            throw new AssertionError("Present must borrow the unmodified host descriptor");
        calls++;
        return KHRSwapchain.VK_SUBOPTIMAL_KHR;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
