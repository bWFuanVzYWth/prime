#language slang 2026
module "state/realtime/detached_guide_medium.slang";

import "bsdf/compact/contract/volume.slang";
import "service/bsdf/support/medium_identity.slang";

// A detached guide needs only medium identity and IOR, but both are part of boundary semantics.
// Two exact u16 MediumIds, two 9-bit IOR encodings and the 0..2 stack count fit in two words.
static const uint PRIME_DETACHED_GUIDE_MEDIUM_ID_MASK = 0xffffu;
static const uint PRIME_DETACHED_GUIDE_IOR_MASK = 0x1ffu;
static const uint PRIME_DETACHED_GUIDE_SECOND_IOR_SHIFT = 9u;
static const uint PRIME_DETACHED_GUIDE_COUNT_SHIFT = 18u;
static const uint PRIME_DETACHED_GUIDE_COUNT_MASK = 0x3u;

public uint2 primePackDetachedGuideMediumStack(PrimeOpenPbrVolumeStack stack) {
    uint firstMediumId = stack.count > 0u ? stack.values[0].mediumId : 0u;
    uint secondMediumId = stack.count > 1u ? stack.values[1].mediumId : 0u;
    uint firstIor = stack.count > 0u ? stack.values[0].iorEncoding : 0u;
    uint secondIor = stack.count > 1u ? stack.values[1].iorEncoding : 0u;
    return uint2(
            (firstMediumId & PRIME_DETACHED_GUIDE_MEDIUM_ID_MASK)
                    | (secondMediumId & PRIME_DETACHED_GUIDE_MEDIUM_ID_MASK) << 16u,
            (firstIor & PRIME_DETACHED_GUIDE_IOR_MASK)
                    | (secondIor & PRIME_DETACHED_GUIDE_IOR_MASK)
                            << PRIME_DETACHED_GUIDE_SECOND_IOR_SHIFT
                    | stack.count << PRIME_DETACHED_GUIDE_COUNT_SHIFT);
}

public PrimeOpenPbrVolumeStack primeUnpackDetachedGuideMediumStack(uint2 packed) {
    PrimeOpenPbrVolumeStack stack;
    uint count = (packed.y >> PRIME_DETACHED_GUIDE_COUNT_SHIFT)
            & PRIME_DETACHED_GUIDE_COUNT_MASK;
    [unroll]
    for (uint index = 0u; index < 2u; ++index) {
        bool active = index < count;
        uint mediumId = index == 0u
                ? packed.x & PRIME_DETACHED_GUIDE_MEDIUM_ID_MASK
                : packed.x >> 16u;
        uint iorEncoding = packed.y >> (index == 0u
                ? 0u
                : PRIME_DETACHED_GUIDE_SECOND_IOR_SHIFT)
                & PRIME_DETACHED_GUIDE_IOR_MASK;
        stack.values[index].extinction = float3(0.0);
        stack.values[index].albedo = float3(0.0);
        stack.values[index].anisotropy = 0.0;
        stack.values[index].ior = active ? primeDecodeMediumIor(iorEncoding) : 1.0;
        stack.values[index].mediumId = active ? mediumId : PRIME_MEDIUM_ID_VACUUM;
        stack.values[index].iorEncoding = active ? iorEncoding : 0u;
    }
    stack.count = count;
    return stack;
}
