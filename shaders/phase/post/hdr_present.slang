#language slang 2026
module "phase/post/hdr_present.slang";


struct PrimeHdrPresentPushConstants
{
    uint2 extent;
    uint compositePrimeHdr;
    float scRgbScale;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeHdrImage;
[[vk::binding(1, 0)]] Texture2D<float4> primeSdrBaseline;
[[vk::binding(2, 0)]] Texture2D<float4> primeUiComposite;
[[vk::binding(3, 0)]]
[[vk::image_format("rgba16f")]]
WTexture2D<float4> primeScRgbOutput;
[[vk::push_constant]] ConstantBuffer<PrimeHdrPresentPushConstants> primeHdrPresentPush;

float primeExtendedSrgbEotf(float encoded)
{
    float magnitude = abs(encoded);
    float linearMagnitude = magnitude <= 0.04045
            ? magnitude / 12.92
            : pow((magnitude + 0.055) / 1.055, 2.4);
    return copysign(linearMagnitude, encoded);
}

float3 primeExtendedSrgbEotf(float3 encoded)
{
    return float3(
            primeExtendedSrgbEotf(encoded.x),
            primeExtendedSrgbEotf(encoded.y),
            primeExtendedSrgbEotf(encoded.z));
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeHdrPresentPush.extent)) return;

    float4 uiComposite = primeUiComposite.Load(int3(pixel, 0));
    if (primeHdrPresentPush.compositePrimeHdr == 0)
    {
        primeScRgbOutput.Store(
                pixel,
                float4(
                        primeExtendedSrgbEotf(uiComposite.rgb)
                                * primeHdrPresentPush.scRgbScale,
                        1.0));
        return;
    }

    // Vanilla UI remains in Minecraft's bottom-up target. Prime HDR and SDR baseline images are
    // canonical top-left, while this pass deliberately preserves a bottom-up output for the
    // existing swapchain presentation blit.
    uint2 primePixel = uint2(
            pixel.x,
            primeHdrPresentPush.extent.y - 1u - pixel.y);
    float3 hdr = primeExtendedSrgbEotf(
            primeHdrImage.Load(int3(primePixel, 0)).rgb);
    float3 baseline = primeSdrBaseline.Load(int3(primePixel, 0)).rgb;
    float coverage = clamp(uiComposite.a, 0.0, 1.0);
    float3 encodedUiPremultiplied = max(
            uiComposite.rgb - baseline * (1.0 - coverage),
            float3(0.0));
    float3 ui = coverage > 0.0
            ? primeExtendedSrgbEotf(encodedUiPremultiplied / coverage) * coverage
            : float3(0.0);
    primeScRgbOutput.Store(
            pixel,
            float4(
                    (ui + hdr * (1.0 - coverage))
                            * primeHdrPresentPush.scRgbScale,
                    1.0));
}

