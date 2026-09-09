// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DynamicBufferPoolTest {
    @Test
    void capacityUsesStableSixtyFourKiBClasses() {
        assertEquals(64L * 1024L, DynamicBufferPool.capacity(1L));
        assertEquals(64L * 1024L, DynamicBufferPool.capacity(64L * 1024L));
        assertEquals(128L * 1024L, DynamicBufferPool.capacity(64L * 1024L + 1L));
    }

    @Test
    void capacityRejectsEmptyAndOverflowingBuffers() {
        assertThrows(IllegalArgumentException.class, () -> DynamicBufferPool.capacity(0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> DynamicBufferPool.capacity(Long.MAX_VALUE));
    }
}
