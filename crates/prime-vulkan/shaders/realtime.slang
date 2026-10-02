#language slang 2026
module realtime;
import "realtime/post.slang";
import "realtime/post_parameters.slang";
import "display/prime_drt.slang";
import "math/color.slang";
#include "atmosphere/aerial_bindings.slang"

[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::push_constant]] ConstantBuffer<PostParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    float3 radiance = primeComposeRealtime(atmosphereAerial(), parameters, id.xy);
    PrimeDRTParameters display = {parameters.display.x, parameters.display.y, parameters.display.z,
                                  parameters.display.w, parameters.extra.x};
    float3 value;
    switch (parameters.output.w) {
    case 1:
        value = encodeSrgb(max(workingToLinear709(radiance) * parameters.display.x, float3(0)));
        break;
    case 2: {
        float depth = parameters.diagnosticAddress[id.y * parameters.sampling.x + id.x].w;
        value = depth < 3.402823466e38f ? float3(saturate(depth / parameters.extra.y)) : float3(0);
        break;
    }
    case 3: {
        float3 normal = parameters.diagnosticAddress[id.y * parameters.sampling.x + id.x].xyz;
        value = dot(normal, normal) > 0 ? normal * 0.5 + 0.5 : float3(0);
        break;
    }
    default:
        value = primeDRT(radiance, display);
        break;
    }
    uint3 encoded = uint3(saturate(value) * 255.0 + 0.5);
    uint2 targetPixel =
            uint2(id.x, parameters.output.z != 0 ? parameters.output.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
}
