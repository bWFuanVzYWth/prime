// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/queue/wavefront.slang";

public interface IPrimeWavefrontQueueStorage {
    uint capacity(uint queue);
    uint reserve(uint queue, uint count);
    void markOverflow(uint queue, uint capacity);
    void store(uint queue, uint entry, uint pathIndex);
}

public void primeAppendScalarQueueEntry<Storage>(
        Storage storage,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Storage : IPrimeWavefrontQueueStorage
{
    if (!continuation) {
        return;
    }
    uint capacity = storage.capacity(queue);
    uint entry = storage.reserve(queue, 1u);
    if (entry < capacity) {
        storage.store(queue, entry, pathIndex);
    } else {
        storage.markOverflow(queue, capacity);
    }
}

// Every invocation must call this from uniform control flow with a subgroup-uniform queue.
// The elected lane reserves one contiguous range, reducing global atomics while retaining the
// producer subgroup's locality. Lane-varying queue partitions must use scalar reservation.
public void primeAppendSubgroupQueueEntry<Storage>(
        Storage storage,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Storage : IPrimeWavefrontQueueStorage
{
    uint4 activeMask = WaveActiveBallot(continuation);
    uint activeCount = countbits(activeMask.x) + countbits(activeMask.y)
            + countbits(activeMask.z) + countbits(activeMask.w);
    uint firstEntry = 0u;
    if (WaveIsFirstLane() && activeCount != 0u) {
        firstEntry = storage.reserve(queue, activeCount);
    }
    firstEntry = WaveReadLaneFirst(firstEntry);
    uint capacity = storage.capacity(queue);
    bool overflow = activeCount > capacity
            || firstEntry > capacity - min(activeCount, capacity);
    if (WaveIsFirstLane() && overflow) {
        storage.markOverflow(queue, capacity);
    }
    if (!continuation) {
        return;
    }
    uint entry = firstEntry + WavePrefixCountBits(continuation);
    if (entry < capacity) {
        storage.store(queue, entry, pathIndex);
    }
}

