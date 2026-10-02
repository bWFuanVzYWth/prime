#language slang 2026
module realtime_transport;
import "realtime/transport.slang";
import "realtime/transport_parameters.slang";
import "bsdf/full/energy.slang";
import "ray_query.slang";
#include "atmosphere/lighting_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::push_constant]] ConstantBuffer<TransportParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
    float3 tail = primeTraceLanding(resources, atmosphereLighting(), energy, parameters, id.xy,
                                    NoTransportDistance());
    parameters.tailAddress[id.y * parameters.sampling.x + id.x] = float4(tail, 1.0);
}
