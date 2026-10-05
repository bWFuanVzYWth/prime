#language slang 2026
module rr_display;
import "display/prime_drt.slang";
import "math/color.slang";
import "reconstruct/rr_format.slang";
import "reconstruct/rr_status.slang";
import "display/image_space.slang";

#if PRIME_LINEAR_OUTPUT
[[vk::binding(4, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> outputImage;
#else
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
#endif
[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> noisyColor;
[[vk::binding(11, 0)]] [[vk::image_format("r32f")]] readonly RWTexture2D<float> linearDepth;
[[vk::binding(13, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> normalRoughness;
[[vk::binding(18,
              0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> reconstructedColor;
[[vk::binding(19, 0)]] [[vk::image_format("r8")]] readonly RWTexture2D<float> guideUnresolved;

// 64-byte push ABI: input extent, output extent; bottom-up, diagnostic view, RR succeeded, spare.
struct RrDisplay {
    uint4 dimensions;
    uint4 control;
    float4 display;
    float4 extra; // saturation, depth display range, reserved, reserved
};
[[vk::push_constant]] ConstantBuffer<RrDisplay> display;

float4 noisyUpsampleRgba(float2 position) {
    int2 lo = int2(floor(position));
    int2 hi = lo + 1;
    float2 blend = frac(position);
    int2 limit = int2(display.dimensions.xy) - 1;
    lo = clamp(lo, int2(0), limit);
    hi = clamp(hi, int2(0), limit);
    return lerp(lerp(noisyColor[lo], noisyColor[int2(hi.x, lo.y)], blend.x),
                lerp(noisyColor[int2(lo.x, hi.y)], noisyColor[hi], blend.x), blend.y);
}
float3 noisyUpsample(float2 position) {
    return noisyUpsampleRgba(position).rgb;
}

bool resolvedFootprint(float2 position) {
    int2 limit = int2(display.dimensions.xy) - 1;
    int2 lo = clamp(int2(floor(position)), int2(0), limit);
    int2 hi = clamp(int2(floor(position)) + 1, int2(0), limit);
    uint mask = primeRrFootprintMask(position);
    return primeRrGuideComplete(guideUnresolved[lo]) &&
           ((mask & 2u) == 0u || primeRrGuideComplete(guideUnresolved[int2(hi.x, lo.y)])) &&
           ((mask & 4u) == 0u || primeRrGuideComplete(guideUnresolved[int2(lo.x, hi.y)])) &&
           ((mask & 8u) == 0u || primeRrGuideComplete(guideUnresolved[hi]));
}

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= display.dimensions.zw))
        return;
    float2 source = (float2(id.xy) + 0.5) *
                            (float2(display.dimensions.xy) / float2(display.dimensions.zw)) -
                    0.5;
#if PRIME_LINEAR_OUTPUT
    float4 chosen = display.control.z != 0 && resolvedFootprint(source) ? reconstructedColor[id.xy]
                                                                        : noisyUpsampleRgba(source);
    outputImage[id.xy] = float4(primeRrFiniteHalfColor(chosen.rgb),
                                isfinite(chosen.a) ? saturate(chosen.a) : 1.0);
#else
    uint2 guidePixel =
            uint2(clamp(floor(source + 0.5), float2(0), float2(display.dimensions.xy - 1)));
    float3 value;
    switch (display.control.y) {
    case 1:
        value = encodeSrgb(max(noisyUpsample(source) * display.display.x, float3(0)));
        break;
    case 2: {
        float depth = linearDepth[guidePixel];
        value = depth < PRIME_RR_SKY_DEPTH ? float3(saturate(depth / display.extra.y)) : float3(0);
        break;
    }
    case 3: {
        float3 normal = normalRoughness[guidePixel].xyz;
        value = dot(normal, normal) > 0 ? normal * 0.5 + 0.5 : float3(0);
        break;
    }
    default: {
        // A failed SDK evaluation has no valid output. The same-frame noisy input remains usable.
        // Budget-exhausted/invalid guides never masquerade as a stable reconstructed surface.
        // This is an engine output fallback; it makes no claim about NGX's private history.
        float3 linear = display.control.z != 0 && resolvedFootprint(source)
                                ? reconstructedColor[id.xy].rgb
                                : noisyUpsample(source);
        PrimeDRTParameters parameters = {display.display.x, display.display.y, display.display.z,
                                         display.display.w, display.extra.x};
        value = primeDRT(linear709ToWorking(primeRrFiniteHalfColor(linear)), parameters);
        break;
    }
    }
    uint3 encoded = uint3(saturate(value) * 255.0 + 0.5);
    uint2 targetPixel =
            uint2(id.x, display.control.x != 0 ? display.dimensions.w - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1);
#endif
}
