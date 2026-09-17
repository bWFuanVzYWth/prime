// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/queue/post_trace.slang";

import "policy/queue/wavefront.slang";

// HitObject traversal may repack invocations. The ordinary producer's subgroup identity no
// longer proves a valid compaction group, even when there is no explicit reorder instruction.
public void primeAppendPostTraceQueueEntry<Storage>(
        Storage storage, uint queue, uint pathIndex, bool continuation)
    where Storage : IPrimeWavefrontQueueStorage
{
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE) && !defined(PRIME_ENABLE_SER)
    primeAppendSubgroupQueueEntry(storage, queue, pathIndex, continuation);
#else
    primeAppendScalarQueueEntry(storage, queue, pathIndex, continuation);
#endif
}
