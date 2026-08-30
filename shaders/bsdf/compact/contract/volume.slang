#language slang 2026
module "bsdf/compact/contract/volume.slang";

import "bsdf/compact/contract/flags.slang";

public struct PrimeOpenPbrVolume {
    public float3 extinction;
    public float3 albedo;
    public float anisotropy;
    public float ior;
    public uint mediumId;
    public uint iorEncoding;
};

public struct PrimeOpenPbrVolumeStack {
    public PrimeOpenPbrVolume values[2];
    public uint count;
};

public bool primeOpenPbrStackPush(
        inout PrimeOpenPbrVolumeStack stack,
        PrimeOpenPbrVolume volume) {
    if (stack.count >= PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE) return false;
    stack.values[stack.count] = volume;
    stack.count++;
    return true;
}

public bool primeOpenPbrStackPop(inout PrimeOpenPbrVolumeStack stack) {
    if (stack.count == 0u) return false;
    stack.count--;
    return true;
}
