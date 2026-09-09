// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;
import net.minecraft.core.SectionPos;

/** Pure spatial policy for the near-camera texture-voxel window. */
public final class VoxelSurfaceCoverage {
    private VoxelSurfaceCoverage() {}

    /** Enumerates the bounded symmetric difference without scanning the resident terrain. */
    public static long[] changedKeys(
            int oldCenterSectionX,
            int oldCenterSectionY,
            int oldCenterSectionZ,
            int newCenterSectionX,
            int newCenterSectionY,
            int newCenterSectionZ) {
        int oldX = SectionCluster.origin(oldCenterSectionX);
        int oldY = SectionCluster.origin(oldCenterSectionY);
        int oldZ = SectionCluster.origin(oldCenterSectionZ);
        int newX = SectionCluster.origin(newCenterSectionX);
        int newY = SectionCluster.origin(newCenterSectionY);
        int newZ = SectionCluster.origin(newCenterSectionZ);
        if (oldX == newX && oldY == newY && oldZ == newZ) {
            return new long[0];
        }
        long[] changed = new long[54];
        int count = appendExclusiveWindow(changed, 0, oldX, oldY, oldZ, newX, newY, newZ);
        count = appendExclusiveWindow(changed, count, newX, newY, newZ, oldX, oldY, oldZ);
        return Arrays.copyOf(changed, count);
    }

    public static boolean includes(
            int clusterX,
            int clusterY,
            int clusterZ,
            int centerSectionX,
            int centerSectionY,
            int centerSectionZ) {
        int centerClusterX = SectionCluster.origin(centerSectionX);
        int centerClusterY = SectionCluster.origin(centerSectionY);
        int centerClusterZ = SectionCluster.origin(centerSectionZ);
        return Math.abs(clusterX - centerClusterX) <= SectionCluster.SECTION_SIZE
                && Math.abs(clusterY - centerClusterY) <= SectionCluster.SECTION_SIZE
                && Math.abs(clusterZ - centerClusterZ) <= SectionCluster.SECTION_SIZE;
    }

    private static int appendExclusiveWindow(
            long[] destination,
            int count,
            int centerX,
            int centerY,
            int centerZ,
            int excludedCenterX,
            int excludedCenterY,
            int excludedCenterZ) {
        int radius = SectionCluster.SECTION_SIZE;
        for (int z = centerZ - radius; z <= centerZ + radius; z += radius) {
            for (int y = centerY - radius; y <= centerY + radius; y += radius) {
                for (int x = centerX - radius; x <= centerX + radius; x += radius) {
                    if (Math.abs(x - excludedCenterX) <= radius
                            && Math.abs(y - excludedCenterY) <= radius
                            && Math.abs(z - excludedCenterZ) <= radius) {
                        continue;
                    }
                    destination[count++] = SectionPos.asLong(x, y, z);
                }
            }
        }
        return count;
    }
}
