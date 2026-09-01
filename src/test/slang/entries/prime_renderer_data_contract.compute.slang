import "prime_coordinate_contract.slang";
import "prime_color_contract.slang";
import "prime_material_core_abi.slang";
import "math/packed_geometry.slang";
import "service/material/tint.slang";

layout(set = 0, binding = 0, std430) readonly buffer PrimeRendererDataInput {
    uint words[];
} primeRendererDataInput;

layout(set = 0, binding = 1, std430) writeonly buffer PrimeRendererDataOutput {
    float4 values[];
} primeRendererDataOutput;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = uint2(primeRendererDataInput.words[0], primeRendererDataInput.words[1]);
    uint2 extent = uint2(primeRendererDataInput.words[2], primeRendererDataInput.words[3]);
    float2 sampleJitter = float2(
            asfloat(primeRendererDataInput.words[4]),
            asfloat(primeRendererDataInput.words[5]));
    float2 previousUv = float2(
            asfloat(primeRendererDataInput.words[6]),
            asfloat(primeRendererDataInput.words[7]));
    float2 currentUv = float2(
            asfloat(primeRendererDataInput.words[8]),
            asfloat(primeRendererDataInput.words[9]));
    float3 encodedSrgb = float3(
            asfloat(primeRendererDataInput.words[10]),
            asfloat(primeRendererDataInput.words[11]),
            asfloat(primeRendererDataInput.words[12]));
    PrimeLinearTintModulation tint = primeUnpackLinearTintModulation(
            uint2(primeRendererDataInput.words[13], primeRendererDataInput.words[14]));
    float3 canonicalBase = float3(
            asfloat(primeRendererDataInput.words[25]),
            asfloat(primeRendererDataInput.words[26]),
            asfloat(primeRendererDataInput.words[27]));

    float2 sampleUv = primeCanonicalSampleUv(pixel, extent);
    primeRendererDataOutput.values[0] = float4(
            sampleUv, primeCanonicalUvToClip(sampleUv));
    primeRendererDataOutput.values[1] = float4(
            primeCanonicalProjectionJitterPixels(sampleJitter),
            primeCanonicalVisibleMotionUv(previousUv, currentUv));
    float3 linearSrgb = float3(
            primeCanonicalDecodeSrgb(encodedSrgb.x),
            primeCanonicalDecodeSrgb(encodedSrgb.y),
            primeCanonicalDecodeSrgb(encodedSrgb.z));
    primeRendererDataOutput.values[2] = float4(
            primeCanonicalLinearSrgbToLinearRec2020(linearSrgb), 1.0);
    primeRendererDataOutput.values[3] = primeApplyStaticTint(
            float4(canonicalBase, 0.75), tint);
    uint packedMaterialIdentity = primeRendererDataInput.words[28];
    uint packedMaterialCore = primeRendererDataInput.words[29];
    primeRendererDataOutput.values[4] = float4(
            float(primeMediumId(packedMaterialIdentity)),
            float(primeMaterialId(packedMaterialIdentity)),
            float(packedMaterialCore & PRIME_MATERIAL_CORE_TEXTURE_ID_MASK),
            float((packedMaterialCore >> PRIME_MATERIAL_CORE_RECIPE_CONTROL_SHIFT)
                    & PRIME_MATERIAL_CORE_RECIPE_CONTROL_MASK));
    PrimeLinearTintModulation continuousTint = primeUnpackLinearTintModulation(
            uint2(primeRendererDataInput.words[30], primeRendererDataInput.words[31]));
    primeRendererDataOutput.values[5] = primeApplyLinearTintModulation(
            float4(canonicalBase, 0.75), continuousTint);
    primeRendererDataOutput.values[6] = float4(
            primeInlineSrgbTintColor(encodedSrgb), 1.0);
}
