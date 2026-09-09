// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/lambert_shadow.slang";
import glsl;
import "prime_abi.slang";
import "contract/lambert.slang";
import "model/lambert/medium.slang";

layout(location = 1) rayPayloadEXT PrimeLambertShadowPayload primeLambertShadow;
public struct PrimeLambertVisibility {
    public float3 filter;
    public float blockerDistance; // negative: connected; nonnegative: opaque blocker or budget
};

public PrimeLambertVisibility primeTraceLambertShadow(float3 origin, float3 direction, float distance,
        uint2 source, uint2 target, PrimeLambertMedium medium, uint limit) {
    PrimeLambertVisibility result;
    result.filter = float3(1.0);
    result.blockerDistance = -1.0;
    float remaining = distance;
    float traveled = 0.0;
    uint transitions = 0u;
    [loop] for (;;) {
        primeLambertShadow = {};
        primeLambertShadow.source = source;
        primeLambertShadow.target = target;
        traceRayEXT(primeScene, gl_RayFlagsNoOpaqueEXT, 0xff, 3, 1, 1,
                origin, 0.0, direction, remaining, 1);
        if (primeLambertShadow.distance < 0.0) {
            result.filter *= primeLambertSegmentFilter(medium, remaining);
            return result;
        }
        if ((primeLambertShadow.flags & PRIME_LAMBERT_DELTA) == 0u) {
            result.blockerDistance = traveled + primeLambertShadow.distance;
            return result;
        }
        float segment = length(primeLambertShadow.position - origin);
        if (!primeLambertInferIncomingMedium(medium, primeLambertShadow.media,
                primeLambertShadow.extinction, primeLambertShadow.adjacentExtinction, primeLambertShadow.flags)) {
            result.blockerDistance = traveled + segment;
            return result;
        }
        result.filter *= primeLambertSegmentFilter(medium, segment);
        if (transitions == limit) {
            result.blockerDistance = traveled + segment;
            return result;
        }
        if ((primeLambertShadow.flags & PRIME_LAMBERT_THIN) != 0u)
            result.filter *= exp(-primeLambertShadow.extinction);
        else if (!primeLambertCrossBoundary(medium, primeLambertShadow.media,
                primeLambertShadow.extinction, primeLambertShadow.adjacentExtinction, primeLambertShadow.flags)) {
            result.blockerDistance = traveled + segment;
            return result;
        }
        origin = primeLambertShadow.position;
        source = primeLambertShadow.source;
        remaining = max(0.0, remaining - segment);
        traveled += segment;
        transitions++;
        if (remaining == 0.0) return result;
    }
}
