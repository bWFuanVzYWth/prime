#language slang 2026
module "entry/atmosphere/atmosphere_sky.compute.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";


import "prime_abi_types.slang";
import "service/image/access.slang";
import "model/atmosphere/phase.slang";
import "math/atmosphere_direction.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceLow;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> transmittanceHigh;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> multiScatteringLow;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> multiScatteringHigh;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> skyView;
[[vk::binding(23, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> cameraTransmittance;


struct AtmospherePushConstants {
    float4x4 inverseViewProjection;
    float eyeRadiusKm;
    float maximumAerialDistanceKm;
    float2 padding;
    float4 sunDirectionIntensity;
};
[[vk::push_constant]] ConstantBuffer<AtmospherePushConstants> atmospherePush;

static const int SKY_STEPS = 128;

float4 skyTransmittance(int group, float sunMu, float normalizedAltitude) {
    float2 uv = atmTransmittanceUv(sunMu, normalizedAltitude);
    return group == 0
            ? atmSample2D(transmittanceLow, uv)
            : atmSample2D(transmittanceHigh, uv);
}

float4 skyMultiScattering(int group, float sunMu, float normalizedAltitude) {
    float2 dimensions = float2(primeReadonlyImageSize(multiScatteringLow));
    float2 uv = atmMultiUv(sunMu, normalizedAltitude, dimensions);
    return group == 0
            ? atmSample2D(multiScatteringLow, uv)
            : atmSample2D(multiScatteringHigh, uv);
}

float4 integrateSkyGroup(int group, float3 origin, float3 direction, AtmRaySegment segment) {
    float3 sunDirection = atmospherePush.sunDirectionIntensity.xyz;
    float phaseCosine = dot(-direction, sunDirection);
    float molecularPhase = atmRayleighPhase(phaseCosine);
    float4 radiance = float4(0.0);
    float4 transmittance = float4(1.0);
    for (int index = 0; index < SKY_STEPS; ++index) {
        float u0 = float(index) / float(SKY_STEPS);
        float u1 = float(index + 1) / float(SKY_STEPS);
        float t0 = u0 * u0 * segment.maximumDistanceKm;
        float t1 = u1 * u1 * segment.maximumDistanceKm;
        float t = lerp(t0, t1, 0.3);
        float stepLength = max(t1 - t0, 0.0);
        float3 point = origin + direction * t;
        float radius = length(point);
        float3 up = point / max(radius, 1.0e-6);
        float altitude = max(radius - ATM_BOTTOM_RADIUS_KM, 0.0);
        float normalizedAltitude = altitude / ATM_THICKNESS_KM;
        float sunMu = dot(up, sunDirection);
        bool sunOccluded = atmRaySegment(point - up * ATM_PLANET_RADIUS_OFFSET_KM,
                sunDirection).hitsGround;
        AtmCoefficients coefficients = atmCoefficients(group, altitude);
        float4 direct = sunOccluded ? float4(0.0)
                : skyTransmittance(group, sunMu, normalizedAltitude)
                * (coefficients.molecularScattering * molecularPhase
                + atmAerosolPhaseScattering(group, altitude, phaseCosine));
        float4 multiple = skyMultiScattering(group, sunMu, normalizedAltitude)
                * (coefficients.aerosolScattering + coefficients.molecularScattering);
        float4 source = atmSolarSpectrum(group) * (direct + multiple);
        float4 stepTransmittance = exp(-stepLength * coefficients.extinction);
        radiance += transmittance * source
                * atmHomogeneousSegmentIntegral(coefficients.extinction, stepLength);
        transmittance *= stepTransmittance;
    }
    if (segment.hitsGround) {
        float3 ground = origin + direction * segment.groundDistanceKm;
        float3 normal = normalize(ground);
        float sunMu = dot(normal, sunDirection);
        float4 groundLight = skyMultiScattering(group, sunMu, 0.0);
        float cosine = max(sunMu, 0.0);
        if (cosine > 0.0) {
            groundLight += skyTransmittance(group, cosine, 0.0) * cosine * ATM_INV_PI;
        }
        radiance += atmSolarSpectrum(group) * groundLight * float4(0.18) * transmittance;
    }
    return radiance;
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    int2 pixel = int2(dispatchThreadId.xy);
    int2 dimensions = primeWriteImageSize(skyView);
    if (any(pixel >= dimensions)) {
        return;
    }
    // Shares the sky's camera-height lifetime and dispatch; the static spectral LUTs remain
    // producer-only. Consumers interpolate two RGB texels instead of eight spectral texel loads.
    uint cameraIndex = dispatchThreadId.y * uint(dimensions.x) + dispatchThreadId.x;
    if (cameraIndex < PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH) {
        float mu = primeAtmosphereDirectionCosine(float(cameraIndex)
                / float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u));
        float radius = clamp(atmospherePush.eyeRadiusKm,
                ATM_BOTTOM_RADIUS_KM + ATM_WORLD_UNIT_SCALE_KM,
                ATM_TOP_RADIUS_KM - ATM_WORLD_UNIT_SCALE_KM);
        float2 transmittanceUv = atmTransmittanceUv(
                mu, (radius - ATM_BOTTOM_RADIUS_KM) / ATM_THICKNESS_KM);
        cameraTransmittance[int2(cameraIndex, 0)] = float4(atmRec2020Transmittance(
                atmSample2D(transmittanceLow, transmittanceUv),
                atmSample2D(transmittanceHigh, transmittanceUv)), 1.0);
    }
    float2 uv = (float2(pixel) + float2(0.5)) / float2(dimensions);
    float3 direction = atmSkyDirection(
            atmSkyUvToParameters(uv, float2(dimensions), atmospherePush.eyeRadiusKm),
            atmospherePush.sunDirectionIntensity.xyz);
    float3 origin = atmMoveToTop(float3(0.0, atmospherePush.eyeRadiusKm, 0.0), direction);
    AtmRaySegment segment = atmRaySegment(origin, direction);
    if (segment.maximumDistanceKm < 0.0) {
        primeWriteImageStore(skyView, pixel, float4(0.0));
        return;
    }
    float4 low = integrateSkyGroup(0, origin, direction, segment);
    float4 high = integrateSkyGroup(1, origin, direction, segment);
    float3 rec2020 = max(atmLinearRec2020FromSpectral(low, high), float3(0.0));
    primeWriteImageStore(skyView, pixel, float4(rec2020, 1.0));
}
