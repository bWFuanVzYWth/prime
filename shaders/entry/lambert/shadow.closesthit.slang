#language slang 2026
module "entry/lambert/shadow.closesthit.slang";
import "model/material/recipe.slang";
import "prime_abi_types.slang";
import glsl;
import "contract/lambert.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";
import "service/material/lambert.slang";
layout(location = 1) rayPayloadInEXT PrimeLambertShadowPayload primeLambertShadow;
[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    primeLambertShadow.distance = RayTCurrent();
    primeLambertShadow.flags = 0u;
    if (GeometryIndex() == 0u) return;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeHitTriangle hit = primeHitTriangle(section, attributes.barycentrics);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section, primitive);
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(section, primitive,
            primePrimitiveMaterialCoreWord(primitive), relation, attributes.barycentrics,
            hit.localPosition, hit.geometricNormal);
    if (!primeRecipeIsTransmissive(resolved.recipeControl)) return;
    primitive = resolved.primitive;
    uint textureId = primePrimitiveTextureId(section, primitive, resolved.materialCoreWord);
    float2 uv = primeInterpolateUv(section, primitive, attributes.barycentrics, hit.localPosition, hit.geometricNormal);
    PrimeLambertMaterial material = primeLambertMaterial(section, primitive, resolved.recipeControl,
            textureId, uv, 0.0, dot(hit.geometricNormal, WorldRayDirection()), relation);
    primeLambertShadow.position = hit.position;
    primeLambertShadow.flags = material.flags;
    primeLambertShadow.extinction = material.color;
    primeLambertShadow.adjacentExtinction = material.adjacent;
    primeLambertShadow.media = material.media;
    primeLambertShadow.source = uint2(InstanceID(), hit.triangleIndex);
}
