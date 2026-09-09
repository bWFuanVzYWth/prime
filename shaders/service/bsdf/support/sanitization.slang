// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/sanitization.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/contract/components.slang";

public BsdfEvaluation primeInvalidBsdfEvaluation()
{
    BsdfEvaluation result;
    result.response = float3(0.0);
    result.pdf = 0.0;
    return result;
}

public BsdfSample primeInvalidBsdfSample()
{
    BsdfSample result;
    result.direction = float3(0.0);
    result.response = float3(0.0);
    result.pdf = 0.0;
    result.relativeEta = 1.0;
    result.eventFlags = 0;
    return result;
}

public bool primeBsdfFinite(float value)
{
    return !isnan(value) && !isinf(value);
}

public bool primeBsdfFinite(float3 value)
{
    return !any(isnan(value)) && !any(isinf(value));
}

public bool primeBsdfDirection(float3 value)
{
    if (!primeBsdfFinite(value)) return false;
    float lengthSquared = dot(value, value);
    return primeBsdfFinite(lengthSquared)
            && lengthSquared > 1.0e-12
            && abs(lengthSquared - 1.0) <= 1.0e-3;
}

public BsdfEvaluation primeSanitizeBsdfEvaluation(BsdfEvaluation evaluation)
{
    if (!primeBsdfFinite(evaluation.response)
            || !primeBsdfFinite(evaluation.pdf)
            || any(evaluation.response < float3(0.0))
            || evaluation.pdf < 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    return evaluation;
}

public BsdfSample primeSanitizeBsdfSample(BsdfSample sampleValue)
{
    if (sampleValue.eventFlags == 0) {
        return primeInvalidBsdfSample();
    }
    if (!primeBsdfDirection(sampleValue.direction)
            || !primeBsdfFinite(sampleValue.response)
            || !primeBsdfFinite(sampleValue.pdf)
            || !primeBsdfFinite(sampleValue.relativeEta)
            || any(sampleValue.response < float3(0.0))
            || !(sampleValue.pdf > 0.0)
            || !(sampleValue.relativeEta > 0.0)) {
        return primeInvalidBsdfSample();
    }
    return sampleValue;
}

public float3 primeSanitizeDenoiseAlbedo(float3 albedo)
{
    return float3(
            primeBsdfFinite(albedo.x) ? clamp(albedo.x, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.y) ? clamp(albedo.y, 0.0, 1.0) : 0.0,
            primeBsdfFinite(albedo.z) ? clamp(albedo.z, 0.0, 1.0) : 0.0);
}

public PrimeBsdfComponents primeSanitizeBsdfComponents(PrimeBsdfComponents components)
{
    if (!primeBsdfFinite(components.diffuseResponse)
            || !primeBsdfFinite(components.specularResponse)
            || !primeBsdfFinite(components.pdf)
            || any(components.diffuseResponse < float3(0.0))
            || any(components.specularResponse < float3(0.0))
            || components.pdf < 0.0) {
        components.diffuseResponse = float3(0.0);
        components.specularResponse = float3(0.0);
        components.pdf = 0.0;
    }
    return components;
}
