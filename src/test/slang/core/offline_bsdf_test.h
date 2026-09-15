// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#ifndef PRIME_OFFLINE_BSDF_TEST_H
#define PRIME_OFFLINE_BSDF_TEST_H

// Offline transport uses the shared complete single-path closure. No event is split or forced, so
// the reference PDF includes reflection/transmission selection and remains directly usable by
// throughput and MIS.
PrimeTransmissiveBsdfSample primeSampleOfflineMinecraftTransmissionFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    return primeSampleMinecraftTransmissionCompleteFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            viewDirection,
            sampleValue,
            medium);
}

BsdfEvaluation primeEvaluateOfflineMinecraftTransmission(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 scatterDirection,
        PrimeOpenPbrVolume medium) {
    float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
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
            primeUsesAirGap());
    return primeEvaluateMinecraftTransmissionCompleteFromState(
            state,
            surface.baseColor,
            primeSurfaceOpacity(surface),
            surface.materialControl,
            viewDirection,
            scatterDirection);
}

// Offline transport has one global guaranteed continuation. Interface type never grants an
// additional roulette exemption, including smooth discrete reflection and refraction chains.
bool primeOfflineSkipsRussianRoulette(BsdfSample bsdf) {
    return false;
}

#endif
