// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/atmosphere/atmosphere_aerial.compute.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";

import "service/image/access.slang";
import "model/atmosphere/epipolar.slang";
import "model/atmosphere/geometry.slang";
import "model/atmosphere/transmittance.slang";
import "model/atmosphere/source.slang";
import "model/atmosphere/parameters.slang";
import "math/sun_shadow_hierarchy.slang";
import "prime_abi_types.slang";
import "service/atmosphere/epipolar_shadow.slang";

public static const uint PRIME_PHASE_GROUP_SIZE_X = PRIME_ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES;

[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture3D<float4> aerialRadiance;

struct AerialPhase {
    float cosine;
    float4 scattering[5];
};

AerialPhase aerialPhase(float cosine) {
    AerialPhase phase;
    phase.cosine = cosine;
    phase.scattering[0] = float4(atmRayleighPhase(cosine));
    AtmPhaseStencil stencil = atmPhaseStencil(cosine);
    for (uint species = 0; species < 4; species++) {
        phase.scattering[species + 1] = atmTabulatedPhase(stencil, species);
    }
    return phase;
}

void aerialStep(float altitude, float viewMu, float sunMu, float terrainSunVisibility,
                float stepLength, AerialPhase phase, out float4 radiance,
                out float4 transmittance) {
    AtmMedium c = atmMedium(altitude);
    float4 direct = float4(0.0);
    for (uint species = 0; species < 5; species++) {
        direct += c.scattering[species] * phase.scattering[species];
    }
    direct *= atmSunT(altitude, sunMu) * ATM_SOLAR;
    float4 multiple = atmIndirect(altitude, viewMu, sunMu, phase.cosine, c);
    // Retain Prime's existing terrain visibility model for the total aerial source.
    float4 source = (direct + multiple) * terrainSunVisibility;
    transmittance = exp(-stepLength * c.extinction);
    radiance = source * atmHomogeneousSegmentIntegral(c.extinction, stepLength);
}

struct AerialSegment {
    float4 radiance;
    float4 transmittance;
};

AerialSegment identityAerialSegment() {
    AerialSegment result;
    result.radiance = float4(0.0);
    result.transmittance = float4(1.0);
    return result;
}

AerialSegment integrateAerialSegment(float3 origin, float3 direction, float segmentStart,
                                     float segmentEnd, AerialPhase phase) {
    AerialSegment result = identityAerialSegment();
    if (segmentEnd <= segmentStart) {
        return result;
    }

    float3 sunDirection = atmospherePush.sunDirectionIntensity.xyz;
    float stepLength = (segmentEnd - segmentStart) / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
    for (int substep = 0; substep < PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES; ++substep) {
        float substepStartFraction =
            float(substep) / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
        float substepEndFraction =
            float(substep + 1) / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
        float substepStart = lerp(segmentStart, segmentEnd, substepStartFraction);
        float substepEnd = lerp(segmentStart, segmentEnd, substepEndFraction);
        float distanceKm = 0.5 * (substepStart + substepEnd);
        float initialHeight = origin.y - ATM_BOTTOM_RADIUS_KM;
        float altitude = atmHeightAt(initialHeight, direction.y, distanceKm);
        float3 up = (origin + direction * distanceKm) / (ATM_BOTTOM_RADIUS_KM + altitude);
        float sunMu = dot(up, sunDirection);
        float terrainSunVisibility = localSunVisibilityInterval(
            PrimeAtmosphereSunShadowProfileSource(), direction,
            substepStart / ATM_WORLD_UNIT_SCALE_KM, substepEnd / ATM_WORLD_UNIT_SCALE_KM);

        float4 radiance;
        float4 transmittance;
        aerialStep(altitude, dot(up, direction), sunMu, terrainSunVisibility, stepLength, phase,
                   radiance, transmittance);
        result.radiance += result.transmittance * radiance;
        result.transmittance *= transmittance;
    }
    return result;
}

float3 aerialDirection(int2 coordinate, int2 dimensions) {
    float2 ndc = atmEpipolarSliceNdc(atmospherePush.epipoleNdc, coordinate, dimensions);
    float4 nearPoint = atmospherePush.inverseViewProjection * float4(ndc, 1.0, 1.0);
    float4 farPoint = atmospherePush.inverseViewProjection * float4(ndc, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

[numthreads(PRIME_PHASE_GROUP_SIZE_X, 1, 1)] void main(uint3 groupId : SV_GroupID,
                                                       uint3 groupThreadId : SV_GroupThreadID) {
    int3 dimensions = primeWriteImageSize(aerialRadiance);
    int radial = int(groupThreadId.x);
    int slice = int(groupId.y);
    int2 coordinate = int2(radial, slice);

    // One workgroup owns one epipolar slice. Its lanes integrate all radial
    // screen rays while five lanes build the groupshared RT blocker profiles.
    if (radial == 0) {
        float3 cameraPosition = atmospherePush.shadowCameraEnabled.xyz;
        float3 edgeDirection = aerialDirection(int2(dimensions.x - 1, slice), dimensions.xy);
        primeInitializeSunShadowProfile(atmospherePush.shadowDirectionBank.xyz, cameraPosition,
                                        edgeDirection);
    }
    GroupMemoryBarrierWithGroupSync();
    if (radial < SUN_SHADOW_CASCADE_COUNT) {
        sunShadowBuildProfileCascade(PrimeAtmosphereSunShadowProfileSource(), radial);
    }
    GroupMemoryBarrierWithGroupSync();

    float3 direction = aerialDirection(coordinate, dimensions.xy);
    float phaseCosine = dot(direction, atmospherePush.sunDirectionIntensity.xyz);
    AerialPhase phase = aerialPhase(phaseCosine);
    float3 origin = float3(0.0, atmospherePush.eyeRadiusKm, 0.0);
    float height = atmospherePush.eyeRadiusKm - ATM_BOTTOM_RADIUS_KM;
    float maximumDistance = atmBoundary(height, direction.y, atmGround(height, direction.y));
    float inverseDepth = 1.0 / float(dimensions.z);
    AerialSegment accumulated = identityAerialSegment();
    for (int depth = 0; depth < dimensions.z; ++depth) {
        float normalizedStart = max(float(depth) - 0.5, 0.0) * inverseDepth;
        float normalizedEnd = (float(depth) + 0.5) * inverseDepth;
        float segmentStart =
            min(atmospherePush.maximumAerialDistanceKm * normalizedStart * normalizedStart,
                maximumDistance);
        float segmentEnd =
            min(atmospherePush.maximumAerialDistanceKm * normalizedEnd * normalizedEnd,
                maximumDistance);
        AerialSegment segment =
            integrateAerialSegment(origin, direction, segmentStart, segmentEnd, phase);
        accumulated.radiance += accumulated.transmittance * segment.radiance;
        accumulated.transmittance *= segment.transmittance;

        float3 rec2020 = atmRadiance(accumulated.radiance);
        primeWriteImageStore(aerialRadiance, int3(coordinate, depth), float4(rec2020, 1.0));
    }
}
