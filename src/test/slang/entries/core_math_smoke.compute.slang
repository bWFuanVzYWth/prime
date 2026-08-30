import "math/transport.slang";
import "math/color_space.slang";

[[vk::binding(0, 0)]] StructuredBuffer<float4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint index = dispatchThreadId.x;
    float4 first = inputs[index * 2];
    float4 second = inputs[index * 2 + 1];
    float3 color = primeDecodeSrgb(first.xyz);
    color = primeLinearSrgbToLinearRec2020(color);
    color = primeLinearRec2020ToLinearBt709(color);
    color = primeEncodeSrgb(color);
    float3 transport = primeProductOver(
            max(first.xyz, float3(1.0e-20)),
            max(second.xyz, float3(1.0e-20)),
            max(first.w, 1.0e-20));
    float weight = primePowerHeuristicValue(abs(first.x), abs(second.x));
    outputs[index] = float4(
            color + transport * weight,
            abs(second.w));
}
