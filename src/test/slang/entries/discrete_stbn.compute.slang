// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "math/direct_discrete_sampling.slang";
import "math/direct_sampling.slang";
import "math/hash.slang";
import "service/light/cell_sample.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    uint2 pixel = uint2(index & 127u, (index >> 7u) & 127u);
    uint frame = inputData[1] + (index >> 14u);
    uint epoch = inputData[2];
    uint vertex = inputData[3];
    PrimeDirectSampleBase base = primeMakeDirectSampleBase(pixel, frame, epoch, vertex, 0u);
    uint packed = inputData[4u + primeStbnSampleAddress(base, PRIME_DIRECT_SAMPLE_AREA_PRIMARY)];
    uint seed = primeDirectDiscreteSeed(pixel, frame, epoch, vertex, 0u);
    float x = primeDecodeStbnDiscreteChannel(packed & 65535u, seed, 0u);
    float y = primeDecodeStbnDiscreteChannel(packed >> 16u, seed, 1u);
    uint column = uint(y * 256.0);
    uint chosen = primeSelectLightCell(y * 256.0, 1.0 / 4096.0, (column + 1u) & 255u);
    uint preparedSeed = primeDirectDiscreteSeed(
            primeHashCombine(primePathSampleSeed(pixel, epoch, 0u), vertex), frame);
    outputData[index * 3u] = uint4(uint(x * 16777216.0), uint(y * 16777216.0), packed,
            uint(chosen == column) | (uint(preparedSeed != seed) << 1u));
    // All six identity components must affect refinement even when the 128x128 table repeats.
    outputData[index * 3u + 1u] = uint4(
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel + uint2(128u, 0u), frame, epoch, vertex, 0u), 0u) * 16777216.0),
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel + uint2(0u, 128u), frame, epoch, vertex, 0u), 0u) * 16777216.0),
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel, frame + 64u, epoch, vertex, 0u), 0u) * 16777216.0),
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel, frame, epoch + 1u, vertex, 0u), 0u) * 16777216.0));
    outputData[index * 3u + 2u] = uint4(
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel, frame, epoch, vertex + 1u, 0u), 0u) * 16777216.0),
            uint(primeDecodeStbnDiscreteChannel(0u, primeDirectDiscreteSeed(pixel, frame, epoch, vertex, 1u), 0u) * 16777216.0),
            asuint(primeDecodeStbnDiscreteChannel(65535u, seed, 0u)),
            asuint(primeDecodeStbnDiscreteChannel(0u, seed, 0u)));
}
