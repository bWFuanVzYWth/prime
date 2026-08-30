#language slang 2026
module "service/reconstruct/nrd/common.slang";

public static const uint PRIME_DISPLAY_MOTION_FLAG = 0x80000000u;

import "model/material/defaults.slang";

// These encoders are the shader half of the immutable NRD 4.17 contract declared in abi.json.
// Every radiance value is demodulated linear Rec.2020 D65. Normals remain in Prime's world axes,
// roughness is linear, motion XY stores FP16 pixel-space previous-minus-current before NRD's
// reciprocal render-size scale, and view Z is positive in Minecraft block units. Changing any
// one side requires rebuilding the bundled native library.

public static const float PRIME_NRD_FP16_MAX = 65504.0;
public static const float PRIME_NRD_MAX_SURFACE_DISTANCE = 65503.0;
public static const float3 PRIME_NRD_HIT_DISTANCE_PARAMETERS = float3(3.0, 0.1, 20.0);

public bool primeNrdIsFinite(float value) {
    return !isnan(value) && !isinf(value);
}

public bool primeNrdIsFinite(float2 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

public bool primeNrdIsFinite(float3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

public bool primeNrdIsFinite(float4 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

public float primeNrdSanitizeUnit(float value, float fallback) {
    return clamp(primeNrdIsFinite(value) ? value : fallback, 0.0, 1.0);
}

public float primeNrdSanitizeHitDistance(float hitDistance) {
    // A non-finite secondary distance is conservatively a miss. Zero has a distinct NRD meaning
    // (no usable hit distance) and would create a false near reflector for a valid noisy signal.
    return primeNrdIsFinite(hitDistance)
            ? clamp(hitDistance, 0.0, PRIME_NRD_FP16_MAX)
            : PRIME_NRD_FP16_MAX;
}

public float primeNrdSanitizePrimaryDistance(float primaryDistance, bool hasSurface) {
    return hasSurface && primeNrdIsFinite(primaryDistance) && primaryDistance >= 0.0
            ? min(primaryDistance, PRIME_NRD_MAX_SURFACE_DISTANCE)
            : -1.0;
}

public float3 primeNrdSafeNormalize(float3 value, float3 fallback) {
    float lengthSquared = dot(value, value);
    if (!primeNrdIsFinite(value)
            || !primeNrdIsFinite(lengthSquared)
            || !(lengthSquared > 1.0e-12)) {
        return fallback;
    }
    float3 normalized = normalize(value);
    return primeNrdIsFinite(normalized) ? normalized : fallback;
}

public float3 primeNrdSanitizeMotion(float3 motion) {
    return primeNrdIsFinite(motion)
            ? clamp(motion, float3(-PRIME_NRD_FP16_MAX), float3(PRIME_NRD_FP16_MAX))
            : float3(0.0);
}

public float primeNrdY(float3 color) {
    return dot(color, float3(0.25, 0.5, 0.25));
}

public float3 primeNrdLinearToYCoCg(float3 color) {
    return float3(
            primeNrdY(color),
            dot(color, float3(0.5, 0.0, -0.5)),
            dot(color, float3(-0.25, 0.5, -0.25)));
}

public float3 primeNrdYCoCgToLinear(float3 color) {
    float t = color.x - color.z;
    return max(float3(t + color.y, color.x + color.z, t - color.y), float3(0.0));
}

public static const uint PRIME_NRD_GUIDE_CONDUCTOR = 1u << 8u;

public uint primeNrdGuideControl(uint materialControl, uint opticalControl) {
    return materialControl
            | (primeFresnelIsConductor(opticalControl & PRIME_OPTICAL_FRESNEL_MASK)
                    ? PRIME_NRD_GUIDE_CONDUCTOR
                    : 0u);
}

public float primeNrdMaterialId(uint materialControl) {
    // R10_G10_B10_A2_UNORM provides four exact classes. These categories deliberately describe
    // reconstruction behavior, not every authored material: ordinary dielectrics, conductors,
    // transmissive interfaces, and strand-like foliage must not exchange temporal history.
    if (primeMaterialIsFoliage(materialControl)) {
        return 1.0;
    }
    if (primeMaterialIsTransmissive(materialControl)) {
        return 2.0 / 3.0;
    }
    return (materialControl & PRIME_NRD_GUIDE_CONDUCTOR) != 0u ? 1.0 / 3.0 : 0.0;
}

public uint primeNrdPrimaryMaterialFlags(
        uint guideMaterialFlags,
        uint visibleMaterialFlags,
        bool preserveVisibleInterface) {
    // A transparent PSR guide describes the surface behind the interface. NRD must still retain
    // the visible interface class or its history can cross into a direct view of that surface.
    return preserveVisibleInterface
            && primeMaterialIsTransmissive(visibleMaterialFlags)
            ? visibleMaterialFlags
            : guideMaterialFlags;
}

public float4 primeNrdDirectNormalRoughness(float3 normal, float roughness) {
    return float4(
            primeNrdSafeNormalize(normal, float3(0.0, 0.0, 1.0)),
            primeNrdSanitizeUnit(
                    roughness, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS));
}

public void primeNrdReadNormalRoughness(
        float4 value, out float3 normal, out float roughness) {
    normal = primeNrdSafeNormalize(value.xyz, float3(0.0, 0.0, 1.0));
    roughness = primeNrdSanitizeUnit(value.w,
            PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
}

public float primeNrdNormalizedSanitizedHitDistance(
        float hitDistance,
        float viewZ,
        float roughness) {
    float spread = 1.0 - exp2(-200.0 * roughness * roughness);
    spread *= pow(roughness, 0.5);
    float scale = (PRIME_NRD_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * PRIME_NRD_HIT_DISTANCE_PARAMETERS.y)
            * lerp(PRIME_NRD_HIT_DISTANCE_PARAMETERS.z, 1.0, spread);
    return clamp(hitDistance / max(scale, 1.0e-6), 0.0, 1.0);
}

public float primeNrdNormalizedHitDistance(float hitDistance, float viewZ, float roughness) {
    // Exact REBLUR_FrontEnd_GetNormHitDist contract. The roughness-dependent scale is essential
    // for specular virtual motion; using the diffuse shortcut here destabilizes highlights.
    return primeNrdNormalizedSanitizedHitDistance(
            primeNrdSanitizeHitDistance(hitDistance),
            primeNrdIsFinite(viewZ) ? abs(viewZ) : PRIME_NRD_FP16_MAX,
            primeNrdSanitizeUnit(
                    roughness, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS));
}

public float primeNrdRadiancePeak(float3 radiance) {
    return max(radiance.x, max(radiance.y, radiance.z));
}

public float primeNrdSanitizeNonnegative(float value) {
    return primeNrdIsFinite(value) ? max(value, 0.0) : 0.0;
}

public float3 primeNrdClampRadiance(float3 radiance, float limit) {
    // Channels are independent estimators. One failed component must not erase the other two.
    radiance = float3(
            primeNrdSanitizeNonnegative(radiance.x),
            primeNrdSanitizeNonnegative(radiance.y),
            primeNrdSanitizeNonnegative(radiance.z));
    float peak = primeNrdRadiancePeak(radiance);
    float3 result = peak > limit ? radiance * (limit / peak) : radiance;
    // Multiplication may round one ulp above the target even though the scale is <= 1.
    return min(result, float3(limit));
}

public float3 primeNrdSanitizeRadiance(float3 radiance) {
    return primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX);
}

public float3 primeNrdSanitizeAlbedo(float3 albedo) {
    return float3(
            primeNrdSanitizeUnit(albedo.x, 0.0),
            primeNrdSanitizeUnit(albedo.y, 0.0),
            primeNrdSanitizeUnit(albedo.z, 0.0));
}

public float primeNrdDemodulateChannel(float radiance, float guide) {
    if (!(guide > 0.0) || !(radiance > 0.0)) return 0.0;
    // Select the clamped result before division so a valid tiny guide cannot create infinity and
    // then be mistaken for a failed channel by a later sanitizer.
    return radiance > PRIME_NRD_FP16_MAX * guide
            ? PRIME_NRD_FP16_MAX
            : radiance / guide;
}

public float3 primeNrdDemodulateSanitized(float3 radiance, float3 guide) {
    return float3(
            primeNrdDemodulateChannel(radiance.x, guide.x),
            primeNrdDemodulateChannel(radiance.y, guide.y),
            primeNrdDemodulateChannel(radiance.z, guide.z));
}

public float3 primeNrdDemodulate(float3 radiance, float3 guide) {
    return primeNrdDemodulateSanitized(
            primeNrdSanitizeRadiance(radiance),
            primeNrdSanitizeAlbedo(guide));
}

public float primeNrdClampRadianceTriple(
        inout float3 first,
        inout float3 second,
        inout float3 third,
        float limit) {
    first = float3(
            primeNrdSanitizeNonnegative(first.x),
            primeNrdSanitizeNonnegative(first.y),
            primeNrdSanitizeNonnegative(first.z));
    second = float3(
            primeNrdSanitizeNonnegative(second.x),
            primeNrdSanitizeNonnegative(second.y),
            primeNrdSanitizeNonnegative(second.z));
    third = float3(
            primeNrdSanitizeNonnegative(third.x),
            primeNrdSanitizeNonnegative(third.y),
            primeNrdSanitizeNonnegative(third.z));

    float sourcePeak = max(
            primeNrdRadiancePeak(first),
            max(primeNrdRadiancePeak(second), primeNrdRadiancePeak(third)));
    if (!(sourcePeak > 0.0)) {
        return 1.0;
    }

    float3 normalizedTotal = first / sourcePeak + second / sourcePeak + third / sourcePeak;
    float normalizedPeak = primeNrdRadiancePeak(normalizedTotal);
    float scale = min(1.0, (limit / sourcePeak) / max(normalizedPeak, 1.0e-20));
    first *= scale;
    second *= scale;
    third *= scale;
    return scale;
}

public float primeNrdClampRadiancePair(
        inout float3 first,
        inout float3 second,
        float limit) {
    first = float3(
            primeNrdSanitizeNonnegative(first.x),
            primeNrdSanitizeNonnegative(first.y),
            primeNrdSanitizeNonnegative(first.z));
    second = float3(
            primeNrdSanitizeNonnegative(second.x),
            primeNrdSanitizeNonnegative(second.y),
            primeNrdSanitizeNonnegative(second.z));
    float sourcePeak = max(primeNrdRadiancePeak(first), primeNrdRadiancePeak(second));
    if (!(sourcePeak > 0.0)) return 1.0;
    float normalizedPeak = primeNrdRadiancePeak(first / sourcePeak + second / sourcePeak);
    float scale = min(1.0, (limit / sourcePeak) / max(normalizedPeak, 1.0e-20));
    first *= scale;
    second *= scale;
    return scale;
}

public float primeNrdClampSanitizedRadiancePair(
        inout float3 first,
        inout float3 second,
        float limit) {
    float sourcePeak = max(primeNrdRadiancePeak(first), primeNrdRadiancePeak(second));
    if (!(sourcePeak > 0.0)) return 1.0;
    float normalizedPeak = primeNrdRadiancePeak(first / sourcePeak + second / sourcePeak);
    float scale = min(1.0, (limit / sourcePeak) / max(normalizedPeak, 1.0e-20));
    first *= scale;
    second *= scale;
    return scale;
}

public float4 primeNrdPackRadianceAndHitDistance(float3 radiance, float normalizedHitDistance) {
    float3 sanitized = primeNrdSanitizeRadiance(radiance);
    return float4(
            primeNrdLinearToYCoCg(sanitized),
            primeNrdSanitizeUnit(normalizedHitDistance, 0.0));
}

public float4 primeNrdPackSanitizedRadianceAndHitDistance(
        float3 radiance,
        float normalizedHitDistance) {
    return float4(primeNrdLinearToYCoCg(radiance), normalizedHitDistance);
}
