// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import net.minecraft.core.SectionPos;

/** Spatial contract for one atomic 64-Section logical cluster. */
public final class SectionCluster {
    public static final int SECTION_SIZE = 4;
    public static final int SECTION_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    public static final int SNAPSHOT_HALO = 1;
    public static final int SNAPSHOT_SIZE = SECTION_SIZE + SNAPSHOT_HALO * 2;

    private SectionCluster() {
    }

    public static int origin(int sectionCoordinate) {
        return Math.floorDiv(sectionCoordinate, SECTION_SIZE) * SECTION_SIZE;
    }

    public static long keyForSection(int sectionX, int sectionY, int sectionZ) {
        return SectionPos.asLong(origin(sectionX), origin(sectionY), origin(sectionZ));
    }

    public static long keyForSection(long sectionKey) {
        return keyForSection(
                SectionPos.x(sectionKey),
                SectionPos.y(sectionKey),
                SectionPos.z(sectionKey));
    }

    public static boolean contains(long clusterKey, int sectionX, int sectionY, int sectionZ) {
        int originX = SectionPos.x(clusterKey);
        int originY = SectionPos.y(clusterKey);
        int originZ = SectionPos.z(clusterKey);
        return sectionX >= originX && sectionX < originX + SECTION_SIZE
                && sectionY >= originY && sectionY < originY + SECTION_SIZE
                && sectionZ >= originZ && sectionZ < originZ + SECTION_SIZE;
    }
}
