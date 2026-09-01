#language slang 2026
module "service/material/auxiliary_access.slang";

import "service/material/access.slang";

// Only full material evaluation imports these arrays; opacity entries cannot see them.
[[vk::binding(19, 0)]]
public Sampler2D<float4> primeMaterialNormalPages[16];
[[vk::binding(49, 0)]]
public Sampler2D<float4> primeMaterialOpticalPages[16];

public float4 primeSampleMaterialNormal(uint textureId, float2 uv, float lod) {
    uint2 channel = primeTextureRecords.Load2(
            textureId * PRIME_TEXTURE_RECORD_SIZE + 12u);
    uint pages = channel.y;
    uint page = pages & 0xffu;
    uint width;
    uint height;
    uint levels;
    primeMaterialNormalPages[NonUniformResourceIndex(page)].GetDimensions(
            0, width, height, levels);
    float2 translated = primeTranslatedUv(
            channel.x,
            primeTextureFrameExtent(textureId),
            int2(width, height),
            uv);
    return primeMaterialNormalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float((pages >> 16u) & 0xffu)));
}

public float4 primeSampleMaterialOptical(uint textureId, float2 uv, float lod) {
    uint2 channel = primeTextureRecords.Load2(
            textureId * PRIME_TEXTURE_RECORD_SIZE + 16u);
    uint pages = channel.x;
    uint page = (pages >> 8u) & 0xffu;
    uint width;
    uint height;
    uint levels;
    primeMaterialOpticalPages[NonUniformResourceIndex(page)].GetDimensions(
            0, width, height, levels);
    uint packedOrigin = channel.y;
    int2 extent = primeTextureFrameExtent(textureId);
    float2 translated = primeTranslatedUv(
            packedOrigin,
            extent,
            int2(width, height),
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
