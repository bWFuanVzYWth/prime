#language slang 2026
module "entry/hit/shadow_opaque.anyhit.slang";

import glsl;
import "prime_abi_types.slang";
import "service/trace/candidate_record.slang";
import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

// This entry is bound only to geometry class 0. It preserves exact endpoint rejection without
// carrying alpha, material, texture, relation, or transparent-medium code into opaque candidates.
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang's GLSL front end otherwise diagnoses this input-only aggregate as uninitialized.
    // Closest-hit or miss always replaces the hit-distance lane after traversal.
    primeShadowPayload.opticalDepthMomentHitDistance.w = 0.0;
    if (primeOpaqueCandidateIsSource(primeShadowPayload.sourcePrimitive)) {
        IgnoreHit();
        return;
    }
    if (InstanceID() != primeShadowPayload.targetEmitter.x) {
        return;
    }
    if (primeOpaqueCandidateEmitter()
            == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
    }
}
