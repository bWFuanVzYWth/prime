// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import dev.prime.render.shader.ShaderAbi;

/** Pure mapping between Minecraft world height and Prime's physical atmosphere shell. */
public final class AtmosphereCoordinates {
    public static final float WORLD_SEA_LEVEL_Y = ShaderAbi.ATMOSPHERE_WORLD_SEA_LEVEL_Y;
    public static final float WORLD_UNIT_SCALE_KM = ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM;

    private AtmosphereCoordinates() {
    }

    public static float eyeRadiusKm(double worldY) {
        float radius = ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + worldAltitudeKm(worldY);
        return Math.max(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + WORLD_UNIT_SCALE_KM,
                Math.min(
                        ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM - WORLD_UNIT_SCALE_KM,
                        radius));
    }

    /** Maps Y=-128 to the virtual planet surface without changing the atmosphere model. */
    public static float worldAltitudeKm(double worldY) {
        return (float) ((worldY - WORLD_SEA_LEVEL_Y) * WORLD_UNIT_SCALE_KM);
    }
}
