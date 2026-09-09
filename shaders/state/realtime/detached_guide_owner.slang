// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/detached_guide_owner.slang";

import "math/queued_psr.slang";
import "math/realtime_storage.slang";
import "service/camera/projection.slang";
import "state/realtime/contract.slang";
import "state/realtime/medium.slang";

// Detached guide traversal owns only guide/branch control and queued PSR state. Keeping this
// explicit prevents the 112-byte transport aggregate from becoming live in its iterative kernel.
public struct PrimeDetachedGuideOwnerState {
    public uint guideControl;
    public uint denoiserControl;
    public float firstDirectionLength;
    public bool primaryRay;
};

public PrimeDetachedGuideOwnerState primeLoadDetachedGuideOwnerState(
        uint pathIndex) {
    PrimeDetachedGuideOwnerState state;
    state.primaryRay = primeWavefrontMissingGuideOwner(pathIndex);
    if (state.primaryRay) {
        uint word = primeMissingGuideOwnerWord(pathIndex, primeWavefrontPixelCount());
        state.guideControl = primeWavefrontQueue[word + 0u];
        state.denoiserControl = primeWavefrontQueue[word + 1u];
        state.firstDirectionLength = asfloat(primeWavefrontQueue[word + 2u]);
        return state;
    }
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    float4 throughputAndReserved =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.throughputAndReserved;
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
    if (owner.primaryRay) {
        uint word = primeMissingGuideOwnerWord(pathIndex, primeWavefrontPixelCount());
        for (uint lane = 0u; lane < 4u; ++lane) state.rotation[lane] = asfloat(primeWavefrontQueue[word + 3u + lane]);
    } else {
        state.rotation = asfloat(primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].psrPacked);
    }
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
    if (owner.primaryRay) {
        uint word = primeMissingGuideOwnerWord(pathIndex, primeWavefrontPixelCount());
        primeWavefrontQueue[word + 0u] = owner.guideControl;
        primeWavefrontQueue[word + 1u] = owner.denoiserControl;
        return;
    }
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl.z =
            owner.guideControl;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.rayDirectionAndDenoiserControl.w =
            asfloat(owner.denoiserControl);
}

public void primeStoreDetachedGuideOwnerPsrState(
        uint pathIndex,
        inout PrimeDetachedGuideOwnerState owner,
        PrimeQueuedPsrState state) {
    if (owner.primaryRay) {
        uint word = primeMissingGuideOwnerWord(pathIndex, primeWavefrontPixelCount());
        primeWavefrontQueue[word + 2u] = asuint(state.firstDirectionLength.w);
        for (uint lane = 0u; lane < 4u; ++lane) primeWavefrontQueue[word + 3u + lane] = asuint(state.rotation[lane]);
    } else {
        primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].psrPacked = asuint(state.rotation);
        primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.throughputAndReserved.w =
                state.firstDirectionLength.w;
    }
    owner.firstDirectionLength = state.firstDirectionLength.w;
    uint transitionMask = PRIME_WAVEFRONT_GUIDE_CONTROL_MASK
            & ~PRIME_WAVEFRONT_GUIDE_FALLBACK;
    uint transitionLimit = PRIME_WAVEFRONT_GUIDE_CONTROL_MASK >> 8u;
    owner.guideControl = (owner.guideControl & ~transitionMask)
            | (primeQueuedPsrStorageFlags(state)
                    & PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            | (min(primeQueuedPsrCount(state), transitionLimit) << 8u);
    primeStoreDetachedGuideOwnerControl(pathIndex, owner);
}
