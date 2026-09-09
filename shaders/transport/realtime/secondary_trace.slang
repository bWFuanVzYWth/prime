// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/secondary_trace.slang";

import "prime_abi_types.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/trace.slang";

public void primeTraceSecondary(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    SurfaceInteraction surface = primeFusedWavefrontTrace(recordIndex);
    primeStoreWavefrontSurface(recordIndex, surface);
}
