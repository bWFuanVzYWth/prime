package dev.prime.render.scene.vanilla;

/**
 * Explicit changes Prime may apply while translating vanilla raster geometry to a world scene.
 *
 * <p>Mandatory representation conversions, including removing fluid faces against proven full
 * collision and collapsing raster-only reverse winding, are translation invariants rather than
 * optional visual fixes. The second component remains serialized for replay-v1 compatibility.
 */
public record VanillaGeometryPolicy(
        boolean closeCoveredFluidGap,
        boolean suppressFluidFaceAgainstFullCollision) {
    public static final VanillaGeometryPolicy VANILLA_PARITY = new VanillaGeometryPolicy(false, true);
}
