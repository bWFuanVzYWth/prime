#language slang 2026
module "phase/post/auto_exposure_histogram.slang";

import "service/reconstruct/auto_exposure.slang";

struct PrimeAutoExposureHistogramPushConstants
{
    uint2 inputExtent;
    uint accumulatedMetering;
    uint reserved;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeAutoExposureInput;
[[vk::binding(1, 0)]]
[[vk::image_format("rgba16f")]]
readonly RWTexture2D<float4> primeAutoExposureAlbedo;
[[vk::binding(2, 0)]]
[[vk::image_format("r8")]]
readonly RWTexture2D<float4> primeAutoExposureMaterialClass;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> primeAutoExposureHistogram;
[[vk::push_constant]]
ConstantBuffer<PrimeAutoExposureHistogramPushConstants> primeAutoExposureHistogramPush;

groupshared uint primeWorkgroupBins[256];
groupshared uint primeWorkgroupValidCount;

uint primeAutoExposureBin(float brightness)
{
    float logBrightness = clamp(
            log2(max(brightness, exp2(PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS))),
            PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS,
            PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS);
    return min(
            uint(floor(
                    (logBrightness - PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS)
                            * (256.0 / PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE))),
            255);
}

public void primePhase(uint3 groupId : SV_GroupID,
        uint3 groupThreadId : SV_GroupThreadID,
        uint groupIndex : SV_GroupIndex)
{
    primeWorkgroupBins[groupIndex] = 0;
    if (groupIndex == 0) primeWorkgroupValidCount = 0;
    GroupMemoryBarrierWithGroupSync();

    uint2 blockOrigin = groupId.xy * 64 + groupThreadId.xy * 4;
    for (uint y = 0; y < 4; ++y) {
        for (uint x = 0; x < 4; ++x) {
            uint2 pixel = blockOrigin + uint2(x, y);
            if (any(pixel >= primeAutoExposureHistogramPush.inputExtent)) continue;

            float3 radiance = primeAutoExposureInput.Load(int3(pixel, 0)).rgb;
            if (any(isnan(radiance)) || any(isinf(radiance))) continue;

            float brightness;
            if (primeAutoExposureHistogramPush.accumulatedMetering != 0) {
                brightness = primeAutoExposureInput.Load(int3(pixel, 0)).a;
            } else {
                uint guideWidth;
                uint guideHeight;
                primeAutoExposureAlbedo.GetDimensions(guideWidth, guideHeight);
                int2 guideExtent = int2(guideWidth, guideHeight);
                int2 guidePixel = min(
                        int2(
                                (float2(pixel) + 0.5)
                                        * float2(guideExtent)
                                        / float2(primeAutoExposureHistogramPush.inputExtent)),
                        guideExtent - 1);
                float4 material = primeAutoExposureAlbedo.Load(guidePixel);
                float materialClassValue =
                        primeAutoExposureMaterialClass.Load(guidePixel).x;
                if (any(isnan(material))
                        || any(isinf(material))
                        || isnan(materialClassValue)
                        || isinf(materialClassValue)) continue;

                uint materialClass =
                        uint(round(clamp(materialClassValue, 0.0, 1.0) * 3.0));
                float confidence = primeAutoExposureMaterialConfidence(
                        materialClass,
                        material.a);
                brightness = primeAutoExposureMeteredBrightness(
                        radiance,
                        material.rgb,
                        confidence);
            }
            if (isnan(brightness) || isinf(brightness)) continue;

            InterlockedAdd(
                    primeWorkgroupBins[primeAutoExposureBin(brightness)],
                    1);
            InterlockedAdd(primeWorkgroupValidCount, 1);
        }
    }
    GroupMemoryBarrierWithGroupSync();

    uint count = primeWorkgroupBins[groupIndex];
    if (count != 0) {
        InterlockedAdd(primeAutoExposureHistogram[groupIndex], count);
    }
    if (groupIndex == 0 && primeWorkgroupValidCount != 0) {
        InterlockedAdd(
                primeAutoExposureHistogram[256],
                primeWorkgroupValidCount);
    }
}

