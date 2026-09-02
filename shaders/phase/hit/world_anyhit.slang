#language slang 2026
module "phase/hit/world_anyhit.slang";

import glsl;
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

public void primePhase(in BuiltInTriangleIntersectionAttributes attributes)
{
    // Slang's GLSL front end otherwise diagnoses this input-only aggregate as uninitialized.
    // t is already reset to zero before traversal and closest-hit overwrites it on acceptance.
    primePayload.t = 0.0;
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    uint triangleIndex = primeGlobalTriangleIndex(section);
    bool sameSourceSection = InstanceID() == primePayload.sectionIndex;
    if (sameSourceSection && triangleIndex == primePayload.positionReserved) {
        IgnoreHit();
        return;
    }
    if (sameSourceSection
            && primeGlobalPrimitiveIndex(section)
                    == primeGlobalPrimitiveIndexFromTriangle(
                            section, primePayload.positionReserved)) {
        IgnoreHit();
        return;
    }
    // The opaque geometry class needs this shader only for exact source-primitive rejection.
    if (GeometryIndex() == 0u) {
        return;
    }
    PrimitiveRecord primitive = primePrimitive(section);
    // Geometry class is already an exact translation result. Use it for the transparent fast
    // path so a no-relation hit does not need a random material-table access.
    bool primaryTransmissive = GeometryIndex() == 2u;
    // Without a relation, transparent radiance hits need neither triangle reconstruction nor
    // opacity evaluation.
    if (primaryTransmissive && section.surfaceRelationAddress == uint64_t(0)) {
        return;
    }
    PrimeSurfaceRelation relation = primeSurfaceRelation(section, primitive);
    if (primaryTransmissive && relation.control == 0u) {
        return;
    }
    uint primaryMaterialCoreWord = primePrimitiveMaterialCoreWord(primitive);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    float3 normal = hit.geometricNormal;
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(
            section,
            primitive,
            primaryMaterialCoreWord,
            relation,
            barycentrics,
            hit.localPosition,
            normal);
    primitive = resolved.primitive;
    if (primeRecipeIsTransmissive(resolved.recipeControl)) {
        // Radiance rays keep transparent geometry as explicit path vertices. Shadow rays use
        // shadow.anyhit.slang instead, where straight glass and water transmission are accumulated.
        return;
    }
    uint recipeControl = resolved.recipeControl;
    if ((recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u) {
        float opacity = primeEvaluateOpacity(
                primitive,
                primePrimitiveTextureId(
                        section, primitive, resolved.materialCoreWord),
                primeInterpolateUv(
                        section, primitive, barycentrics, hit.localPosition, normal));
        if (opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
            IgnoreHit();
        }
    }
}
