package dev.primept;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;

final class NativePresentTest {
    private static int bootstrapResult;
    private static int bootstrapCalls;

    @Test
    void processPresentForwardsBorrowedHandlesAndMergedResultBeforeWorldAttach() throws Exception {
        var binding = StreamlineBootstrap.class.getDeclaredField("present");
        binding.setAccessible(true);
        Object previous = binding.get(null);
        try {
            binding.set(null, MethodHandles.lookup().findStatic(
                                      NativePresentTest.class, "bootstrapPresent",
                                      MethodType.methodType(int.class, long.class, long.class)));
            bootstrapCalls = 0;
            for (int result : new int[] {0, 1000001003, -1000001004, -4, -13}) {
                bootstrapResult = result;
                assertEquals(result, StreamlineBootstrap.present(17, 29));
            }
            assertEquals(5, bootstrapCalls);
        } finally {
            binding.set(null, previous);
        }
    }

    @Test
    void presentForwardsBorrowedHandlesAndActualResult() throws Exception {
        var binding = NativeBridge.class.getDeclaredField("vulkanPresent");
        binding.setAccessible(true);
        Object previous = binding.get(null);
        try {
            binding.set(null, null);
            assertFalse(NativeBridge.hasVulkanPresent());
            binding.set(null, MethodHandles.lookup().findStatic(
                                      NativePresentTest.class, "present",
                                      MethodType.methodType(int.class, long.class, long.class)));
            assertTrue(NativeBridge.hasVulkanPresent());
            assertEquals(-1000001004, NativeBridge.presentVulkan(17, 29));
        } finally {
            binding.set(null, previous);
        }
    }
    private static int present(long queue, long info) {
        assertEquals(17, queue);
        assertEquals(29, info);
        return -1000001004;
    }

    private static int bootstrapPresent(long queue, long info) {
        assertEquals(17, queue);
        assertEquals(29, info);
        bootstrapCalls++;
        return bootstrapResult;
    }
}
