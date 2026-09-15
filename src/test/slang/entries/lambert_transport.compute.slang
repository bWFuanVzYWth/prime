// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "math/lambert_transport.slang";
import "math/lambert_roulette.slang";
import "math/direct_sampling.slang";
import "model/lambert/medium.slang";
import "contract/lambert.slang";
import "math/lambert_storage.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    uint mode = inputData[1];
    if (mode == 0u) {
        uint offset = 4u + 4u * index;
        uint minimum = inputData[offset], maximum = inputData[offset+1u], completed = inputData[offset+2u];
        outputData[index] = float4(primeLambertBounceLimit(minimum, maximum),
                float(primeLambertNeedsRoulette(completed, minimum, maximum)),
                float(primeLambertIncludesEmission(completed, true)),
                float(primeLambertIncludesEmission(completed, false)));
    } else if (mode == 1u) {
        PrimeLambertMedium medium = primeLambertCameraMedium(false);
        uint first = 65534u, second = 65535u;
        float3 sigmaA = float3(0.2, 0.5, 1.0), sigmaB = float3(0.8, 0.1, 0.25);
        primeLambertCrossBoundary(medium, first, sigmaA, float3(0.0), PRIME_LAMBERT_ENTERING);
        float3 filter = primeLambertSegmentFilter(medium, 2.0);
        primeLambertCrossBoundary(medium, second | (first << 16u), sigmaB, sigmaA, PRIME_LAMBERT_ENTERING);
        filter *= primeLambertSegmentFilter(medium, 3.0);
        primeLambertCrossBoundary(medium, second | (first << 16u), sigmaB, sigmaA, 0u);
        filter *= primeLambertSegmentFilter(medium, 1.0);
        primeLambertCrossBoundary(medium, first, sigmaA, float3(0.0), 0u);
        outputData[0] = float4(filter, medium.id);
        // Repeated explicit transitions have no depth limit or hidden return history.
        for (uint depth = 1u; depth <= 64u; ++depth)
            primeLambertCrossBoundary(medium, depth | ((depth - 1u) << 16u), sigmaA, sigmaB, PRIME_LAMBERT_ENTERING);
        outputData[1] = float4(float(medium.id == 64u), asfloat(second), float(sizeof(PrimeLambertRecord)), float(sizeof(PrimeLambertInterface)));
        primeLambertCrossBoundary(medium, first | (second << 16u), sigmaA, sigmaB, 0u);
        outputData[2] = float4(primeLambertExtinction(medium), float(medium.id == second));
        primeLambertCrossBoundary(medium, second, sigmaB, float3(0.0), 0u);
        outputData[3] = float4(primeLambertExtinction(medium), float(medium.id == 0u));
        medium.extinction = sigmaA;
        medium.id = first;
        outputData[4] = float4(primeLambertSegmentFilter(medium, 2.0), float(medium.id == first));
    } else if (mode == 2u) {
        float cosine = float(index) / float(max(inputData[0] - 1u, 1u));
        float f = primeLambertFresnel(cosine, inputData[2] != 0u);
        bool left = primeLambertReflect(f, 0.25), right = primeLambertReflect(f, 0.75);
        float energy = 0.5 * (primeLambertBranchWeight(f, left) + primeLambertBranchWeight(f, right));
        float3 normal = normalize(float3(0.2, -0.7, 0.9));
        float3 point = float3(7, -4, 2), plane = float3(-3, 1, 5);
        float3 mirrored = primeLambertMirrorPoint(point, plane, normal);
        float error = length(primeLambertMirrorPoint(mirrored, plane, normal) - point);
        outputData[index] = float4(f, energy, error, float(left && (f == 1.0 || !right)));
    } else if (mode == 3u) {
        uint minimum = inputData[2], maximum = inputData[3];
        float a = asfloat(inputData[4]);
        float3 throughput = float3(1.0);
        float sum = 0.0;
        uint completed = 0u;
        uint limit = primeLambertBounceLimit(minimum, maximum);
        [loop] for (uint bounce = 0u; bounce < limit; ++bounce) {
            sum += throughput.x;
            throughput *= a;
            completed++;
            if (primeLambertNeedsRoulette(completed, minimum, maximum)
                    && !primeLambertRoulette(throughput,
                            primeLambertRouletteSample(uint2(index & 1023u, index >> 10u), 91u, 7u, completed))) {
                throughput = float3(0.0);
                break;
            }
        }
        sum += throughput.x;
        outputData[index] = float4(sum, float(completed), throughput.x, 0.0);
    } else if (mode == 5u) {
        uint pixels = inputData[4u + index];
        outputData[index] = asfloat(uint4(primeLambertScratchWord(pixels - 1u, 2u) + 4u,
                primeLambertCommandWord(pixels, 2u), primeLambertQueueWord(pixels, 0u, 0u),
                primeLambertQueueWord(pixels, 2u, pixels - 1u) + 1u));
    } else {
        // Two branches, lighting / continuation and all 64 configured depths must have distinct addresses.
        uint branch = index & 1u;
        uint vertex = index >> 1u;
        PrimeDirectSampleBase sample = primeMakeDirectSampleBase(uint2(3, 17), inputData[2], inputData[3], vertex, branch);
        outputData[index] = float4(asfloat(primeStbnSampleAddress(sample, 0u)),
                asfloat(primeStbnSampleAddress(sample, 1u)), asfloat(primeStbnSampleAddress(sample, 2u)), asfloat(sample.frame));
    }
}
