#language slang 2026
module realtime_primary_rr;
import "trace/closest.slang";
import "realtime/primary.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_environment.slang";
import "reconstruct/primary_guides.slang";
import "reconstruct/rr_guides.slang";
#include "atmosphere/lighting_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
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
[[vk::push_constant]] ConstantBuffer<PrimaryParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    PrimaryRrGuides guides = {linearDepth,     motionVectors,      normalRoughness,
                              diffuseAlbedo,   specularAlbedo,     specularHitDistance,
                              guideUnresolved, transmissionEnergy, rrCamera};
    PrimaryEnvironment environment = {atmosphereLighting(), parameters.right.w, parameters.up.w};
    primeTracePrimary(resources, environment, parameters, id.xy, guides);
}
