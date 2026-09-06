#language slang 2026
module "entry/lambert/world.anyhit.slang";
import glsl;
import "contract/lambert.slang";
import "contract/lambert_trace.slang";
import "service/trace/lambert_candidate.slang";
#if defined(PRIME_ENABLE_SER)
layout(location = 2) rayPayloadInEXT PrimeLambertTracePayload primeLambertTrace;
#else
layout(location = 0) rayPayloadInEXT PrimeLambertPayload primeLambertPayload;
#endif
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang requires an aggregate write before reading the incoming payload.
#if defined(PRIME_ENABLE_SER)
    primeLambertTrace.reserved = 0u;
    uint2 source = primeLambertTrace.source;
#else
    primeLambertPayload.distance = 0.0;
    uint2 source = uint2(primeLambertPayload.section, primeLambertPayload.triangle);
#endif
    if (primeLambertRejectCandidate(source, attributes.barycentrics)) IgnoreHit();
}
