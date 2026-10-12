// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/canonical.slang";

public static const uint PRIME_MATERIAL_AUX = 1u;
public static const uint PRIME_MATERIAL_MERS = 2u;
public static const uint PRIME_MATERIAL_AUTHORED_EMISSION = 4u;
public static const uint PRIME_MATERIAL_ATLAS_LOOKUP = 8u;
public static const uint PRIME_MATERIAL_NORMAL = 16u;
public static const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;
public static const uint PRIME_DEFAULT_IOR_BYTE = 85u;

public float primeDecodeMaterialIor(uint value) {
    return 1.0 + 1.5 * (float(value) / 255.0);
}
public float primeDecodeMaterialIor(float value) {
    return primeDecodeMaterialIor(uint(round(saturate(value) * 255.0)));
}

public struct PrimeCanonicalNormalSample {
    public float3 tangentNormal;
    public float distributionRoughness;
};
public PrimeCanonicalNormalSample primeDecodeCanonicalNormal(float4 sampleValue) {
    PrimeCanonicalNormalSample result;
    float2 normalXY = (sampleValue.xy * 255.0 - 128.0) / 127.0;
    float normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
    result.tangentNormal = normalize(float3(normalXY, normalZ));
    result.distributionRoughness = saturate(sampleValue.b);
    return result;
}
public float primeCombineNormalRoughness(float roughness, float distributionRoughness) {
    float materialAlpha = roughness * roughness;
    float distributionAlpha = distributionRoughness * distributionRoughness;
    return sqrt(
            sqrt(min(materialAlpha * materialAlpha + distributionAlpha * distributionAlpha, 1.0)));
}
