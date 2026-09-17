// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/surface/access.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "contract/path.slang";
import "math/surface_normal.slang";

public bool primeKnownHitKind(SurfaceInteraction surface) {
    return surface.hitKind == PRIME_HIT_NONE
            || surface.hitKind == PRIME_HIT_SURFACE;
}

public float3 primeSurfaceOutwardShadingNormal(SurfaceInteraction surface) {
    return primeOutwardShadingNormal(surface.shadingNormal, surface.geometricNormal);
}

public float3 primeSurfaceShadingNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    return primeIncidentShadingNormal(surface.shadingNormal, surface.geometricNormal, viewDirection);
}

public float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(surface.opacity, 0.0, 1.0);
}

public float primeSurfaceLinearRoughness(SurfaceInteraction surface) {
    return primeMaterialEffectiveLinearRoughness(
            surface.materialControl, surface.roughness);
}
