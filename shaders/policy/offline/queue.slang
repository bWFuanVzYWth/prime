// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/offline/queue.slang";

import "state/offline/queue.slang";

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
public typealias PrimeOfflineContinuationAppender =
        PrimeSubgroupOfflineContinuationAppender;
#else
public typealias PrimeOfflineContinuationAppender =
        PrimeScalarOfflineContinuationAppender;
#endif
