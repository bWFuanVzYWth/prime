#language slang 2026
module "transport/scatter/delta.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/opaque/discrete.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/transport.slang";
import "service/light/emission.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/opaque/discrete.slang";
import "service/bsdf/opaque/traits.slang";
import "service/bsdf/dielectric/albedo.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/dielectric/discrete.slang";
import "service/bsdf/support/conversion.slang";

import "service/trace/context.slang";
import "service/trace/surface.slang";
import "model/realtime/transport.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/material/normal.slang";

PrimePathScatter primeEnforceDiscreteGeometricSupport(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack originalVolumeStack,
        PrimePathScatter scatter) {
    if (!primeGeometricSampleSupport(
            primeMaterialUsesNormalMap(surface.opticalControl),
            surface.geometricNormal,
            viewDirection,
            scatter.bsdf)) {
        scatter.bsdf = primeInvalidBsdfSample();
        scatter.volumeStack = originalVolumeStack;
    }
    return scatter;
}

// Delta chaining rejects foliage before asking either supported family for traits. This keeps the
// phase independent of the foliage closure while preserving the exact terminal classification.
public bool primeSurfaceIsDiscreteOnly(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (primeMaterialIsFoliage(surface.materialControl)) {
        return false;
    }
    PrimeClosureTraits traits = primeMaterialIsTransmissive(surface.materialControl)
            ? primeMinecraftTransmissionClosureTraits(
                    primeSurfaceOutwardShadingNormal(surface),
                    surface.materialControl,
                    surface.roughness,
                    surface.opticalControl,
                    viewDirection,
                    volumeStack,
                    surface.adjacentInterfaceControl,
                    primeUsesAirGap())
            : primeOpaqueClosureTraits(
                    surface.roughness,
                    surface.opticalControl,
                    surface.materialControl);
    return primeClosureIsDiscreteOnly(traits);
}


public PrimePathScatter primeSampleDiscretePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimePathScatter result;
    result.bsdf = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionState(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        primeSurfaceOutwardShadingNormal(surface),
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl,
                        primeUsesAirGap());
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionDiscreteFromState(
                        state,
                        viewDirection,
                        sampleValue.z,
                        volumeStack);
        sampled = primeApplyAdjacentMediumTransition(
                sampled,
                state,
                volumeStack,
                surface.baseColor,
                primeSurfaceOpacity(surface),
                surface.materialControl,
                surface.opticalControl,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl,
                primeUsesAirGap());
        result.bsdf = sampled.bsdfSample;
        result.volumeStack = sampled.volumeStack;
    } else {
        float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueDiscreteState state = primeOpaqueDiscreteState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                surface.t);
        result.bsdf = primeSampleOpaqueDiscreteFromState(
                state,
                shadingNormal,
                viewDirection,
                volumeStack);
    }
    return primeEnforceDiscreteGeometricSupport(
            surface, viewDirection, volumeStack, result);
}

// Canonical reconstruction follows transmission whenever that exact discrete event is valid.
// TIR and unsupported transmission fall back to the reflection event from the same production
// sampler, so radiance and guide cannot drift onto different Fresnel or refraction formulas.
public PrimePathScatter primeSamplePreferredTransmissionDiscretePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimePathScatter preferred = primeSampleDiscretePathSurface(
            context,
            surface,
            viewDirection,
            float3(0.0),
            volumeStack);
    if (primeHasScatter(preferred.bsdf)
            || !primeMaterialIsTransmissive(surface.materialControl)) {
        return preferred;
    }
    return primeSampleDiscretePathSurface(
            context,
            surface,
            viewDirection,
            float3(0.0, 0.0, 1.0),
            volumeStack);
}

public uint primeDiscreteEventKind(BsdfSample sample) {
    return sample.eventFlags
            & (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_TRANSMISSION);
}

public PrimeGuidedPathScatter primeSampleGuidedDiscretePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        uint bounce) {
    PrimeGuidedPathScatter result;
    result.scatter.bsdf = primeInvalidBsdfSample();
    result.scatter.volumeStack = volumeStack;
    result.albedos.diffuse = float3(0.0);
    result.albedos.specular = float3(0.0);
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionState(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        primeSurfaceOutwardShadingNormal(surface),
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl,
                        primeUsesAirGap());
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionDiscreteFromState(
                        state,
                        viewDirection,
                        sampleValue.z,
                        volumeStack);
        sampled = primeApplyAdjacentMediumTransition(
                sampled,
                state,
                volumeStack,
                surface.baseColor,
                primeSurfaceOpacity(surface),
                surface.materialControl,
                surface.opticalControl,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl,
                primeUsesAirGap());
        result.scatter.bsdf = sampled.bsdfSample;
        result.scatter.volumeStack = sampled.volumeStack;
    } else {
        float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueDiscreteState state = primeOpaqueDiscreteState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                surface.t);
        result.albedos = primeOpaqueDiscreteAlbedosFromState(
                state, viewDirection);
        result.scatter.bsdf = primeSampleOpaqueDiscreteFromState(
                state,
                shadingNormal,
                viewDirection,
                volumeStack);
    }
    result.scatter = primeEnforceDiscreteGeometricSupport(
            surface, viewDirection, volumeStack, result.scatter);
    return result;
}
