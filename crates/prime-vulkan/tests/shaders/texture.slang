#language slang 2026
module texture_test;
import "ray_query.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(4, 0)]] StructuredBuffer<uint> texels;
[[vk::push_constant]] ConstantBuffer<uint4> config;
[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    outputs[i] = sampleTexture(textures, texels, config.x, inputs[i].xy,
                               log2(max(1.0, inputs[i].z * 4.0)));
}
