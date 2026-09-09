// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/shadow.anyhit.slang";
import "service/trace/candidate_record.slang";
import glsl;
import "contract/lambert.slang";
import "service/trace/lambert_candidate.slang";
layout(location = 1) rayPayloadInEXT PrimeLambertShadowPayload primeLambertShadow;
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang requires an aggregate write before reading the incoming payload. Hit/miss overwrites this lane.
    primeLambertShadow.distance = 0.0;
    if (primeLambertRejectCandidate(primeLambertShadow.source, attributes.barycentrics)
            || (InstanceID() == primeLambertShadow.target.x
                    && primeCandidateEmitter(primeCandidateRecord()) == primeLambertShadow.target.y)) IgnoreHit();
}
