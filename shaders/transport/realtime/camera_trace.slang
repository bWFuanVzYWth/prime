#language slang 2026
module "transport/realtime/camera_trace.slang";

import glsl;
import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/atmosphere/trace.slang";
import "service/camera/projection.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/queue/wavefront.slang";

import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/realtime/contract.slang";
import "state/realtime/surface.slang";
import "state/realtime/queue.slang";
import "contract/path.slang";

public void primeTraceRealtimeCamera(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
    SurfaceInteraction surface = primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            primePush.cameraPosition,
            traceDirection,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            0u);
    primeStoreWavefrontSurface(pathIndex, surface);

    // SER may repack invocations. Queue publication after it must not use subgroup collectives.
    primeAppendWavefrontPath(PRIME_WAVEFRONT_PRIMARY_QUEUE, pathIndex);
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, pathIndex);
    }
}
