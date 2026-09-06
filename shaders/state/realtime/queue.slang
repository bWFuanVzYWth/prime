#language slang 2026
module "state/realtime/queue.slang";

import "policy/queue/wavefront.slang";
import "state/realtime/contract.slang";
public uint primeWavefrontQueuedPath(uint queue, uint entry) {
    return primeWavefrontQueue[primeWavefrontQueueWord(queue, entry)];
}

// Indirect width is fetched before ray generation begins. The first consumer can therefore clear
// only the counter in-shader; the following wavefront barrier publishes the reset before reuse.
public void primeResetWavefrontQueue(uint queue) {
    uint ignored;
    InterlockedExchange(
            primeWavefrontQueue[primeWavefrontQueueCommandWord(queue)],
            0u,
            ignored);
}

public struct PrimeRealtimeQueueStorage : IPrimeWavefrontQueueStorage {
    public uint capacity(uint queue) {
        return primeWavefrontQueueCapacity(queue);
    }

    public uint reserve(uint queue, uint count) {
        uint firstEntry;
        InterlockedAdd(
                primeWavefrontQueue[primeWavefrontQueueCommandWord(queue)],
                count,
                firstEntry);
        return firstEntry;
    }

    public void markOverflow(uint queue, uint capacity) {
        uint commandWord = primeWavefrontQueueCommandWord(queue);
        uint ignored;
        InterlockedMin(primeWavefrontQueue[commandWord], capacity, ignored);
        // Queue zero's low two bytes are immutable delta / minimum-bounce metadata.
        InterlockedOr(primeWavefrontQueue[commandWord + 3u], 0x80000000u, ignored);
    }

    public void store(uint queue, uint entry, uint pathIndex) {
        primeWavefrontQueue[primeWavefrontQueueWord(queue, entry)] = pathIndex;
    }
};

public void primeAppendWavefrontPath(uint queue, uint pathIndex) {
    primeAppendScalarQueueEntry(
            PrimeRealtimeQueueStorage(), queue, pathIndex, true);
}

public interface IPrimeWavefrontContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation);
}

public struct PrimeScalarWavefrontContinuationAppender
        : IPrimeWavefrontContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendScalarQueueEntry(
                PrimeRealtimeQueueStorage(), queue, pathIndex, continuation);
    }
};

public struct PrimeSubgroupWavefrontContinuationAppender
        : IPrimeWavefrontContinuationAppender {
    public void append(uint queue, uint pathIndex, bool continuation) {
        primeAppendSubgroupQueueEntry(
                PrimeRealtimeQueueStorage(), queue, pathIndex, continuation);
    }
};

public void primeAppendWavefrontContinuation<Appender>(
        Appender appender,
        uint queue,
        uint pathIndex,
        bool continuation)
    where Appender : IPrimeWavefrontContinuationAppender
{
    appender.append(queue, pathIndex, continuation);
}
