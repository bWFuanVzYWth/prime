#language slang 2026
module "math/lambert_storage.slang";
import "prime_lambert_contract.slang";

public uint primeLambertScratchWord(uint index, uint row) {
    return index * (PRIME_LAMBERT_SCRATCH_RECORD_SIZE / 4u) + 4u * row;
}
public uint primeLambertCommandWord(uint pixels, uint queue) {
    return primeLambertScratchWord(pixels, 0u) + queue * (PRIME_LAMBERT_COMMAND_STRIDE / 4u);
}
public uint primeLambertQueueWord(uint pixels, uint queue, uint index) {
    return primeLambertCommandWord(pixels, PRIME_LAMBERT_QUEUE_COUNT) + queue * pixels + index;
}
