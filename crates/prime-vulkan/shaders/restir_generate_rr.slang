#language slang 2026
module restir_generate_rr;
#include "restir/bindings.slang"
import "restir/indexing.slang";
import "restir/path.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/rr_decorrelation.slang";
import "restir/rr_guides.slang";
import "realtime/primary_parameters.slang";
import "reconstruct/primary_guides.slang";
import "reconstruct/rr_guides.slang";

[[vk::binding(11, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> linearDepth;
[[vk::binding(12, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> motionVectors;
[[vk::binding(13, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> normalRoughness;
[[vk::binding(14, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> diffuseAlbedo;
[[vk::binding(15, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> specularAlbedo;
[[vk::binding(16, 0)]] [[vk::image_format("r16f")]] RWTexture2D<float> specularHitDistance;
[[vk::binding(17, 0)]] ConstantBuffer<RrCamera> rrCamera;
[[vk::binding(19, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> guideUnresolved;
[[vk::binding(20, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> specularMotion;
[[vk::binding(21, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> visibleDepth;
[[vk::binding(22, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> visibleMotion;

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.frame.dimensions.xy))
        return;
    RestirScene resources = restirScene();
    float4 *guideSeeds = (float4 *)parameters.retrace;
    // Supply all pointer fields directly: a zero pointer initializer unnecessarily introduces
    // shaderInt64 in Slang's generated module even when that field has no guide consumer.
    PrimaryParameters camera = {
            resources.frame.position,
            resources.frame.forward,
            resources.frame.right,
            resources.frame.up,
            uint4(resources.frame.dimensions.xy, resources.frame.target.y,
                  resources.frame.target.w),
            uint4(resources.frame.target.z, asuint(resources.frame.displayExtra.z), 0, 0),
            guideSeeds,
            guideSeeds,
            guideSeeds,
            guideSeeds};
    PrimaryRrGuides guides = {linearDepth,
                              motionVectors,
                              normalRoughness,
                              diffuseAlbedo,
                              specularAlbedo,
                              specularHitDistance,
                              guideUnresolved,
                              specularMotion,
                              transmissionEnergy,
                              rrCamera,
                              visibleDepth,
                              visibleMotion,
                              (motionFeatures & 2u) != 0};
    RestirRrPrimaryObserver observer = {resources.resources,
                                        camera,
                                        guides,
                                        id.xy,
                                        (float2(id.xy) + 0.5 + resources.sampleJitter) /
                                                float2(camera.sampling.xy),
                                        false};
    RestirPrimary primary;
    float3 residual;
    uint offset = restirOffset(id.xy, parameters.frame.dimensions.xy);
    PathReservoir initial =
            restirGenerate(resources, id.xy, resources.frame.target.y + resources.frame.target.w,
                           primary, residual, observer);
    parameters.current[offset] = initial;
    if (restirRrOutputActive(parameters.rrDecorrelation, parameters.control.y != 0u))
        parameters.rrInitialEstimate[offset] =
                float4(initial.integrand * initial.weight, initial.weight);
    parameters.primary[offset] = primary;
    parameters.residual[offset] = float4(residual, 0);
    if ((parameters.config.queue.w & 1u) != 0u)
        parameters.currentAges[offset] = 0u;
    if ((parameters.config.queue.w & 8u) != 0u)
        parameters.currentProfiles[offset] = parameters.currentSourceProfile;
}
