#language slang 2026
module "state/lambert.slang";

import "prime_abi.slang";
import "prime_lambert_contract.slang";
import "contract/lambert.slang";

[[vk::binding(PRIME_LAMBERT_PATH_BINDING, 1)]]
public RWStructuredBuffer<PrimeLambertRecord> primeLambertPaths;
[[vk::binding(PRIME_LAMBERT_QUEUE_BINDING, 1)]]
public RWStructuredBuffer<uint> primeLambertQueue;

public uint primeLambertPixelCount() { return primePush.outputExtent.x * primePush.outputExtent.y; }
public uint2 primeLambertPixel(uint index) {
    return uint2(index % primePush.outputExtent.x, index / primePush.outputExtent.x);
}
public uint primeLambertQueueWord(uint queue, uint index) {
    return PRIME_LAMBERT_QUEUE_COUNT * (PRIME_LAMBERT_COMMAND_STRIDE / 4u)
            + queue * primeLambertPixelCount() + index;
}
public uint primeLambertQueuedPath(uint queue, uint index) {
    return primeLambertQueue[primeLambertQueueWord(queue, index)];
}
