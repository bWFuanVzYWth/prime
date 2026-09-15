// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/labpbr.slang";

import "model/material/defaults.slang";

public static const uint PRIME_RECIPE_NORMAL_TEXTURE = 1 << 5;
public static const uint PRIME_RECIPE_OPTICAL_TEXTURE = 1 << 6;

public bool primeHasLabPbrNormal(uint flags) { return (flags & PRIME_RECIPE_NORMAL_TEXTURE) != 0; }
public bool primeHasLabPbrSpecular(uint flags) { return (flags & PRIME_RECIPE_OPTICAL_TEXTURE) != 0; }

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

public float primeDecodeLabPbrEmission(uint encoded)
{
    return encoded < 255 ? float(encoded) / 254.0 : 0.0;
}

public float primeDecodeLabPbrEmission(float encodedUnorm)
{
    return primeDecodeLabPbrEmission(
            uint(round(clamp(encodedUnorm, 0.0, 1.0) * 255.0)));
}
