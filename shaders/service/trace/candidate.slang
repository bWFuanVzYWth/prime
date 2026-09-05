#language slang 2026
module "service/trace/candidate.slang";

import glsl;
import "prime_abi_types.slang";
import "prime_material_core_abi.slang";
import "math/packed_geometry.slang";
import "service/trace/candidate_record.slang";
import "service/material/coverage.slang";
import "model/material/recipe.slang";
import "model/material/defaults.slang";
import "math/triangle_uv.slang";

public struct PrimeCandidateSurface {
    public PrimeCandidateRecord record;
    public uint core;
    public uint recipe;
    // Negative means unqueried; fallback selection invalidates the primary alpha sample.
    public float opacity;
};

uint primeCandidateCore(uint identity) {
    uint materialId = primeMaterialId(identity);
    return materialId == 0u ? 0u : primeMaterialCoreWord(materialId);
}

uint primeCandidateRecipe(uint3 header, uint core) {
    uint control = header.x >> 24u;
    if (primeMaterialId(header.y) != 0u) {
        return ((core >> PRIME_MATERIAL_CORE_RECIPE_CONTROL_SHIFT)
                & PRIME_MATERIAL_CORE_RECIPE_CONTROL_MASK)
                | (control & PRIME_RECIPE_TANGENT_NEGATIVE);
    }
    uint flags = header.z;
    return (control | ((flags & 3u) << 8u)
            | ((flags >> 16u) & PRIME_RECIPE_BUILTIN_MASK)) & PRIME_RECIPE_MATERIAL_MASK;
}

// Only repeated UV needs the BLAS vertices. No world-space hit point, triangle identity, tangent
// or full hit structure crosses the opacity lookup. Affine normals keep the full f32 arithmetic.
float2 primeCandidateRepeatedUv(uint3 uv, float2 barycentrics) {
    float3 first = HitTriangleVertexPosition(0u);
    float3 edgeOne = HitTriangleVertexPosition(1u) - first;
    float3 edgeTwo = HitTriangleVertexPosition(2u) - first;
    float3 localPosition = barycentrics.x * edgeOne + barycentrics.y * edgeTwo + first;
    bool affine = (primeCandidateSection()->instanceTint & 0x40000000u) != 0u;
    float3 normal = affine
            ? cross(mul(ObjectToWorld3x4(), float4(edgeOne, 0.0)),
                    mul(ObjectToWorld3x4(), float4(edgeTwo, 0.0)))
            : cross(edgeOne, edgeTwo);
    return primeRepeatedUv(uv, localPosition, primeTriangleUnitNormal(normal));
}

public float2 primeCandidateUv(PrimeCandidateRecord record, float2 barycentrics) {
    uint4 words = primeCandidateUvWords(record);
    if (words.w == PRIME_CONSTANT_UV_DENSITY) return float2(asfloat(words.x), asfloat(words.y));
    if (asfloat(words.w) < 0.0) return primeCandidateRepeatedUv(words.xyz, barycentrics);
    return primeTriangleUv(words.xyz, barycentrics);
}

public float3 primeCandidateNormal() {
    float3 first = HitTriangleVertexPosition(0u);
    float3 edgeOne = HitTriangleVertexPosition(1u) - first;
    float3 edgeTwo = HitTriangleVertexPosition(2u) - first;
    bool affine = (primeCandidateSection()->instanceTint & 0x40000000u) != 0u;
    float3 normal = affine
            ? cross(mul(ObjectToWorld3x4(), float4(edgeOne, 0.0)),
                    mul(ObjectToWorld3x4(), float4(edgeTwo, 0.0)))
            : cross(edgeOne, edgeTwo);
    return primeTriangleUnitNormal(normal);
}

uint primeCandidateTextureId(uint identity, uint flags, uint core) {
    if (primeMaterialId(identity) != 0u) return core & PRIME_MATERIAL_CORE_TEXTURE_ID_MASK;
    if ((flags & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u) return 0u;
    if ((flags & 4u) == 0u) return flags >> 3u & 0x00ffffffu;
    uint encoded = flags >> 3u & 0x00ffffffu;
    uint64_t address = primeCandidateSection()->lightAddress;
    if (address == uint64_t(0) || encoded == 0u) return 0u;
    SectionLightHeader* header = (SectionLightHeader*)address;
    if (encoded - 1u >= header->emitterCount) return 0u;
    return ((LightEmitter*)header->emitterAddress)[encoded - 1u].metadata.w;
}

public uint primeCandidateTextureId(PrimeCandidateRecord record, uint core) {
    uint3 header = primeCandidateHeader(record);
    return primeCandidateTextureId(header.y, header.z, core);
}

public float primeCandidateOpacity(PrimeCandidateRecord record, uint core, float2 barycentrics) {
    // Finish geometry before loading texture metadata. The two resource paths have narrow,
    // separate signatures and never receive a full PrimitiveRecord or hit structure.
    float2 uv = primeCandidateUv(record, barycentrics);
    uint3 header = primeCandidateHeader(record);
    uint flags = header.z;
    if ((flags & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u) {
        return primeDynamicOpacity(min((flags >> 3u) & 0x3fu, PRIME_SCENE_TEXTURE_COUNT - 1u), uv);
    }
    return primeStaticOpacity(primeCandidateTextureId(header.y, flags, core), uv);
}

public uint64_t primeCandidateRelation(uint3 headerWords) {
    uint64_t address = primeCandidateSection()->surfaceRelationAddress;
    if (address == uint64_t(0) || primeMaterialId(headerWords.y) == 0u) {
        return uint64_t(0);
    }
    uint encodedOffset;
    uint encodedEmitter = (headerWords.z & 4u) != 0u ? (headerWords.z >> 3u & 0x00ffffffu) : 0u;
    uint emitterIndex = encodedEmitter - 1u;
    if (emitterIndex != 0xffffffffu) {
        uint64_t lightAddress = primeCandidateSection()->lightAddress;
        if (lightAddress == uint64_t(0)) return uint64_t(0);
        SectionLightHeader* header = (SectionLightHeader*)lightAddress;
        if (emitterIndex >= header->emitterCount) return uint64_t(0);
        encodedOffset = ((LightEmitter*)header->emitterAddress)[emitterIndex].relationOffset;
    } else {
        encodedOffset = headerWords.z >> 3u & 0x00ffffffu;
    }
    return encodedOffset == 0u ? uint64_t(0) : address + uint64_t(encodedOffset - 1u) * 4u;
}

public PrimeCandidateSurface primeCandidateSurface(
        PrimeCandidateRecord record, uint3 header, uint64_t relation, float2 barycentrics) {
    PrimeCandidateSurface result;
    result.record = record;
    result.opacity = -1.0;
    // Read only the discriminator. Fallback UVs, tint, core and tangent are not live during
    // the primary coverage test; the fallback record is addressed only after it is selected.
    uint control = relation == uint64_t(0) ? 0u : ((uint*)relation)[0];
    uint kind = control & 0xfu;
    bool backFacing = HitKind() == HIT_KIND_TRIANGLE_BACK_FACE;
    bool fallback = kind == 3u && backFacing;
    bool covered = false;
    result.core = primeCandidateCore(header.y);
    if (kind == 2u) {
        fallback = (control & (1u << 4u)) != 0u && backFacing;
        if (!fallback) {
            result.opacity = primeCandidateOpacity(result.record, result.core, barycentrics);
            covered = result.opacity >= PRIME_CUTOUT_ALPHA_THRESHOLD;
            fallback = !covered;
        }
    }
    if (fallback) {
        result.record.address = relation;
        result.record.index = 0u;
        result.record.kind = 2u;
        result.opacity = -1.0;
        header = primeCandidateHeader(result.record);
        result.core = primeCandidateCore(header.y);
    }
    result.recipe = covered ? (control >> 8u & PRIME_RECIPE_MATERIAL_MASK)
            : primeCandidateRecipe(header, result.core);
    return result;
}

public PrimeCandidateSurface primeCandidateSurface(float2 barycentrics) {
    PrimeCandidateRecord record = primeCandidateRecord();
    uint3 header = primeCandidateHeader(record);
    return primeCandidateSurface(record, header, primeCandidateRelation(header), barycentrics);
}

public bool primeCandidateCutout(PrimeCandidateSurface surface, float2 barycentrics) {
    if ((surface.recipe & PRIME_RECIPE_ALPHA_CUTOUT) == 0u) return false;
    if (surface.opacity >= 0.0) return surface.opacity < PRIME_CUTOUT_ALPHA_THRESHOLD;
    return primeCandidateOpacity(surface.record, surface.core, barycentrics) < PRIME_CUTOUT_ALPHA_THRESHOLD;
}
