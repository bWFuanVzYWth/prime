// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Explicit policy inputs for one captured-cluster translation. */
public record ClusterTranslationSettings(
        boolean buildOpacityMicromap,
        int segmentTriangleTarget,
        int maxOpacity2StateSubdivisionLevel,
        int maxOpacity4StateSubdivisionLevel,
        boolean voxelSurfacesEnabled,
        float voxelSurfaceMaximumHeight) {
    public ClusterTranslationSettings {
        if (segmentTriangleTarget < 2 || (segmentTriangleTarget & 1) != 0) {
            throw new IllegalArgumentException(
                    "Cluster segment capacity must contain whole quads");
        }
        if (maxOpacity2StateSubdivisionLevel < 0
                || maxOpacity4StateSubdivisionLevel < 0) {
            throw new IllegalArgumentException(
                    "Opacity-micromap subdivision level must be nonnegative");
        }
        if (!Float.isFinite(voxelSurfaceMaximumHeight)
                || voxelSurfaceMaximumHeight < 0.0F) {
            throw new IllegalArgumentException(
                    "Voxel-surface maximum height must be finite and nonnegative");
        }
    }

    public ClusterTranslationSettings(
            boolean buildOpacityMicromap,
            int segmentTriangleTarget,
            int maxOpacityMicromapSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight) {
        this(
                buildOpacityMicromap,
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                maxOpacityMicromapSubdivisionLevel,
                voxelSurfacesEnabled,
                voxelSurfaceMaximumHeight);
    }
}
