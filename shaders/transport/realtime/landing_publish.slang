// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/landing_publish.slang";

import "policy/realtime/queue.slang";
import "prime_realtime_abi.slang";
import "math/queued_psr.slang";
import "service/reconstruct/nrd/common.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";
import "state/realtime/transparent.slang";
import "contract/realtime/transparent.slang";
import "policy/realtime/continuation.slang";
import "policy/runtime/config.slang";

PrimeWavefrontContinuationControl primeLoadWavefrontContinuationControl(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    PrimeWavefrontContinuationControl control;
    control.guide = sourceAndPathControl.z;
    control.path = sourceAndPathControl.w;
    control.denoiser = asuint(rayDirectionAndDenoiserControl.w);
    return control;
}

void primeStoreWavefrontContinuationControl(
        uint pathIndex,
        PrimeWavefrontContinuationControl control) {
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl.z =
            control.guide;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl.w =
            control.path;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.rayDirectionAndDenoiserControl.w =
            asfloat(control.denoiser);
}

public uint primeSealLandingGuide(
        uint recordIndex,
        uint control) {
    bool guidePending = primeWavefrontGuideEnabled(control)
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    if (!guidePending) {
        return control;
    }

    // Reaching the configured delta limit is the only live path that can leave landing without a
    // solid-angle guide. Keep its resident visible-interface fallback, terminate its hit-distance
    // lane, and prevent PSR/guide state from extending into the secondary wavefront stages.
    uint2 pixel = primeWavefrontPixel(recordIndex);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    if (transmission) {
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmission,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
    control &= ~PRIME_WAVEFRONT_GUIDE_ENABLED;
    return control;
}

public void primePublishLandingContinuation(
        uint recordIndex, bool transparent, bool continuation) {
    if (continuation) {
        PrimeWavefrontContinuationControl control =
                primeLoadWavefrontContinuationControl(recordIndex);
        uint psrControl = control.guide
                & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
        PrimeQueuedPsrState psrState = primeQueuedPsrWithRuntimeControl(
                primeEmptyQueuedPsrState(), 0u, psrControl);
        bool invalidGuide = primeQueuedPsrOverflowed(psrState)
                && (control.denoiser & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
                && !primeWavefrontDirectionalGuide(control.denoiser);
        if (transparent && invalidGuide) {
            primeInvalidatePendingTransparentGuide(
                    recordIndex, control.denoiser);
        }
        if (transparent) {
            control.denoiser = primeSealLandingGuide(
                    recordIndex, control.denoiser);
        }
        control = primeEnterSecondaryControl(control);
        // Landing cannot run RR: its first secondary guide query is still pending.
        continuation = primeRealtimeCanContinue(0u, primeMinimumRealtimeBounces(), primeMaximumBounces());
        if (!continuation) control.denoiser &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
        primeStoreWavefrontContinuationControl(recordIndex, control);
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
