// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/atmosphere/atmosphere_aerial_transmittance.compute.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";
import "model/atmosphere/geometry.slang";

import "service/image/access.slang";
import "prime_abi_types.slang";
import "contract/coordinate.slang";

public static const uint PRIME_PHASE_GROUP_SIZE_X = PRIME_ATMOSPHERE_AERIAL_DEPTH;

[[vk::binding(6, 0)]] [[vk::image_format(
    "rgba16f")]] writeonly RWTexture3D<float4> aerialTransmittance;

struct AtmospherePushConstants {
    float4x4 inverseViewProjection;
    float eyeRadiusKm;
    float maximumAerialDistanceKm;
    float2 epipoleNdc;
    float4 sunDirectionIntensity;
    float4 shadowDirectionBank;
    float4 shadowCameraEnabled;
};
[[vk::push_constant]] ConstantBuffer<AtmospherePushConstants> atmospherePush;

groupshared float4 prefixTransmittance[PRIME_ATMOSPHERE_AERIAL_DEPTH];
groupshared float3 aerialRayDirection;
groupshared float3 aerialRayOrigin;
groupshared float aerialMaximumDistance;

float3 aerialDirection(int2 coordinate, int2 dimensions) {
    float2 uv = (float2(coordinate) + float2(0.5)) / float2(dimensions);
    float2 ndc = primeCanonicalUvToClip(uv);
    float4 nearPoint = atmospherePush.inverseViewProjection * float4(ndc, 1.0, 1.0);
    float4 farPoint = atmospherePush.inverseViewProjection * float4(ndc, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

void aerialSegmentTransmittance(float3 origin, float3 direction, float segmentStart,
                                float segmentEnd, out float4 transmittance) {
    transmittance = float4(1.0);
    if (segmentEnd <= segmentStart) {
        return;
    }

    float stepLength = (segmentEnd - segmentStart) / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
    for (int substep = 0; substep < PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES; ++substep) {
        float distanceKm =
            lerp(segmentStart, segmentEnd,
                 (float(substep) + 0.5) / float(PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES));
        float altitude = atmHeightAt(origin.y - ATM_BOTTOM_RADIUS_KM, direction.y, distanceKm);
        transmittance *= exp(-stepLength * atmMedium(altitude).extinction);
    }
}

[numthreads(PRIME_PHASE_GROUP_SIZE_X, 1, 1)] void main(uint3 groupId : SV_GroupID,
                                                       uint3 groupThreadId : SV_GroupThreadID) {
    int3 dimensions = primeWriteImageSize(aerialTransmittance);
    int2 coordinate = int2(groupId.xy);
    int depth = int(groupThreadId.x);
    // Depth lanes share one screen ray; matrix reconstruction and atmosphere
    // boundary intersection therefore belong to the workgroup, not each lane.
    if (depth == 0) {
        aerialRayDirection = aerialDirection(coordinate, dimensions.xy);
        aerialRayOrigin = float3(0.0, atmospherePush.eyeRadiusKm, 0.0);
        float height = atmospherePush.eyeRadiusKm - ATM_BOTTOM_RADIUS_KM;
        aerialMaximumDistance =
            atmBoundary(height, aerialRayDirection.y, atmGround(height, aerialRayDirection.y));
    }
    GroupMemoryBarrierWithGroupSync();
    float3 direction = aerialRayDirection;
    float3 origin = aerialRayOrigin;
    float maximumDistance = aerialMaximumDistance;
    float inverseDepth = 1.0 / float(dimensions.z);
    float normalizedStart = max(float(depth) - 0.5, 0.0) * inverseDepth;
    float normalizedEnd = (float(depth) + 0.5) * inverseDepth;
    float segmentStart =
        min(atmospherePush.maximumAerialDistanceKm * normalizedStart * normalizedStart,
            maximumDistance);
    float segmentEnd = min(atmospherePush.maximumAerialDistanceKm * normalizedEnd * normalizedEnd,
                           maximumDistance);
    aerialSegmentTransmittance(origin, direction, segmentStart, segmentEnd,
                               prefixTransmittance[depth]);
    GroupMemoryBarrierWithGroupSync();

    for (int offset = 1; offset < PRIME_ATMOSPHERE_AERIAL_DEPTH; offset <<= 1) {
        float4 earlierLow = float4(1.0);
        float4 currentLow = float4(1.0);
        if (depth >= offset) {
            earlierLow = prefixTransmittance[depth - offset];
            currentLow = prefixTransmittance[depth];
        }
        GroupMemoryBarrierWithGroupSync();
        if (depth >= offset) {
            prefixTransmittance[depth] = earlierLow * currentLow;
        }
        GroupMemoryBarrierWithGroupSync();
    }

    float3 transmittance = atmRec2020Transmittance(prefixTransmittance[depth]);
    primeWriteImageStore(aerialTransmittance, int3(coordinate, depth), float4(transmittance, 1.0));
}
