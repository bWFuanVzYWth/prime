#language slang 2026
module "state/realtime/path_storage.slang";

import "bsdf/compact/contract/volume.slang";
import "state/realtime/medium.slang";

// Descriptor-free exact codec for the two u16 identities, two f32 extinctions and f32 eta.
// Identity packing recovers one lane without coupling a sampled extinction to a global table.
static const uint PRIME_WAVEFRONT_MEDIUM_ID_MASK = 0xffffu;

public void primePackWavefrontMediumState(
        PrimeOpenPbrVolumeStack stack,
        float etaScale,
        out uint4 first,
        out uint4 second) {
    uint mediumIds = (stack.values[0].mediumId & PRIME_WAVEFRONT_MEDIUM_ID_MASK)
            | (stack.values[1].mediumId & PRIME_WAVEFRONT_MEDIUM_ID_MASK) << 16u;
    first = uint4(asuint(stack.values[0].extinction), mediumIds);
    second = uint4(asuint(stack.values[1].extinction), asuint(etaScale));
}

public PrimeOpenPbrVolume primeUnpackWavefrontMediumState(
        uint4 first,
        uint4 second,
        uint index,
        uint iorEncoding) {
    uint mediumIds = first.w;
    uint mediumId = index == 0u
            ? mediumIds & PRIME_WAVEFRONT_MEDIUM_ID_MASK
            : mediumIds >> 16u;
    return primeUnpackWavefrontMedium(
            uint4(index == 0u ? first.xyz : second.xyz, mediumId),
            iorEncoding);
}

public float primeUnpackWavefrontEtaScale(uint4 second) {
    return asfloat(second.w);
}
