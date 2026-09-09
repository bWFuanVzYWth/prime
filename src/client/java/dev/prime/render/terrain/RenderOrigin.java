// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

public final class RenderOrigin {
    private static final int SECTION_SIZE = 16;

    private RenderOrigin() {
    }

    public static int alignToSection(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), SECTION_SIZE) * SECTION_SIZE;
    }

    public static boolean needsRebase(
            double cameraX,
            double cameraY,
            double cameraZ,
            int originX,
            int originY,
            int originZ,
            int maximumDistance) {
        return Math.abs(cameraX - originX) > maximumDistance
                || Math.abs(cameraY - originY) > maximumDistance
                || Math.abs(cameraZ - originZ) > maximumDistance;
    }
}
