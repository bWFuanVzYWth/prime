import "math/direct_sampling.slang";
import "math/lambert.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    uint2 pixel = uint2(index & 127u, (index >> 7u) & 127u);
    uint sampleIndex = (index >> 14u) + inputData[1];
    uint bounce = inputData[2];
    PrimeDirectSampleBase base = primeMakeDirectSampleBase(pixel, sampleIndex, inputData[3], bounce, 0u);
    float2 scatter = primeDecodeStbnSample(inputData[4u + primeStbnSampleAddress(base, 0u)]);
    float2 sun = primeDecodeStbnSample(inputData[4u + primeStbnSampleAddress(base, PRIME_DIRECT_SAMPLE_SUN)]);
    float3 direction = primeLambertDirection(float3(0.0, 0.0, 1.0), scatter);
    float3 normal = normalize(float3(-0.4, 0.9, -0.1));
    float3 rotated = primeLambertDirection(normal, scatter);
    float cosine = dot(normal, rotated);
    float3 albedo = float3(0.2, 0.5, 1.0);
    // Constant incident radiance has the analytic integral albedo * radiance.
    float3 estimate = primeLambertDirect(albedo, cosine, float3(2.0), primeLambertPdf(cosine));
    PrimeDirectSampleBase next = primeMakeDirectSampleBase(pixel, sampleIndex, inputData[3], bounce + 1u, 0u);
    float2 nextScatter = primeDecodeStbnSample(inputData[4u + primeStbnSampleAddress(next, 0u)]);
    outputData[index * 4u] = float4(scatter, sun);
    outputData[index * 4u + 1u] = float4(direction, length(rotated));
    outputData[index * 4u + 2u] = float4(estimate, cosine);
    outputData[index * 4u + 3u] = float4(nextScatter, asfloat(base.texel), asfloat(base.frame));
}
