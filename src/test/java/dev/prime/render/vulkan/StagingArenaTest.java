// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class StagingArenaTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void indivisibleUploadsRoundToWholePages() {
        assertEquals(64L * MIB, StagingArena.PAGE_SIZE);
        assertEquals(StagingArena.PAGE_SIZE, StagingArena.allocationCapacity(0L));
        assertEquals(StagingArena.PAGE_SIZE, StagingArena.allocationCapacity(StagingArena.PAGE_SIZE));
        assertEquals(
                StagingArena.PAGE_SIZE * 2L,
                StagingArena.allocationCapacity(StagingArena.PAGE_SIZE + 1L));
        assertEquals(70L << 30, StagingArena.allocationCapacity(70L << 30));
        assertThrows(
                IllegalArgumentException.class,
                () -> StagingArena.allocationCapacity(Long.MAX_VALUE));
    }

    @Test
    void alignmentRejectsInvalidAndOverflowingDomains() {
        assertEquals(16L, VulkanContext.alignUp(9L, 8L));
        assertThrows(IllegalArgumentException.class, () -> VulkanContext.alignUp(-1L, 8L));
        assertThrows(
                IllegalArgumentException.class,
                () -> VulkanContext.alignUp(Long.MAX_VALUE, 8L));
        assertThrows(IllegalArgumentException.class, () -> VulkanContext.alignUp(1L, 3L));
    }
}
