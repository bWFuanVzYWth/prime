#language slang 2026
module "phase/hit/world_closesthit.slang";

import glsl;
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import "service/material/normal_mapping.slang";
import "service/material/surface.slang";

import "service/trace/hit.slang";
import "contract/path.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

public void primePhase(in BuiltInTriangleIntersectionAttributes attributes)
{
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    float3 normal = hit.geometricNormal;
    bool dynamic = (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            && section.lightAddress != uint64_t(0);
    primePayload.hitKind = PRIME_HIT_SURFACE;
    primitive = primeResolveSurfacePrimitive(
            section,
            primitive,
            relation,
            barycentrics,
            hit.localPosition,
            normal);
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    uint textureId = bakedMaterial ? 0u : primePrimitiveTextureId(section, primitive);
    float textureLodValue =
            bakedMaterial
                    ? 0.0
                    : primeRayConeTextureLod(section, primitive, normal, hit.position);
    float2 materialUv = bakedMaterial
            ? float2(0.0)
            : primeInterpolateUv(
                    section, primitive, barycentrics, hit.localPosition, normal);
    PrimeMaterialSample material = primeEvaluateMaterial(
            primitive,
            textureId,
            materialUv,
            textureLodValue,
            section.instanceTint,
            normal);
    material.shadingNormal = primeNormalMappedShadingNormalForView(
            material.opticalControl,
            normal,
            -WorldRayDirection(),
            material.shadingNormal);
    primePayload.t = RayTCurrent();
    // Translation makes BLAS winding authoritative. Opaque shading orients this outward face
    // normal at the integrator boundary; transparent transport keeps its winding-defined sign.
    primePayload.geometricNormal = normal;
    primePayload.position = hit.position;
    primePayload.positionReserved = primePackSurfaceMotion(
            hit.triangleIndex, dynamic);
    primePayload.baseColor = material.baseColor;
    primePayload.materialControl = material.materialControl;
    primePayload.sectionIndex = InstanceID();
    if (dynamic) {
        // Dynamic geometry cannot emit. Preserve both hardware barycentrics as f32 in its otherwise
        // unused emitter/LOD lanes so current and previous positions use the same interpolation.
        primePayload.emitterIndex = asuint(barycentrics.x);
        primePayload.textureLod = barycentrics.y;
    } else {
        primePayload.emitterIndex = primePrimitiveEmitterIndex(primitive);
        primePayload.textureLod = textureLodValue;
    }
    primePayload.opacity = material.opacity;
    primePayload.shadingNormal = material.shadingNormal;
    primePayload.roughness = material.roughness;
    primePayload.opticalControl = material.opticalControl;
    primePayload.mediumId = primePrimitiveMediumId(primitive);
    primePayload.adjacentMediumId = 0u;
    primePayload.adjacentBaseColor = float3(0.0);
    primePayload.adjacentInterfaceControl = 0u;
    if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK)
            == PRIME_SURFACE_RELATION_BOUNDARY) {
        PrimeMaterialSample adjacent = primeEvaluateMaterialReference(
                relation.control >> 8u,
                relation.tint,
                relation.textureId,
                primeUnpackUv(relation.referenceUv));
        primePayload.adjacentBaseColor = adjacent.baseColor;
        primePayload.adjacentMediumId = relation.mediumId;
        uint adjacentFresnelCode = adjacent.opticalControl
                & PRIME_OPTICAL_FRESNEL_MASK;
        primePayload.adjacentInterfaceControl =
                (adjacent.materialControl & 0xffu)
                | adjacentFresnelCode << 8u
                | 1u << 16u
                | ((relation.control & PRIME_SURFACE_RELATION_MICRO_GAP_ELIGIBLE) != 0u
                        ? 1u << 17u
                        : 0u);
    }
}
