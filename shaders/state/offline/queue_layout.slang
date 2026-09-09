// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/offline/queue_layout.slang";

import "prime_offline_abi.slang";

public uint primeOfflineStageWords(uint pixelCount) {
    return pixelCount * (PRIME_OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE / 4u);
}

public uint primeOfflineQueueCommandWord(uint pixelCount, uint queue) {
    return primeOfflineStageWords(pixelCount)
            + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

public uint primeOfflineQueueWord(
        uint pixelCount,
        uint queue,
        uint entry) {
    return primeOfflineQueueCommandWord(
                    pixelCount, PRIME_WAVEFRONT_QUEUE_COUNT)
            + queue * pixelCount
            + entry;
}
