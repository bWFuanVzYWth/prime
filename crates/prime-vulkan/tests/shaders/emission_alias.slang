#language slang 2026
module emission_alias_test;
import "ray_query.slang";
import "emission_alias.slang";
import "restir/scene.slang";
import "restir/rng.slang";
import "math/ray_offset.slang";

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
    for (uint row = 0; row < 8; ++row)
        outputs[i * 8 + row] = uint4(0);
    TraceScene resources = {acceleration,        textures,        texels, instanceMaterials,
                            staticTrianglePages, float2(0.0, 1.0)};
    float3 receiver = float3(0.4, 0.35, 4.0);
    float3 sample = asfloat(inputs[i]).xyz;
    LightSample light;
    if (config.x == 0u) {
        light = sampleLocalLight(resources, float4(sample.x), sample.yz, 17u, receiver);
    } else {
        SampleGenerator replay = SampleGenerator(inputs[i].w);
        float choice = sampleNext1D(replay);
        float4 selectors = sampleNext4D(replay);
        float2 areaSample = sampleNext2D(replay);
        outputs[i * 8 + 7] = uint4(asuint(choice), replay.rng, 0, 0);
        if (choice >= 0.5)
            return; // This fixture has no Sun source; preserve the actual local RNG domain.
        light = sampleLocalLight(resources, selectors, areaSample, 17u, receiver);
        SurfaceHit origin = {};
        SurfacePoint surface = {receiver, float3(0, 0, -1), 1e-6};
        origin.surface = surface;
        RestirLightSample actual =
                restirSampleLocalLight(resources, origin, float3(0, 0, -1), float4(1, 0, 0, 0), 17u,
                                       selectors, areaSample, 0.5);
        outputs[i * 8 + 4] = uint4(actual.hit.primitiveIndex(), actual.hit.pageID(),
                                   actual.hit.data.z, actual.hit.data.w);
        LightTree tree = *((LightTree *)staticTrianglePages[0].lightGrid);
        TreePage page = tree.pages[light.page];
        uint stride = page.format == 3 ? 108u : (page.format == 2 ? 68u : 60u);
        SurfaceRecord record = loadQuad(page.emitters + light.emitter * stride, page.format,
                                        actual.hit.primitiveIndex() & 1u);
        float2 bary = actual.hit.barycentrics();
        SurfacePoint physical =
                reconstructStaticSurface(record.triangle.p0.xyz, record.triangle.p1.xyz,
                                         record.triangle.p2.xyz, bary, page.origin);
        SurfaceHit endpoint = traceClosest(resources, physical.position + physical.normal * 2.0,
                                           -physical.normal, 17u);
        float reverse = 0.5 * emissiveHitPdf(resources, endpoint, receiver);
        outputs[i * 8 + 5] = asuint(float4(endpoint.surface.position, reverse));
        outputs[i * 8 + 6] = uint4(asuint(actual.pdf), replay.rng, asuint(endpoint.emitterAreaPdf),
                                   asuint(actual.Le.x));
    }
    outputs[i * 8] = uint4(light.valid, light.page, light.emitter, light.half);
    if (!light.valid)
        return;
    float3 origin = light.surface.position + light.surface.normal * 2.0;
    SurfaceHit hit = traceClosest(resources, origin, -light.surface.normal, 17u);
    float reverse = hit.found ? emissiveHitPdf(resources, hit, receiver) : -1.0;
    outputs[i * 8 + 1] = asuint(float4(light.bary, light.areaPdf, hit.emitterAreaPdf));
    outputs[i * 8 + 2] = asuint(float4(light.surface.position, reverse));
    LightTree tree = *((LightTree *)staticTrianglePages[0].lightGrid);
    TreePage page = tree.pages[light.page];
    uint stride = page.format == 3 ? 108u : (page.format == 2 ? 68u : 60u);
    uint offset = page.emitters[light.emitter * stride + 11u];
    outputs[i * 8 + 3] = uint4(asuint(light.radiance), offset);
    if (config.x == 0u) {
        SurfaceRecord record =
                loadQuad(page.emitters + light.emitter * stride, page.format, light.half);
        SourceSample source = sourceSample(resources, record, light.bary, 0.0);
        outputs[i * 8 + 7] = asuint(float4(source.material.uv, hit.emission.x, 0.0));
    }
}
