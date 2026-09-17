// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/sample.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/dielectric/contract.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/sample.slang";
import "service/bsdf/dispatch/ordinary_sample.slang";
import "transport/surface/access.slang";
import "service/trace/context.slang";
import "contract/material/normal.slang";
import "transport/scatter/contract.slang";
import "contract/medium.slang";

public PrimeGuidedPathScatter primeSamplePathSurfaceWithAlbedos(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeMedium medium,
        bool needsGuide,
        bool usesAirGap) {
    if (!primeMaterialIsTransmissive(surface.materialControl)) {
        return primeSampleOrdinaryPathSurface(
                surface, viewDirection, sampleValue, medium, needsGuide);
    }
    PrimeGuidedPathScatter result = {};
    result.scatter.medium = medium;
    float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
    PrimeCompactTransmissionState state =
            primeMinecraftBoundaryTransmissionState(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    outwardNormal,
                    surface.materialControl,
                    surface.roughness,
                    surface.opticalControl,
                    viewDirection,
                    surface.t,
                    medium,
                    surface.mediumId,
                    surface.adjacentBaseColor,
                    surface.adjacentInterfaceControl,
                    surface.adjacentMediumId,
                    usesAirGap);
    if (needsGuide) result.albedos = primeDenoiseAlbedosFromState(
            state,
            viewDirection,
            PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
    PrimeTransmissiveBsdfSample sampled =
            primeSampleMinecraftTransmissionCompleteFromState(
                    state,
                    viewDirection,
                    sampleValue,
                    medium);
    sampled = primeApplyAirGapMediumTransition(
            sampled,
            state,
            surface.baseColor,
            primeSurfaceOpacity(surface),
            surface.materialControl,
            surface.opticalControl,
            surface.mediumId,
            surface.adjacentBaseColor,
            surface.adjacentInterfaceControl,
            surface.adjacentMediumId,
            usesAirGap);
    result.scatter.bsdf = sampled.bsdfSample;
    result.scatter.medium = sampled.medium;
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

public PrimePathScatter primeSamplePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeMedium medium,
        bool usesAirGap) {
    return primeSamplePathSurfaceWithAlbedos(
            surface, viewDirection, sampleValue, medium, false, usesAirGap).scatter;
}
