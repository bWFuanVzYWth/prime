#language slang 2026
module "transport/surface/access.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "contract/path.slang";

public bool primeKnownHitKind(SurfaceInteraction surface) {
    return surface.hitKind == PRIME_HIT_NONE
            || surface.hitKind == PRIME_HIT_SURFACE;
}

public bool primePreviousCannotUseMis(PathState path) {
    return primePathBounce(path.control) == 0u
            || (primePathFlags(path.control)
                    & PRIME_PATH_PREVIOUS_DISCRETE) != 0u;
}

public float3 primeSurfaceOutwardShadingNormal(SurfaceInteraction surface) {
    float3 shadingNormal = surface.shadingNormal;
    return dot(shadingNormal, surface.geometricNormal) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

public float3 primeSurfaceShadingNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    float3 shadingNormal = primeSurfaceOutwardShadingNormal(surface);
    return dot(surface.geometricNormal, viewDirection) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

public float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(surface.opacity, 0.0, 1.0);
}

public float primeSurfaceLinearRoughness(SurfaceInteraction surface) {
    return primeMaterialEffectiveLinearRoughness(
            surface.materialControl, surface.roughness);
}

