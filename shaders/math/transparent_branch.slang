// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/transparent_branch.slang";

import "math/direct_sampling.slang";

public uint primePrimaryTransparentSampleAddress(uint2 pixel, uint sampleIndex, uint epoch) {
    // Vertex 0, branch 0 is unused by first-surface light sampling (which starts at vertex 1).
    return primeStbnSampleAddress(primeMakeDirectSampleBase(pixel, sampleIndex, epoch, 0u, 0u),
            PRIME_DIRECT_SAMPLE_AREA_PRIMARY);
}

public bool primeChoosePrimaryTransmission(uint packedStbn) {
    return (packedStbn & 0x8000u) == 0u;
}

// A Bernoulli selection of the existing conditional pair, not a change to the BSDF distribution.
// Single valid events (including TIR) are selected with unit probability.
public float2 primePrimaryTransparentBranchWeights(
        bool transmission, bool reflection, bool chooseTransmission) {
    if (transmission && reflection)
        return chooseTransmission ? float2(2.0, 0.0) : float2(0.0, 2.0);
    return float2(float(transmission), float(reflection));
}
