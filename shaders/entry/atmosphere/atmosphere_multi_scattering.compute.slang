#language slang 2026
module "entry/atmosphere/atmosphere_multi_scattering.compute.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";


import "service/image/access.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceLow;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceHigh;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> multiScatteringLow;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> multiScatteringHigh;


static const int MULTI_RAY_STEPS = 128;
static const int MULTI_SQRT_DIRECTION_SAMPLES = 16;
static const int MULTI_DIRECTION_SAMPLES = 256;

float4 sampleTransmittance(int group, float sunMu, float normalizedAltitude) {
    float2 uv = atmTransmittanceUv(sunMu, normalizedAltitude);
    return group == 0
            ? atmSample2D(transmittanceLow, uv)
            : atmSample2D(transmittanceHigh, uv);
}

float3 sphereDirection(int sampleIndex) {
    float side = float(MULTI_SQRT_DIRECTION_SAMPLES);
    float x = float(sampleIndex / MULTI_SQRT_DIRECTION_SAMPLES) + 0.5;
    float y = float(sampleIndex % MULTI_SQRT_DIRECTION_SAMPLES) + 0.5;
    float theta = 2.0 * ATM_PI * x / side;
    float phi = acos(1.0 - 2.0 * y / side);
    float sine = sin(phi);
    return float3(cos(theta) * sine, cos(phi), sin(theta) * sine);
}

void integrateDirection(
        int group,
        float3 origin,
        float3 direction,
        float3 sunDirection,
        AtmRaySegment segment,
        out float4 radiance,
        out float4 transmittance,
        out float4 scatteringAsOne) {
    radiance = float4(0.0);
    transmittance = float4(1.0);
    scatteringAsOne = float4(0.0);
    float stepLength = segment.maximumDistanceKm / float(MULTI_RAY_STEPS);
    for (int index = 0; index < MULTI_RAY_STEPS; ++index) {
        float t = (float(index) + 0.3) * stepLength;
        float3 point = origin + direction * min(t, segment.maximumDistanceKm);
        float radius = length(point);
        float3 up = point / max(radius, 1.0e-6);
        float altitude = max(radius - ATM_BOTTOM_RADIUS_KM, 0.0);
        float normalizedAltitude = altitude / ATM_THICKNESS_KM;
        float sunMu = dot(up, sunDirection);
        bool sunOccluded = atmRaySegment(point - up * ATM_PLANET_RADIUS_OFFSET_KM,
                sunDirection).hitsGround;
        float4 sunTransmittance = sunOccluded
                ? float4(0.0)
                : sampleTransmittance(group, sunMu, normalizedAltitude);
        AtmCoefficients coefficients = atmCoefficients(group, altitude);
        float4 scattering = coefficients.aerosolScattering + coefficients.molecularScattering;
        float4 source = sunTransmittance * scattering * ATM_INV_4PI;
        float4 stepTransmittance = exp(-stepLength * coefficients.extinction);
        float4 segmentIntegral = atmHomogeneousSegmentIntegral(
                coefficients.extinction, stepLength);
        radiance += transmittance * source * segmentIntegral;
        scatteringAsOne += transmittance * scattering * segmentIntegral;
        transmittance *= stepTransmittance;
    }
    if (segment.hitsGround) {
        float3 ground = origin + direction * segment.groundDistanceKm;
        float3 normal = normalize(ground);
        float cosine = max(dot(normal, sunDirection), 0.0);
        if (cosine > 0.0) {
            float4 groundLight = sampleTransmittance(group, cosine, 0.0)
                    * float4(0.18) * cosine * ATM_INV_PI;
            radiance += transmittance * groundLight;
        }
    }
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    int2 pixel = int2(dispatchThreadId.xy);
    int group = int(dispatchThreadId.z);
    int2 dimensions = primeWriteImageSize(multiScatteringLow);
    if (any(pixel >= dimensions) || group >= 2) {
        return;
    }
    float2 uv = (float2(pixel) + float2(0.5)) / float2(dimensions);
    uv = clamp(float2(atmFromSubUvToUnit(uv.x, float(dimensions.x)),
            atmFromSubUvToUnit(uv.y, float(dimensions.y))), 0.0, 1.0);
    float sunMu = atmMultiSunMuFromU(uv.x);
    float normalizedAltitude = atmMultiNormalizedAltitudeFromV(uv.y);
    float3 sunDirection = normalize(float3(sqrt(max(1.0 - sunMu * sunMu, 0.0)), sunMu, 0.0));
    float3 origin = float3(0.0,
            ATM_BOTTOM_RADIUS_KM + normalizedAltitude * ATM_THICKNESS_KM, 0.0);
    float4 inScattered = float4(0.0);
    float4 scattering = float4(0.0);
    float4 groundFeedback = float4(0.0);
    for (int index = 0; index < MULTI_DIRECTION_SAMPLES; ++index) {
        float3 direction = sphereDirection(index);
        AtmRaySegment segment = atmRaySegment(origin, direction);
        if (segment.maximumDistanceKm < 0.0) {
            continue;
        }
        float4 radiance;
        float4 transmittance;
        float4 scatteringAsOne;
        integrateDirection(group, origin, direction, sunDirection, segment,
                radiance, transmittance, scatteringAsOne);
        inScattered += radiance;
        scattering += scatteringAsOne;
        if (segment.hitsGround) groundFeedback += transmittance * float4(0.18);
    }
    float integrationWeight = 4.0 * ATM_PI
            / float(MULTI_DIRECTION_SAMPLES) * ATM_INV_4PI;
    float4 feedback = min((scattering + groundFeedback) * integrationWeight, float4(0.9));
    float4 transfer = inScattered * integrationWeight
            / max(float4(1.0) - feedback, float4(0.1));
    if (group == 0) {
        primeWriteImageStore(multiScatteringLow, pixel, max(transfer, float4(0.0)));
    } else {
        primeWriteImageStore(multiScatteringHigh, pixel, max(transfer, float4(0.0)));
    }
}
