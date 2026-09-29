#language slang 2026
module lights_test;
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
    float3 sample = asfloat(inputs[i]).xyz;
    LightSample light = sampleLocalLight(resources, sample.x, sample.yz, 17);
    outputs[i * 4] = uint4(light.valid, 0, 0, 0);
    if (!light.valid)
        return;
    float3 origin = light.surface.position + light.surface.normal * 2.0;
    SurfaceHit hit = traceClosest(resources, origin, -light.surface.normal, 17);
    float reverse = hit.found ? emissiveHitPdf(resources, hit, origin) : -1.0;
    outputs[i * 4] = uint4(light.valid, light.page, light.emitter, light.twoSided);
    outputs[i * 4 + 1] = asuint(float4(light.surface.position, light.areaPdf));
    outputs[i * 4 + 2] = asuint(float4(light.radiance, reverse));
    outputs[i * 4 + 3] = asuint(float4(light.surface.normal, light.surface.offset));
}
