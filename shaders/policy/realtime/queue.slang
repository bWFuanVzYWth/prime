// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/realtime/queue.slang";

import "state/realtime/queue.slang";

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
public typealias PrimeWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
public typealias PrimeWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

// SER may repack invocations after traversal; only non-SER variants retain the producer subgroup.
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE) && !defined(PRIME_ENABLE_SER)
public typealias PrimePostTraceWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
public typealias PrimePostTraceWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif
