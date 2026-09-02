#language slang 2026
module "service/material/auxiliary_access.slang";

import "service/material/access.slang";
import "math/packed_geometry.slang";

// Only full material evaluation imports these arrays; opacity entries cannot see them.
[[vk::binding(19, 0)]]
public Sampler2D<float4> primeMaterialNormalPages[16];
[[vk::binding(49, 0)]]
public Sampler2D<float4> primeMaterialOpticalPages[16];

public struct PrimeAuxiliaryTextureRecord {
    public uint4 words;
};

public PrimeAuxiliaryTextureRecord primeLoadAuxiliaryTextureRecord(uint textureId) {
    PrimeAuxiliaryTextureRecord record;
    record.words = primeTextureRecords.Load4(
            textureId * PRIME_TEXTURE_RECORD_SIZE + 12u);
    return record;
}

float4 primeSampleMaterialNormalRecord(
        uint packedOrigin,
        uint pages,
        uint extentCode,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    uint page = pages & 0xffu;
    int2 backingExtent;
    if (!primeDecodeTexturePageExtent(extentCode, backingExtent)) {
        uint width;
        uint height;
        uint levels;
        primeMaterialNormalPages[NonUniformResourceIndex(page)].GetDimensions(
                0, width, height, levels);
        backingExtent = int2(width, height);
    }
    float2 translated = primeTranslatedUv(
            packedOrigin,
            int2(packedFrameExtent & 0xffffu, packedFrameExtent >> 16u),
            backingExtent,
            uv);
    return primeMaterialNormalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float((pages >> 16u) & 0xffu)));
}

public float4 primeSampleMaterialNormal(
        PrimeAuxiliaryTextureRecord record,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    return primeSampleMaterialNormalRecord(
            record.words.x,
            record.words.y,
            record.words.w & 0xffffu,
            packedFrameExtent,
            uv,
            lod);
}

public float4 primeSampleMaterialNormal(
        uint textureId,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    uint2 channel = primeTextureRecords.Load2(
            textureId * PRIME_TEXTURE_RECORD_SIZE + 12u);
    uint extentCodes = primeTextureRecordWord(
            textureId, PRIME_TEXTURE_AUXILIARY_EXTENT_OFFSET);
    return primeSampleMaterialNormalRecord(
            channel.x,
            channel.y,
            extentCodes & 0xffffu,
            packedFrameExtent,
            uv,
            lod);
}

float4 primeSampleMaterialOpticalRecord(
        uint pages,
        uint packedOrigin,
        uint extentCode,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    uint page = (pages >> 8u) & 0xffu;
    int2 backingExtent;
    if (!primeDecodeTexturePageExtent(extentCode, backingExtent)) {
        uint width;
        uint height;
        uint levels;
        primeMaterialOpticalPages[NonUniformResourceIndex(page)].GetDimensions(
                0, width, height, levels);
        backingExtent = int2(width, height);
    }
    int2 extent = int2(
            packedFrameExtent & 0xffffu,
            packedFrameExtent >> 16u);
    float2 translated = primeTranslatedUv(
            packedOrigin,
            extent,
            backingExtent,
            uv);
    float4 filtered = primeMaterialOpticalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float((pages >> 24u) & 0xffu)));
    int2 origin = int2(packedOrigin & 0xffffu, packedOrigin >> 16u);
    int2 texel = origin + clamp(
            int2(floor(uv * float2(extent))),
            int2(0),
            max(extent - 1, int2(0)));
    float4 categorical = primeMaterialOpticalPages[NonUniformResourceIndex(page)].Load(
            int3(texel, 0));
    return float4(filtered.r, categorical.g, categorical.b, filtered.a);
}

public float4 primeSampleMaterialOptical(
        PrimeAuxiliaryTextureRecord record,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    return primeSampleMaterialOpticalRecord(
            record.words.y,
            record.words.z,
            record.words.w >> 16u,
            packedFrameExtent,
            uv,
            lod);
}

public float4 primeSampleMaterialOptical(
        uint textureId,
        uint packedFrameExtent,
        float2 uv,
        float lod) {
    uint3 channel = primeTextureRecords.Load3(
            textureId * PRIME_TEXTURE_RECORD_SIZE + 16u);
    return primeSampleMaterialOpticalRecord(
            channel.x,
            channel.y,
            channel.z >> 16u,
            packedFrameExtent,
            uv,
            lod);
}
