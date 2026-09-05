import "math/lambert_roulette.slang";
import "math/sampling.slang";
import "math/hash.slang";
import "prime_lambert_contract.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    if (inputData[1] == 1u) {
        uint offset = 4u + index * 8u;
        float3 throughput = asfloat(uint3(inputData[offset], inputData[offset + 1u], inputData[offset + 2u]));
        bool alive = primeLambertRoulette(throughput, inputData[offset + 3u], asfloat(inputData[offset + 4u]));
        outputData[index * 2u] = float4(throughput, alive ? 1.0 : 0.0);
        return;
    }
    uint2 pixel = uint2(index & 127u, (index >> 7u) & 127u);
    uint frame = inputData[2] + (index >> 14u);
    uint epoch = inputData[3];
    float3 albedo = asfloat(uint3(inputData[4], inputData[5], inputData[6]));
    float3 throughput = float3(1.0);
    float3 sum = float3(0.0);
    uint vertices = 0u;
    float mappingError = 0.0;
    float first = 0.0;
    bool alive = true;
    for (uint bounce = 0u; bounce < PRIME_LAMBERT_MAXIMUM_BOUNCES; ++bounce) {
        // Current lighting precedes roulette; an escaped endpoint after twelve scatters is retained.
        sum += throughput * albedo;
        ++vertices;
        throughput *= albedo;
        float sample = primeLambertRouletteSample(pixel, frame, epoch, bounce + 1u);
        PrimePreparedSampleBase legacy = primePreparePathSample(primePathSampleSeed(pixel, epoch, 0u), frame, bounce + 1u);
        mappingError += abs(sample - primeHashSample1D(legacy, PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE, PRIME_SAMPLE_DIMENSION_PRIMARY));
        if (bounce == 0u) first = sample;
        alive = primeLambertRoulette(throughput, bounce + 1u, sample);
        if (!alive) break;
    }
    if (alive) sum += throughput;
    outputData[index * 2u] = float4(sum, float(vertices));
    outputData[index * 2u + 1u] = float4(mappingError, first,
            primeLambertRouletteSample(pixel, frame, epoch, 2u), 0.0);
}
