#language slang 2026
module realtime_rr;
import "realtime/post.slang";
import "realtime/post_parameters.slang";
import "math/color.slang";
import "reconstruct/rr_format.slang";
import "reconstruct/rr_guides.slang";
import "reconstruct/reflection_motion.slang";
import "reconstruct/rr_status.slang";
#include "atmosphere/aerial_bindings.slang"

[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> noisyColor;
[[vk::binding(11, 0)]] [[vk::image_format("r32f")]] readonly RWTexture2D<float> linearDepth;
[[vk::binding(16, 0)]] [[vk::image_format("r16f")]] readonly RWTexture2D<float> specularDistance;
[[vk::binding(17, 0)]] ConstantBuffer<RrCamera> rrCamera;
[[vk::binding(19, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> guideStatus;
[[vk::binding(20, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> specularMotion;
[[vk::push_constant]] ConstantBuffer<PostParameters> parameters;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.sampling.xy))
        return;
    float3 radiance = primeComposeRealtime(atmosphereAerial(), parameters, id.xy);
    // Complete scene-linear radiance and camera air segment precede reconstruction.
    noisyColor[id.xy] = float4(primeRrFiniteHalfColor(workingToLinear709(radiance)),
                               primeRrForeground(guideStatus[id.xy]) ? 1.0 : 0.0);
    float2 sampleUv = (float2(id.xy) + 0.5 + parameters.sample.xy) / float2(parameters.sampling.xy);
    primeCompleteReflectionMotion(rrCamera, id.xy, parameters.sampling.xy, sampleUv, guideStatus,
                                  specularMotion, linearDepth[id.xy], specularDistance[id.xy]);
}
