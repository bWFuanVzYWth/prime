package dev.primept;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;

final class NativePresentTest {
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
}
