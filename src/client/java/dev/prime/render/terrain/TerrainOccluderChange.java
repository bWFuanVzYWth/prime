// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/**
 * Immutable world-block bounds whose opaque visibility may have changed.
 *
 * <p>Maximum coordinates are exclusive. Consumers must project the complete box because a
 * replacement may either add a nearer occluder or reveal geometry behind the retired cluster.
 */
public record TerrainOccluderChange(
        int minimumX,
        int minimumY,
        int minimumZ,
        int maximumX,
        int maximumY,
        int maximumZ) {
    public TerrainOccluderChange {
        if (maximumX <= minimumX
                || maximumY <= minimumY
                || maximumZ <= minimumZ) {
            throw new IllegalArgumentException(
                    "Terrain occluder bounds must have positive extent");
        }
    }
}
