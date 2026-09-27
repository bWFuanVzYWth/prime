#language slang 2026
module realtime_display;
import "frame.slang";
import "math/color.slang";
import "display/prime_drt.slang";

[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(9, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> noisy;
[[vk::binding(10, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> depth;
[[vk::binding(11, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> normal;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    PrimeDRTParameters display = {frame.display.x, frame.display.y, frame.display.z,
                                  frame.display.w, frame.displayExtra.x};
    float3 value;
    switch (asuint(frame.displayExtra.w)) {
    case 1:
        value = encodeSrgb(max(workingToLinear709(noisy[id.xy].rgb) * frame.display.x, float3(0)));
        break;
    case 2: {
        float z = depth[id.xy];
        value = z < 3.402823466e38f ? float3(saturate(z / frame.displayExtra.y)) : float3(0);
        break;
    }
    case 3: {
        float4 n = normal[id.xy];
        value = n.w > 0 ? n.xyz * 0.5 + 0.5 : float3(0);
        break;
    }
    default:
        value = primeDRT(noisy[id.xy].rgb, display);
        break;
    }
    uint3 encoded = uint3(saturate(value) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? frame.dimensions.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
}
