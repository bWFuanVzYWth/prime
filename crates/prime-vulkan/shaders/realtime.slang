#language slang 2026
module realtime;
import "frame.slang";
import "transport.slang";
import "ray_query.slang";
import "display/prime_drt.slang";
import "math/color.slang";
#include "atmosphere/consumer_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    TraceResult sample =
            traceSample(resources, atmosphereEnvironment(), frame, id.xy, frame.target.y);
    // Consume this primary sample directly; no full-resolution intermediate images.
    PrimeDRTParameters display = {frame.display.x, frame.display.y, frame.display.z,
                                  frame.display.w, frame.displayExtra.x};
    float3 value;
    switch (asuint(frame.displayExtra.w)) {
    case 1:
        value = encodeSrgb(max(workingToLinear709(sample.radiance) * frame.display.x, float3(0)));
        break;
    case 2: {
        float z = sample.depth;
        value = z < 3.402823466e38f ? float3(saturate(z / frame.displayExtra.y)) : float3(0);
        break;
    }
    case 3: {
        value = dot(sample.normal, sample.normal) > 0 ? sample.normal * 0.5 + 0.5 : float3(0);
        break;
    }
    default:
        value = primeDRT(sample.radiance, display);
        break;
    }
    uint3 encoded = uint3(saturate(value) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? frame.dimensions.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
}
