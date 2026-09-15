// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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
        float3 startingExtinction,
        uint startingMediumId) {
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(startingExtinction, distance);
    primeShadowPayload.startingExtinctionLastBoundary = float4(startingExtinction, -1.0);
    primeShadowPayload.startingMediumId = startingMediumId;
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
        PrimeOpenPbrVolume medium) {
    return primeTracePreparedShadow(context,
            surface.position,
            light.direction,
            light.distance,
            uint2(surface.sectionIndex, primeSurfaceTriangleIndex(surface)),
            targetEmitter,
            primeShadowCanonicalExtinction(medium.extinction),
            medium.mediumId);
}

public PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        LightSample light,
        PrimeOpenPbrVolume medium) {
    return primeTraceShadow(
            context,
            surface,
            light,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            medium);
}
