// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/atmosphere/epipolar_shadow.slang";

import "prime_abi_types.slang";
import "service/image/access.slang";
import "math/sun_shadow_hierarchy.slang";

[[vk::binding(18, 0)]] [[vk::image_format("rg32f")]]
public readonly RWTexture2D<float2> sunShadowHierarchy0;
[[vk::binding(19, 0)]] [[vk::image_format("rg32f")]]
public readonly RWTexture2D<float2> sunShadowHierarchy1;
[[vk::binding(20, 0)]] [[vk::image_format("rg32f")]]
public readonly RWTexture2D<float2> sunShadowHierarchy2;
[[vk::binding(21, 0)]] [[vk::image_format("rg32f")]]
public readonly RWTexture2D<float2> sunShadowHierarchy3;
[[vk::binding(22, 0)]] [[vk::image_format("rg32f")]]
public readonly RWTexture2D<float2> sunShadowHierarchy4;

public struct AtmospherePushConstants {
    public float4x4 inverseViewProjection;
    public float eyeRadiusKm;
    public float maximumAerialDistanceKm;
    public float2 epipoleNdc;
    public float4 sunDirectionIntensity;
    public float4 shadowDirectionBank;
    public float4 shadowCameraEnabled;
};

[[vk::push_constant]]
public ConstantBuffer<AtmospherePushConstants> atmospherePush;

public static const int SUN_SHADOW_CASCADE_COUNT = 5;
public static const float SUN_SHADOW_MAX_DISTANCE_METERS = 2048.0;
public static const float SUN_SHADOW_DEPTH_BIAS_METERS = 0.02;
public static const float SUN_SHADOW_CASCADE_RADIUS_TEXELS = 254.0;
public static const float SUN_SHADOW_TRAVERSAL_EPSILON = 1.0e-5;

float2 sunShadowHierarchyRange(int cascade, int level, int2 worldNode) {
    int resolution = primeSunShadowHierarchyResolution(level);
    int2 coordinate = primeSunShadowHierarchyOffset(level)
            + (worldNode & (resolution - 1));
    if (cascade == 0) return primeReadonlyImageLoad(sunShadowHierarchy0, coordinate).rg;
    if (cascade == 1) return primeReadonlyImageLoad(sunShadowHierarchy1, coordinate).rg;
    if (cascade == 2) return primeReadonlyImageLoad(sunShadowHierarchy2, coordinate).rg;
    if (cascade == 3) return primeReadonlyImageLoad(sunShadowHierarchy3, coordinate).rg;
    return primeReadonlyImageLoad(sunShadowHierarchy4, coordinate).rg;
}

public void sunShadowBasis(
        float3 directionToSun, out float3 basisU, out float3 basisV) {
    // Permuted Frisvad basis keeps its unavoidable seam outside the visible solar hemisphere.
    if (directionToSun.y < -0.9999999) {
        basisU = float3(1.0, 0.0, 0.0);
    } else {
        float reciprocal = 1.0 / (1.0 + directionToSun.y);
        basisU = normalize(float3(
                1.0 - directionToSun.x * directionToSun.x * reciprocal,
                -directionToSun.x,
                -directionToSun.x * directionToSun.z * reciprocal));
    }
    basisV = cross(directionToSun, basisU);
}

public float sunShadowTexelSize(int cascade) {
    return cascade == 0 ? 0.5
            : (cascade == 1 ? 1.0
            : (cascade == 2 ? 2.0
            : (cascade == 3 ? 4.0 : 8.0)));
}

public interface IPrimeSunShadowProfileSource {
    bool enabled();
    float texelSize(int cascade);
    float2 hierarchyRange(int cascade, int level, int2 worldNode);
}

public struct PrimeAtmosphereSunShadowProfileSource
        : IPrimeSunShadowProfileSource {
    public bool enabled() {
        return atmospherePush.shadowCameraEnabled.w >= 0.5;
    }

    public float texelSize(int cascade) {
        return sunShadowTexelSize(cascade);
    }

    public float2 hierarchyRange(int cascade, int level, int2 worldNode) {
        return sunShadowHierarchyRange(cascade, level, worldNode);
    }
};

// Adapted from Intel's Outdoor Light Scattering Sample.
// Copyright 2017 Intel Corporation
// Licensed under the Apache License, Version 2.0. See
// THIRD_PARTY_LICENSES/APACHE-2.0.txt and OUTDOOR-LIGHT-SCATTERING-NOTICE.txt.
//
// Prime modification: the reference's raster-shadow line and 1D min/max texture
// are replaced by an exact leaf-cell profile over world-space RT clipmaps.
// Missing coverage and profile overflow stay shadowed to prevent cave light leaks.

public static const int SUN_SHADOW_PROFILE_NEAR_CAPACITY = 512;
public static const int SUN_SHADOW_PROFILE_FAR_CAPACITY = 256;
public static const int SUN_SHADOW_PROFILE_TOTAL_CAPACITY =
        SUN_SHADOW_PROFILE_NEAR_CAPACITY
                + (SUN_SHADOW_CASCADE_COUNT - 1)
                        * SUN_SHADOW_PROFILE_FAR_CAPACITY;
public static const float SUN_SHADOW_PROFILE_EPSILON = 1.0e-4;

public groupshared float3 sunShadowProfileBasisU;
public groupshared float3 sunShadowProfileBasisV;
public groupshared float3 sunShadowProfileDirectionToSun;
public groupshared float2 sunShadowProfileCamera;
public groupshared float2 sunShadowProfileDirection;
public groupshared float sunShadowProfileDirectionMax;
public groupshared float sunShadowProfileCameraDepth;
public groupshared int sunShadowProfileCounts[SUN_SHADOW_CASCADE_COUNT];
public groupshared float2 sunShadowProfileEntries[SUN_SHADOW_PROFILE_TOTAL_CAPACITY];

public int primeSunShadowProfileCount(int cascade) {
    return sunShadowProfileCounts[cascade];
}

public float2 primeSunShadowProfileEntry(int index) {
    return sunShadowProfileEntries[index];
}

public void primeInitializeSunShadowProfile(
        float3 directionToSun,
        float3 cameraPosition,
        float3 edgeDirection) {
    sunShadowProfileDirectionToSun = normalize(directionToSun);
    sunShadowBasis(
            sunShadowProfileDirectionToSun,
            sunShadowProfileBasisU,
            sunShadowProfileBasisV);
    sunShadowProfileCamera = float2(
            dot(cameraPosition, sunShadowProfileBasisU),
            dot(cameraPosition, sunShadowProfileBasisV));
    sunShadowProfileCameraDepth = dot(
            cameraPosition, sunShadowProfileDirectionToSun);
    float2 projectedEdge = float2(
            dot(edgeDirection, sunShadowProfileBasisU),
            dot(edgeDirection, sunShadowProfileBasisV));
    float projectedLength = length(projectedEdge);
    sunShadowProfileDirection = projectedLength > 1.0e-6
            ? projectedEdge / projectedLength
            : float2(1.0, 0.0);
    sunShadowProfileDirectionMax = max(
            abs(sunShadowProfileDirection.x),
            abs(sunShadowProfileDirection.y));
}

int sunShadowProfileBase(int cascade) {
    return cascade == 0
            ? 0
            : SUN_SHADOW_PROFILE_NEAR_CAPACITY
                    + (cascade - 1) * SUN_SHADOW_PROFILE_FAR_CAPACITY;
}

int sunShadowProfileCapacity(int cascade) {
    return cascade == 0
            ? SUN_SHADOW_PROFILE_NEAR_CAPACITY
            : SUN_SHADOW_PROFILE_FAR_CAPACITY;
}

public float sunShadowProfileCascadeEnd<Source>(Source source, int cascade)
    where Source : IPrimeSunShadowProfileSource
{
    float radius = SUN_SHADOW_CASCADE_RADIUS_TEXELS
            * source.texelSize(cascade);
    return min(
            radius / max(
                    sunShadowProfileDirectionMax,
                    SUN_SHADOW_PROFILE_EPSILON),
            SUN_SHADOW_MAX_DISTANCE_METERS);
}

public void sunShadowBuildProfileCascade<Source>(Source source, int cascade)
    where Source : IPrimeSunShadowProfileSource
{
    if (!source.enabled()) {
        sunShadowProfileCounts[cascade] = 0;
        return;
    }

    float startScalar = cascade == 0
            ? 0.0
            : sunShadowProfileCascadeEnd(source, cascade - 1);
    float endScalar = sunShadowProfileCascadeEnd(source, cascade);
    if (endScalar <= startScalar + SUN_SHADOW_PROFILE_EPSILON) {
        sunShadowProfileCounts[cascade] = 0;
        return;
    }

    float texelSize = source.texelSize(cascade);
    float2 gridRate = sunShadowProfileDirection / texelSize;
    // Only the toroidal storage coordinate matters. Keeping DDA coordinates
    // within one clipmap period prevents the directional boundary offset from
    // disappearing into the ULP of a large world-space grid coordinate.
    float2 absoluteGridOrigin = sunShadowProfileCamera / texelSize;
    float2 gridPoint = fmod(
            absoluteGridOrigin + gridRate * startScalar,
            float(PRIME_SUN_SHADOW_RESOLUTION));
    int2 cell = int2(floor(
            gridPoint + sign(gridRate) * SUN_SHADOW_PROFILE_EPSILON));
    int2 cellStep = int2(sign(gridRate));
    float2 nextCrossing = float2(1.0e20);
    float2 crossingStep = float2(1.0e20);
    if (gridRate.x > 0.0) {
        nextCrossing.x =
                (float(cell.x + 1) - gridPoint.x) / gridRate.x;
        crossingStep.x = 1.0 / gridRate.x;
    } else if (gridRate.x < 0.0) {
        nextCrossing.x =
                (float(cell.x) - gridPoint.x) / gridRate.x;
        crossingStep.x = -1.0 / gridRate.x;
    }
    if (gridRate.y > 0.0) {
        nextCrossing.y =
                (float(cell.y + 1) - gridPoint.y) / gridRate.y;
        crossingStep.y = 1.0 / gridRate.y;
    } else if (gridRate.y < 0.0) {
        nextCrossing.y =
                (float(cell.y) - gridPoint.y) / gridRate.y;
        crossingStep.y = -1.0 / gridRate.y;
    }
    float profileLength = endScalar - startScalar;
    float cursor = 0.0;
    int count = 0;
    int base = sunShadowProfileBase(cascade);
    int capacity = sunShadowProfileCapacity(cascade);

    for (; count < capacity; ++count) {
        float spanEnd = min(
                profileLength,
                min(nextCrossing.x, nextCrossing.y));
        if (spanEnd <= cursor + SUN_SHADOW_PROFILE_EPSILON) {
            sunShadowProfileCounts[cascade] = -1;
            return;
        }

        float2 resolvedShadow =
                source.hierarchyRange(cascade, 0, cell);
        sunShadowProfileEntries[base + count] =
                float2(startScalar + spanEnd, resolvedShadow.x);
        cursor = spanEnd;
        if (cursor + SUN_SHADOW_PROFILE_EPSILON >= profileLength) {
            sunShadowProfileCounts[cascade] = count + 1;
            return;
        }

        bool crossesX = nextCrossing.x
                <= spanEnd + SUN_SHADOW_PROFILE_EPSILON;
        bool crossesY = nextCrossing.y
                <= spanEnd + SUN_SHADOW_PROFILE_EPSILON;
        if (!crossesX && !crossesY) {
            sunShadowProfileCounts[cascade] = -1;
            return;
        }
        if (crossesX) {
            cell.x += cellStep.x;
            nextCrossing.x += crossingStep.x;
        }
        if (crossesY) {
            cell.y += cellStep.y;
            nextCrossing.y += crossingStep.y;
        }
    }

    // A truncated profile is unknown, never lit.
    sunShadowProfileCounts[cascade] = -1;
}

int sunShadowProfileFind(int cascade, float scalar) {
    int count = sunShadowProfileCounts[cascade];
    int base = sunShadowProfileBase(cascade);
    int low = 0;
    int high = count;
    while (low < high) {
        int middle = (low + high) >> 1;
        if (sunShadowProfileEntries[base + middle].x
                <= scalar + SUN_SHADOW_PROFILE_EPSILON) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    return low;
}

float sunShadowProfileCascadeLitLength(
        int cascade,
        float startDistance,
        float endDistance,
        float scalarRate,
        float rayDepthRate) {
    int count = sunShadowProfileCounts[cascade];
    if (count <= 0 || endDistance <= startDistance) {
        return 0.0;
    }

    int base = sunShadowProfileBase(cascade);
    if (scalarRate <= SUN_SHADOW_PROFILE_EPSILON) {
        return primeSunShadowLeafVisibleLength(
                sunShadowProfileCameraDepth,
                rayDepthRate,
                startDistance,
                endDistance,
                sunShadowProfileEntries[base].y,
                SUN_SHADOW_DEPTH_BIAS_METERS);
    }

    float startScalar = scalarRate * startDistance;
    int index = sunShadowProfileFind(cascade, startScalar);
    float cursor = startDistance;
    float litLength = 0.0;
    int capacity = sunShadowProfileCapacity(cascade);
    for (int traversal = 0;
            traversal < capacity
                    && index < count
                    && cursor + SUN_SHADOW_TRAVERSAL_EPSILON < endDistance;
            ++traversal, ++index) {
        float2 entry = sunShadowProfileEntries[base + index];
        float spanEnd = min(entry.x / scalarRate, endDistance);
        if (spanEnd <= cursor + SUN_SHADOW_TRAVERSAL_EPSILON) {
            continue;
        }
        litLength += primeSunShadowLeafVisibleLength(
                sunShadowProfileCameraDepth,
                rayDepthRate,
                cursor,
                spanEnd,
                entry.y,
                SUN_SHADOW_DEPTH_BIAS_METERS);
        cursor = spanEnd;
    }
    // Any tail not represented by the profile is intentionally shadowed.
    return litLength;
}

public float localSunVisibilityInterval<Source>(
        Source source,
        float3 rayDirection,
        float startDistance,
        float endDistance)
    where Source : IPrimeSunShadowProfileSource
{
    if (!source.enabled()
            || endDistance <= startDistance) {
        return 1.0;
    }
    float tracedEnd = min(endDistance, SUN_SHADOW_MAX_DISTANCE_METERS);
    if (tracedEnd <= startDistance) {
        return 0.0;
    }

    float2 projectedRay = float2(
            dot(rayDirection, sunShadowProfileBasisU),
            dot(rayDirection, sunShadowProfileBasisV));
    float scalarRate = dot(projectedRay, sunShadowProfileDirection);
    float perpendicularRate = abs(
            projectedRay.x * sunShadowProfileDirection.y
                    - projectedRay.y * sunShadowProfileDirection.x);
    if (scalarRate < -SUN_SHADOW_PROFILE_EPSILON
            || perpendicularRate > 2.0e-4) {
        return 0.0;
    }
    scalarRate = max(scalarRate, 0.0);
    float rayDepthRate =
            dot(rayDirection, sunShadowProfileDirectionToSun);

    float cursor = startDistance;
    float litLength = 0.0;
    for (int cascade = 0;
            cascade < SUN_SHADOW_CASCADE_COUNT && cursor < tracedEnd;
            ++cascade) {
        float cascadeEnd = scalarRate > SUN_SHADOW_PROFILE_EPSILON
                ? min(
                        tracedEnd,
                        sunShadowProfileCascadeEnd(source, cascade) / scalarRate)
                : tracedEnd;
        if (cascadeEnd > cursor) {
            litLength += sunShadowProfileCascadeLitLength(
                    cascade,
                    cursor,
                    cascadeEnd,
                    scalarRate,
                    rayDepthRate);
            cursor = cascadeEnd;
        }
    }
    return clamp(
            litLength / (endDistance - startDistance),
            0.0,
            1.0);
}

