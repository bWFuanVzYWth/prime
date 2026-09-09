// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/material/coverage.slang";

import "prime_abi.slang";
import "prime_material_core_abi.slang";

[[vk::binding(18, 0)]] public ByteAddressBuffer primeTextureRecords;
[[vk::binding(52, 0)]] public ByteAddressBuffer primeMaterialCoreRecords;
// Static pages carry linear Rec.2020 RGB and exact 0..255 f16 coverage codes.
[[vk::binding(51, 0)]] public Sampler2D<float4> primeBaseColorPages[64];

public static const uint PRIME_TEXTURE_RECORD_SIZE = 32u;

public uint primeTextureRecordWord(uint textureId, uint byteOffset) {
    return primeTextureRecords.Load(textureId * PRIME_TEXTURE_RECORD_SIZE + byteOffset);
}

public uint primeMaterialCoreWord(uint materialId) {
    return primeMaterialCoreRecords.Load(materialId * PRIME_MATERIAL_CORE_RECORD_SIZE);
}

public float primeStaticOpacity(uint textureId, float2 uv) {
    uint3 rect = primeTextureRecords.Load3(textureId * PRIME_TEXTURE_RECORD_SIZE);
    uint page = rect.z >> 8u & 0xffu;
    int2 origin = int2(rect.x & 0xffffu, rect.x >> 16u);
    int2 extent = int2(rect.y & 0xffffu, rect.y >> 16u);
    int2 texel = origin + clamp(int2(floor(uv * float2(extent))),
            int2(0), max(extent - 1, int2(0)));
    return primeBaseColorPages[NonUniformResourceIndex(page)].Load(int3(texel, 0)).a / 255.0;
}

public float primeDynamicOpacity(uint textureIndex, float2 uv) {
    uint width;
    uint height;
    uint levels;
    primeSceneTextures[NonUniformResourceIndex(textureIndex)].GetDimensions(
            0, width, height, levels);
    int2 extent = int2(width, height);
    int2 texel = clamp(int2(floor(uv * float2(extent))),
            int2(0), max(extent - 1, int2(0)));
    // Exact mip-0 alpha is the same coverage function used when constructing OMMs.
    return primeSceneTextures[NonUniformResourceIndex(textureIndex)].Load(int3(texel, 0)).a;
}
