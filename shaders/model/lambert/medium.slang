// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/lambert/medium.slang";

import "contract/lambert.slang";
import "model/material/transparent.slang";

// Boundary endpoints are translation output. Traversal never infers missing media.
public struct PrimeLambertMedium {
    public float3 extinction;
    public uint id;
};
public float3 primeLambertExtinction(PrimeLambertMedium medium) {
    return medium.extinction;
}
public PrimeLambertMedium primeLambertCameraMedium(bool water) {
    PrimeLambertMedium result = {};
    if (water) {
        result.extinction = PRIME_PURE_WATER_ABSORPTION_M_INV;
        result.id = 1u;
    }
    return result;
}
public float3 primeLambertSegmentFilter(PrimeLambertMedium medium, float distance) {
    return exp(-medium.extinction * distance);
}
public void primeLambertCrossBoundary(inout PrimeLambertMedium medium, uint media,
        float3 extinction, float3 adjacent, uint flags) {
    bool entering = (flags & PRIME_LAMBERT_ENTERING) != 0u;
    medium.id = entering ? media & 0xffffu : media >> 16u;
    medium.extinction = entering ? extinction : adjacent;
}
