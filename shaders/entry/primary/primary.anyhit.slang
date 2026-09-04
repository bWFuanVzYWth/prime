#language slang 2026
module "entry/primary/primary.anyhit.slang";

import glsl;
import "prime_abi_types.slang";
import "contract/primary_color.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";

layout(location = 0) rayPayloadInEXT PrimePrimaryColorPayload primePrimaryPayload;

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    if (GeometryIndex() == 0u) {
        return;
    }
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(
            section,
            primitive,
            primePrimitiveMaterialCoreWord(primitive),
            primeSurfaceRelation(section, primitive),
            barycentrics,
            hit.localPosition,
            hit.geometricNormal);
    primitive = resolved.primitive;
    if ((resolved.recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u) {
        float opacity = primeEvaluateOpacity(
                primitive,
                primePrimitiveTextureId(
                        section, primitive, resolved.materialCoreWord),
                primeInterpolateUv(
                        section,
                        primitive,
                        barycentrics,
                        hit.localPosition,
                        hit.geometricNormal));
        if (opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
            IgnoreHit();
        }
    }
}
