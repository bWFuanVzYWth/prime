#language slang 2026
module "transport/direct/shadow.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "model/material/transparent.slang";
import "contract/trace/shadow_payload.slang";
import "service/trace/context.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

layout(location = 1) rayPayloadEXT PrimeShadowPayload primeShadowPayload;

public struct PrimeShadowTrace {
    public float3 transmittance;
    public float hitDistance;
};

public PrimeShadowTrace primeTracePreparedShadow(
        inout PrimeIntegratorContext context,
        float3 physicalOrigin,
        float3 direction,
        float distance,
        uint2 sourcePrimitive,
        uint2 targetEmitter,
        float3 startingExtinction0,
        float3 startingExtinction1,
        uint2 startingMediumIds,
        uint startingMediumCount) {
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(
            float3(0.0), distance);
    primeShadowPayload.startingExtinction0Winding = float4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = float4(
            startingExtinction1, startingMediumCount > 1u ? 1.0 : 0.0);
    primeShadowPayload.startingMediumIds = startingMediumIds;
    primeShadowPayload.startingMediumCount = startingMediumCount;
    primeShadowPayload.sourcePrimitive = sourcePrimitive;
    primeShadowPayload.targetEmitter = targetEmitter;
    // Source and target geometry are rejected by exact identity in any-hit. The physical segment
    // therefore remains open at both endpoints without deleting nearby occluders or media.
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoOpaqueEXT,
            0xff,
            3,
            1,
            1,
            physicalOrigin,
            0.0,
            direction,
            distance,
            1);
    PrimeShadowTrace result;
    result.transmittance = clamp(exp(-primeShadowOpticalDepth(
                    primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                    primeShadowPayload.terminalExtinctionRayDistance.xyz,
                    primeShadowPayload.startingExtinction0Winding,
                    primeShadowPayload.startingExtinction1Winding,
                    primeShadowPayload.startingMediumCount,
                    primeShadowPayload.terminalExtinctionRayDistance.w)),
            float3(0.0),
            float3(1.0));
    result.hitDistance = primeNrdSanitizeHitDistance(
            primeShadowPayload.opticalDepthMomentHitDistance.w);
    return result;
}

public PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        LightSample light,
        uint2 targetEmitter,
        PrimeOpenPbrVolumeStack volumeStack) {
    uint startingMediumCount = min(
            volumeStack.count, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    float3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[0].extinction)
            : float3(0.0);
    float3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[1].extinction)
            : float3(0.0);
    uint2 startingMediumIds = uint2(
            startingMediumCount > 0u ? volumeStack.values[0].mediumId : 0u,
            startingMediumCount > 1u ? volumeStack.values[1].mediumId : 0u);
    return primeTracePreparedShadow(context,
            surface.position,
            light.direction,
            light.distance,
            uint2(surface.sectionIndex, primeSurfaceTriangleIndex(surface)),
            targetEmitter,
            startingExtinction0,
            startingExtinction1,
            startingMediumIds,
            startingMediumCount);
}

public PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        LightSample light,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeTraceShadow(
            context,
            surface,
            light,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            volumeStack);
}
