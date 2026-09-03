#language slang 2026
module "entry/hit/shadow_opaque.anyhit.slang";

import glsl;
import "prime_abi_types.slang";
import "service/trace/identity.slang";
import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

// This entry is bound only to geometry class 0. It preserves exact endpoint rejection without
// carrying alpha, material, texture, relation, or transparent-medium code into opaque candidates.
[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang's GLSL front end otherwise diagnoses this input-only aggregate as uninitialized.
    // Closest-hit or miss always replaces the hit-distance lane after traversal.
    primeShadowPayload.opticalDepthMomentHitDistance.w = 0.0;
    SectionRecord section = primeSection();
    uint triangleIndex = primeGlobalTriangleIndex(section);
    bool sameSourceSection = InstanceID()
            == primeShadowPayload.sourcePrimitive.x;
    if (sameSourceSection
            && (triangleIndex == primeShadowPayload.sourcePrimitive.y
                    || primeGlobalPrimitiveIndex(section)
                            == primeGlobalPrimitiveIndexFromTriangle(
                                    section,
                                    primeShadowPayload.sourcePrimitive.y))) {
        IgnoreHit();
        return;
    }
    if (InstanceID() != primeShadowPayload.targetEmitter.x) {
        return;
    }
    PrimitiveRecord primitive = primeRawPrimitive(section);
    if (primePrimitiveEmitterIndex(primitive)
            == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
    }
}
