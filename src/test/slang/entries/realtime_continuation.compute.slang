import "policy/realtime/continuation.slang";
import "policy/transport/path.slang";
import "math/transport.slang";
import "math/hash.slang";
import "math/sampling.slang";
import "prime_abi_types.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    if (inputData[1] == 0u) {
        uint o = 4u + index * 4u;
        uint minimum = inputData[o], maximum = inputData[o + 1u], secondary = inputData[o + 2u];
        outputData[index] = float4(primeRealtimeBounceLimit(minimum, maximum),
                primeRealtimeCanContinue(secondary, minimum, maximum) ? 1.0 : 0.0,
                primeRealtimeNeedsRoulette(secondary, minimum, maximum) ? 1.0 : 0.0,
                primeRealtimeCompletedBounceCount(secondary));
        return;
    }
    uint minimum = inputData[2], maximum = inputData[3];
    float albedo = asfloat(inputData[4]);
    uint seed = primePathSampleSeed(uint2(index & 1023u, index >> 10u), 7u, index & 1u);
    float3 throughput = float3(1.0);
    float sum = 0.0;
    uint completed = 0u, roulettes = 0u;
    for (uint secondary = 0u; secondary < primeRealtimeBounceLimit(minimum, maximum); ++secondary) {
        sum += throughput.x;
        throughput *= albedo;
        ++completed;
        if (!primeRealtimeCanContinue(secondary, minimum, maximum)) break;
        if (primeRealtimeNeedsRoulette(secondary, minimum, maximum)) {
            ++roulettes;
            PrimePreparedSampleBase sample = primePreparePathSample(seed, 91u, secondary);
            float survival = primeRussianRouletteSurvival(throughput, 1.0);
            if (primeHashSample1D(sample, PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                    PRIME_SAMPLE_DIMENSION_PRIMARY) >= survival) break;
            throughput = primeRussianRouletteReweight(throughput, survival);
        }
    }
    outputData[index] = float4(sum, completed, roulettes, 0.0);
}
