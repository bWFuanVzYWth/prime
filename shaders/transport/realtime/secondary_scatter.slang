#language slang 2026
module "transport/realtime/secondary_scatter.slang";

import "prime_abi_types.slang";
import "policy/queue/wavefront.slang";
import "policy/realtime/secondary.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/advance.slang";
import "transport/realtime/scatter_hit.slang";
import "contract/path.slang";
import "transport/realtime/continuation.slang";

public void primeScatterSecondaryPath(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint targetQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool continuation = surface.hitKind == PRIME_HIT_SURFACE
            ? primeAdvanceWavefrontHit(PrimeSecondaryTransportPhase(), context, recordIndex, record, surface, control)
            : primeWavefrontTransparentBranch(control)
            ? primeAdvanceTransparentWavefrontMiss(
                    PrimeSecondaryTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control)
            : primeAdvanceOrdinaryWavefrontMiss(
                    PrimeSecondaryTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control);
    if (continuation) continuation = primeContinueRealtimePath(recordIndex);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            targetQueue,
            recordIndex,
            continuation);
}
