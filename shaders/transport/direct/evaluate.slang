#language slang 2026
module "transport/direct/evaluate.slang";

import "contract/light_sample.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/opaque/state.slang";
import "math/transport.slang";
import "service/light/sample.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/opaque/evaluate.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/evaluate.slang";
import "service/bsdf/foliage/evaluate.slang";
import "service/trace/context.slang";
import "contract/light.slang";
import "contract/material/normal.slang";
import "transport/direct/shadow.slang";
import "transport/surface/access.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 shadingNormal,
        LightSample light,
        float3 lightRadiance,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesAirGap,
        bool conditionalTransparentBranch,
        bool usesMis) {
    PrimeDirectLightingSplit result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    result.direction = light.direction;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    bool foliage = primeMaterialIsFoliage(surface.materialControl);
    bool normalMapped = primeMaterialUsesNormalMap(surface.opticalControl);
    bool geometricReflection = primeGeometricReflectionSupport(
            surface.geometricNormal, viewDirection, light.direction);
    bool geometricTransmission = primeGeometricEventSupport(
            surface.geometricNormal,
            viewDirection,
            light.direction,
            PRIME_BSDF_EVENT_TRANSMISSION);
    if (normalMapped && !geometricReflection && !geometricTransmission) {
        return result;
    }
    if (normalMapped && !transmissive && !foliage && !geometricReflection) {
        return result;
    }
    float cosine = transmissive || foliage
            ? abs(dot(shadingNormal, light.direction))
            : max(dot(shadingNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lightRadiance <= float3(0.0))) {
        return result;
    }
    if (transmissive) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        bool reflection = dot(outwardNormal, viewDirection)
                * dot(outwardNormal, light.direction) >= 0.0;
        if (normalMapped && reflection != geometricReflection) {
            return result;
        }
        BsdfEvaluation evaluation;
        if (conditionalTransparentBranch) {
            // The first visible interface exposes fixed-split conditional proposals. Smooth
            // transmission remains discrete; a rough stained-glass texel has a finite conditional
            // transmission lobe and therefore participates in NEE from the opposite hemisphere.
            evaluation = reflection
                    ? primeEvaluateMinecraftTransparentReflection(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl,
                            usesAirGap)
                    : primeEvaluateMinecraftTransparentTransmission(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl,
                            usesAirGap);
        } else {
            PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
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
            evaluation = primeEvaluateMinecraftTransmissionCompleteFromState(
                    state,
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.materialControl,
                    viewDirection,
                    light.direction);
        }
        float weightedInversePdf = primePowerHeuristicOverPdf(
                light.pdf, usesMis ? evaluation.pdf : 0.0);
        float3 contribution = primeTripleProduct(
                lightRadiance, evaluation.response, weightedInversePdf);
        if (reflection) {
            result.specular = contribution;
        } else {
            result.diffuse = contribution;
        }
        return result;
    }
    if (foliage) {
        bool reflection = dot(shadingNormal, viewDirection)
                * dot(shadingNormal, light.direction) >= 0.0;
        if (normalMapped && reflection != geometricReflection) {
            return result;
        }
        PrimeBsdfComponents components = primeEvaluateMinecraftFoliageComponents(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
        float weightedInversePdf = primePowerHeuristicOverPdf(
                light.pdf, usesMis ? components.pdf : 0.0);
        result.diffuse = primeTripleProduct(
                lightRadiance, components.diffuseResponse, weightedInversePdf);
        result.specular = primeTripleProduct(
                lightRadiance, components.specularResponse, weightedInversePdf);
        return result;
    }
    // The translation layer supplies conservative defaults when no community material data is
    // authored. Material provenance must not select a second BSDF: every ordinary opaque surface
    // uses the compact OpenPBR white-furnace-tested BasicMetallic composition.
    PrimeBsdfComponents components = primeEvaluateOpaqueComponents(
            surface.baseColor,
            shadingNormal,
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            light.direction,
            0.0,
            volumeStack);
    float weightedInversePdf = primePowerHeuristicOverPdf(
            light.pdf, usesMis ? components.pdf : 0.0);
    result.diffuse = primeTripleProduct(
            lightRadiance, components.diffuseResponse, weightedInversePdf);
    result.specular = primeTripleProduct(
            lightRadiance, components.specularResponse, weightedInversePdf);
    return result;
}

public float3 primeEvaluateCompleteDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 shadingNormal,
        LightSample light,
        float3 lightRadiance,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesAirGap,
        bool usesMis) {
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            lightRadiance,
            volumeStack,
            usesAirGap,
            false,
            usesMis);
    return split.diffuse + split.specular;
}
