#language slang 2026
module "state/realtime/psr.slang";

import "math/queued_psr.slang";
import "service/camera/projection.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";

public PrimeQueuedPsrState primeLoadWavefrontPsrState(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    uint2 pixel = primeWavefrontPixel(pathIndex);
    PrimeQueuedPsrState state;
    state.firstDirectionLength = float4(
            primeCameraRayDirection(pixel, primeRealtimeCameraSample(pixel)),
            record.throughputAndReserved.w);
    state.rotation = asfloat(primeWavefrontPaths[pathIndex].psrPacked);
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * rsqrt(rotationLengthSquared)
            : float4(0.0, 0.0, 0.0, 1.0);
    uint pathControl = record.sourceAndPathControl.w;
    uint storageFlags = record.sourceAndPathControl.z
            & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
    uint count = (pathControl >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state = primeQueuedPsrWithRuntimeControl(state, count, storageFlags);
    return state;
}

public void primeStoreWavefrontPsrState(
        uint pathIndex,
        PrimeQueuedPsrState state) {
    primeWavefrontPaths[pathIndex].psrPacked =
            primePackWavefrontPsrState(state);
    primeWavefrontPaths[pathIndex].transport.throughputAndReserved.w =
            state.firstDirectionLength.w;
}
