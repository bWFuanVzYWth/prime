#language slang 2026
module "phase/realtime/delta_walk.slang";

import "prime_realtime_abi.slang";
import "contract/realtime/shader_record.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/delta_walk.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = primeWavefrontShaderRecord.control == 0u
            ? PRIME_WAVEFRONT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_TRACE_QUEUE_1;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontDeltaWalk(context, sourceQueue, launchIndex);
}
