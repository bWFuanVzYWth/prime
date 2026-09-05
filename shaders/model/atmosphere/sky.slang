#language slang 2026
module "model/atmosphere/sky.slang";

import "prime_abi_types.slang";


// Camera geometry and LUT addressing are visible to rendering entries. Spectral integration
// and Rec.2020 conversion live in spectrum.slang, visible only to LUT producers.
public static const float ATM_PI = 3.141592653589793;
public static const float ATM_INV_PI = 0.3183098861837907;
public static const float ATM_INV_4PI = 0.07957747154594767;
public static const float ATM_RAYLEIGH_PHASE_SCALE = 0.05968310365946075;
// World positions and travelled distances enter this unchanged physical atmosphere
// through ATM_WORLD_UNIT_SCALE_KM. The explicit factor documents that coordinate contract.
public static const float ATM_WORLD_TO_ATMOSPHERE_SCALE =
        PRIME_ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE;
public static const float ATM_BOTTOM_RADIUS_KM = PRIME_ATMOSPHERE_BOTTOM_RADIUS_KM;
public static const float ATM_TOP_RADIUS_KM = PRIME_ATMOSPHERE_TOP_RADIUS_KM;
public static const float ATM_THICKNESS_KM =
        ATM_TOP_RADIUS_KM - ATM_BOTTOM_RADIUS_KM;
public static const float ATM_WORLD_SEA_LEVEL_Y = PRIME_ATMOSPHERE_WORLD_SEA_LEVEL_Y;
public static const float ATM_WORLD_UNIT_SCALE_KM = PRIME_ATMOSPHERE_WORLD_UNIT_SCALE_KM;
public static const float ATM_PLANET_RADIUS_OFFSET_KM = 0.01;
public static const float ATM_MULTI_ALTITUDE_SCALE_KM = 8.0;
public static const float ATM_SKY_FRACTION = 0.75;
public static const float ATM_GROUND_FRACTION = 0.25;
public static const float ATM_AERIAL_MAX_DISTANCE_KM = PRIME_ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;
public static const float ATM_SPACE_SUN_INTENSITY = PRIME_ATMOSPHERE_SPACE_SUN_INTENSITY;
public static const float ATM_SUN_ANGULAR_RADIUS_RADIANS =
        PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS;
public static const float ATM_REFERENCE_SUN_INTENSITY = 205.0;

public struct AtmRaySegment {
    public float topDistanceKm;
    public float groundDistanceKm;
    public float maximumDistanceKm;
    public bool hitsGround;
};

public float atmRaySphere(float3 origin, float3 direction, float radius) {
    float b = dot(origin, direction);
    float c = dot(origin, origin) - radius * radius;
    if (c > 0.0 && b > 0.0) {
        return -1.0;
    }
    float discriminant = b * b - c;
    if (discriminant < 0.0) {
        return -1.0;
    }
    return discriminant > b * b ? -b + sqrt(discriminant) : -b - sqrt(discriminant);
}

// Integral of exp(-extinction * distance) over one homogeneous segment. The Taylor branch
// avoids cancellation at small optical depth without replacing zero extinction by absorption.
public float atmHomogeneousSegmentIntegral(float extinction, float length)
{
    float segmentLength = max(length, 0.0);
    float x = extinction * segmentLength;
    if (x <= 0.05) {
        float factor = 1.0 + x * (-0.5 + x * (1.0 / 6.0
                + x * (-1.0 / 24.0 + x * (1.0 / 120.0))));
        return segmentLength * factor;
    }
    return segmentLength * (1.0 - exp(-x)) / x;
}

public float4 atmHomogeneousSegmentIntegral(float4 extinction, float length)
{
    return float4(
            atmHomogeneousSegmentIntegral(extinction.x, length),
            atmHomogeneousSegmentIntegral(extinction.y, length),
            atmHomogeneousSegmentIntegral(extinction.z, length),
            atmHomogeneousSegmentIntegral(extinction.w, length));
}

public AtmRaySegment atmRaySegment(float3 origin, float3 direction) {
    AtmRaySegment segment;
    segment.topDistanceKm = atmRaySphere(origin, direction, ATM_TOP_RADIUS_KM);
    segment.groundDistanceKm = atmRaySphere(origin, direction, ATM_BOTTOM_RADIUS_KM);
    segment.hitsGround = segment.groundDistanceKm >= 0.0;
    segment.maximumDistanceKm = segment.hitsGround
            ? segment.groundDistanceKm
            : segment.topDistanceKm;
    return segment;
}

public bool atmDistantDirectionVisible(float eyeRadiusKm, float3 direction) {
    float3 horizonPosition = float3(
            0.0, eyeRadiusKm - ATM_PLANET_RADIUS_OFFSET_KM, 0.0);
    return !atmRaySegment(horizonPosition, direction).hitsGround;
}

public float3 atmMoveToTop(float3 position, float3 direction) {
    float radius = length(position);
    if (radius <= ATM_TOP_RADIUS_KM) {
        return position;
    }
    float distance = atmRaySphere(position, direction, ATM_TOP_RADIUS_KM);
    if (distance < 0.0) {
        return position;
    }
    return position + direction * distance
            - position / max(radius, 1.0e-6) * ATM_PLANET_RADIUS_OFFSET_KM;
}

public float atmRayleighPhase(float cosine) {
    return ATM_RAYLEIGH_PHASE_SCALE * (1.0 + cosine * cosine);
}

public float2 atmTransmittanceUv(float cosine, float normalizedAltitude) {
    float radius = ATM_BOTTOM_RADIUS_KM
            + clamp(normalizedAltitude, 0.0, 1.0) * ATM_THICKNESS_KM;
    float mu = clamp(cosine, -1.0, 1.0);
    float h = sqrt(max(ATM_TOP_RADIUS_KM * ATM_TOP_RADIUS_KM
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float rho = sqrt(max(radius * radius - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float discriminant = radius * radius * (mu * mu - 1.0)
            + ATM_TOP_RADIUS_KM * ATM_TOP_RADIUS_KM;
    float distance = max(0.0, -radius * mu + sqrt(max(discriminant, 0.0)));
    float minimumDistance = ATM_TOP_RADIUS_KM - radius;
    float maximumDistance = rho + h;
    return float2(
            clamp((distance - minimumDistance)
                    / max(maximumDistance - minimumDistance, 1.0e-6), 0.0, 1.0),
            clamp(rho / max(h, 1.0e-6), 0.0, 1.0));
}

public float atmFromUnitToSubUv(float value, float resolution) {
    return (value + 0.5 / resolution) * (resolution / (resolution + 1.0));
}

public float atmFromSubUvToUnit(float value, float resolution) {
    return (value - 0.5 / resolution) * (resolution / (resolution - 1.0));
}

public float atmMultiSunMuFromU(float u) {
    float x = clamp(u, 0.0, 1.0) * 2.0 - 1.0;
    return x * abs(x);
}

public float atmMultiUFromSunMu(float sunMu) {
    float mu = clamp(sunMu, -1.0, 1.0);
    float x = sign(mu) * sqrt(abs(mu));
    return clamp(x * 0.5 + 0.5, 0.0, 1.0);
}

public float atmMultiNormalizedAltitudeFromV(float v) {
    float minimumAltitude = ATM_PLANET_RADIUS_OFFSET_KM;
    float maximumAltitude =
            ATM_THICKNESS_KM - ATM_PLANET_RADIUS_OFFSET_KM;
    float range = maximumAltitude - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    float cdf = clamp(v, 0.0, 1.0) * cdfMaximum;
    float altitude = minimumAltitude
            - ATM_MULTI_ALTITUDE_SCALE_KM * log(max(1.0 - cdf, 1.0e-6));
    return clamp(altitude / ATM_THICKNESS_KM, 0.0, 1.0);
}

public float atmMultiVFromNormalizedAltitude(float normalizedAltitude) {
    float minimumAltitude = ATM_PLANET_RADIUS_OFFSET_KM;
    float maximumAltitude =
            ATM_THICKNESS_KM - ATM_PLANET_RADIUS_OFFSET_KM;
    float range = maximumAltitude - minimumAltitude;
    float altitude = clamp(normalizedAltitude, 0.0, 1.0) * ATM_THICKNESS_KM;
    float h = clamp(altitude, minimumAltitude, maximumAltitude) - minimumAltitude;
    float cdfMaximum = max(1.0 - exp(-range / ATM_MULTI_ALTITUDE_SCALE_KM), 1.0e-6);
    return clamp((1.0 - exp(-h / ATM_MULTI_ALTITUDE_SCALE_KM)) / cdfMaximum, 0.0, 1.0);
}

public float2 atmMultiUv(float sunMu, float normalizedAltitude, float2 dimensions) {
    float2 unitUv = float2(atmMultiUFromSunMu(sunMu),
            atmMultiVFromNormalizedAltitude(normalizedAltitude));
    return float2(atmFromUnitToSubUv(unitUv.x, dimensions.x),
            atmFromUnitToSubUv(unitUv.y, dimensions.y));
}

public float2 atmSkyHorizonAngles(float viewRadiusKm) {
    float horizonDistance = sqrt(max(viewRadiusKm * viewRadiusKm
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float beta = acos(clamp(horizonDistance / max(viewRadiusKm, 1.0e-6), 0.0, 1.0));
    return float2(ATM_PI - beta, beta);
}

public float2 atmSkyUvToParameters(float2 uvIn, float2 dimensions, float viewRadiusKm) {
    float2 uv = clamp(float2(
            atmFromSubUvToUnit(uvIn.x, dimensions.x),
            atmFromSubUvToUnit(uvIn.y, dimensions.y)), 0.0, 1.0);
    float2 horizon = atmSkyHorizonAngles(viewRadiusKm);
    float viewZenithCosine;
    if (uv.y < ATM_SKY_FRACTION) {
        float coordinate = 1.0 - uv.y / ATM_SKY_FRACTION;
        coordinate = 1.0 - coordinate * coordinate;
        viewZenithCosine = cos(horizon.x * coordinate);
    } else {
        float coordinate = (uv.y - ATM_SKY_FRACTION) / ATM_GROUND_FRACTION;
        viewZenithCosine = cos(horizon.x + horizon.y * coordinate * coordinate);
    }
    float horizontalCosine = -(uv.x * uv.x * 2.0 - 1.0);
    return float2(viewZenithCosine, horizontalCosine);
}

public float3 atmSkyDirection(float2 parameters, float3 sunDirection) {
    float3 up = float3(0.0, 1.0, 0.0);
    float3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float sunHorizontalLength = length(sunHorizontal);
    float3 forward = sunHorizontalLength > 1.0e-5
            ? sunHorizontal / sunHorizontalLength
            : float3(1.0, 0.0, 0.0);
    float3 side = normalize(cross(up, forward));
    float viewZenithCosine = clamp(parameters.x, -1.0, 1.0);
    float horizontalCosine = clamp(parameters.y, -1.0, 1.0);
    float viewSine = sqrt(max(1.0 - viewZenithCosine * viewZenithCosine, 0.0));
    float sideScale = sqrt(max(1.0 - horizontalCosine * horizontalCosine, 0.0));
    return normalize(up * viewZenithCosine
            + viewSine * (forward * horizontalCosine + side * sideScale));
}

public float2 atmSkyUvFromDirection(
        float3 directionIn,
        float3 sunDirection,
        float2 dimensions,
        float viewRadiusKm) {
    float3 direction = normalize(directionIn);
    float3 up = float3(0.0, 1.0, 0.0);
    float viewZenithCosine = dot(direction, up);
    float3 viewHorizontal = direction - up * viewZenithCosine;
    float3 sunHorizontal = sunDirection - up * dot(sunDirection, up);
    float denominator = length(viewHorizontal) * length(sunHorizontal);
    float horizontalCosine = denominator > 1.0e-6
            ? clamp(dot(viewHorizontal, sunHorizontal) / denominator, -1.0, 1.0)
            : 1.0;
    bool hitsGround = atmRaySphere(float3(0.0, viewRadiusKm, 0.0), direction,
            ATM_BOTTOM_RADIUS_KM) >= 0.0;
    float2 horizon = atmSkyHorizonAngles(viewRadiusKm);
    float viewAngle = acos(clamp(viewZenithCosine, -1.0, 1.0));
    float v;
    if (!hitsGround) {
        float coordinate = clamp(viewAngle / max(horizon.x, 1.0e-6), 0.0, 1.0);
        coordinate = 1.0 - sqrt(max(1.0 - coordinate, 0.0));
        v = coordinate * ATM_SKY_FRACTION;
    } else {
        float coordinate = clamp((viewAngle - horizon.x) / max(horizon.y, 1.0e-6), 0.0, 1.0);
        v = sqrt(coordinate) * ATM_GROUND_FRACTION + ATM_SKY_FRACTION;
    }
    float u = sqrt(clamp(-horizontalCosine * 0.5 + 0.5, 0.0, 1.0));
    return float2(atmFromUnitToSubUv(u, dimensions.x),
            atmFromUnitToSubUv(v, dimensions.y));
}

public float4 atmSample2D(readonly RWTexture2D<float4> source, float2 uv) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    int2 dimensions = int2(width, height);
    float2 coordinate = clamp(uv, float2(0.0), float2(1.0))
            * float2(dimensions) - float2(0.5);
    int2 lower = int2(floor(coordinate));
    float2 fraction = frac(coordinate);
    int2 maximum = dimensions - int2(1);
    int2 p00 = clamp(lower, int2(0), maximum);
    int2 p10 = clamp(lower + int2(1, 0), int2(0), maximum);
    int2 p01 = clamp(lower + int2(0, 1), int2(0), maximum);
    int2 p11 = clamp(lower + int2(1), int2(0), maximum);
    float4 row0 = lerp(source[p00], source[p10], fraction.x);
    float4 row1 = lerp(source[p01], source[p11], fraction.x);
    return lerp(row0, row1, fraction.y);
}

public float4 atmSample3D(readonly RWTexture3D<float4> source, float3 uvw) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    int3 dimensions = int3(width, height, depth);
    float3 coordinate = clamp(uvw, float3(0.0), float3(1.0))
            * float3(dimensions) - float3(0.5);
    int3 lower = int3(floor(coordinate));
    float3 fraction = frac(coordinate);
    int3 maximum = dimensions - int3(1);
    int3 p000 = clamp(lower, int3(0), maximum);
    int3 p100 = clamp(lower + int3(1, 0, 0), int3(0), maximum);
    int3 p010 = clamp(lower + int3(0, 1, 0), int3(0), maximum);
    int3 p110 = clamp(lower + int3(1, 1, 0), int3(0), maximum);
    int3 p001 = clamp(lower + int3(0, 0, 1), int3(0), maximum);
    int3 p101 = clamp(lower + int3(1, 0, 1), int3(0), maximum);
    int3 p011 = clamp(lower + int3(0, 1, 1), int3(0), maximum);
    int3 p111 = clamp(lower + int3(1), int3(0), maximum);
    float4 z0y0 = lerp(source[p000], source[p100], fraction.x);
    float4 z0y1 = lerp(source[p010], source[p110], fraction.x);
    float4 z1y0 = lerp(source[p001], source[p101], fraction.x);
    float4 z1y1 = lerp(source[p011], source[p111], fraction.x);
    return lerp(
            lerp(z0y0, z0y1, fraction.y),
            lerp(z1y0, z1y1, fraction.y),
            fraction.z);
}
