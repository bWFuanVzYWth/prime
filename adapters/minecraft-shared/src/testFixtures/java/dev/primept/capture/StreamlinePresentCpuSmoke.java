package dev.primept.capture;

import dev.primept.NativeBridge;
import dev.primept.StreamlineBootstrap;
import dev.primept.StreamlineFrames;
import dev.primept.StreamlinePresent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import sun.misc.Unsafe;

/** Executes the transformed host present path with a fake synchronous native function and no GPU. */
public final class StreamlinePresentCpuSmoke {
    private static int calls;
    private static final List<Integer> actions = new ArrayList<>();
    private static int sdkResult = KHRSwapchain.VK_SUBOPTIMAL_KHR;
    private static int driverResult = KHRSwapchain.VK_SUBOPTIMAL_KHR;
    private static boolean publishResult = true;
    private StreamlinePresentCpuSmoke() {}

    public static void run(String className) throws Exception {
        Class<?> surfaceType = Class.forName(className);
        String prefix = className.substring(0, className.lastIndexOf('.') + 1);
        for (String type :
             List.of(prefix + "VulkanInstance", prefix + "VulkanDevice",
                     prefix + "VulkanCommandEncoder", "net.minecraft.client.Minecraft")) {
            Class<?> transformed = Class.forName(type, false, surfaceType.getClassLoader());
            if (Arrays.stream(transformed.getDeclaredMethods())
                        .noneMatch(method -> method.getName().contains("primept$")))
                throw new AssertionError("Presentation lifecycle mixin missing: " + type);
        }
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
        var installed = field(StreamlineBootstrap.class, "installed");
        var frame = field(StreamlineBootstrap.class, "frame");
        var active = field(StreamlineFrames.class, "active");
        var prepared = field(StreamlineFrames.class, "prepared");
        boolean previousInstalled = installed.getBoolean(null);
        Object previousFrame = frame.get(null);
        actions.clear();
        try {
            installed.setBoolean(null, true);
            binding.set(null, MethodHandles.lookup().findStatic(
                                      StreamlinePresentCpuSmoke.class, "present",
                                      MethodType.methodType(int.class, long.class, long.class)));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkPresentInfoKHR.calloc(stack)
                                   .sType$Default()
                                   .pWaitSemaphores(stack.longs(29))
                                   .pSwapchains(stack.longs(23))
                                   .pImageIndices(stack.ints(0))
                                   .swapchainCount(1);
                sdkResult = VK10.VK_SUCCESS;
                for (int result :
                     new int[] {VK10.VK_SUCCESS, KHRSwapchain.VK_SUBOPTIMAL_KHR,
                                KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR, VK10.VK_ERROR_DEVICE_LOST}) {
                    driverResult = result;
                    if (StreamlinePresent.invoke(queue, info, true) != result ||
                        info.pResults() != null)
                        throw new AssertionError(
                                "Actual driver result must survive SDK success without changing the descriptor");
                }
                sdkResult = VK10.VK_ERROR_DEVICE_LOST;
                driverResult = KHRSwapchain.VK_SUBOPTIMAL_KHR;
                if (StreamlinePresent.invoke(queue, info, true) != VK10.VK_ERROR_DEVICE_LOST)
                    throw new AssertionError("SDK failure must take precedence");
                sdkResult = VK10.VK_SUCCESS;
                publishResult = false;
                try {
                    StreamlinePresent.invoke(queue, info, true);
                    throw new AssertionError("Missing actual Present result must fail closed");
                } catch (IllegalStateException expected) {}
                publishResult = true;
            }
            frame.set(null, MethodHandles.lookup().findStatic(
                                    StreamlinePresentCpuSmoke.class, "frame",
                                    MethodType.methodType(int.class, int.class, int.class)));
            active.setBoolean(null, true);
            prepared.setBoolean(null, false);
            StreamlineFrames.beforePresent();
            if (!actions.equals(List.of(3)) || StreamlineFrames.active())
                throw new AssertionError(
                        "Missing HUD-less frame must suspend old FG before actual Present");
            actions.clear();
            active.setBoolean(null, true);
            prepared.setBoolean(null, true);
            StreamlineFrames.beforePresent();
            if (!actions.isEmpty() || !StreamlineFrames.active())
                throw new AssertionError("Prepared FG must retain the actual Present boundary");
            StreamlineFrames.shutdown();
            if (!actions.equals(List.of(3, 4)))
                throw new AssertionError(
                        "Host shutdown must suspend consumers before SDK shutdown");
        } finally {
            installed.setBoolean(null, previousInstalled);
            frame.set(null, previousFrame);
            binding.set(null, previous);
            active.setBoolean(null, false);
            prepared.setBoolean(null, false);
        }
        System.out.println(
                "PRIME_STREAMLINE_PRESENT_CPU_OK: actual transformed host present, borrowed descriptor, one native call, preserved suboptimal result; no window or GPU");
    }

    private static int frame(int action, int enabled) {
        if (enabled != 0)
            throw new AssertionError("Retirement must disable frame generation");
        actions.add(action);
        return 0;
    }

    private static int present(long queue, long info) {
        var descriptor = VkPresentInfoKHR.create(info);
        if (queue != 17 || descriptor.swapchainCount() != 1 ||
            descriptor.pSwapchains().get(0) != 23 || descriptor.pImageIndices().get(0) != 0 ||
            descriptor.waitSemaphoreCount() != 1 || descriptor.pWaitSemaphores().get(0) != 29)
            throw new AssertionError("Present must borrow the unmodified host descriptor");
        calls++;
        if (descriptor.pResults() != null && publishResult)
            descriptor.pResults().put(0, driverResult);
        return sdkResult;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
