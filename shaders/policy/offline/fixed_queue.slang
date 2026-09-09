// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/offline/fixed_queue.slang";

public uint primeOfflineSourceQueue(uint control) {
    return control & 1u;
}

public uint primeOfflineTargetQueue(uint control) {
    return primeOfflineSourceQueue(control) ^ 1u;
}
