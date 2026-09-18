// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import dev.prime.render.shader.ShaderAbi;

/** Pure mapping between Minecraft world height and Prime's physical atmosphere shell. */
public final class AtmosphereCoordinates {
    public static final float WORLD_GROUND_Y = ShaderAbi.ATMOSPHERE_WORLD_GROUND_Y;
    public static final float WORLD_UNIT_SCALE_KM = ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM;

    private AtmosphereCoordinates() {
    }

    public static float eyeRadiusKm(double worldY, AtmosphereSettings settings) {
        float radius = ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + worldAltitudeKm(worldY, settings);
        return Math.max(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM,
                Math.min(
                        ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM - WORLD_UNIT_SCALE_KM,
                        radius));
    }

    /** Y=-64 is the datum; the configured offset is in physical meters, independent of scene scale. */
    public static float worldAltitudeKm(double worldY, AtmosphereSettings settings) {
        return (float) ((worldY - WORLD_GROUND_Y) * WORLD_UNIT_SCALE_KM
                + settings.altitudeOffsetMeters() * 0.001);
    }
}
