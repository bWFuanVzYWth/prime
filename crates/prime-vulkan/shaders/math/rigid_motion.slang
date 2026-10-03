#language slang 2026
module "math/rigid_motion.slang";

// The CPU proves the same ordered local geometry and stable instance identity.
// Match the current physical-hit reconstruction order; safe ray offsets are excluded.
public float3 primeRigidPreviousPosition(float3 v0, float3 v1, float3 v2, float2 bary,
                                         float3x4 previous) {
    precise float3 local = v0 + mad(bary.x, v1 - v0, bary.y * (v2 - v0));
    precise float3 position;
    for (int row = 0; row < 3; ++row)
        position[row] =
                previous[row][3] + mad(previous[row][0], local.x,
                                       mad(previous[row][1], local.y, previous[row][2] * local.z));
    return position;
}
