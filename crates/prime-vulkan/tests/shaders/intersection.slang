#language slang 2026
module intersection_test;
import "math/ray_offset.slang";
import "ray_query.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::binding(2, 0)]] RaytracingAccelerationStructure acceleration;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(4, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(5, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(6, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::push_constant]] ConstantBuffer<uint4> config;

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    TraceScene resources = {acceleration, textures, texels, instanceMaterials, staticTrianglePages};
    float3 origin = asfloat(inputs[3 * i]).xyz;
    float3 incoming = asfloat(inputs[3 * i + 1]).xyz;
    float3 outgoing = asfloat(inputs[3 * i + 2]).xyz;
    SurfaceHit hit = traceClosest(resources, origin, incoming, 0);
    bool blocked = false;
    if (hit.found)
        blocked = traceOcclusion(resources, spawnRay(hit.surface, outgoing), outgoing, 0);
    outputs[2 * i] = uint4(hit.found, blocked, 0, 0);
    outputs[2 * i + 1] =
            hit.found ? asuint(float4(hit.surface.normal, hit.surface.offset)) : uint4(0);
}
