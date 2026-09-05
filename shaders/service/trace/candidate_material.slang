#language slang 2026
module "service/trace/candidate_material.slang";

import "service/trace/candidate_record.slang";
import "service/material/medium_access.slang";
import "math/packed_geometry.slang";
import "model/material/recipe.slang";

public float2 primeCandidateReferenceUv(PrimeCandidateRecord record) {
    uint4 uv = primeCandidateUvWords(record);
    if (uv.w == PRIME_CONSTANT_UV_DENSITY) return float2(asfloat(uv.x), asfloat(uv.y));
    float2 first = primeUnpackUv(uv.x);
    float2 second = primeUnpackUv(uv.y);
    float2 third = primeUnpackUv(uv.z);
    return 0.5 * (min(first, min(second, third)) + max(first, max(second, third)));
}

public uint primeCandidateMaterialTint(PrimeCandidateRecord record) {
    uint3 header = primeCandidateHeader(record);
    if (record.kind == 2u || primeMaterialId(header.y) != 0u) return header.x;
    uint instanceTint = primeCandidateSection()->instanceTint;
    if ((instanceTint & 0x80000000u) == 0u) return header.x;
    uint4 uv = primeCandidateUvWords(record);
    bool baked = uv.w == PRIME_CONSTANT_UV_DENSITY && (uv.z & PRIME_CONSTANT_UV_BAKED_MATERIAL) != 0u;
    return baked ? header.x : (header.x & 0xff000000u) | (instanceTint & 0x00ffffffu);
}

public uint primeCandidateMediumId(PrimeCandidateRecord record) {
    uint identity = primeCandidateIdentity(record);
    uint materialId = primeMaterialId(identity);
    return materialId == 0u ? primeMediumId(identity) : primeMaterialMediumId(materialId);
}
