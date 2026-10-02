#language slang 2026
module realtime_primary;
import "trace/closest.slang";
import "realtime/primary.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_guide_contract.slang";
import "realtime/primary_environment.slang";
#include "atmosphere/lighting_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::push_constant]] ConstantBuffer<PrimaryParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    PrimaryEnvironment environment = {atmosphereLighting(), parameters.right.w, parameters.up.w};
    primeTracePrimary(resources, environment, parameters, id.xy, NoPrimaryGuides());
}
