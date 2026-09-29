#language slang 2026
module realtime;
import "frame.slang";
import "transport.slang";
import "ray_query.slang";
#include "atmosphere/consumer_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> noisy;
[[vk::binding(10, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> depth;
[[vk::binding(11, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> normal;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    TraceResult sample =
            traceSample(resources, atmosphereEnvironment(), frame, id.xy, frame.target.y);
    // Canonical top-left orientation, linear Rec.2020 without exposure or primeDRT.
    noisy[id.xy] = float4(sample.radiance, 1);
    depth[id.xy] = sample.depth; // Positive view-Z in blocks; FLT_MAX means sky/miss.
    normal[id.xy] = float4(sample.normal, dot(sample.normal, sample.normal) > 0 ? 1 : 0);
}
