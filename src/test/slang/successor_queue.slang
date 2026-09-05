import "policy/queue/single_successor.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> outputData;

struct TestStorage : IPrimeSuccessorQueueStorage {
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
    primeAppendSuccessor(TestStorage(), inputData[1], tid.x, continuation);
}
