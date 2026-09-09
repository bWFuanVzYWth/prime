// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/post/ui_alpha_clear.compute.slang";

struct PrimeUiAlphaClearPushConstants
{
    uint2 extent;
};

[[vk::binding(0, 0)]]
[[vk::image_format("rgba8")]]
RWTexture2D<unorm float4> primeMainColor;
[[vk::push_constant]] ConstantBuffer<PrimeUiAlphaClearPushConstants> primeUiAlphaClearPush;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeUiAlphaClearPush.extent)) return;
    float4 color = primeMainColor.Load(pixel);
    primeMainColor.Store(pixel, float4(color.rgb, 0.0));
}
