#language slang 2026
module lights_test;
import "ray_query.slang";
import "grid_sampling.slang";
import "math/z_sobol.slang";

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
    if (config.x == 2 || config.x == 3) {
        uint stride = config.x == 2 ? 2 : 3;
        float3 receiver = asfloat(inputs[i * stride].xyz);
        uint coverage = inputs[i * stride].w;
        LightSample light;
        if (config.x == 2) {
            uint4 coordinate = inputs[i * 2 + 1];
            ZSobol sampler = makeZSobol(coordinate.xy, coordinate.z & 255, 8, 8,
                                        coverage ^ (coordinate.z >> 8));
            light = sampleLocalLight(resources, sampler, coordinate.w, receiver);
            coverage = sampler.bits1D(512 + 4 * coordinate.w + 2);
        } else {
            // Exact lattice witnesses for probabilities too rare for a short RNG run.
            light = sampleLocalLight(resources, asfloat(inputs[i * 3 + 1]),
                                     asfloat(inputs[i * 3 + 2].xy), coverage, receiver);
        }
        outputs[i * 5] = uint4(light.valid, 0, 0, 0);
        outputs[i * 5 + 4] = uint4(0, 0xffffffffu, 0xffffffffu, 0);
        if (!light.valid)
            return;
        float3 delta = light.surface.position - receiver;
        float distanceSquared = dot(delta, delta);
        float3 direction = normalize(delta);
        SurfaceHit hit = traceClosest(resources, receiver, direction, coverage);
        float reverse = hit.found ? emissiveHitPdf(resources, hit, receiver) : -1.0;
        float cosine = abs(dot(light.surface.normal, direction));
        float forward = cosine > 0 ? light.areaPdf * distanceSquared / cosine : 0;
        outputs[i * 5] = uint4(light.valid, light.page, light.emitter, light.twoSided);
        outputs[i * 5 + 1] = asuint(float4(light.surface.position, light.areaPdf));
        outputs[i * 5 + 2] = asuint(float4(light.radiance, reverse));
        outputs[i * 5 + 3] = asuint(float4(light.surface.normal, forward));
        if (hit.found && hit.light != 0xffffffffu) {
            GridEmitter ref = resources.staticTrianglePages[0].lightGrid[0].emitters[hit.light];
            outputs[i * 5 + 4] = uint4(1, ref.page, ref.emitter, hit.light);
        }
        return;
    }
    float3 sample = asfloat(inputs[i]).xyz;
    float3 receiver = float3(0, 0, config.x == 1 ? -1000 : 1000);
    LightSample light = sampleLocalLight(resources, float4(sample.x), sample.yz, 17, receiver);
    outputs[i * 4] = uint4(light.valid, 0, 0, 0);
    if (!light.valid)
        return;
    float3 origin = light.surface.position + light.surface.normal * (config.x == 1 ? -2.0 : 2.0);
    SurfaceHit hit = traceClosest(resources, origin,
                                  light.surface.normal * (config.x == 1 ? 1.0 : -1.0), 17);
    float reverse = hit.found ? emissiveHitPdf(resources, hit, receiver) : -1.0;
    outputs[i * 4] = uint4(light.valid, light.page, light.emitter, light.twoSided);
    outputs[i * 4 + 1] = asuint(float4(light.surface.position, light.areaPdf));
    outputs[i * 4 + 2] = asuint(float4(light.radiance, reverse));
    outputs[i * 4 + 3] = asuint(float4(light.surface.normal, light.surface.offset));
}
