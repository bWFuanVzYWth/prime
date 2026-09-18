import glsl;
import "model/atmosphere/optical_depth.slang";
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[numthreads(64,1,1)]
void main(uint3 id : SV_DispatchThreadID) {
    float4 tau=atmOpticalDepth(uint2(id.x%512u,id.x/512u));
    output[id.x]=float4(unpackHalf2x16ToFloat(packHalf2x16(tau.xy)),unpackHalf2x16ToFloat(packHalf2x16(tau.zw)));
}
