#language slang 2026
module "phase/post/renderer_signal_range.slang";

struct PrimeRendererSignalRangePushConstants
{
    uint2 extent;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeSignalNoisyDiffuse;
[[vk::binding(1, 0)]] Texture2D<float4> primeSignalNoisySpecular;
[[vk::binding(2, 0)]] Texture2D<float4> primeSignalNormalRoughness;
[[vk::binding(3, 0)]] Texture2D<float4> primeSignalMaterial;
[[vk::binding(4, 0)]] Texture2D<float4> primeSignalSpecularMaterial;
[[vk::binding(5, 0)]] Texture2D<uint> primeSignalReconstructionControl;
[[vk::binding(6, 0)]] RWStructuredBuffer<uint> primeSignalRange;
[[vk::push_constant]]
ConstantBuffer<PrimeRendererSignalRangePushConstants> primeSignalRangePush;

static const uint PRIME_SIGNAL_RADIANCE_FINITE = 0u;
static const uint PRIME_SIGNAL_RADIANCE_NONFINITE = 1u;
static const uint PRIME_SIGNAL_RADIANCE_NEGATIVE = 2u;
static const uint PRIME_SIGNAL_RADIANCE_SATURATED = 3u;
static const uint PRIME_SIGNAL_RADIANCE_MAX = 4u;
static const uint PRIME_SIGNAL_SURFACE = 5u;
static const uint PRIME_SIGNAL_NORMAL_NONFINITE = 6u;
static const uint PRIME_SIGNAL_NORMAL_LENGTH_ERROR_MAX = 7u;
static const uint PRIME_SIGNAL_ROUGHNESS_INVALID = 8u;
static const uint PRIME_SIGNAL_ROUGHNESS_MIN = 9u;
static const uint PRIME_SIGNAL_ROUGHNESS_MAX = 10u;
static const uint PRIME_SIGNAL_ROUGHNESS_ZERO = 11u;
static const uint PRIME_SIGNAL_ALBEDO_INVALID = 12u;
static const uint PRIME_SIGNAL_ALBEDO_MAX = 13u;
static const uint PRIME_SIGNAL_HIT_FINITE = 14u;
static const uint PRIME_SIGNAL_HIT_INVALID = 15u;
static const uint PRIME_SIGNAL_HIT_ZERO = 16u;
static const uint PRIME_SIGNAL_HIT_SATURATED = 17u;
static const uint PRIME_SIGNAL_HIT_MIN_POSITIVE = 18u;
static const uint PRIME_SIGNAL_HIT_MAX = 19u;
static const uint PRIME_SIGNAL_RADIANCE_HISTOGRAM = 32u;
static const uint PRIME_SIGNAL_ROUGHNESS_HISTOGRAM = 160u;
static const uint PRIME_SIGNAL_HIT_HISTOGRAM = 224u;
static const uint PRIME_SIGNAL_LOG_HISTOGRAM_BINS = 128u;
static const uint PRIME_SIGNAL_ROUGHNESS_HISTOGRAM_BINS = 64u;
static const uint PRIME_SIGNAL_SURFACE_VALID = 0x04u;
static const float PRIME_SIGNAL_FP16_MAX = 65504.0;

bool primeSignalFinite(float value)
{
    return !isnan(value) && !isinf(value);
}

bool primeSignalFinite(float3 value)
{
    return !any(isnan(value)) && !any(isinf(value));
}

uint primeSignalLogBin(float value)
{
    float scaled = (log2(value) + 32.0) * 2.0;
    return uint(clamp(floor(scaled), 0.0, 127.0));
}

void primeSignalRecordHitDistance(float value)
{
    if (!primeSignalFinite(value) || value < 0.0) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_HIT_INVALID], 1u);
        return;
    }
    InterlockedAdd(primeSignalRange[PRIME_SIGNAL_HIT_FINITE], 1u);
    InterlockedMax(primeSignalRange[PRIME_SIGNAL_HIT_MAX], asuint(value));
    if (value == 0.0) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_HIT_ZERO], 1u);
        return;
    }
    if (value >= PRIME_SIGNAL_FP16_MAX) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_HIT_SATURATED], 1u);
    }
    InterlockedMin(primeSignalRange[PRIME_SIGNAL_HIT_MIN_POSITIVE], asuint(value));
    InterlockedAdd(
            primeSignalRange[PRIME_SIGNAL_HIT_HISTOGRAM + primeSignalLogBin(value)],
            1u);
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeSignalRangePush.extent)) return;

    float4 diffuse = primeSignalNoisyDiffuse.Load(int3(pixel, 0));
    float4 specular = primeSignalNoisySpecular.Load(int3(pixel, 0));
    bool radianceFinite = primeSignalFinite(diffuse.rgb)
            && primeSignalFinite(specular.rgb);
    if (!radianceFinite) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_RADIANCE_NONFINITE], 1u);
    } else {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_RADIANCE_FINITE], 1u);
        if (any(diffuse.rgb < 0.0) || any(specular.rgb < 0.0)) {
            InterlockedAdd(primeSignalRange[PRIME_SIGNAL_RADIANCE_NEGATIVE], 1u);
        }
        float maximumRadiance = max(max(diffuse.r, diffuse.g), diffuse.b);
        maximumRadiance = max(
                maximumRadiance,
                max(max(specular.r, specular.g), specular.b));
        maximumRadiance = max(maximumRadiance, 0.0);
        InterlockedMax(
                primeSignalRange[PRIME_SIGNAL_RADIANCE_MAX],
                asuint(maximumRadiance));
        if (maximumRadiance >= PRIME_SIGNAL_FP16_MAX) {
            InterlockedAdd(primeSignalRange[PRIME_SIGNAL_RADIANCE_SATURATED], 1u);
        }
        if (maximumRadiance > 0.0) {
            InterlockedAdd(
                    primeSignalRange[
                            PRIME_SIGNAL_RADIANCE_HISTOGRAM
                                    + primeSignalLogBin(maximumRadiance)],
                    1u);
        }
    }

    uint control = primeSignalReconstructionControl.Load(int3(pixel, 0));
    if ((control & PRIME_SIGNAL_SURFACE_VALID) == 0u) return;
    InterlockedAdd(primeSignalRange[PRIME_SIGNAL_SURFACE], 1u);

    float4 normalRoughness = primeSignalNormalRoughness.Load(int3(pixel, 0));
    if (!primeSignalFinite(normalRoughness.xyz)) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_NORMAL_NONFINITE], 1u);
    } else {
        float lengthError = abs(length(normalRoughness.xyz) - 1.0);
        InterlockedMax(
                primeSignalRange[PRIME_SIGNAL_NORMAL_LENGTH_ERROR_MAX],
                asuint(lengthError));
    }
    float roughness = normalRoughness.w;
    if (!primeSignalFinite(roughness) || roughness < 0.0 || roughness > 1.0) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_ROUGHNESS_INVALID], 1u);
    } else {
        InterlockedMin(primeSignalRange[PRIME_SIGNAL_ROUGHNESS_MIN], asuint(roughness));
        InterlockedMax(primeSignalRange[PRIME_SIGNAL_ROUGHNESS_MAX], asuint(roughness));
        if (roughness == 0.0) {
            InterlockedAdd(primeSignalRange[PRIME_SIGNAL_ROUGHNESS_ZERO], 1u);
        }
        uint bin = min(uint(floor(roughness * 64.0)), 63u);
        InterlockedAdd(
                primeSignalRange[PRIME_SIGNAL_ROUGHNESS_HISTOGRAM + bin],
                1u);
    }

    float3 diffuseAlbedo = primeSignalMaterial.Load(int3(pixel, 0)).rgb;
    float3 specularAlbedo = primeSignalSpecularMaterial.Load(int3(pixel, 0)).rgb;
    if (!primeSignalFinite(diffuseAlbedo)
            || !primeSignalFinite(specularAlbedo)
            || any(diffuseAlbedo < 0.0)
            || any(diffuseAlbedo > 1.0)
            || any(specularAlbedo < 0.0)
            || any(specularAlbedo > 1.0)) {
        InterlockedAdd(primeSignalRange[PRIME_SIGNAL_ALBEDO_INVALID], 1u);
    } else {
        float maximumAlbedo = max(max(diffuseAlbedo.r, diffuseAlbedo.g), diffuseAlbedo.b);
        maximumAlbedo = max(
                maximumAlbedo,
                max(max(specularAlbedo.r, specularAlbedo.g), specularAlbedo.b));
        InterlockedMax(
                primeSignalRange[PRIME_SIGNAL_ALBEDO_MAX],
                asuint(maximumAlbedo));
    }

    primeSignalRecordHitDistance(diffuse.a);
    primeSignalRecordHitDistance(specular.a);
}
