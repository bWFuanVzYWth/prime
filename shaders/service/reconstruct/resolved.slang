#language slang 2026
module "service/reconstruct/resolved.slang";

import "contract/path.slang";
import "contract/realtime/guide.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/resolved.slang";
import "model/realtime/guide.slang";

public PrimeResolvedSample primeResolveOrdinarySample(PrimeOrdinarySample ordinary) {
    PrimeResolvedSample result;
    result.radiance = ordinary.radiance;
    result.guides = ordinary.guides;
    result.reflectionDiffuseRadiance = float3(0.0);
    result.reflectionSpecularRadiance = float3(0.0);
    result.transmissionGuides = primeEmptyDenoiserGuides();
    result.reflectionGuides = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    result.reflectionDirectionalGuide = false;
    result.transparentPrimary = false;
    result.transparentReflectionOnly = false;
    return result;
}

public bool primeRrUsesReflectionPrimaryGuide(
        PrimeResolvedSample result,
        float reflectionVirtualPositionKind) {
    return result.transparentPrimary
            && result.transparentReflectionOnly
            && reflectionVirtualPositionKind == 0.0
            && result.reflectionGuides.primaryHitKind == PRIME_HIT_SURFACE;
}

public float4 primeCapturedReflectionPreviousVirtualPosition(
        PrimeResolvedSample result,
        bool reachedSolidAngle) {
    float3 position = result.reflectionGuides.primaryHasMotion
            ? result.reflectionGuides.primaryPreviousPosition
            : result.reflectionGuides.primaryPosition;
    bool finite = !any(isnan(position)) && !any(isinf(position));
    if (!finite) return float4(0.0, 0.0, 0.0, -1.0);
    if (result.reflectionDirectionalGuide) {
        float lengthSquared = dot(position, position);
        return lengthSquared > 1.0e-12
                        && !isnan(lengthSquared)
                        && !isinf(lengthSquared)
                ? float4(position * rsqrt(lengthSquared), 1.0)
                : float4(0.0, 0.0, 0.0, -1.0);
    }
    return reachedSolidAngle
            ? float4(position, 0.0)
            : float4(0.0, 0.0, 0.0, -1.0);
}

public PrimeDenoiserGuides primeSelectReconstructionPrimaryGuides(
        PrimeResolvedSample result,
        bool nrdShInputs,
        bool usesTransmissionGuide,
        bool usesReflectionGuide) {
    if (usesReflectionGuide) return result.reflectionGuides;
    if (result.transparentPrimary
            && (usesTransmissionGuide
                    || (nrdShInputs && !result.transparentReflectionOnly))) {
        PrimeDenoiserGuides guides = result.transmissionGuides;
        if (!nrdShInputs) {
            guides.primarySpecularAlbedo = result.guides.primarySpecularAlbedo;
        }
        return guides;
    }
    return result.guides;
}
