package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class VulkanMemorySnapshotTest {
    @Test
    void snapshotOwnsAnImmutableValidatedHeapList() {
        ArrayList<VulkanMemorySnapshot.Heap> source = new ArrayList<>(List.of(
                new VulkanMemorySnapshot.Heap(0, 4096L, 3072L, 8192L, 16384L)));
        VulkanMemorySnapshot snapshot = new VulkanMemorySnapshot(
                1, 3, 4096L, 3072L, source);
        source.clear();

        assertEquals(1, snapshot.heaps().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.heaps().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new VulkanMemorySnapshot(0, 1, 1L, 2L, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VulkanMemorySnapshot.Heap(-1, 0L, 0L, 0L, 0L));
    }
}
