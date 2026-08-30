package dev.prime.render.vulkan;

import java.util.List;

/** Point-in-time VMA allocation totals and per-heap budget estimates for measurement checkpoints. */
public record VulkanMemorySnapshot(
        int blockCount,
        int allocationCount,
        long blockBytes,
        long allocationBytes,
        List<Heap> heaps) {
    public VulkanMemorySnapshot {
        heaps = List.copyOf(heaps);
        if (blockCount < 0
                || allocationCount < 0
                || blockBytes < 0L
                || allocationBytes < 0L
                || allocationBytes > blockBytes) {
            throw new IllegalArgumentException("Invalid VMA memory statistics");
        }
    }

    public record Heap(
            int index,
            long allocatorBlockBytes,
            long allocatorAllocationBytes,
            long estimatedUsageBytes,
            long estimatedBudgetBytes) {
        public Heap {
            if (index < 0
                    || allocatorBlockBytes < 0L
                    || allocatorAllocationBytes < 0L
                    || allocatorAllocationBytes > allocatorBlockBytes
                    || estimatedUsageBytes < 0L
                    || estimatedBudgetBytes < 0L) {
                throw new IllegalArgumentException("Invalid VMA heap statistics");
            }
        }
    }
}
