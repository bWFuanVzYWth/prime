// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/realtime/queue.slang";

import "state/realtime/queue.slang";
import "policy/queue/post_trace.slang";

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
public typealias PrimeWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
public typealias PrimeWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

public struct PrimePostTraceWavefrontContinuationAppender : IPrimeWavefrontContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendPostTraceQueueEntry(PrimeRealtimeQueueStorage(), queue, pathIndex, continuation);
    }
}
