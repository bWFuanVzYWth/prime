#language slang 2026
module "service/bsdf/dispatch/guide_albedo.slang";

import "prime_abi_types.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/dielectric/contract.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/opaque/albedo.slang";
import "service/bsdf/opaque/state.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/foliage/albedo.slang";
import "service/bsdf/foliage/state.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeDenoiseAlbedos primeSurfaceGuideAlbedos(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesAirGap) {
    float3 outwardNormal = surface.shadingNormal;
    outwardNormal = dot(outwardNormal, surface.geometricNormal) >= 0.0
            ? outwardNormal
            : -outwardNormal;
    float3 shadingNormal = dot(surface.geometricNormal, viewDirection) >= 0.0
            ? outwardNormal
            : -outwardNormal;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
    }
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionState(
                        surface.baseColor,
                        clamp(surface.opacity, 0.0, 1.0),
                        outwardNormal,
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        surface.mediumId,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl,
                        surface.adjacentMediumId,
                        usesAirGap);
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
    }
    PrimeCompactOpaqueState state = primeOpaqueState(
            surface.baseColor,
            shadingNormal,
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            surface.t,
            volumeStack);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, PRIME_DENOISE_CLOSURE_OPAQUE);
}
