#language slang 2026
module "state/realtime/detached_guide_owner.slang";

import "math/queued_psr.slang";
import "service/camera/projection.slang";
import "state/realtime/contract.slang";
import "state/realtime/medium.slang";

// Detached guide traversal owns only guide/branch control and queued PSR state. Keeping this
// explicit prevents the 112-byte transport aggregate from becoming live in its iterative kernel.
public struct PrimeDetachedGuideOwnerState {
    public uint guideControl;
    public uint denoiserControl;
    public float firstDirectionLength;
};

public PrimeDetachedGuideOwnerState primeLoadDetachedGuideOwnerState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    float4 throughputAndReserved =
            primeWavefrontPaths[pathIndex].transport.throughputAndReserved;
    PrimeDetachedGuideOwnerState state;
    state.guideControl = sourceAndPathControl.z;
    state.denoiserControl = asuint(rayDirectionAndDenoiserControl.w);
    state.firstDirectionLength = throughputAndReserved.w;
    return state;
}

public PrimeQueuedPsrState primeLoadDetachedGuideOwnerPsrState(
        uint pathIndex,
        PrimeDetachedGuideOwnerState owner) {
    uint2 pixel = primeWavefrontPixel(pathIndex);
    PrimeQueuedPsrState state;
    state.firstDirectionLength = float4(
            primeCameraRayDirection(pixel, primeRealtimeCameraSample(pixel)),
            owner.firstDirectionLength);
    state.rotation = asfloat(primeWavefrontPaths[pathIndex].psrPacked);
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * rsqrt(rotationLengthSquared)
            : float4(0.0, 0.0, 0.0, 1.0);
    return primeQueuedPsrWithRuntimeControl(
            state,
            (owner.guideControl & PRIME_WAVEFRONT_GUIDE_CONTROL_MASK) >> 8u,
            owner.guideControl & PRIME_WAVEFRONT_PSR_CONTROL_MASK);
}

public void primeStoreDetachedGuideOwnerControl(
        uint pathIndex,
        PrimeDetachedGuideOwnerState owner) {
    primeWavefrontPaths[pathIndex].transport.sourceAndPathControl.z =
            owner.guideControl;
    primeWavefrontPaths[pathIndex]
            .transport.rayDirectionAndDenoiserControl.w =
            asfloat(owner.denoiserControl);
}

public void primeStoreDetachedGuideOwnerPsrState(
        uint pathIndex,
        inout PrimeDetachedGuideOwnerState owner,
        PrimeQueuedPsrState state) {
    primeWavefrontPaths[pathIndex].psrPacked = asuint(state.rotation);
    primeWavefrontPaths[pathIndex].transport.throughputAndReserved.w =
            state.firstDirectionLength.w;
    owner.firstDirectionLength = state.firstDirectionLength.w;
    uint transitionMask = PRIME_WAVEFRONT_GUIDE_CONTROL_MASK
            & ~PRIME_WAVEFRONT_GUIDE_FALLBACK;
    uint transitionLimit = PRIME_WAVEFRONT_GUIDE_CONTROL_MASK >> 8u;
    owner.guideControl = (owner.guideControl & ~transitionMask)
            | (primeQueuedPsrStorageFlags(state)
                    & PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            | (min(primeQueuedPsrCount(state), transitionLimit) << 8u);
    primeWavefrontPaths[pathIndex].transport.sourceAndPathControl.z =
            owner.guideControl;
}
