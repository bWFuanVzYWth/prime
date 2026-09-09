// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/lambert_queue.slang";

import "prime_lambert_contract.slang";
import "state/lambert.slang";
import "policy/queue/single_successor.slang";

struct PrimeLambertQueueStorage : IPrimeSuccessorQueueStorage {
    uint reserve(uint queue, uint count) {
        uint first;
        InterlockedAdd(primeLambertQueue[primeLambertCommandWord(queue)], count, first);
        return first;
    }
    void store(uint queue, uint entry, uint path) {
        primeLambertQueue[primeLambertQueueWord(queue, entry)] = path;
    }
};

public void primeLambertAppend(uint queue, uint path, bool continuation) {
    primeAppendSuccessor(PrimeLambertQueueStorage(), queue, path, continuation);
}
