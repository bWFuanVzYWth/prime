#language slang 2026
module path_trace;
import "frame.slang";
import "transport.slang";
import "bsdf/full/energy.slang";
import "ray_query.slang";
import "display/prime_drt.slang";
#include "atmosphere/consumer_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(5, 0)]] RWStructuredBuffer<float4> accumulation;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::push_constant]] ConstantBuffer<Frame> frame;
// ID 3 remains available to the material mask specialization.
[[vk::constant_id(4)]] const uint singleOfflineSample = 0;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    // The host selects this profile only for dimensions.w == 1. Both profiles
    // share one trace body; the single-sample profile removes the outer loop.
    uint samples = singleOfflineSample != 0 ? 1 : frame.dimensions.w;
    float3 mean = float3(0);
    for (uint i = 0; i < samples; ++i) {
        float3 sample = traceSample(resources, atmosphereEnvironment(), energy, frame, id.xy,
                                    frame.target.y + i);
        uint baseCount = frame.dimensions.z;
        if (i == 0)
            mean = baseCount == 0 ? float3(0) : accumulation[id.y * frame.dimensions.x + id.x].rgb;
        mean += (sample - mean) / float(baseCount + i + 1);
        if (singleOfflineSample != 0)
            break;
    }
    uint pixel = id.y * frame.dimensions.x + id.x;
    accumulation[pixel] = float4(mean, 1);
    // Bit 31 skips the fused SDR transform when the GPU exposure/HDR display consumes history.
    if ((asuint(frame.displayExtra.w) & 0x80000000u) != 0)
        return;
    PrimeDRTParameters display = {frame.display.x, frame.display.y, frame.display.z,
                                  frame.display.w, frame.displayExtra.x};
    uint3 encoded = uint3(saturate(primeDRT(mean, display)) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? frame.dimensions.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
}
