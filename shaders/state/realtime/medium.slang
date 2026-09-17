// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/medium.slang";

import "contract/medium.slang";
import "service/bsdf/support/medium_identity.slang";

// PSR/fallback occupy bits 0..2 and guide transition count occupies bits 8..14.
public static const uint PRIME_WAVEFRONT_GUIDE_CONTROL_MASK = 0x00007f07u;
static const uint PRIME_WAVEFRONT_IOR_SHIFT = 16u;

public PrimeMedium primeUnpackWavefrontMedium(uint4 packed, uint iorEncoding) {
    PrimeMedium medium = primeVacuumMedium();
    medium.extinction = asfloat(packed.xyz);
    medium.mediumId = packed.w;
    medium.iorEncoding = iorEncoding;
    medium.ior = primeDecodeMediumIor(packed.w, iorEncoding);
    return medium;
}

public uint primePackWavefrontMediumIor(PrimeMedium medium) {
    return medium.iorEncoding << PRIME_WAVEFRONT_IOR_SHIFT;
}

public uint primeUnpackWavefrontMediumIor(uint packed) {
    return (packed >> PRIME_WAVEFRONT_IOR_SHIFT) & 0x1ffu;
}

public uint primeMergeWavefrontGuideControl(uint current, uint guideControl) {
    return (current & ~PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)
            | (guideControl & PRIME_WAVEFRONT_GUIDE_CONTROL_MASK);
}
