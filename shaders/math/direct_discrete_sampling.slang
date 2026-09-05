#language slang 2026
module "math/direct_discrete_sampling.slang";

import "math/direct_sampling.slang";
import "math/hash.slang";

// Area bank 0 contains the world/local tree choice and the texture-cell alias choice.
// Keep the 16 STBN rank bits and fill only their subinterval with a separate hash domain.
public uint primeDirectDiscreteSeed(uint pathVertexSeed, uint sampleIndex) {
    return primeHashCombine(primeHashCombine(pathVertexSeed, 0x5354424eu), sampleIndex);
}

public uint primeDirectDiscreteSeed(
        uint2 pixel, uint sampleIndex, uint epoch, uint vertex, uint branch) {
    return primeDirectDiscreteSeed(
            primeHashCombine(primePathSampleSeed(pixel, epoch, branch), vertex), sampleIndex);
}

public float primeDecodeStbnDiscreteChannel(uint code, uint seed, uint dimension) {
    uint low = primeHash32(primeHashCombine(seed, dimension)) & 255u;
    // All 24 bits are exact in f32. A half-step offset could round the maximum to 1.
    return float((code << 8u) | low) * (1.0 / 16777216.0);
}

public float primeDirectDiscreteSample1D(PrimeDirectSampleBase base, uint seed, uint dimension) {
    uint packed = primeRealtimeStbn[primeStbnSampleAddress(base, PRIME_DIRECT_SAMPLE_AREA_PRIMARY)];
    return primeDecodeStbnDiscreteChannel((packed >> (dimension * 16u)) & 0xffffu, seed, dimension);
}

public float2 primeDirectDiscreteSample2D(PrimeDirectSampleBase base, uint seed) {
    uint packed = primeRealtimeStbn[primeStbnSampleAddress(base, PRIME_DIRECT_SAMPLE_AREA_PRIMARY)];
    return float2(primeDecodeStbnDiscreteChannel(packed & 0xffffu, seed, 0u),
            primeDecodeStbnDiscreteChannel(packed >> 16u, seed, 1u));
}
