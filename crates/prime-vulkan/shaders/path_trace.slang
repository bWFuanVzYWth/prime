#language slang 2026
module path_trace;
import "frame.slang";
import "transport.slang";
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
[[vk::push_constant]] ConstantBuffer<Frame> frame;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    uint pixel = id.y * frame.dimensions.x + id.x;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    uint count = frame.dimensions.z;
    float3 mean = count == 0 ? float3(0) : accumulation[pixel].rgb;
    for (uint i = 0; i < frame.dimensions.w; ++i) {
        float3 sample =
                traceSample(resources, atmosphereEnvironment(), frame, id.xy, frame.target.y + i)
                        .radiance;
        mean += (sample - mean) / float(++count);
    }
    accumulation[pixel] = float4(mean, 1);
    PrimeDRTParameters display = {frame.display.x, frame.display.y, frame.display.z,
                                  frame.display.w, frame.displayExtra.x};
    uint3 encoded = uint3(saturate(primeDRT(mean, display)) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? frame.dimensions.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
}
