// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "service/reconstruct/nrd/direction.slang";
import "math/reconstruction_scratch.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

float3 vectorAt(uint offset) {
    return asfloat(uint3(inputData[offset], inputData[offset + 1u], inputData[offset + 2u]));
}

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    if (inputData[1] == 1u) {
        if (index != 0u) return;
        for (uint word = 0u; word < 20u; ++word) outputData[word / 4u][word % 4u] = -1.0;
        for (uint pixel = 0u; pixel < 2u; ++pixel) {
            for (uint component = 0u; component < 8u; ++component) {
                uint word = primeReconstructionGuideWord(pixel, component);
                outputData[word / 4u][word % 4u] = float(pixel * 100u + component);
            }
        }
        return;
    }
    uint offset = 4u + index * 20u;
    float3 area = vectorAt(offset);
    float3 continuation = vectorAt(offset + 4u);
    float3 guide = vectorAt(offset + 8u);
    float3 areaDirection = vectorAt(offset + 12u);
    float3 continuationDirection = vectorAt(offset + 16u);
    outputData[index] = float4(primeNrdEffectiveDirection(
            area + continuation, area, areaDirection, continuationDirection, guide), 0.0);
}
