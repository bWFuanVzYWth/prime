// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/auto_exposure.slang";

public static const float PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS = -16.0;
public static const float PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS = 20.0;
public static const float PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE =
        PRIME_AUTO_EXPOSURE_MAX_LOG_BRIGHTNESS - PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS;
public static const float PRIME_AUTO_EXPOSURE_KEY = 0.16;
public static const float PRIME_AUTO_EXPOSURE_BASELINE_EV = 0.0;
public static const float PRIME_AUTO_EXPOSURE_FULL_MIN_EV = -16.0;
public static const float PRIME_AUTO_EXPOSURE_FULL_MAX_EV = 16.0;
public static const float PRIME_AUTO_EXPOSURE_LN_10 = 2.302585092994046;
public static const float PRIME_AUTO_EXPOSURE_DARKEN_T90 = 0.5;
public static const float PRIME_AUTO_EXPOSURE_BRIGHTEN_T90 = 2.0;
public static const uint PRIME_AUTO_EXPOSURE_TAIL_DENOMINATOR = 200;
public static const float PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO = 0.18;
public static const float PRIME_AUTO_EXPOSURE_MIN_ALBEDO = 0.02;
public static const float PRIME_AUTO_EXPOSURE_ALBEDO_BLEND = 1.0;
public static const float PRIME_AUTO_EXPOSURE_SCENE_KEY_MIN_RANGE_EV = 2.0;
public static const uint PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC = 0;
public static const uint PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE = 3;

public struct PrimeAutoExposureState
{
    public float exposureEv;
    public uint initialized;
    public float targetEv;
    public float measuredLogBrightness;
};

public float primeAutoExposureBrightness(float3 radiance)
{
    return dot(
            max(radiance, float3(0.0)),
            float3(0.2627, 0.6780, 0.0593));
}

public float primeAutoExposureMaterialConfidence(
        uint materialClass,
        float primaryDistance)
{
    bool diffuseSurface = materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_DIELECTRIC
            || materialClass == PRIME_AUTO_EXPOSURE_MATERIAL_FOLIAGE;
    return diffuseSurface && primaryDistance >= 0.0 ? 1.0 : 0.0;
}

public float primeAutoExposureAlbedoScale(float3 albedo, float confidence)
{
    float albedoBrightness =
            primeAutoExposureBrightness(clamp(albedo, float3(0.0), float3(1.0)));
    float fullScale = PRIME_AUTO_EXPOSURE_REFERENCE_ALBEDO
            / max(albedoBrightness, PRIME_AUTO_EXPOSURE_MIN_ALBEDO);
    float blendedScale = lerp(
            1.0, fullScale, PRIME_AUTO_EXPOSURE_ALBEDO_BLEND);
    return lerp(1.0, blendedScale, clamp(confidence, 0.0, 1.0));
}

public float primeAutoExposureMeteredBrightness(
        float3 radiance,
        float3 albedo,
        float confidence)
{
    return primeAutoExposureBrightness(radiance)
            * primeAutoExposureAlbedoScale(albedo, confidence);
}

public float primeAutoExposureBinLogBrightness(uint bin)
{
    return PRIME_AUTO_EXPOSURE_MIN_LOG_BRIGHTNESS
            + (float(bin) + 0.5)
                    * (PRIME_AUTO_EXPOSURE_LOG_BRIGHTNESS_RANGE / 256.0);
}

public float primeAutoExposureSceneKeyBiasEv(
        float measuredLogBrightness,
        float minimumLogBrightness,
        float maximumLogBrightness)
{
    float range = maximumLogBrightness - minimumLogBrightness;
    if (range <= 0.0) return 0.0;
    float q = (
            2.0 * measuredLogBrightness
                    - minimumLogBrightness
                    - maximumLogBrightness)
            / max(range, PRIME_AUTO_EXPOSURE_SCENE_KEY_MIN_RANGE_EV);
    return 2.0 * q;
}

public float primeAutoExposureTargetEv(
        float measuredLogBrightness,
        float minimumLogBrightness,
        float maximumLogBrightness,
        float compensation)
{
    float fullTargetEv = clamp(
            log2(PRIME_AUTO_EXPOSURE_KEY) + PRIME_AUTO_EXPOSURE_BASELINE_EV
                    - measuredLogBrightness
                    + primeAutoExposureSceneKeyBiasEv(
                            measuredLogBrightness,
                            minimumLogBrightness,
                            maximumLogBrightness),
            PRIME_AUTO_EXPOSURE_FULL_MIN_EV,
            PRIME_AUTO_EXPOSURE_FULL_MAX_EV);
    return lerp(
            PRIME_AUTO_EXPOSURE_BASELINE_EV,
            fullTargetEv,
            compensation);
}

public float primeAutoExposureAdapt(
        float previousEv,
        float targetEv,
        float deltaSeconds)
{
    float t90 = targetEv < previousEv
            ? PRIME_AUTO_EXPOSURE_DARKEN_T90
            : PRIME_AUTO_EXPOSURE_BRIGHTEN_T90;
    float blend = 1.0 - exp(
            -max(deltaSeconds, 0.0) * PRIME_AUTO_EXPOSURE_LN_10 / t90);
    return lerp(previousEv, targetEv, clamp(blend, 0.0, 1.0));
}
