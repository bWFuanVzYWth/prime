// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/ordinary_sample.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/foliage/contract.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/opaque/albedo.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/opaque/sample.slang";
import "service/bsdf/foliage/albedo.slang";
import "service/bsdf/foliage/state.slang";
import "service/bsdf/foliage/sample.slang";
import "transport/surface/access.slang";
import "contract/material/normal.slang";
import "transport/scatter/contract.slang";
import "contract/medium.slang";

// Caller excludes transmissive surfaces. Guides share the sampled state without selecting
// a second sampling path or changing the random-to-event mapping.
public PrimeGuidedPathScatter primeSampleOrdinaryPathSurface(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeMedium medium,
        bool needsGuide) {
    PrimeGuidedPathScatter result = {};
    result.scatter.medium = medium;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                medium);
        if (needsGuide) result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_FOLIAGE);
        result.scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue);
    } else {
        float3 shadingNormal =
                primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueState state = primeOpaqueState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                medium);
        if (needsGuide) result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_OPAQUE);
        result.scatter.bsdf = primeSampleOpaqueFromState(
                state,
                shadingNormal,
                viewDirection,
                sampleValue);
    }
    if (!primeGeometricSampleSupport(
            primeMaterialUsesNormalMap(surface.opticalControl),
            surface.geometricNormal,
            viewDirection,
            result.scatter.bsdf)) {
        result.scatter.bsdf = primeInvalidBsdfSample();
        result.scatter.medium = medium;
    }
    return result;
}
