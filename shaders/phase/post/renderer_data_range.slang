#language slang 2026
module "phase/post/renderer_data_range.slang";

struct PrimeRendererDataRangePushConstants
{
    uint2 extent;
    uint historyReset;
    uint reserved;
};

[[vk::binding(0, 0)]] Texture2D<float> primeRendererDataViewZ;
[[vk::binding(1, 0)]] Texture2D<float4> primeRendererDataMotion;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> primeRendererDataRange;
[[vk::push_constant]]
ConstantBuffer<PrimeRendererDataRangePushConstants> primeRendererDataRangePush;

static const uint PRIME_RANGE_MOTION_FINITE = 0u;
static const uint PRIME_RANGE_MOTION_NONFINITE = 1u;
static const uint PRIME_RANGE_MOTION_OUTSIDE_UNIT = 2u;
static const uint PRIME_RANGE_MOTION_NONZERO = 3u;
static const uint PRIME_RANGE_MOTION_MAX_X = 4u;
static const uint PRIME_RANGE_MOTION_MAX_Y = 5u;
static const uint PRIME_RANGE_MOTION_MAX_PIXELS = 6u;
static const uint PRIME_RANGE_DEPTH_SURFACE = 7u;
static const uint PRIME_RANGE_DEPTH_INVALID = 8u;
static const uint PRIME_RANGE_DEPTH_SKY = 9u;
static const uint PRIME_RANGE_DEPTH_MIN = 10u;
static const uint PRIME_RANGE_DEPTH_MAX = 11u;
static const uint PRIME_RANGE_HISTORY_RESET = 12u;
static const uint PRIME_RANGE_MOTION_HISTOGRAM = 16u;
static const uint PRIME_RANGE_DEPTH_HISTOGRAM = 144u;
static const uint PRIME_RANGE_HISTOGRAM_BINS = 128u;
static const float PRIME_RANGE_SKY_VIEW_Z = 65504.0;

uint primeRendererDataRangeBin(float value)
{
    // Two logarithmic bins per octave over [2^-32, 2^32); endpoints saturate.
    float scaled = (log2(value) + 32.0) * 2.0;
    return uint(clamp(floor(scaled), 0.0, 127.0));
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeRendererDataRangePush.extent)) return;
    if (all(pixel == uint2(0u))) {
        primeRendererDataRange[PRIME_RANGE_HISTORY_RESET] =
                primeRendererDataRangePush.historyReset;
    }

    float2 motion = primeRendererDataMotion.Load(int3(pixel, 0)).xy;
    if (any(isnan(motion)) || any(isinf(motion))) {
        InterlockedAdd(primeRendererDataRange[PRIME_RANGE_MOTION_NONFINITE], 1u);
    } else {
        float2 magnitude = abs(motion);
        InterlockedAdd(primeRendererDataRange[PRIME_RANGE_MOTION_FINITE], 1u);
        if (any(magnitude > 1.0)) {
            InterlockedAdd(primeRendererDataRange[PRIME_RANGE_MOTION_OUTSIDE_UNIT], 1u);
        }
        InterlockedMax(primeRendererDataRange[PRIME_RANGE_MOTION_MAX_X], asuint(magnitude.x));
        InterlockedMax(primeRendererDataRange[PRIME_RANGE_MOTION_MAX_Y], asuint(magnitude.y));
        float pixelMagnitude = max(
                magnitude.x * float(primeRendererDataRangePush.extent.x),
                magnitude.y * float(primeRendererDataRangePush.extent.y));
        InterlockedMax(
                primeRendererDataRange[PRIME_RANGE_MOTION_MAX_PIXELS],
                asuint(pixelMagnitude));
        if (pixelMagnitude > 0.0) {
            InterlockedAdd(primeRendererDataRange[PRIME_RANGE_MOTION_NONZERO], 1u);
            InterlockedAdd(
                    primeRendererDataRange[
                            PRIME_RANGE_MOTION_HISTOGRAM
                                    + primeRendererDataRangeBin(pixelMagnitude)],
                    1u);
        }
    }

    float viewZ = primeRendererDataViewZ.Load(int3(pixel, 0));
    if (!(viewZ > 0.0) || isnan(viewZ) || isinf(viewZ)) {
        InterlockedAdd(primeRendererDataRange[PRIME_RANGE_DEPTH_INVALID], 1u);
    } else if (viewZ >= PRIME_RANGE_SKY_VIEW_Z) {
        InterlockedAdd(primeRendererDataRange[PRIME_RANGE_DEPTH_SKY], 1u);
    } else {
        uint bits = asuint(viewZ);
        InterlockedAdd(primeRendererDataRange[PRIME_RANGE_DEPTH_SURFACE], 1u);
        InterlockedMin(primeRendererDataRange[PRIME_RANGE_DEPTH_MIN], bits);
        InterlockedMax(primeRendererDataRange[PRIME_RANGE_DEPTH_MAX], bits);
        InterlockedAdd(
                primeRendererDataRange[
                        PRIME_RANGE_DEPTH_HISTOGRAM
                                + primeRendererDataRangeBin(viewZ)],
                1u);
    }
}
