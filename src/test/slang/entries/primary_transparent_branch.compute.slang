// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "math/transparent_branch.slang";
import "math/direct_sampling.slang";
import "math/realtime_storage.slang";
import "prime_realtime_abi.slang";
import "contract/realtime/transparent.slang";
import "service/reconstruct/primary_guide.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    uint mode = inputData[1];
    if (mode == 4u) {
        // Run one invocation so the simulated late guide completion can interleave arbitrarily
        // with already initialized opaque receiver normals, without a scheduling assumption.
        if (index != 0u) return;
        uint pixels = inputData[2];
        for (uint word = 0u; word < pixels * 70u + 4u; ++word)
            outputData[word / 4u][word % 4u] = 0xdeadbeefu;
        for (uint path = 0u; path < 2u * pixels; ++path) {
            uint base = primeRealtimePhaseWord(path, pixels) + 3u;
            for (uint lane = 0u; lane < 3u; ++lane)
                outputData[(base + lane) / 4u][(base + lane) % 4u] = 100u + path * 3u + lane;
        }
        for (uint pixel = 1u; pixel <= 2u; ++pixel) {
            uint base = primeRealtimePhaseWord(pixel, pixels);
            for (uint lane = 0u; lane < 10u; ++lane)
                outputData[(base + lane) / 4u][(base + lane) % 4u] = 500u + pixel * 10u + lane;
        }
        for (uint pixel = 1u; pixel <= 2u; ++pixel) {
            for (uint branch = 0u; branch < 2u; ++branch) {
                uint base = primeRealtimePhaseWord(pixel + branch * pixels, pixels) + 3u;
                for (uint lane = 0u; lane < 3u; ++lane)
                    outputData[(base + lane) / 4u][(base + lane) % 4u] = 0u;
            }
        }
        return;
    }
    if (mode == 0u) {
        uint2 pixel = uint2(index & 127u, (index >> 7u) & 127u);
        uint frame = inputData[2] + (index >> 14u);
        uint address = primePrimaryTransparentSampleAddress(pixel, frame, inputData[3]);
        uint packed = inputData[4u + address];
        bool chooseTransmission = primeChoosePrimaryTransmission(packed);
        float2 weights = primePrimaryTransparentBranchWeights(true, true, chooseTransmission);
        // Only the two future terms are thinned. Primary emission/NEE is evaluated once.
        float3 estimate = float3(1.0, 2.0, 4.0)
                + weights.x * float3(0.25, 3.0, 9.0) + weights.y * float3(5.0, 0.5, 2.0);
        uint lightAddress = primeStbnSampleAddress(
                primeMakeDirectSampleBase(pixel, frame, inputData[3], 1u, 0u), PRIME_DIRECT_SAMPLE_AREA_PRIMARY);
        outputData[index * 2u] = uint4(asuint(weights), address, uint(lightAddress == address));
        outputData[index * 2u + 1u] = uint4(asuint(estimate), uint(chooseTransmission));
        return;
    }
    if (mode == 1u) {
        bool t = (index & 0x10000u) != 0u, r = (index & 0x20000u) != 0u;
        bool choose = primeChoosePrimaryTransmission(index);
        float2 weights = primePrimaryTransparentBranchWeights(t, r, choose);
        outputData[index] = uint4(asuint(weights), uint(choose), 0u);
        return;
    }
    if (mode == 2u) {
        bool hasGuide = (index & 1u) != 0u, diffuse = (index & 2u) != 0u, hit = (index & 4u) != 0u;
        float distance = asfloat(inputData[4u + (index >> 3u)]);
        PrimeTransparentBranchResult result = (PrimeTransparentBranchResult)0;
        result.diffuseRadiance = float3(2.0, 4.0, 8.0);
        result.specularRadiance = float3(3.0, 6.0, 12.0);
        result.guides.primaryPosition = float3(9.0, 10.0, 11.0);
        result.guides.primaryPreviousPosition = float3(-1.0, -2.0, -3.0);
        result.guides.primaryAlbedo = float3(0.2, 0.4, 0.6);
        result.guides.primarySpecularAlbedo = float3(0.1, 0.3, 0.5);
        result.guides.primaryNormal = float3(0.0, 1.0, 0.0);
        result.guides.primaryHasMotion = true;
        result.guides.primaryMaterialFlags = 123u;
        result.guides.diffuseHitDistance = 17.0;
        result.guides.specularHitDistance = 19.0;
        result.anchorDistance = 23.0;
        result.directionalGuide = true;
        primeRecordPrimaryGuideSegment(result, hasGuide, diffuse, hit, distance);
        bool unchanged = all(result.diffuseRadiance == float3(2.0, 4.0, 8.0))
                && all(result.specularRadiance == float3(3.0, 6.0, 12.0))
                && all(result.guides.primaryPosition == float3(9.0, 10.0, 11.0))
                && all(result.guides.primaryPreviousPosition == float3(-1.0, -2.0, -3.0))
                && all(result.guides.primaryAlbedo == float3(0.2, 0.4, 0.6))
                && all(result.guides.primarySpecularAlbedo == float3(0.1, 0.3, 0.5))
                && all(result.guides.primaryNormal == float3(0.0, 1.0, 0.0))
                && result.guides.primaryHasMotion && result.guides.primaryMaterialFlags == 123u
                && result.anchorDistance == 23.0 && result.directionalGuide;
        outputData[index] = uint4(asuint(result.firstHitDistance),
                asuint(result.guides.diffuseHitDistance), asuint(result.guides.specularHitDistance), uint(unchanged));
        return;
    }
    uint pixels = inputData[2];
    uint pathIndex = inputData[4u + index];
    outputData[index * 3u] = uint4(primeRealtimeTransportIndex(pathIndex, pixels),
            pathIndex,
            primeRealtimeSurfaceWord(pathIndex, pixels), primeMissingGuideOwnerWord(pathIndex, pixels));
    outputData[index * 3u + 1u] = uint4(PRIME_MISSING_GUIDE_WORDS,
            PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u,
            pixels * (PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE / 4u + 2u * PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u), 0u);
    outputData[index * 3u + 2u] = uint4(primeRealtimePhaseWord(pathIndex, pixels),
            primeRealtimePhaseWord(pathIndex % pixels, pixels), pixels * PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u, 0u);
}
