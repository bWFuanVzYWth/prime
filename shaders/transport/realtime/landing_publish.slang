#language slang 2026
module "transport/realtime/landing_publish.slang";

import "policy/realtime/queue.slang";
import "prime_realtime_abi.slang";
import "math/queued_psr.slang";
import "service/reconstruct/nrd/common.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/secondary_transition.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "contract/realtime/transparent.slang";

public PrimeWavefrontTransportRecord primeSealLandingGuide(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        uint control) {
    bool guidePending = primeWavefrontGuideEnabled(control)
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    if (!guidePending) {
        return record;
    }

    // Reaching the configured delta limit is the only live path that can leave landing without a
    // solid-angle guide. Keep its resident visible-interface fallback, terminate its hit-distance
    // lane, and prevent PSR/guide state from extending into the fixed wavefront prefix and tail.
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
    record.rayDirectionAndDenoiserControl.w = asfloat(control);
    return record;
}

public void primePublishLandingContinuation(
        uint recordIndex, bool transparent, bool continuation) {
    if (continuation) {
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(recordIndex);
        uint psrControl = record.sourceAndPathControl.z
                & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
        PrimeQueuedPsrState psrState = primeQueuedPsrWithRuntimeControl(
                primeEmptyQueuedPsrState(), 0u, psrControl);
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        bool invalidGuide = primeQueuedPsrOverflowed(psrState)
                && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
                && !primeWavefrontDirectionalGuide(control);
        if (transparent && invalidGuide) {
            primeInvalidatePendingTransparentGuide(recordIndex, record);
        }
        if (transparent) {
            record = primeSealLandingGuide(recordIndex, record, control);
        }
        record = primeEnterSecondaryRecord(record);
        primeStoreWavefrontTransportRecord(recordIndex, record);
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
