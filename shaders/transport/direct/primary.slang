// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/direct/primary.slang";

import "contract/light_sample.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/light/sample.slang";
import "service/light/area_sample.slang";
import "model/material/defaults.slang";
import "service/trace/context.slang";
import "contract/light.slang";
import "transport/direct/evaluate.slang";
import "transport/direct/selection.slang";
import "transport/direct/shadow.slang";
import "transport/surface/access.slang";
import "bsdf/compact/contract/volume.slang";

public bool primeDirectSampleEligible(
        SurfaceInteraction surface,
        float3 shadingNormal,
        LightSample light) {
    return light.pdf > 0.0
            && (primeMaterialIsTransmissive(surface.materialControl)
                    || primeMaterialIsFoliage(surface.materialControl)
                    || dot(shadingNormal, light.direction) > 0.0);
}
public struct PrimePrimarySunSample {
    public PrimeDirectLightingSplit lighting;
    public float penumbra;
    public float visibility;
};

public float primeSigmaPackDirectionalPenumbra(float distanceToOccluder) {
    distanceToOccluder = primeNrdSanitizeHitDistance(distanceToOccluder);
    if (distanceToOccluder >= PRIME_NRD_FP16_MAX) {
        return PRIME_NRD_FP16_MAX;
    }
    float penumbraRadius = 0.5 * distanceToOccluder
            * tan(ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return min(max(penumbraRadius, 0.0), 32768.0);
}

public PrimePrimarySunSample primeEstimatePrimaryDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimePrimarySunSample result;
    result.lighting.diffuse = float3(0.0);
    result.lighting.specular = float3(0.0);
    result.lighting.direction = float3(0.0);
    result.penumbra = 0.0;
    result.visibility = 0.0;
    LightSample light = primeSampleSun(integrator, sampleValue);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return result;
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface, light, volumeStack);
    result.penumbra = primeSigmaPackDirectionalPenumbra(shadow.hitDistance);
    result.visibility = float(shadow.hitDistance >= PRIME_NRD_FP16_MAX);
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, light) * shadow.transmittance;
    result.lighting = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            primeUsesAirGap(),
            conditionalTransparentBranch,
            true);
    return result;
}

public PrimeDirectLightingSplit primeEstimatePrimaryDirectPreparedAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        AreaLightSample area,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            area.light,
            uint2(area.sectionIndex, area.emitterIndex),
            volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            primeUsesAirGap(),
            conditionalTransparentBranch,
            false);
}

public float3 primeEstimateDirectPreparedAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        AreaLightSample area,
        PrimeOpenPbrVolumeStack volumeStack) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            area.light,
            uint2(area.sectionIndex, area.emitterIndex),
            volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            primeUsesAirGap(),
            true);
}
public float3 primeEstimateDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    LightSample light = primeSampleSun(integrator, sampleValue);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return float3(0.0);
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface, light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, light) * shadow.transmittance;
    return primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            primeUsesAirGap(),
            true);
}
