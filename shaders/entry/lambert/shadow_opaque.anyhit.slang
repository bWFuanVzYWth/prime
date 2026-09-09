// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/shadow_opaque.anyhit.slang";
import glsl;
import "prime_abi_types.slang";
import "contract/lambert.slang";
import "service/trace/candidate_record.slang";
layout(location = 1) rayPayloadInEXT PrimeLambertShadowPayload primeLambertShadow;

// Bound only to geometry class 0; opaque candidates need neither position fetch nor textures.
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang's input-aggregate workaround; closest-hit/miss replaces this output lane.
    primeLambertShadow.distance = 0.0;
    if (primeOpaqueCandidateIsSource(primeLambertShadow.source)
            || (InstanceID() == primeLambertShadow.target.x
                    && primeOpaqueCandidateEmitter() == primeLambertShadow.target.y)) IgnoreHit();
}
