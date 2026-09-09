// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/direct/area.slang";

import "prime_abi_types.slang";
import "contract/light.slang";
import "contract/light_sample.slang";
import "math/sampling.slang";
import "service/light/area.slang";
import "service/trace/context.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    return primeEstimatePrimaryDirectAreaLightSampled(
            context,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY),
            volumeStack,
            conditionalTransparentBranch);
}

public PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLightSampled(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    return primeEstimatePrimaryDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            conditionalTransparentBranch);
}

public float3 primeEstimateDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeOpenPbrVolumeStack volumeStack) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    return primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack);
}

public PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    // Stars are evaluated only when the continuation ray misses. Primary non-sun guides therefore
    // contain only the explicitly sampled area-light family.
    return primeEstimatePrimaryDirectAreaLight(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            conditionalTransparentBranch);
}

public PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirectSampled(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeOpenPbrVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    return primeEstimatePrimaryDirectAreaLightSampled(
            context,
            surface,
            viewDirection,
            treeSample,
            positionSample,
            volumeStack,
            conditionalTransparentBranch);
}
