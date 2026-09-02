#language slang 2026
module "phase/post/ui_alpha_extract.slang";

struct PrimeUiAlphaExtractPushConstants
{
    uint2 extent;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeMainColor;
[[vk::binding(1, 0)]]
[[vk::image_format("r8")]]
WTexture2D<unorm float> primeUiAlpha;
[[vk::push_constant]] ConstantBuffer<PrimeUiAlphaExtractPushConstants> primeUiAlphaExtractPush;

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeUiAlphaExtractPush.extent)) return;
    uint2 minecraftPixel = uint2(
            pixel.x,
            primeUiAlphaExtractPush.extent.y - 1u - pixel.y);
    primeUiAlpha.Store(
            pixel,
            saturate(primeMainColor.Load(int3(minecraftPixel, 0)).a));
}
