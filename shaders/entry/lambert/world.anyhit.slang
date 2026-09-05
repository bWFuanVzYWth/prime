#language slang 2026
module "entry/lambert/world.anyhit.slang";
import glsl;
import "contract/lambert.slang";
import "service/trace/lambert_candidate.slang";
layout(location = 0) rayPayloadInEXT PrimeLambertPayload primeLambertPayload;
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang requires an aggregate write before reading the incoming payload. Hit/miss overwrites this lane.
    primeLambertPayload.distance = 0.0;
    if (primeLambertRejectCandidate(uint2(primeLambertPayload.section, primeLambertPayload.triangle),
            attributes.barycentrics)) IgnoreHit();
}
