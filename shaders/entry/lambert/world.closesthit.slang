#language slang 2026
module "entry/lambert/world.closesthit.slang";
import "prime_abi_types.slang";
import "model/material/recipe.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import glsl;
import "contract/lambert.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";
import "service/material/primary_color.slang";
import "math/packed_geometry.slang";
layout(location = 0) rayPayloadInEXT PrimeLambertPayload primeLambertPayload;
[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(section, primitive,
            primePrimitiveMaterialCoreWord(primitive), primeSurfaceRelation(section, primitive),
            barycentrics, hit.localPosition, hit.geometricNormal);
    primitive = resolved.primitive;
    bool baked = primeUsesBakedMaterial(primitive);
    uint textureId = baked ? 0u : primePrimitiveTextureId(section, primitive, resolved.materialCoreWord);
    float2 uv = baked ? float2(0.0) : primeInterpolateUv(section, primitive,
            barycentrics, hit.localPosition, hit.geometricNormal);
    float lod = baked ? 0.0 : primeRayConeTextureLod(primitive, textureId, hit.geometricNormal, hit.position);
    primeLambertPayload.albedo = clamp(primePrimaryColor(section, primitive, textureId, uv, lod).rgb,
            float3(0.0), float3(1.0));
    bool motion = primeLambertPayload.motion != 0u
            && (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            && section.lightAddress != uint64_t(0);
    primeLambertPayload.previousPosition = motion
            ? primePreviousDynamicSurface(section, hit.triangleIndex, barycentrics).position
            : hit.position;
    primeLambertPayload.motion = uint(motion);
    primeLambertPayload.position = hit.position;
    primeLambertPayload.normal = dot(hit.geometricNormal, WorldRayDirection()) > 0.0
            ? -hit.geometricNormal : hit.geometricNormal;
    primeLambertPayload.distance = RayTCurrent();
    primeLambertPayload.section = InstanceID();
    primeLambertPayload.triangle = hit.triangleIndex;
}
