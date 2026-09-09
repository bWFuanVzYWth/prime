// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import glsl;
import "model/atmosphere/transmittance.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint> input;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;

[numthreads(64, 1, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    float4 low;
    float4 high;
    atmTransmittanceTexel(int2(thread.x % 256u, thread.x / 256u), int2(256, 64), low, high);
    // Match the production storage-image conversion before testing either interpolation stage.
    output[2u * thread.x] = float4(unpackHalf2x16ToFloat(packHalf2x16(low.xy)), unpackHalf2x16ToFloat(packHalf2x16(low.zw)));
    output[2u * thread.x + 1u] = float4(unpackHalf2x16ToFloat(packHalf2x16(high.xy)), unpackHalf2x16ToFloat(packHalf2x16(high.zw)));
}
