// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "display/exposure_histogram.slang";
import "display/exposure.slang";
import "math/color.slang";

[[vk::binding(0, 0)]] Texture2D<float4> linearInput;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> histogram;
struct HistogramParameters {
    uint2 extent;
    uint linear709;
    uint addressEnabled;
    float4 *linearAddress;
    uint2 padding;
};
[[vk::push_constant]] ConstantBuffer<HistogramParameters> parameters;
groupshared uint bins[256];
groupshared uint validCount;

[numthreads(16, 16, 1)] void main(uint3 group : SV_GroupID, uint3 lane : SV_GroupThreadID,
                                  uint index : SV_GroupIndex) {
    bins[index] = 0;
    if (index == 0)
        validCount = 0;
    GroupMemoryBarrierWithGroupSync();
    uint2 origin = group.xy * 64 + lane.xy * 4;
    for (uint y = 0; y < 4; ++y)
        for (uint x = 0; x < 4; ++x) {
            uint2 pixel = origin + uint2(x, y);
            if (any(pixel >= parameters.extent))
                continue;
            float3 value =
                    parameters.addressEnabled != 0
                            ? parameters.linearAddress[pixel.y * parameters.extent.x + pixel.x].rgb
                            : linearInput.Load(int3(pixel, 0)).rgb;
            if (any(isnan(value)) || any(isinf(value)))
                continue;
            if (parameters.linear709 != 0)
                value = linear709ToWorking(value);
            // Legacy confidence=0 branch: raw and Offline have no material-class/albedo guide.
            float brightness = primeAutoExposureBrightness(value);
            if (isnan(brightness) || isinf(brightness))
                continue;
            float logarithm = clamp(
                    log2(max(brightness, exp2(PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS))),
                    PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS, PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS);
            uint bin = min(uint(floor((logarithm - PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS) *
                                      (256.0 / PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE))),
                           255u);
            InterlockedAdd(bins[bin], 1u);
            InterlockedAdd(validCount, 1u);
        }
    GroupMemoryBarrierWithGroupSync();
    if (bins[index] != 0)
        InterlockedAdd(histogram[index], bins[index]);
    if (index == 0 && validCount != 0)
        InterlockedAdd(histogram[256], validCount);
}
