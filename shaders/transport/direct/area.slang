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
import "contract/medium.slang";

public PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeMedium medium,
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
            medium,
            conditionalTransparentBranch);
}

public PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLightSampled(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeMedium medium,
        bool conditionalTransparentBranch) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    return primeEstimateDirectAreaSplit(
            context,
            surface,
            viewDirection,
            area,
            medium,
            conditionalTransparentBranch);
}

public float3 primeEstimateDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeMedium medium) {
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
            medium);
}

public PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeMedium medium,
        bool conditionalTransparentBranch) {
    // Stars are evaluated only when the continuation ray misses. Primary non-sun guides therefore
    // contain only the explicitly sampled area-light family.
    return primeEstimatePrimaryDirectAreaLight(
            context,
            surface,
            viewDirection,
            preparedSample,
            medium,
            conditionalTransparentBranch);
}

public PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirectSampled(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeMedium medium,
        bool conditionalTransparentBranch) {
    return primeEstimatePrimaryDirectAreaLightSampled(
            context,
            surface,
            viewDirection,
            treeSample,
            positionSample,
            medium,
            conditionalTransparentBranch);
}
