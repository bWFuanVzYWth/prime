// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/offline/queue.slang";

import "prime_abi.slang";
import "prime_offline_abi.slang";
import "policy/queue/wavefront.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/queue_layout.slang";

public uint primeOfflineQueueCommandWord(uint queue) {
    return primeOfflineQueueCommandWord(primeOfflinePixelCount(), queue);
}

public uint primeOfflineQueueWord(uint queue, uint entry) {
    return primeOfflineQueueWord(
            primeOfflinePixelCount(), queue, entry);
}

public uint primeOfflineQueuedPath(uint queue, uint entry) {
    return primeOfflineQueue[primeOfflineQueueWord(queue, entry)];
}

public void primeResetOfflineQueue(uint queue) {
    uint ignored;
    InterlockedExchange(
            primeOfflineQueue[primeOfflineQueueCommandWord(queue)],
            0u,
            ignored);
}

public struct PrimeOfflineQueueStorage : IPrimeWavefrontQueueStorage {
    public uint capacity(uint queue) {
        return primeOfflinePixelCount();
    }

    public uint reserve(uint queue, uint count) {
        uint firstEntry;
        InterlockedAdd(
                primeOfflineQueue[primeOfflineQueueCommandWord(queue)],
                count,
                firstEntry);
        return firstEntry;
    }

    public void markOverflow(uint queue, uint capacity) {
        uint commandWord = primeOfflineQueueCommandWord(queue);
        uint ignored;
        InterlockedMin(primeOfflineQueue[commandWord], capacity, ignored);
        InterlockedOr(primeOfflineQueue[commandWord + 3u], 1u, ignored);
    }

    public void store(uint queue, uint entry, uint pathIndex) {
        primeOfflineQueue[primeOfflineQueueWord(queue, entry)] = pathIndex;
    }
};

public void primeAppendOfflinePath(uint queue, uint pathIndex) {
    primeAppendScalarQueueEntry(
            PrimeOfflineQueueStorage(), queue, pathIndex, true);
}

public interface IPrimeOfflineContinuationAppender {
    void append(uint queue, uint pathIndex, bool continuation);
}

public struct PrimeScalarOfflineContinuationAppender
        : IPrimeOfflineContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendScalarQueueEntry(
                PrimeOfflineQueueStorage(), queue, pathIndex, continuation);
    }
};

public struct PrimeSubgroupOfflineContinuationAppender
        : IPrimeOfflineContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendSubgroupQueueEntry(
                PrimeOfflineQueueStorage(), queue, pathIndex, continuation);
    }
};

public void primeAppendOfflineContinuation<Appender>(
        Appender appender,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Appender : IPrimeOfflineContinuationAppender
{
    appender.append(queue, pathIndex, continuation);
}
