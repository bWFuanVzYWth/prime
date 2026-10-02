#language slang 2026
module realtime_rr;
import "realtime/post.slang";
import "realtime/post_parameters.slang";
import "math/color.slang";
import "reconstruct/rr_format.slang";
#include "atmosphere/aerial_bindings.slang"

[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> noisyColor;
[[vk::push_constant]] ConstantBuffer<PostParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    float3 radiance = primeComposeRealtime(atmosphereAerial(), parameters, id.xy);
    // Complete scene-linear radiance and camera air segment precede reconstruction.
    noisyColor[id.xy] = float4(primeRrFiniteHalfColor(workingToLinear709(radiance)), 1.0);
}
