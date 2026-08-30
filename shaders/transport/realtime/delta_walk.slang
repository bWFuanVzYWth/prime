#language slang 2026
module "transport/realtime/delta_walk.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "transport/realtime/delta_walk_state.slang";
import "transport/realtime/trace.slang";
import "transport/scatter/delta.slang";

import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "contract/path.slang";

public bool primeDeltaWalkHitIsDeltaOnly(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return false;
    }
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    return primeSurfaceIsDiscreteOnly(
            surface,
            -path.rayDirection,
            primeWavefrontVolumeStack(record));
}

public void primeQueueDeltaWalkLanding(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        SurfaceInteraction surface) {
    primeStoreWavefrontSurface(recordIndex, surface);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_PRIMARY_QUEUE,
            recordIndex,
            true);
}

public void primeProcessDeltaWalkBranch(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        uint maximumTransitions) {
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    if (!primeWavefrontActive(record)) {
        return;
    }

    // PRIMARY already completed the first delta transition. Each iteration either lands without
    // sampling, or performs exactly one additional delta transition.
    uint transitions = 1u;
    [loop]
    for (uint step = 0u; step < maximumTransitions; ++step) {
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        bool transparent = primeWavefrontTransparentBranch(control);
        SurfaceInteraction surface = primeFusedWavefrontTrace(
                context, recordIndex, transparent);
        record = primeLoadWavefrontTransportRecord(recordIndex);
        control = asuint(record.rayDirectionAndDenoiserControl.w);

        if (!primeDeltaWalkHitIsDeltaOnly(
                recordIndex, record, surface)) {
            primeQueueDeltaWalkLanding(context, recordIndex, surface);
            return;
        }

        // Limit one permits the transition already completed by PRIMARY and its landing trace.
        if (transitions >= maximumTransitions) {
            bool guidePending = transparent
                    && primeWavefrontGuideEnabled(control)
                    && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
                    && !primeWavefrontDirectionalGuide(control)
                    && !primeWavefrontGuideFallback(
                            record.sourceAndPathControl.z);
            if (guidePending) {
                primeInvalidatePendingTransparentGuide(recordIndex, record);
            }
            record = primeWavefrontWithActive(record, false);
            record = primeWavefrontWithGuideFallback(record, false);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            return;
        }

        bool continuation = transparent
                ? primeAdvanceTransparentDeltaWalk(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control)
                : primeAdvanceOrdinaryDeltaWalk(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control);
        if (!continuation) {
            return;
        }
        transitions++;
        record = primeLoadWavefrontTransportRecord(recordIndex);
    }
}

public void primeWavefrontDeltaWalk(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint taskIndex = primeWavefrontQueuedPath(
            sourceQueue, launchIndex);
    uint maximumTransitions = primeAdditionalSpecularBounces();
    primeProcessDeltaWalkBranch(context, taskIndex, maximumTransitions);
}
