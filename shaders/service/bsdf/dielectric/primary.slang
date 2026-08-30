#language slang 2026
module "service/bsdf/dielectric/primary.slang";

import "service/bsdf/support/sanitization.slang";

import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/conditional.slang";
import "service/bsdf/dielectric/sample.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";

public struct PrimeTransmissivePrimarySample {
    public PrimeTransmissiveBsdfSplit paths;
    public PrimeDenoiseAlbedos albedos;
};

public PrimeTransmissivePrimarySample primeSampleMinecraftTransmissionPrimary(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 reflectionSample,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        uint mediumId,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        uint adjacentMediumId,
        bool usesAirGap) {
    // This is the only fixed-split entry point. Translation, closure initialization, local-view
    // conversion and guide energy are shared before reflection and refraction diverge.
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            mediumId,
            adjacentBaseColor,
            adjacentInterfaceControl,
            adjacentMediumId,
            usesAirGap);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    // One directional-energy lookup supplies the reflection guide and the complementary weights
    // used by the smooth conditional events. Rough event responses come from the closure itself.
    PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(
            state, localView);

    PrimeTransmissivePrimarySample result;
    result.albedos.diffuse = primeSanitizeDenoiseAlbedo(
            primeMinecraftPrimaryTransmissionAlbedo(state, materialControl));
    result.albedos.specular = primeSanitizeDenoiseAlbedo(
            mirror.reflectance);
    result.paths = primeSampleMinecraftTransmissionSplitFromState(
            state,
            localView,
            mirror,
            outwardNormal,
            viewDirection,
            reflectionSample,
            baseColor,
            opacity,
            materialControl,
            volumeStack);
    result.paths.transmission = primeApplyAdjacentMediumTransition(
            result.paths.transmission,
            state,
            volumeStack,
            baseColor,
            opacity,
            materialControl,
            opticalControl,
            mediumId,
            adjacentBaseColor,
            adjacentInterfaceControl,
            adjacentMediumId,
            usesAirGap);
    return result;
}
