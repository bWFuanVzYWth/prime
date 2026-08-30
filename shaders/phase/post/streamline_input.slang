#language slang 2026
module "phase/post/streamline_input.slang";

import "service/reconstruct/streamline_input.slang";

struct PrimeStreamlineInputPushConstants
{
    uint2 extent;
};

[[vk::binding(0, 0)]] Texture2D<float> primeStreamlineViewZ;
[[vk::binding(1, 0)]] Texture2D<float4> primeStreamlineMotionSource;
[[vk::binding(2, 0)]]
[[vk::image_format("r32f")]]
WTexture2D<float> primeStreamlineDepth;
[[vk::binding(3, 0)]]
[[vk::image_format("rg32f")]]
WTexture2D<float2> primeStreamlineMotion;
[[vk::push_constant]]
ConstantBuffer<PrimeStreamlineInputPushConstants> primeStreamlineInputPush;

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeStreamlineInputPush.extent)) return;
    uint2 sourcePixel = uint2(
            pixel.x,
            primeStreamlineInputPush.extent.y - 1u - pixel.y);
    float viewZ = primeStreamlineViewZ.Load(int3(sourcePixel, 0));
    float2 motion = primeStreamlineMotionSource.Load(int3(sourcePixel, 0)).xy;
    primeStreamlineDepth.Store(pixel, primeStreamlineReversedDepth(viewZ));
    primeStreamlineMotion.Store(pixel, primeStreamlineTopLeftMotion(motion));
}
