// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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
