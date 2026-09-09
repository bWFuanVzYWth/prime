// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "model/atmosphere/sky.slang";

[[vk::binding(0, 0)]] StructuredBuffer<float4> atmosphereSegmentInput;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> atmosphereSegmentOutput;

struct AtmosphereSegmentPush {
    uint count;
};
[[vk::push_constant]] ConstantBuffer<AtmosphereSegmentPush> atmosphereSegmentPush;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint index = dispatchThreadId.x;
    if (index >= atmosphereSegmentPush.count) return;
    float4 source = atmosphereSegmentInput[index];
    float4 vectorIntegral = atmHomogeneousSegmentIntegral(
            float4(source.xyz, source.x), source.w);
    atmosphereSegmentOutput[index] = float4(
            vectorIntegral.xyz,
            atmHomogeneousSegmentIntegral(source.x, source.w));
}
