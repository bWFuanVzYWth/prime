// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.prime.render.terrain.SectionCluster;
import net.minecraft.core.SectionPos;

/** A bounded, coalescing cross-thread invalidation channel in terrain's atomic cluster unit. */
final class BoundedDirtyClusters {
    private final int maximumKeys;
    private final LongOpenHashSet keys = new LongOpenHashSet();
    private boolean fullInvalidation;

    BoundedDirtyClusters(int maximumKeys) {
        if (maximumKeys <= 0) {
            throw new IllegalArgumentException("Dirty-cluster capacity must be positive");
        }
        this.maximumKeys = maximumKeys;
    }

    synchronized void addCluster(long key) {
        if (this.fullInvalidation) {
            return;
        }
        this.keys.add(key);
        if (this.keys.size() > this.maximumKeys) {
            this.keys.clear();
            this.fullInvalidation = true;
        }
    }

    /**
     * Adds every atomic cluster touched by the block range after expanding it by one block on all
     * six faces. The expansion is required because a block model at a section boundary can depend
     * on its neighbour. Mapping before insertion prevents 64 Sections in one cluster from
     * consuming 64 queue slots or spuriously escalating to a full rebuild.
     */
    synchronized void addExpandedBlockRange(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        if (this.fullInvalidation) {
            return;
        }
        int minimumClusterX = SectionCluster.origin(
                expandedMinimumSection(minimumX, maximumX));
        int minimumClusterY = SectionCluster.origin(
                expandedMinimumSection(minimumY, maximumY));
        int minimumClusterZ = SectionCluster.origin(
                expandedMinimumSection(minimumZ, maximumZ));
        int maximumClusterX = SectionCluster.origin(
                expandedMaximumSection(minimumX, maximumX));
        int maximumClusterY = SectionCluster.origin(
                expandedMaximumSection(minimumY, maximumY));
        int maximumClusterZ = SectionCluster.origin(
                expandedMaximumSection(minimumZ, maximumZ));
        long clusterCountX = ((long) maximumClusterX - minimumClusterX)
                / SectionCluster.SECTION_SIZE + 1L;
        long clusterCountY = ((long) maximumClusterY - minimumClusterY)
                / SectionCluster.SECTION_SIZE + 1L;
        long clusterCountZ = ((long) maximumClusterZ - minimumClusterZ)
                / SectionCluster.SECTION_SIZE + 1L;
        if (clusterCountX > this.maximumKeys
                || clusterCountY > this.maximumKeys
                || clusterCountZ > this.maximumKeys
                || clusterCountX * clusterCountY > this.maximumKeys
                || clusterCountX * clusterCountY * clusterCountZ > this.maximumKeys) {
            this.invalidateAll();
            return;
        }
        for (int z = minimumClusterZ;
                z <= maximumClusterZ;
                z += SectionCluster.SECTION_SIZE) {
            for (int y = minimumClusterY;
                    y <= maximumClusterY;
                    y += SectionCluster.SECTION_SIZE) {
                for (int x = minimumClusterX;
                        x <= maximumClusterX;
                        x += SectionCluster.SECTION_SIZE) {
                    this.keys.add(SectionPos.asLong(x, y, z));
                    if (this.keys.size() > this.maximumKeys) {
                        this.invalidateAll();
                        return;
                    }
                }
            }
        }
    }

    synchronized void invalidateAll() {
        this.keys.clear();
        this.fullInvalidation = true;
    }

    synchronized Batch drain() {
        Batch result = new Batch(this.fullInvalidation, this.keys.toLongArray());
        this.keys.clear();
        this.fullInvalidation = false;
        return result;
    }

    synchronized void clear() {
        this.keys.clear();
        this.fullInvalidation = false;
    }

    private static int expandedMinimumSection(int firstBlock, int secondBlock) {
        return (int) Math.floorDiv((long) Math.min(firstBlock, secondBlock) - 1L, 16L);
    }

    private static int expandedMaximumSection(int firstBlock, int secondBlock) {
        return (int) Math.floorDiv((long) Math.max(firstBlock, secondBlock) + 1L, 16L);
    }

    record Batch(boolean fullInvalidation, long[] keys) {
    }
}
