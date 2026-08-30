#language slang 2026
module "phase/lighting/sun_shadow.slang";


import "prime_abi.slang";
import "contract/trace/shadow_payload.slang";
import "contract/path.slang";

import glsl;
layout(location = 1) rayPayloadEXT PrimeShadowPayload primeShadowPayload;

static const int PRIME_SUN_SHADOW_RESOLUTION = 512;
static const float PRIME_SUN_SHADOW_NO_HIT_DEPTH = -1.0e20;

void primeStoreSunShadowDepth(int imageIndex, int2 coordinate, float depth) {
    if (imageIndex == 0) {
        primeSunShadowDepth0.Store(coordinate, depth);
    } else if (imageIndex == 1) {
        primeSunShadowDepth1.Store(coordinate, depth);
    } else if (imageIndex == 2) {
        primeSunShadowDepth2.Store(coordinate, depth);
    } else if (imageIndex == 3) {
        primeSunShadowDepth3.Store(coordinate, depth);
    } else if (imageIndex == 4) {
        primeSunShadowDepth4.Store(coordinate, depth);
    } else if (imageIndex == 5) {
        primeSunShadowDepth5.Store(coordinate, depth);
    } else if (imageIndex == 6) {
        primeSunShadowDepth6.Store(coordinate, depth);
    } else if (imageIndex == 7) {
        primeSunShadowDepth7.Store(coordinate, depth);
    } else if (imageIndex == 8) {
        primeSunShadowDepth8.Store(coordinate, depth);
    } else {
        primeSunShadowDepth9.Store(coordinate, depth);
    }
}

public void primePhase()
{
    int2 launch = int2(DispatchRaysIndex().xy);
    if (any(launch >= int2(primePush.outputExtent))) {
        return;
    }
    int2 regionCoordinate = int2(primePush.path.xy) + launch;
    int target = int(primePush.path.z);
    int imageIndex = target & 15;
    int storageBits = int(primePush.path.w);
    int2 storageOffset = int2(
            storageBits & (PRIME_SUN_SHADOW_RESOLUTION - 1),
            (storageBits >> 9) & (PRIME_SUN_SHADOW_RESOLUTION - 1));
    int2 storageCoordinate =
            (storageOffset + regionCoordinate) & (PRIME_SUN_SHADOW_RESOLUTION - 1);

    float3 basisU = primePush.inverseViewProjection[0].xyz;
    float texelSize = primePush.inverseViewProjection[0].w;
    float3 basisV = primePush.inverseViewProjection[1].xyz;
    float halfTraceDistance = primePush.inverseViewProjection[1].w;
    float3 planeOrigin = primePush.inverseViewProjection[2].xyz;
    float3 rayOrigin = planeOrigin
            + basisU * ((float(regionCoordinate.x) + 0.5) * texelSize)
            + basisV * ((float(regionCoordinate.y) + 0.5) * texelSize);
    float3 directionToSun = normalize(primePush.sunDirection);

    float traceDistance = 2.0 * halfTraceDistance;
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(0.0, 0.0, 0.0, traceDistance);
    primeShadowPayload.startingExtinction0Winding = float4(0.0);
    primeShadowPayload.startingExtinction1Winding = float4(0.0);
    primeShadowPayload.startingMediumIds = uint2(0u);
    primeShadowPayload.startingMediumCount = 0u;
    primeShadowPayload.sourcePrimitive = uint2(PRIME_NO_PRIMITIVE_INDEX);
    primeShadowPayload.targetEmitter = uint2(PRIME_NO_PRIMITIVE_INDEX);
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            3,
            1,
            1,
            rayOrigin,
            0.0,
            -directionToSun,
            traceDistance,
            1);

    float hitDistance = primeShadowPayload.opticalDepthMomentHitDistance.w;
    float depth = hitDistance >= 65504.0
            ? PRIME_SUN_SHADOW_NO_HIT_DEPTH
            : dot(
                    rayOrigin
                            - directionToSun * hitDistance,
                    directionToSun);
    primeStoreSunShadowDepth(imageIndex, storageCoordinate, depth);
}


