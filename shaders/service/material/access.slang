#language slang 2026
module "service/material/access.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";

// Reviewed binding in shaders/abi.json. This stays with static albedo access so opacity-only
// entries do not see the independently bound normal and optical page arrays.
[[vk::binding(18, 0)]]
public ByteAddressBuffer primeTextureRecords;

// Static Minecraft albedo is translated once into renderer-owned linear Rec.2020 pages. Alpha is
// an exact 0..255 coverage code carried in f16, so filtering preserves linear coverage semantics.
[[vk::binding(51, 0)]]
public Sampler2D<float4> primeBaseColorPages[64];

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

static const uint PRIME_TEXTURE_RECORD_SIZE = 32u;
static const uint PRIME_MISSING_MATERIAL_PAGE = 0xffu;

public uint primeTextureRecordWord(uint textureId, uint byteOffset) {
    return primeTextureRecords.Load(textureId * PRIME_TEXTURE_RECORD_SIZE + byteOffset);
}

public uint primePrimitiveStaticTextureId(PrimitiveRecord primitive) {
    return (primitive.flagsEmitter >> 3u) & 0x00ffffffu;
}

public int2 primeTextureFrameExtent(uint textureId) {
    uint packed = primeTextureRecordWord(textureId, 4u);
    return int2(packed & 0xffffu, packed >> 16u);
}

public int primeTextureMaximumMip(uint textureId) {
    return int(primeTextureRecordWord(textureId, 20u) & 0xffu);
}

public uint primePrimitiveMediumId(PrimitiveRecord primitive) {
    return primeMediumId(primitive.mediumMaterialIds);
}

public uint primePrimitiveMaterialId(PrimitiveRecord primitive) {
    return primeMaterialId(primitive.mediumMaterialIds);
}

public uint primeTextureBaseColorPage(uint textureId) {
    return (primeTextureRecordWord(textureId, 20u) >> 8u) & 0xffu;
}

public float2 primeTranslatedUv(
        uint packedOrigin, int2 extent, int2 backingExtent, float2 uv) {
    float2 origin = float2(packedOrigin & 0xffffu, packedOrigin >> 16u);
    return (origin + uv * float2(extent)) / float2(max(backingExtent, int2(1)));
}

public float4 primeSampleStaticAlbedo(uint textureId, float2 uv, float lod) {
    uint page = primeTextureBaseColorPage(textureId);
    uint width;
    uint height;
    uint levels;
    primeBaseColorPages[NonUniformResourceIndex(page)].GetDimensions(
            0, width, height, levels);
    uint2 rect = primeTextureRecords.Load2(textureId * PRIME_TEXTURE_RECORD_SIZE);
    float4 sample = primeBaseColorPages[NonUniformResourceIndex(page)].SampleLevel(
            primeTranslatedUv(
                    rect.x,
                    int2(rect.y & 0xffffu, rect.y >> 16u),
                    int2(width, height),
                    uv),
            min(lod, float(primeTextureMaximumMip(textureId))));
    return float4(sample.rgb, sample.a / 255.0);
}

float4 primeLoadSceneTexture(uint index, int2 texel)
{
    return primeSceneTextures[NonUniformResourceIndex(index)].Load(int3(texel, 0));
}

public static const uint PRIME_CONSTANT_UV_DENSITY = 0x80000000u;
public static const uint PRIME_CONSTANT_UV_OWN_TINT = 1u;
public static const uint PRIME_CONSTANT_UV_BAKED_MATERIAL = 2u;
public static const uint PRIME_RECIPE_ALPHA_CUTOUT = 1u;
public static const uint PRIME_RECIPE_ANIMATED = 1u << 1u;
public static const uint PRIME_RECIPE_SCATTERING_SHIFT = 2u;
public static const uint PRIME_RECIPE_SCATTERING_MASK =
        3u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_OPAQUE = 0u;
public static const uint PRIME_RECIPE_DIELECTRIC_SOLID =
        1u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_DIELECTRIC_THIN =
        2u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_FOLIAGE_THIN =
        3u << PRIME_RECIPE_SCATTERING_SHIFT;
public static const uint PRIME_RECIPE_WATER = 1u << 4u;
public static const uint PRIME_RECIPE_TANGENT_NEGATIVE = 1u << 7u;
public static const uint PRIME_RECIPE_BUILTIN_SHIFT = 11u;
public static const uint PRIME_RECIPE_BUILTIN_MASK =
        15u << PRIME_RECIPE_BUILTIN_SHIFT;
public static const uint PRIME_RECIPE_MATERIAL_MASK =
        0xffu | PRIME_RECIPE_BUILTIN_MASK;

public uint primePrimitiveControl(PrimitiveRecord primitive) {
    return (primitive.tint >> 24u)
            | ((primitive.flagsEmitter & 3u) << 8u)
            | ((primitive.flagsEmitter >> 16u) & PRIME_RECIPE_BUILTIN_MASK);
}

public bool primeRecipeIsTransmissive(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_SOLID
            || scattering == PRIME_RECIPE_DIELECTRIC_THIN;
}

public bool primeRecipeIsThinWalled(uint control) {
    uint scattering = control & PRIME_RECIPE_SCATTERING_MASK;
    return scattering == PRIME_RECIPE_DIELECTRIC_THIN
            || scattering == PRIME_RECIPE_FOLIAGE_THIN;
}

public static const uint PRIME_DYNAMIC_TEXTURE_FLAG = 0x80000000u;
public static const uint PRIME_VISIBLE_EMISSION_FLAG = 1u << 10u;
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

public float primeEvaluateOpacity(
        PrimitiveRecord primitive, uint textureId, float2 uv) {
    if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) == 0u) {
        uint page = primeTextureBaseColorPage(textureId);
        uint packedOrigin = primeTextureRecordWord(textureId, 0u);
        int2 origin = int2(packedOrigin & 0xffffu, packedOrigin >> 16u);
        int2 extent = primeTextureFrameExtent(textureId);
        int2 texel = origin + clamp(
                int2(floor(uv * float2(extent))),
                int2(0),
                max(extent - 1, int2(0)));
        return primeBaseColorPages[NonUniformResourceIndex(page)].Load(int3(texel, 0)).a
                / 255.0;
    }
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    int2 extent = primeSceneTextureExtent(textureIndex);
    int2 texel = clamp(
            int2(floor(uv * float2(extent))),
            int2(0),
            max(extent - 1, int2(0)));
    // OMMs are baked from mip-0 alpha. Exact texel fetches keep unknown any-hit decisions and
    // known OMM states on the same coverage function, independent of ray-cone LOD and filtering.
    return primeLoadSceneTexture(textureIndex, texel).a;
}
