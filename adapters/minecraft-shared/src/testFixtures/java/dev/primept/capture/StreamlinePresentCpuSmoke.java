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
import org.lwjgl.system.MemoryUtil;
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
    private static long expectedDescriptor, expectedResults;
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
        var processPresent = field(StreamlineBootstrap.class, "present");
        Object previousPresent = processPresent.get(null);
        boolean previousActive = active.getBoolean(null);
        boolean previousPrepared = prepared.getBoolean(null);
        actions.clear();
        try {
            installed.setBoolean(null, true);
            processPresent.set(null,
                               MethodHandles.lookup().findStatic(
                                       StreamlinePresentCpuSmoke.class, "present",
                                       MethodType.methodType(int.class, long.class, long.class)));
            // The title screen has no world NativeBridge and has never prepared FG inputs.
            binding.set(null, null);
            active.setBoolean(null, false);
            prepared.setBoolean(null, false);
            publishResult = false;
            sdkResult = VK10.VK_SUCCESS;
            field(surfaceType, "currentImageIndex").setInt(surface, 0);
            field(surfaceType, "swapchainSuboptimal").setBoolean(surface, false);
            int beforeTitle = calls;
            surfaceType.getMethod("present").invoke(surface);
            if (calls != beforeTitle + 1 ||
                field(surfaceType, "swapchainSuboptimal").getBoolean(surface) ||
                field(surfaceType, "currentImageIndex").getInt(surface) != -1)
                throw new AssertionError(
                        "Installed inactive title Present must use the process bridge exactly once without pResults");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkPresentInfoKHR.calloc(stack)
                                   .sType$Default()
                                   .pWaitSemaphores(stack.longs(29))
                                   .pSwapchains(stack.longs(23))
                                   .pImageIndices(stack.ints(0))
                                   .swapchainCount(1);
                expectedDescriptor = info.address();
                for (int result : new int[] {VK10.VK_SUCCESS, KHRSwapchain.VK_SUBOPTIMAL_KHR,
                                             KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR,
                                             VK10.VK_ERROR_DEVICE_LOST, VK10.VK_ERROR_UNKNOWN}) {
                    // Native owns SDK/driver/callback priority. Java must preserve its merged code.
                    sdkResult = result;
                    for (boolean rendererBridge : new boolean[] {false, true}) {
                        int before = calls;
                        if (StreamlinePresent.invoke(queue, info, rendererBridge) != result ||
                            calls != before + 1 || info.pResults() != null)
                            throw new AssertionError(
                                    "Installed Present must forward the original descriptor and merged result once");
                    }
                }
                var results = stack.ints(VK10.VK_ERROR_UNKNOWN);
                info.pResults(results);
                expectedResults = MemoryUtil.memAddress(results);
                sdkResult = VK10.VK_SUCCESS;
                int before = calls;
                if (StreamlinePresent.invoke(queue, info, false) != VK10.VK_SUCCESS ||
                    calls != before + 1 || results.get(0) != VK10.VK_ERROR_UNKNOWN ||
                    MemoryUtil.memAddress(info.pResults()) != expectedResults)
                    throw new AssertionError(
                            "An asynchronous SDK Present may leave original pResults unwritten");
                publishResult = true;
                for (int result :
                     new int[] {VK10.VK_SUCCESS, KHRSwapchain.VK_SUBOPTIMAL_KHR,
                                KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR, VK10.VK_ERROR_DEVICE_LOST}) {
                    sdkResult = driverResult = result;
                    before = calls;
                    if (StreamlinePresent.invoke(queue, info, false) != result ||
                        calls != before + 1 || results.get(0) != result ||
                        MemoryUtil.memAddress(info.pResults()) != expectedResults)
                        throw new AssertionError(
                                "Original pResults must remain borrowed unchanged");
                }
            }
            expectedDescriptor = expectedResults = 0;
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
            processPresent.set(null, previousPresent);
            binding.set(null, previous);
            active.setBoolean(null, previousActive);
            prepared.setBoolean(null, previousPrepared);
            expectedDescriptor = expectedResults = 0;
            publishResult = true;
        }
        System.out.println(
                "PRIME_STREAMLINE_PRESENT_CPU_OK: actual transformed inactive title Present without world bridge, original descriptor and pResults, one process call, unwritten async results and merged errors; no window or GPU");
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
        if (expectedDescriptor != 0 && info != expectedDescriptor)
            throw new AssertionError("Present must use the original descriptor address");
        long resultsAddress =
                descriptor.pResults() == null ? 0 : MemoryUtil.memAddress(descriptor.pResults());
        if (resultsAddress != expectedResults)
            throw new AssertionError(
                    "Present must not replace pResults with a short-lived scratch");
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
