#language slang 2026
module "service/material/access.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_material_core_abi.slang";
import "math/packed_geometry.slang";

import "service/material/coverage.slang";
import "model/material/recipe.slang";

public float4 primeSampleSceneTexture(uint index, float2 uv, float lod)
{
    return primeSceneTextures[NonUniformResourceIndex(index)].SampleLevel(uv, lod);
}

public struct PrimeSceneTextureDimensions {
    public int2 extent;
    public int mipLevels;
};

public PrimeSceneTextureDimensions primeSceneTextureDimensions(uint index) {
    uint width;
    uint height;
    uint levels;
    primeSceneTextures[NonUniformResourceIndex(index)].GetDimensions(
            0, width, height, levels);
    PrimeSceneTextureDimensions result;
    result.extent = int2(width, height);
    result.mipLevels = int(levels);
    return result;
}

public int2 primeSceneTextureExtent(uint index) {
    return primeSceneTextureDimensions(index).extent;
}

static const uint PRIME_MISSING_MATERIAL_PAGE = 0xffu;
public static const uint PRIME_TEXTURE_AUXILIARY_EXTENT_OFFSET = 24u;

public uint primePrimitiveStaticTextureId(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter >> 3u) & 0x00ffffffu;
}

public int2 primeTextureFrameExtent(uint textureId) {
    uint packed = primeTextureRecordWord(textureId, 4u);
    return int2(packed & 0xffffu, packed >> 16u);
}

public int primeTextureMaximumMip(uint textureId) {
    return int(primeTextureRecordWord(textureId, 8u) & 0xffu);
}

public uint primePrimitiveMaterialId(PrimitiveRecord primitive) {
    return primeMaterialId(primitive.identity);
}

public uint primePrimitiveTintId(PrimitiveRecord primitive) {
    return primePrimitiveMaterialId(primitive) == 0u
            ? primitive.tint & 0xffffu
            : primitive.identity & 0xffffu;
}

public float2 primeTranslatedUv(
        uint packedOrigin, int2 extent, int2 backingExtent, float2 uv) {
    float2 origin = float2(packedOrigin & 0xffffu, packedOrigin >> 16u);
    return (origin + uv * float2(extent)) / float2(backingExtent);
}

public struct PrimeBaseTextureRecord {
    public uint3 words;
};

public PrimeBaseTextureRecord primeLoadBaseTextureRecord(uint textureId) {
    PrimeBaseTextureRecord record;
    record.words = primeTextureRecords.Load3(
            textureId * PRIME_TEXTURE_RECORD_SIZE);
    return record;
}

public float4 primeSampleStaticAlbedo(
        PrimeBaseTextureRecord record, float2 uv, float lod) {
    uint info = record.words.z;
    uint page = info >> 8u & 0xffu;
    uint extentCode = info >> 16u;
    int2 backingExtent;
    if (!primeDecodeTexturePageExtent(extentCode, backingExtent)) {
        uint width;
        uint height;
        uint levels;
        primeBaseColorPages[NonUniformResourceIndex(page)].GetDimensions(
                0, width, height, levels);
        backingExtent = int2(width, height);
    }
    float4 sample = primeBaseColorPages[NonUniformResourceIndex(page)].SampleLevel(
            primeTranslatedUv(
                    record.words.x,
                    int2(record.words.y & 0xffffu, record.words.y >> 16u),
                    backingExtent,
                    uv),
            min(lod, float(info & 0xffu)));
    return float4(sample.rgb, sample.a / 255.0);
}

public float4 primeSampleStaticAlbedo(uint textureId, float2 uv, float lod) {
    return primeSampleStaticAlbedo(
            primeLoadBaseTextureRecord(textureId), uv, lod);
}

public uint primePrimitiveControl(PrimitiveRecord primitive) {
    return (primitive.tint >> 24u)
            | ((primitive.flagsEmitter & 3u) << 8u)
            | ((primitive.flagsEmitter >> 16u) & PRIME_RECIPE_BUILTIN_MASK);
}

static const uint PRIME_DYNAMIC_RED_ALPHA_FLAG = 1u << 9u;
static const uint PRIME_DYNAMIC_TEXTURE_INDEX_MASK = 0x3fu;

public uint primePrimitiveTextureIndex(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            ? ((primitive.flagsEmitter >> 3u) & PRIME_DYNAMIC_TEXTURE_INDEX_MASK)
            : 0u;
}

public bool primeUsesDynamicRedAlpha(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter
            & (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG))
            == (PRIME_DYNAMIC_TEXTURE_FLAG | PRIME_DYNAMIC_RED_ALPHA_FLAG);
}

public float4 primeSamplePrimitiveTexture(
        PrimitiveRecord primitive,
        uint textureId,
        float2 uv,
        float textureLodValue) {
    if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) == 0u) {
        return primeSampleStaticAlbedo(textureId, uv, textureLodValue);
    }
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    return primeSampleSceneTexture(textureIndex, uv, textureLodValue);
}

public bool primeUsesBakedMaterial(PrimitiveRecord primitive) {
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY
            && (primitive.uv2 & PRIME_CONSTANT_UV_BAKED_MATERIAL) != 0u;
}

public float2 primePrimitiveMaterialReferenceUv(PrimitiveRecord primitive) {
    if (primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY) {
        return float2(asfloat(primitive.uv0), asfloat(primitive.uv1));
    }
    float2 first = primeUnpackUv(primitive.uv0);
    float2 second = primeUnpackUv(primitive.uv1);
    float2 third = primeUnpackUv(primitive.uv2);
    return 0.5 * (min(first, min(second, third)) + max(first, max(second, third)));
}

public float primeEvaluateOpacity(PrimitiveRecord primitive, uint textureId, float2 uv) {
    if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) == 0u) {
        return primeStaticOpacity(textureId, uv);
    }
    return primeDynamicOpacity(min(primePrimitiveTextureIndex(primitive),
            PRIME_SCENE_TEXTURE_COUNT - 1u), uv);
}
