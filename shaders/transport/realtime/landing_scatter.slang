#language slang 2026
module "transport/realtime/landing_scatter.slang";

import "policy/realtime/landing.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/advance.slang";
import "transport/realtime/landing_publish.slang";

public void primeScatterLandingPath(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool transparent = primeWavefrontTransparentBranch(control);
    bool continuation = transparent
            ? primeAdvanceTransparentWavefrontRecord(
                    PrimeLandingTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeLandingTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control);
    primePublishLandingContinuation(recordIndex, transparent, continuation);
}
