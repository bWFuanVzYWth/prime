import "prime_coordinate_contract.slang";
import "prime_color_contract.slang";

layout(set = 0, binding = 0, std430) readonly buffer PrimeRendererDataBenchmarkInput {
    uint words[];
} primeRendererDataBenchmarkInput;

layout(set = 0, binding = 1, std430) writeonly buffer PrimeRendererDataBenchmarkOutput {
    float4 values[];
} primeRendererDataBenchmarkOutput;

groupshared float4 primeRendererDataBenchmarkGroup[64];

[numthreads(64, 1, 1)]
void main(
        uint3 dispatchThreadId : SV_DispatchThreadID,
        uint3 groupThreadId : SV_GroupThreadID)
{
    uint width = primeRendererDataBenchmarkInput.words[0];
    uint height = primeRendererDataBenchmarkInput.words[1];
    uint index = dispatchThreadId.x;
    float4 value = float4(0.0);
    if (index < width * height) {
        uint2 pixel = uint2(index % width, index / width);
        float2 sampleJitter = float2(
                asfloat(primeRendererDataBenchmarkInput.words[2]),
                asfloat(primeRendererDataBenchmarkInput.words[3]));
        float2 previousUv = float2(
                asfloat(primeRendererDataBenchmarkInput.words[4]),
                asfloat(primeRendererDataBenchmarkInput.words[5]));
        float3 encodedSrgb = float3(
                asfloat(primeRendererDataBenchmarkInput.words[6]),
                asfloat(primeRendererDataBenchmarkInput.words[7]),
                asfloat(primeRendererDataBenchmarkInput.words[8]));
        float2 sampleUv = primeCanonicalSampleUv(pixel, uint2(width, height));
        float2 clip = primeCanonicalUvToClip(sampleUv);
        float2 motion = primeCanonicalVisibleMotionUv(previousUv, sampleUv);
        float2 projectionJitter = primeCanonicalProjectionJitterPixels(sampleJitter);
        float3 linearSrgb = float3(
                primeCanonicalDecodeSrgb(encodedSrgb.x),
                primeCanonicalDecodeSrgb(encodedSrgb.y),
                primeCanonicalDecodeSrgb(encodedSrgb.z));
        float3 rec2020 = primeCanonicalLinearSrgbToLinearRec2020(linearSrgb);
        value = float4(
                rec2020.xy + clip * 0.000001,
                rec2020.z + projectionJitter.x * 0.000001,
                motion.x + motion.y + projectionJitter.y * 0.000001);
    }
    primeRendererDataBenchmarkGroup[groupThreadId.x] = value;
    GroupMemoryBarrierWithGroupSync();
    if (groupThreadId.x == 0) {
        float4 sum = float4(0.0);
        for (uint lane = 0; lane < 64; lane++) {
            sum += primeRendererDataBenchmarkGroup[lane];
        }
        primeRendererDataBenchmarkOutput.values[index / 64] = sum;
    }
}
