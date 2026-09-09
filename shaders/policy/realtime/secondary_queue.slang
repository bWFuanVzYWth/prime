// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/realtime/secondary_queue.slang";

import "prime_realtime_abi.slang";

public uint primeSecondarySourceQueue(uint control) {
    return (control & 1u) == 0u
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1;
}

public uint primeSecondaryTargetQueue(uint control) {
    return (control & 1u) == 0u
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
}
