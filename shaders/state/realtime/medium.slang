#language slang 2026
module "state/realtime/medium.slang";

import "bsdf/compact/contract/volume.slang";
import "service/bsdf/support/medium_identity.slang";

static const uint PRIME_WAVEFRONT_MEDIUM_0_WATER = 1u << 3u;
static const uint PRIME_WAVEFRONT_MEDIUM_1_WATER = 1u << 4u;
static const uint PRIME_WAVEFRONT_MEDIUM_0_IOR_SHIFT = 16u;
static const uint PRIME_WAVEFRONT_MEDIUM_1_IOR_SHIFT = 24u;
// PSR/fallback occupy bits 0..2 and guide transition count occupies bits 8..14.
public static const uint PRIME_WAVEFRONT_GUIDE_CONTROL_MASK = 0x00007f07u;

public uint4 primePackWavefrontMedium(PrimeOpenPbrVolume medium) {
    // Continuous absorption stays bit-exact while the fourth lane carries exact identity.
    return uint4(asuint(medium.extinction), medium.mediumId);
}
public PrimeOpenPbrVolume primeUnpackWavefrontMedium(
        uint4 packedMedium, uint iorEncoding) {
    PrimeOpenPbrVolume medium;
    medium.extinction = asfloat(packedMedium.xyz);
    medium.ior = primeDecodeMediumIor(iorEncoding);
    medium.albedo = float3(0.0);
    medium.anisotropy = 0.0;
    medium.mediumId = packedMedium.w;
    medium.iorEncoding = iorEncoding;
    return medium;
}

public uint primePackWavefrontMediumIors(PrimeOpenPbrVolumeStack stack) {
    uint first = stack.values[0].iorEncoding;
    uint second = stack.values[1].iorEncoding;
    return ((first & PRIME_MEDIUM_IOR_CODE_MASK)
                    << PRIME_WAVEFRONT_MEDIUM_0_IOR_SHIFT)
            | ((second & PRIME_MEDIUM_IOR_CODE_MASK)
                    << PRIME_WAVEFRONT_MEDIUM_1_IOR_SHIFT)
            | ((first & PRIME_MEDIUM_IOR_WATER) != 0u
                    ? PRIME_WAVEFRONT_MEDIUM_0_WATER
                    : 0u)
            | ((second & PRIME_MEDIUM_IOR_WATER) != 0u
                    ? PRIME_WAVEFRONT_MEDIUM_1_WATER
                    : 0u);
}

public uint primeUnpackWavefrontMediumIor(uint packed, uint mediumIndex) {
    bool second = mediumIndex != 0u;
    uint code = packed >> (second
            ? PRIME_WAVEFRONT_MEDIUM_1_IOR_SHIFT
            : PRIME_WAVEFRONT_MEDIUM_0_IOR_SHIFT)
            & PRIME_MEDIUM_IOR_CODE_MASK;
    uint waterBit = second
            ? PRIME_WAVEFRONT_MEDIUM_1_WATER
            : PRIME_WAVEFRONT_MEDIUM_0_WATER;
    return code | ((packed & waterBit) != 0u ? PRIME_MEDIUM_IOR_WATER : 0u);
}

public uint primeMergeWavefrontGuideControl(uint current, uint guideControl) {
    return (current & ~PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)
            | (guideControl & PRIME_WAVEFRONT_GUIDE_CONTROL_MASK);
}
