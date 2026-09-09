// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/world.closesthit.slang";
import "prime_abi_types.slang";
import "model/material/recipe.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import glsl;
import "contract/lambert.slang";
import "contract/path.slang";
import "service/trace/hit.slang";
import "service/trace/identity.slang";
import "service/material/primary_color.slang";
import "service/material/lambert.slang";
import "service/light/emission.slang";
import "policy/runtime/config.slang";
import "math/packed_geometry.slang";
layout(location = 0) rayPayloadInEXT PrimeLambertPayload primeLambertPayload;
[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section, primitive);
    PrimeResolvedSurface resolved = primeResolveSurfacePrimitive(section, primitive,
            primePrimitiveMaterialCoreWord(primitive), relation,
            barycentrics, hit.localPosition, hit.geometricNormal);
    primitive = resolved.primitive;
    bool baked = primeUsesBakedMaterial(primitive);
    uint textureId = baked ? 0u : primePrimitiveTextureId(section, primitive, resolved.materialCoreWord);
    float2 uv = baked ? float2(0.0) : primeInterpolateUv(section, primitive,
            barycentrics, hit.localPosition, hit.geometricNormal);
    float lod = baked ? 0.0 : primeRayConeTextureLod(primitive, textureId, hit.geometricNormal, hit.position);
    PrimeLambertMaterial material = primeLambertMaterial(section, primitive, resolved.recipeControl,
            textureId, uv, lod, dot(hit.geometricNormal, WorldRayDirection()), relation);
    primeLambertPayload.albedo = material.color;
    primeLambertPayload.auxiliary = material.adjacent;
    primeLambertPayload.media = material.media;
    primeLambertPayload.flags = material.flags;
    if ((material.flags & PRIME_LAMBERT_DELTA) == 0u && primeLambertPayload.motion != 2u) {
        uint emitter = primePrimitiveEmitterIndex(primitive);
        if (emitter != PRIME_NO_PRIMITIVE_INDEX) {
            primeLambertPayload.flags |= PRIME_LAMBERT_EMITTER;
            primeLambertPayload.auxiliary = primeEvaluateHitEmitterRadiance(
                    InstanceID(), emitter, hit.position, WorldRayDirection());
        } else if ((primitive.flagsEmitter & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG))
                == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_VISIBLE_EMISSION_FLAG)) {
            primeLambertPayload.auxiliary = material.color * PRIME_LEVEL_15_BLOCK_INTENSITY
                    * primeBlockLightRadianceMultiplier();
        }
    }
    bool motion = primeLambertPayload.motion != 0u
            && (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            && section.lightAddress != uint64_t(0);
    primeLambertPayload.previousPosition = hit.position;
    primeLambertPayload.previousNormal = hit.geometricNormal;
    if (motion) {
        PrimePreviousDynamicSurface previous = primePreviousDynamicSurface(section, hit.triangleIndex, barycentrics);
        primeLambertPayload.previousPosition = previous.position;
        primeLambertPayload.previousNormal = previous.geometricNormal;
    }
    primeLambertPayload.motion = uint(motion);
    primeLambertPayload.position = hit.position;
    primeLambertPayload.normal = dot(hit.geometricNormal, WorldRayDirection()) > 0.0
            ? -hit.geometricNormal : hit.geometricNormal;
    primeLambertPayload.distance = RayTCurrent();
    primeLambertPayload.section = InstanceID();
    primeLambertPayload.triangle = hit.triangleIndex;
}
