#language slang 2026
module "phase/post/rr_prepare.slang";

import "service/image/access.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/epipolar.slang";
import "model/material/defaults.slang";
import "service/reconstruct/nrd/common.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrNoisyDiffuse;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrNoisySpecular;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> rrDiffuseMaterial;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> rrSpecularMaterial;
[[vk::binding(4, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> rrSourceNormalRoughness;
[[vk::binding(5, 0)]] [[vk::image_format("r32f")]] writeonly RWTexture2D<float4> rrLinearViewZ;
[[vk::binding(6, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> rrPrimaryPosition;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrSunLighting;
[[vk::binding(8, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> rrStableRadiance;
[[vk::binding(9, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> rrAerialRadiance;
[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> rrAerialTransmittance;
[[vk::binding(11, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> rrInputColor;
[[vk::binding(12, 0)]] [[vk::image_format("rg16f")]] writeonly RWTexture2D<float4> rrMotion;
[[vk::binding(13, 0)]] [[vk::image_format("rgba32f")]] writeonly RWTexture2D<float4> rrNormalRoughness;
[[vk::binding(14, 0)]] [[vk::image_format("rg32f")]] writeonly RWTexture2D<float4> rrSpecularMotion;
[[vk::binding(15, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> rrReflectionPreviousVirtualPosition;
[[vk::binding(16, 0)]] [[vk::image_format("r16f")]] writeonly RWTexture2D<float4> rrSpecularHitDistance;
[[vk::binding(17, 0)]] [[vk::image_format("r16f")]] writeonly RWTexture2D<float4> rrResponsivity;

struct PrimeRrPreparePushConstants {
    float4x4 currentClipToWorld;
    float4x4 previousWorldToClip;
    float4x4 currentWorldToView;
    float sunRadianceMultiplier;
    float responsivity;
    float2 epipoleNdc;
    float2 currentJitterPixels;
};
[[vk::push_constant]] ConstantBuffer<PrimeRrPreparePushConstants> rrPush;

static const float PRIME_RR_SKY_VIEW_Z = 65504.0;
static const float PRIME_RR_SPECULAR_MOTION_ROUGHNESS = 0.25;

// The viewport corner angles are identical for every lane in this 8x8 workgroup.
groupshared AtmEpipolarRange rrEpipolarRange;

float3 rrRayDirection(float2 screenUv) {
    float2 clipXY = screenUv * float2(2.0, -2.0) + float2(-1.0, 1.0);
    float4 nearPoint = rrPush.currentClipToWorld * float4(clipXY, 1.0, 1.0);
    float4 farPoint = rrPush.currentClipToWorld * float4(clipXY, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

bool rrPreviousMotion(
        float2 currentSampleUv,
        float3 previousTarget,
        bool directional,
        out float2 motion) {
    motion = float2(0.0);
    // The primary point was traced through pixel center + the current camera jitter. Projecting
    // that point with a non-jittered matrix therefore lands at this sample UV, not at pixel center.
    // Subtracting pixel center would leak the current Halton offset into every static motion vector.
    float4 previousClip = rrPush.previousWorldToClip
            * float4(previousTarget, directional ? 0.0 : 1.0);
    if (!(previousClip.w > 1.0e-6)
            || any(isnan(previousClip))
            || any(isinf(previousClip))) {
        return false;
    }
    float2 previousNdc = previousClip.xy / previousClip.w;
    float2 previousUv = previousNdc * float2(0.5, -0.5) + float2(0.5);
    // NGX receives current-frame to previous-frame motion without jitter in normalized UV units.
    // Static geometry projects back to currentSampleUv, so its vector is zero. The bridge supplies
    // renderWidth/renderHeight as MV scales to convert actual camera motion to pixel space.
    motion = previousUv - currentSampleUv;
    if (any(isnan(motion)) || any(isinf(motion))) {
        motion = float2(0.0);
        return false;
    }
    return true;
}

float2 rrMotionVector(float2 currentSampleUv, float3 position, bool sky) {
    float2 motion;
    rrPreviousMotion(
            currentSampleUv,
            sky ? rrRayDirection(currentSampleUv) : position,
            sky,
            motion);
    return motion;
}

float3 rrApplyAerial(
        int2 pixel, int2 extent, float primaryDistance, float3 radiance) {
    if (!primeNrdIsFinite(primaryDistance) || primaryDistance < 0.0) {
        return radiance;
    }
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    int3 dimensions = primeReadonlyImageSize(rrAerialRadiance);
    float firstSliceDepth = 0.5 / float(max(dimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    float2 screenUv = (float2(pixel) + float2(0.5)
            + rrPush.currentJitterPixels) / float2(extent);
    float3 uvw = float3(screenUv, normalizedDepth);
    float3 inscatter = max(
            atmSampleEpipolarWithRange(
                    rrAerialRadiance,
                    screenUv,
                    normalizedDepth,
                    rrPush.epipoleNdc,
                    rrEpipolarRange).rgb,
            float3(0.0));
    float3 transmittance = clamp(
            atmSample3D(rrAerialTransmittance, uvw).rgb,
            float3(0.0),
            float3(1.0));
    if (any(isnan(inscatter)) || any(isinf(inscatter))) {
        inscatter = float3(0.0);
    }
    if (any(isnan(transmittance)) || any(isinf(transmittance))) {
        transmittance = float3(1.0);
    }
    return radiance * lerp(float3(1.0), transmittance, nearWeight)
            + inscatter * nearWeight * rrPush.sunRadianceMultiplier;
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    if (groupIndex == 0u) {
        rrEpipolarRange = atmEpipolarVisibleRange(
                rrPush.epipoleNdc);
    }
    GroupMemoryBarrierWithGroupSync();
    int2 pixel = int2(dispatchThreadId.xy);
    int2 extent = primeWriteImageSize(rrInputColor);
    if (any(pixel >= extent)) {
        return;
    }

    primeWriteImageStore(rrResponsivity, pixel, float4(rrPush.responsivity));

    float4 material = primeImageLoad(rrDiffuseMaterial, pixel);
    float4 specularMaterial = primeImageLoad(rrSpecularMaterial, pixel);
    float4 noisySpecular = primeReadonlyImageLoad(rrNoisySpecular, pixel);
    primeWriteImageStore(rrSpecularHitDistance, pixel, float4(
            primeNrdSanitizeHitDistance(noisySpecular.a)));
    // This pass consumes raygen's private RR scratch contract. Radiance, distances, albedos,
    // roughness and the sky marker have already been made finite and range-limited at the writer.
    bool sky = material.a < 0.0;
    if (sky) {
        // Raygen already writes finite surface albedos. Only sky needs the SDK's neutral fallback.
        primeImageStore(rrDiffuseMaterial, pixel, float4(float3(0.5), material.a));
        primeImageStore(rrSpecularMaterial, pixel, float4(float3(0.5), specularMaterial.a));
        primeWriteImageStore(rrNormalRoughness, pixel, float4(
                0.0, 0.0, 1.0, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS));
        primeWriteImageStore(rrLinearViewZ, pixel, float4(PRIME_RR_SKY_VIEW_Z));
        float2 currentSampleUv =
                (float2(pixel) + float2(0.5) + rrPush.currentJitterPixels)
                / float2(extent);
        float2 motion = rrMotionVector(currentSampleUv, float3(0.0), true);
        float4 packedMotion = float4(
                primeNrdSanitizeMotion(float3(motion, 0.0)), 0.0);
        primeWriteImageStore(rrMotion, pixel, packedMotion);
        primeWriteImageStore(rrSpecularMotion, pixel, packedMotion);
        primeWriteImageStore(rrInputColor, pixel, float4(
                primeReadonlyImageLoad(rrStableRadiance, pixel).rgb, 1.0));
        return;
    }

    float3 normal;
    float roughness;
    primeNrdReadNormalRoughness(
            primeReadonlyImageLoad(rrSourceNormalRoughness, pixel), normal, roughness);
    primeWriteImageStore(rrNormalRoughness, pixel, float4(normal, roughness));
    // The transmitted PSR and visible reflection interface can have different roughness.
    float reflectionRoughness = specularMaterial.a;
    float4 noisyDiffuse = primeReadonlyImageLoad(rrNoisyDiffuse, pixel);
    float4 sun = primeReadonlyImageLoad(rrSunLighting, pixel);
    float3 inputRadiance = noisyDiffuse.rgb
            + noisySpecular.rgb
            + sun.rgb * sun.a
            + primeReadonlyImageLoad(rrStableRadiance, pixel).rgb;
    // Finish and store color before matrix reconstruction so lighting vectors are no longer live
    // while primary and virtual-reflection motion are projected.
    inputRadiance = rrApplyAerial(pixel, extent, material.a, inputRadiance);
    primeWriteImageStore(rrInputColor, pixel, float4(
            primeNrdClampRadiance(inputRadiance, PRIME_NRD_FP16_MAX), 1.0));

    // The position is not needed by material decode or lighting. Loading it here keeps four
    // components out of the color/aerial live range.
    float4 primary = primeReadonlyImageLoad(rrPrimaryPosition, pixel);
    bool primaryHasMotion = primary.w < 0.0;
    float primaryDistance = primaryHasMotion ? -primary.w - 1.0 : primary.w;
    float2 currentSampleUv = (float2(pixel) + float2(0.5) + rrPush.currentJitterPixels)
            / float2(extent);
    float3 currentPrimaryPosition = primaryHasMotion
            ? rrRayDirection(currentSampleUv) * primaryDistance
            : primary.xyz;
    float4 viewPosition = rrPush.currentWorldToView
            * float4(currentPrimaryPosition, 1.0);
    float viewZ = abs(viewPosition.z);
    if (!(viewZ > 0.0) || isnan(viewZ) || isinf(viewZ)) {
        viewZ = PRIME_RR_SKY_VIEW_Z;
    }
    primeWriteImageStore(rrLinearViewZ, pixel, float4(viewZ));
    float2 primaryMotion = rrMotionVector(currentSampleUv, primary.xyz, false);
    primeWriteImageStore(rrMotion, pixel, float4(
            primeNrdSanitizeMotion(float3(primaryMotion, 0.0)), 0.0));

    float2 specularMotion = primaryMotion;
    float4 reflectionPreviousVirtualPosition = primeReadonlyImageLoad(
            rrReflectionPreviousVirtualPosition, pixel);
    bool hasReflectionProbe = reflectionPreviousVirtualPosition.w >= 0.0
            && primeNrdIsFinite(reflectionPreviousVirtualPosition);
    if (hasReflectionProbe
            && reflectionRoughness < PRIME_RR_SPECULAR_MOTION_ROUGHNESS) {
        // The raygen probe supplies the previous-frame virtual target itself. Its w lane selects
        // a finite mirrored hit (zero) or an environment direction (one).
        float2 reflectionMotion;
        if (rrPreviousMotion(
                currentSampleUv,
                reflectionPreviousVirtualPosition.xyz,
                reflectionPreviousVirtualPosition.w > 0.5,
                reflectionMotion)) {
            specularMotion = reflectionMotion;
        }
    }
    primeWriteImageStore(rrSpecularMotion, pixel, float4(
            primeNrdSanitizeMotion(float3(specularMotion, 0.0)), 0.0));
}

