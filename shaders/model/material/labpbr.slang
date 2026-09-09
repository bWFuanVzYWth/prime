// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/labpbr.slang";

import "model/material/defaults.slang";

public struct PrimeLabPbrSample
{
    public float3 tangentNormal;
    public float ambientOcclusion;
    public float height;
    public float perceptualRoughness;
    public float dielectricF0;
    public uint metalId;
    public float porosity;
    public float subsurface;
    public float emission;
};

public static const uint PRIME_RECIPE_NORMAL_TEXTURE = 1 << 5;
public static const uint PRIME_RECIPE_OPTICAL_TEXTURE = 1 << 6;

public bool primeHasLabPbrNormal(uint flags) { return (flags & PRIME_RECIPE_NORMAL_TEXTURE) != 0; }
public bool primeHasLabPbrSpecular(uint flags) { return (flags & PRIME_RECIPE_OPTICAL_TEXTURE) != 0; }

public struct PrimeCanonicalOptics
{
    public float roughness;
    public uint opticalControl;
};

public struct PrimeLabPbrNormalAtlasSample
{
    public float3 tangentNormal;
    public float distributionRoughness;
};

public PrimeLabPbrNormalAtlasSample primeAdaptLabPbrNormalAtlas(float4 sourceSample)
{
    PrimeLabPbrNormalAtlasSample result;
    float2 normalXY = sourceSample.xy * 2.0 - 1.0;
    float normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
    result.tangentNormal = normalize(float3(normalXY, normalZ));
    result.distributionRoughness = clamp(sourceSample.a, 0.0, 1.0);
    return result;
}

public float primeCombineLabPbrNormalRoughness(
        float materialRoughness, float distributionRoughness)
{
    // Approximate convolution in squared-alpha space. The atlas stores perceptual roughness
    // so smooth normal mips retain useful precision in RGBA8.
    float materialAlpha = materialRoughness * materialRoughness;
    float distributionAlpha = distributionRoughness * distributionRoughness;
    float combinedAlphaSquared = min(
            materialAlpha * materialAlpha
                    + distributionAlpha * distributionAlpha,
            1.0);
    return sqrt(sqrt(combinedAlphaSquared));
}

public PrimeCanonicalOptics primeAdaptLabPbrSpecular(float4 sourceSample)
{
    uint4 bytes = uint4(round(clamp(sourceSample, 0.0, 1.0) * 255.0));
    uint fresnelCode = bytes.y < 230
            ? bytes.y + 1
            : bytes.y <= 237
                    ? bytes.y + 1
                    : bytes.y == 255 ? PRIME_FRESNEL_CUSTOM_CONDUCTOR : 0;
    uint subsurfaceCode = bytes.z >= 66 ? bytes.z - 65 : 0;
    uint porosityCode = bytes.z <= 64 ? bytes.z : 0;
    PrimeCanonicalOptics result;
    result.roughness = clamp(1.0 - float(bytes.x) / 255.0, 0.0, 1.0);
    result.opticalControl = fresnelCode
            | subsurfaceCode << PRIME_OPTICAL_SUBSURFACE_SHIFT
            | porosityCode << PRIME_OPTICAL_POROSITY_SHIFT;
    return result;
}

public float primeDecodeLabPbrEmission(uint encoded)
{
    return encoded < 255 ? float(encoded) / 254.0 : 0.0;
}

public float primeDecodeLabPbrEmission(float encodedUnorm)
{
    return primeDecodeLabPbrEmission(
            uint(round(clamp(encodedUnorm, 0.0, 1.0) * 255.0)));
}

public PrimeLabPbrSample primeDecodeLabPbr(uint packedNormal, uint packedSpecular, uint flags)
{
    uint4 normalBytes = uint4(round(unpackUnorm4x8ToFloat(packedNormal) * 255.0));
    uint4 specularBytes = uint4(round(unpackUnorm4x8ToFloat(packedSpecular) * 255.0));
    PrimeLabPbrSample result;
    float2 normalXY = float2(normalBytes.xy) * (2.0 / 255.0) - float2(1.0);
    float normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
    result.tangentNormal = normalize(float3(normalXY, normalZ));
    result.ambientOcclusion = float(normalBytes.z) / 255.0;
    result.height = float(normalBytes.w) / 255.0;

    float smoothness = float(specularBytes.x) / 255.0;
    result.perceptualRoughness = clamp(1.0 - smoothness, 0.0, 1.0);
    result.metalId = specularBytes.y >= 230 ? specularBytes.y : 0;
    result.dielectricF0 = specularBytes.y < 230
            ? float(specularBytes.y) / 255.0
            : 0.0;
    result.porosity = specularBytes.z <= 64
            ? float(specularBytes.z) / 64.0
            : 0.0;
    result.subsurface = specularBytes.z >= 65
            ? float(specularBytes.z - 65) / 190.0
            : 0.0;
    result.emission = primeDecodeLabPbrEmission(specularBytes.w);

    if (!primeHasLabPbrNormal(flags)) {
        result.tangentNormal = float3(0.0, 0.0, 1.0);
        result.ambientOcclusion = 1.0;
        result.height = 1.0;
    }
    if (!primeHasLabPbrSpecular(flags)) {
        result.perceptualRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        result.dielectricF0 = PRIME_DEFAULT_DIELECTRIC_F0;
        result.metalId = 0;
        result.porosity = 0.0;
        result.subsurface = 0.0;
        result.emission = 0.0;
    }
    return result;
}
