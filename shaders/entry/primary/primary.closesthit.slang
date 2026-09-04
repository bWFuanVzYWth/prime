#language slang 2026
module "entry/primary/primary.closesthit.slang";

import glsl;
import "prime_abi_types.slang";
import "contract/primary_color.slang";
import "service/material/access.slang";
import "service/material/primary_color.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";

layout(location = 0) rayPayloadInEXT PrimePrimaryColorPayload primePrimaryPayload;

[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section, primitive);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    uint materialCoreWord = primePrimitiveMaterialCoreWord(primitive);
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(
            section,
            primitive,
            materialCoreWord,
            relation,
            barycentrics,
            hit.localPosition,
            hit.geometricNormal);
    primitive = resolved.primitive;
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    uint textureId = bakedMaterial
            ? 0u
            : primePrimitiveTextureId(
                    section, primitive, resolved.materialCoreWord);
    float2 uv = bakedMaterial
            ? float2(0.0)
            : primeInterpolateUv(
                    section,
                    primitive,
                    barycentrics,
                    hit.localPosition,
                    hit.geometricNormal);
    float lod = bakedMaterial
            ? 0.0
            : primeRayConeTextureLod(
                    primitive, textureId, hit.geometricNormal, hit.position);
    primePrimaryPayload.color = primePrimaryColor(
            section, primitive, textureId, uv, lod).rgb;
    primePrimaryPayload.hit = 1u;
}
