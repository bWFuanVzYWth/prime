#language slang 2026
module primary_rr_test;
import "trace/closest.slang";
import "realtime/primary.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_environment.slang";
import "reconstruct/primary_guides.slang";
import "reconstruct/rr_guides.slang";
import "reconstruct/reflection_motion.slang";
import "math/z_sobol.slang";
import "realtime/landing.slang";

// Actual production traversal and image sink; only environment radiance is constant.
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> reports;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::binding(11, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> linearDepth;
[[vk::binding(12, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> motionVectors;
[[vk::binding(13, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> normalRoughness;
[[vk::binding(14, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> diffuseAlbedo;
[[vk::binding(15, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> specularAlbedo;
[[vk::binding(16, 0)]] [[vk::image_format("r16f")]] RWTexture2D<float> specularHitDistance;
[[vk::binding(17, 0)]] ConstantBuffer<RrCamera> rrCamera;
[[vk::binding(19, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> guideUnresolved;
[[vk::binding(20, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> specularMotion;
[[vk::push_constant]] ConstantBuffer<PrimaryParameters> parameters;

public struct TestEnvironment : IPrimaryEnvironment {
    public float3 escape(float3 direction) {
        return float3(1.0);
    }
};

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    PrimaryRrGuides guides = {linearDepth,     motionVectors,  normalRoughness,
                              diffuseAlbedo,   specularAlbedo, specularHitDistance,
                              guideUnresolved, specularMotion, transmissionEnergy,
                              rrCamera};
    TestEnvironment environment;
    primeTracePrimary(resources, environment, parameters, id.xy, guides);
    // Test-only synthetic K2 hit distance; this isolates the production post completion helper.
    // Zero leaves the initialized distance at zero; explicit K1 reflection motion bypasses it.
    if (parameters.config.w != 0)
        specularHitDistance[id.xy] = asfloat(parameters.config.w);
    float2 sampleUv = (float2(id.xy) + 0.5 + rrCamera.jitter.xy) / float2(parameters.sampling.xy);
    primeCompleteReflectionMotion(rrCamera, id.xy, parameters.sampling.xy, sampleUv,
                                  guideUnresolved, specularMotion, linearDepth[id.xy],
                                  specularHitDistance[id.xy]);
    uint index = id.y * parameters.sampling.x + id.x;
    ZSobol sampler = makeZSobolS8(id.xy, parameters.sampling.z & 0xffu, parameters.config.x,
                                  parameters.sampling.w ^ (parameters.sampling.z >> 8));
    reports[index] = uint4(asuint(sampler.sample1D(PRIME_PRIMARY_BRANCH_DOMAIN)),
                           primeLandingFlags(parameters.commonAddress,
                                             parameters.sampling.x * parameters.sampling.y, index),
                           asuint(parameters.prefixAddress[index].x), 0);
}
