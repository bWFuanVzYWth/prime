// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "policy/queue/single_successor.slang";
import "policy/queue/wavefront.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> outputData;

#if defined(PRIME_TEST_WAVEFRONT_QUEUE)
struct TestStorage : IPrimeWavefrontQueueStorage {
#else
struct TestStorage : IPrimeSuccessorQueueStorage {
#endif
    uint capacity(uint queue) { return inputData[0]; }
    void markOverflow(uint queue, uint capacity) { InterlockedOr(outputData[queue * 4u + 2u], 1u); }
    uint reserve(uint queue, uint count) {
        uint first;
        InterlockedAdd(outputData[queue * 4u], count, first);
        InterlockedAdd(outputData[queue * 4u + 1u], 1u);
        return first;
    }
    void store(uint queue, uint entry, uint path) {
        outputData[8u + queue * inputData[0] + entry] = path + 1u;
    }
};

bool continues(uint index) {
    if (index >= inputData[0]) return false;
    if (inputData[2u + index] == 0u) return false;
    return true;
}

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    // Match the production helper-return / common append shape, including inactive tail lanes.
    bool continuation = continues(tid.x);
#if defined(PRIME_TEST_WAVEFRONT_QUEUE)
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
    primeAppendSubgroupQueueEntry(TestStorage(), inputData[1], tid.x, continuation);
#else
    primeAppendScalarQueueEntry(TestStorage(), inputData[1], tid.x, continuation);
#endif
#else
    primeAppendSuccessor(TestStorage(), inputData[1], tid.x, continuation);
#endif
}
