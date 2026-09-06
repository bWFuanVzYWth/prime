#language slang 2026
module "policy/queue/single_successor.slang";

#ifndef PRIME_ENABLE_SUBGROUP_QUEUE
#define PRIME_ENABLE_SUBGROUP_QUEUE 0
#endif

public interface IPrimeSuccessorQueueStorage {
    uint reserve(uint queue, uint count);
    void store(uint queue, uint entry, uint pathIndex);
}

// N slots: N unique inputs publish at most one successor. All lanes, including terminated paths,
// call uniformly with the same queue. Post-HitObject subgroup compaction causes device loss in
// Lambert camera; SER entries must publish individually.
public void primeAppendSuccessor<Storage>(Storage storage, uint queue, uint pathIndex, bool continuation)
    where Storage : IPrimeSuccessorQueueStorage
{
#if PRIME_ENABLE_SUBGROUP_QUEUE && !defined(PRIME_ENABLE_SER)
    uint4 mask = WaveActiveBallot(continuation);
    uint count = countbits(mask.x) + countbits(mask.y) + countbits(mask.z) + countbits(mask.w);
    uint first = 0u;
    if (WaveIsFirstLane() && count != 0u) first = storage.reserve(queue, count);
    first = WaveReadLaneFirst(first);
    uint slot = first + WavePrefixCountBits(continuation);
    if (continuation) storage.store(queue, slot, pathIndex);
#else
    if (continuation) storage.store(queue, storage.reserve(queue, 1u), pathIndex);
#endif
}
