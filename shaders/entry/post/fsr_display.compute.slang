#language slang 2026
module "entry/post/fsr_display.compute.slang";

import "service/reconstruct/auto_exposure.slang";
import "service/reconstruct/display.slang";

struct PrimeFsrDisplayPushConstants
{
    uint2 outputExtent;
    float finalExposureMultiplier;
    float reinhardOutputPeak;
    float reinhardCurvePeak;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeFsrLinearOutput;
[[vk::binding(1, 0)]]
[[vk::image_format("rgba8")]]
WTexture2D<unorm float4> primeDisplayOutput;
[[vk::binding(2, 0)]] StructuredBuffer<PrimeAutoExposureState> primeAutoExposureState;
[[vk::binding(3, 0)]]
[[vk::image_format("rgba16f")]]
WTexture2D<float4> primeHdrDisplayOutput;
[[vk::push_constant]] ConstantBuffer<PrimeFsrDisplayPushConstants> primeFsrDisplayPush;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeFsrDisplayPush.outputExtent)) return;

    float3 radiance = primeFsrLinearOutput.Load(int3(pixel, 0)).rgb;
    bool hdrActive = primeFsrDisplayPush.reinhardOutputPeak > 1.0;
    if (any(isnan(radiance)) || any(isinf(radiance))) radiance = float3(0.0);
    float exposure = exp2(primeAutoExposureState[0].exposureEv)
            * primeFsrDisplayPush.finalExposureMultiplier;
    float3 nonnegative = max(radiance, float3(0.0));
    float3 sdr = primeDisplayTransformToSrgb(nonnegative, exposure);
    if (hdrActive) {
        float3 hdr = primeDisplayTransformToSrgb(
                nonnegative,
                exposure,
                primeFsrDisplayPush.reinhardOutputPeak,
                primeFsrDisplayPush.reinhardCurvePeak);
        primeHdrDisplayOutput.Store(pixel, float4(hdr, 1.0));
    }
    // HDR presentation uses zero alpha as an exact coverage accumulator for vanilla UI.
    primeDisplayOutput.Store(
            pixel,
            float4(sdr, hdrActive ? 0.0 : 1.0));
}
