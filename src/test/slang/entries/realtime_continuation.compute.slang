// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "policy/realtime/continuation.slang";
import "policy/transport/path.slang";
import "math/transport.slang";
import "math/hash.slang";
import "math/sampling.slang";
import "prime_abi_types.slang";
import "state/mis.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/secondary_transition.slang";
import "state/realtime/transport.slang";
import "model/realtime/transport.slang";
import "contract/medium.slang";
import "contract/path.slang";

[[vk::binding(100, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(101, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    if (inputData[1] == 2u) {
        uint o = 4u + index * 4u;
        PathState path = {};
        path.sourcePrimitive = uint2(inputData[o], inputData[o + 1u]);
        path.control = primeMakePathStateControl(inputData[o + 2u], inputData[o + 3u]);
        path.previousBsdfPdf = 0.25;
        path.physicalOrigin = float3(13.0, -19.0, 0.5);
        path.previousLightNormal = float3(0.0, 1.0, 0.0);
        path.etaScale = 1.0;
        bool before = primePreviousCannotUseMis(path);
        primeEnterSecondaryAfterNee(path);
        primeEnterSecondaryAfterNee(path);
        PrimeWavefrontTransportRecord record = primeMakeWavefrontTransportRecord(
                path, primeVacuumMedium(), primeInitialDenoiserState(), 0u, true);
        record = primeEnterSecondaryRecord(record);
        uint flags = (record.sourceAndPathControl.w >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT)
                & PRIME_WAVEFRONT_PATH_FLAGS_MASK;
        bool after = primePreviousCannotUseMis(record.sourceAndPathControl.xy, flags);
        bool preserved = before == primePreviousCannotUseMis(path) && before == after
                && primePathBounce(path.control) == 0u
                && (record.sourceAndPathControl.w & PRIME_WAVEFRONT_BYTE_MASK) == 0u
                && all(record.sourceAndPathControl.xy == uint2(inputData[o], inputData[o + 1u]))
                && flags == inputData[o + 3u]
                && all(record.physicalOriginAndPreviousBsdfPdf == float4(13.0, -19.0, 0.5, 0.25))
                && all(path.previousLightNormal == float3(0.0, 1.0, 0.0));
        float hit = primeAreaHitMisWeightValue(true, after, path.previousBsdfPdf, 0.5);
        float nee = primePowerHeuristicOverPdfValue(0.5, path.previousBsdfPdf) * 0.5;
        outputData[index * 2u] = float4(float(before), float(after), hit, nee);
        outputData[index * 2u + 1u] = float4(float(preserved),
                primeAreaHitMisWeightValue(false, after, path.previousBsdfPdf, 0.5), 0.0, 0.0);
        return;
    }
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
