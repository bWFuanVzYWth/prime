// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Objects;

/** Immutable fixed-slot capture value for one logical 4x4x4 Section cluster. */
public final class CapturedCluster {
    private final int clusterX;
    private final int clusterY;
    private final int clusterZ;
    private final CapturedSectionGeometry[] sections;

    private CapturedCluster(
            int clusterX,
            int clusterY,
            int clusterZ,
            CapturedSectionGeometry[] sections) {
        this.clusterX = clusterX;
        this.clusterY = clusterY;
        this.clusterZ = clusterZ;
        this.sections = sections.clone();
    }

    int clusterX() {
        return this.clusterX;
    }

    int clusterY() {
        return this.clusterY;
    }

    int clusterZ() {
        return this.clusterZ;
    }

    CapturedSectionGeometry section(int localIndex) {
        return this.sections[localIndex];
    }

    public static final class Builder {
        private final int clusterX;
        private final int clusterY;
        private final int clusterZ;
        private final CapturedSectionGeometry[] sections =
                new CapturedSectionGeometry[SectionCluster.SECTION_COUNT];
        private boolean built;

        public Builder(int clusterX, int clusterY, int clusterZ) {
            if (SectionCluster.origin(clusterX) != clusterX
                    || SectionCluster.origin(clusterY) != clusterY
                    || SectionCluster.origin(clusterZ) != clusterZ) {
                throw new IllegalArgumentException(
                        "Captured cluster origin must be aligned to four Sections");
            }
            this.clusterX = clusterX;
            this.clusterY = clusterY;
            this.clusterZ = clusterZ;
        }

        public void add(
                int sectionX,
                int sectionY,
                int sectionZ,
                CapturedSectionGeometry section) {
            if (this.built) {
                throw new IllegalStateException("Captured cluster was already built");
            }
            long clusterKey = net.minecraft.core.SectionPos.asLong(
                    this.clusterX, this.clusterY, this.clusterZ);
            if (!SectionCluster.contains(clusterKey, sectionX, sectionY, sectionZ)) {
                throw new IllegalArgumentException(
                        "Captured Section does not belong to its cluster");
            }
            int localIndex = localIndex(
                    sectionX - this.clusterX,
                    sectionY - this.clusterY,
                    sectionZ - this.clusterZ);
            if (this.sections[localIndex] != null) {
                throw new IllegalArgumentException(
                        "Captured Section was added to its cluster more than once");
            }
            this.sections[localIndex] = Objects.requireNonNull(section, "section");
        }

        public CapturedCluster build() {
            if (this.built) {
                throw new IllegalStateException("Captured cluster was already built");
            }
            this.built = true;
            return new CapturedCluster(
                    this.clusterX, this.clusterY, this.clusterZ, this.sections);
        }
    }

    static int sectionX(int localIndex) {
        return localIndex % SectionCluster.SECTION_SIZE;
    }

    static int sectionY(int localIndex) {
        return localIndex / SectionCluster.SECTION_SIZE
                % SectionCluster.SECTION_SIZE;
    }

    static int sectionZ(int localIndex) {
        return localIndex
                / (SectionCluster.SECTION_SIZE * SectionCluster.SECTION_SIZE);
    }

    private static int localIndex(int x, int y, int z) {
        return x
                + y * SectionCluster.SECTION_SIZE
                + z * SectionCluster.SECTION_SIZE * SectionCluster.SECTION_SIZE;
    }
}
