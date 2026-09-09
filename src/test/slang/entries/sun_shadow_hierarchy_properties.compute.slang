// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "math/sun_shadow_hierarchy.slang";

struct PrimeSunShadowLeafCase
{
    float rayDepthAtZero;
    float rayDepthRate;
    float startDistance;
    float endDistance;
    float blockerDepth;
    float bias;
};

struct PrimeSunShadowLeafPush
{
    uint count;
};

[[vk::binding(0, 0)]] StructuredBuffer<PrimeSunShadowLeafCase> primeSunShadowLeafInput;
[[vk::binding(1, 0)]] RWStructuredBuffer<float> primeSunShadowLeafOutput;
[[vk::push_constant]] ConstantBuffer<PrimeSunShadowLeafPush> primeSunShadowLeafPush;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint index = dispatchThreadId.x;
    if (index >= primeSunShadowLeafPush.count) return;
    PrimeSunShadowLeafCase value = primeSunShadowLeafInput[index];
    primeSunShadowLeafOutput[index] = primeSunShadowLeafVisibleLength(
            value.rayDepthAtZero,
            value.rayDepthRate,
            value.startDistance,
            value.endDistance,
            value.blockerDepth,
            value.bias);
}
