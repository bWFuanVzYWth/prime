// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/sample.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/opaque/albedo.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/opaque/sample.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/sample.slang";
import "service/bsdf/foliage/albedo.slang";
import "service/bsdf/foliage/state.slang";
import "service/bsdf/foliage/sample.slang";
import "transport/surface/access.slang";
import "service/trace/context.slang";
import "contract/material/normal.slang";
import "transport/scatter/contract.slang";
import "bsdf/compact/contract/volume.slang";

public PrimePathScatter primeSamplePathSurfaceWithMinimumRoughness(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium,
        float minimumLinearRoughness,
        bool usesAirGap) {
    PrimePathScatter result;
    result.medium = medium;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state =
                primeMinecraftFoliageStateWithMinimumRoughness(
                        surface.baseColor,
                        primeSurfaceShadingNormal(surface, viewDirection),
                        surface.roughness,
                        surface.opticalControl,
                        surface.materialControl,
                        viewDirection,
                        surface.t,
                        medium,
                        minimumLinearRoughness);
        result.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, medium);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
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
                        minimumLinearRoughness,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl,
                        surface.adjacentMediumId,
                        usesAirGap);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialControl,
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
        result.bsdf = sampled.bsdfSample;
        result.medium = sampled.medium;
    } else {
        float3 shadingNormal =
                primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueState state = primeOpaqueStateWithMinimumRoughness(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                medium,
                minimumLinearRoughness);
        result.bsdf = primeSampleOpaqueFromState(
                state,
                shadingNormal,
                viewDirection,
                sampleValue,
                medium);
    }
    if (!primeGeometricSampleSupport(
            primeMaterialUsesNormalMap(surface.opticalControl),
            surface.geometricNormal,
            viewDirection,
            result.bsdf)) {
        result.bsdf = primeInvalidBsdfSample();
        result.medium = medium;
    }
    return result;
}

public PrimePathScatter primeSamplePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium,
        bool usesAirGap) {
    return primeSamplePathSurfaceWithMinimumRoughness(
            context,
            surface,
            viewDirection,
            sampleValue,
            medium,
            0.0,
            usesAirGap);
}

public PrimeGuidedPathScatter primeSampleGuidedPathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium,
        uint bounce,
        bool usesAirGap) {
    PrimeGuidedPathScatter result;
    result.scatter.medium = medium;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                medium);
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_FOLIAGE);
        result.scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, medium);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
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
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialControl,
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
                surface.t,
                medium);
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_OPAQUE);
        result.scatter.bsdf = primeSampleOpaqueFromState(
                state,
                shadingNormal,
                viewDirection,
                sampleValue,
                medium);
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
