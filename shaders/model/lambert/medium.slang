// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/lambert/medium.slang";

import "contract/lambert.slang";
import "model/material/transparent.slang";

// Same two active nested media as the scene translator supports, without IOR or PBR state.
public struct PrimeLambertMedium {
    public float3 outer;
    public float3 inner;
    public uint ids;
    public uint count;
};
public float3 primeLambertExtinction(PrimeLambertMedium medium) {
    return medium.count == 0u ? float3(0.0) : medium.count == 1u ? medium.outer : medium.inner;
}
public PrimeLambertMedium primeLambertCameraMedium(bool water) {
    PrimeLambertMedium result = {};
    if (water) {
        result.outer = PRIME_PURE_WATER_ABSORPTION_M_INV;
        result.ids = 1u;
        result.count = 1u;
    }
    return result;
}
public float3 primeLambertSegmentFilter(PrimeLambertMedium medium, float distance) {
    return exp(-primeLambertExtinction(medium) * distance);
}
public bool primeLambertEnterMedium(inout PrimeLambertMedium medium, uint id, float3 extinction) {
    if (id == 0u) return true;
    if (medium.count == 2u) return false;
    if (medium.count == 0u) { medium.outer = extinction; medium.ids = id; }
    else { medium.inner = extinction; medium.ids |= id << 16u; }
    medium.count++;
    return true;
}
public void primeLambertLeaveMedium(inout PrimeLambertMedium medium, uint id) {
    // A first exit can originate inside geometry that was not in the camera's known water state.
    if (medium.count > 1u && (medium.ids >> 16u) == id) {
        medium.count = 1u;
        medium.ids &= 0xffffu;
    } else if (medium.count > 0u && (medium.ids & 0xffffu) == id) {
        medium.outer = medium.inner;
        medium.ids >>= 16u;
        medium.count--;
    }
}
public bool primeLambertCrossBoundary(inout PrimeLambertMedium medium, uint media,
        float3 extinction, float3 adjacent, uint flags) {
    uint first = media & 0xffffu;
    uint second = media >> 16u;
    bool entering = (flags & PRIME_LAMBERT_ENTERING) != 0u;
    primeLambertLeaveMedium(medium, entering ? second : first);
    return primeLambertEnterMedium(medium, entering ? first : second,
            entering ? extinction : adjacent);
}

public bool primeLambertInferIncomingMedium(inout PrimeLambertMedium medium,
        uint media, float3 extinction, float3 adjacent, uint flags) {
    if ((flags & PRIME_LAMBERT_DELTA) == 0u || (flags & PRIME_LAMBERT_THIN) != 0u) return true;
    bool entering = (flags & PRIME_LAMBERT_ENTERING) != 0u;
    uint incoming = entering ? media >> 16u : media & 0xffffu;
    uint current = medium.count == 0u ? 0u : medium.count == 1u ? medium.ids & 0xffffu : medium.ids >> 16u;
    if (incoming == 0u || incoming == current) return true;
    return primeLambertEnterMedium(medium, incoming, entering ? adjacent : extinction);
}
