#language slang 2026
module display_test;
import "display/prime_drt.slang";
import "legacy_display.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> config;
[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    float4 color = asfloat(inputs[4 * i]);
    float4 a = asfloat(inputs[4 * i + 1]), b = asfloat(inputs[4 * i + 2]);
    float headroom = asfloat(inputs[4 * i + 3]).x;
    PrimeDRTParameters parameters = {a.x, a.y, a.z, a.w, b.x};
    outputs[2 * i] = asuint(float4(primeDRT(color.xyz, parameters), 0));
    PrimeRgbReinhardParameters legacy = {headroom, a.y, a.w, b.x};
    // The old entry zeroes non-finite radiance before invoking its display transform.
    if (any(isnan(color.xyz)) || any(isinf(color.xyz)))
        color = float4(0);
    outputs[2 * i + 1] = asuint(float4(primeDisplayTransformToSrgb(color.xyz, a.x, legacy), 0));
}
