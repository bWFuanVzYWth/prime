#language slang 2026
module "transport/realtime/fixed_scatter.slang";

import "prime_abi_types.slang";
import "policy/queue/wavefront.slang";
import "policy/realtime/fixed.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/advance.slang";

public void primeScatterFixedPath(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint targetQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool continuation = primeWavefrontTransparentBranch(control)
            ? primeAdvanceTransparentWavefrontRecord(
                    PrimeFixedTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeFixedTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            targetQueue,
            recordIndex,
            continuation);
}
