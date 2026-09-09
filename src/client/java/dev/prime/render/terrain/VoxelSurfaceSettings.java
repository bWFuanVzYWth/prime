// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** User-facing scale for texture-derived outward relief. */
public final class VoxelSurfaceSettings {
    public static final int STEPS_PER_UNIT = 100;
    public static final int MINIMUM_STEPS = 0;
    public static final int MAXIMUM_STEPS = 200;
    public static final int DEFAULT_STEPS = 100;
    public static final float BASE_HEIGHT = 1.0F / 16.0F;

    private VoxelSurfaceSettings() {
    }

    public static float maximumHeight(int steps) {
        if (steps < MINIMUM_STEPS || steps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException(
                    "Voxel-surface strength is outside the supported range");
        }
        return BASE_HEIGHT * steps / STEPS_PER_UNIT;
    }
}
