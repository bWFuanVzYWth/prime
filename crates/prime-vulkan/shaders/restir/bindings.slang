// Entry-point bindings share the existing self-contained scene and atmospheric resources.
import "restir/parameters.slang";
import "trace/closest.slang";
import "restir/scene.slang";
#include "atmosphere/consumer_bindings.slang"
#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
#endif
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::binding(23, 0)]] ConstantBuffer<RestirParameters> parameters;
[[vk::push_constant]] ConstantBuffer<RestirPass> pass;

RestirScene restirScene(bool previous = false) {
    RestirScene result;
#if defined(PRIME_RESTIR_MATERIAL_ONLY)
    result.resources = {textures, texels, instanceMaterials, staticTrianglePages};
#else
    result.resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
#endif
    result.environment = atmosphereEnvironment();
    result.energy = {transmissionEnergy};
    result.frame = previous ? parameters.previousFrame : parameters.frame;
    // Both temporal directions use the current scene's illumination. Only camera,
    // sampling identity and accepted jitter come from the previous frame.
    result.frame.right.w = parameters.frame.right.w;
    result.frame.up.w = parameters.frame.up.w;
    if (!previous)
        result.frame.target.y += pass.sampleOffset;
    result.tlasInstances = parameters.tlasInstances;
    result.terrainCount = parameters.control.x;
    result.sampleJitter = previous ? parameters.sampleJitter.zw : parameters.sampleJitter.xy;
    result.historyStatic = parameters.historyStatic;
    result.historyDynamic = parameters.historyDynamic;
    result.historyEmitters = parameters.historyEmitters;
    result.identityControl = parameters.identityControl;
    return result;
}
