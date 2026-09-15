// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/path_storage.slang";

import "contract/medium.slang";
import "state/realtime/medium.slang";

// RGB extinction and etaScale retain all f32 bits. The exact u16 identity occupies
// the unused middle bytes of path control; the IOR source is in guide control.
public uint primeWavefrontMediumId(uint pathControl) {
    return (pathControl >> 8u) & 0xffffu;
}

public uint4 primePackWavefrontMediumState(PrimeMedium medium, float etaScale) {
    return uint4(asuint(medium.extinction), asuint(etaScale));
}

public PrimeMedium primeUnpackWavefrontMediumState(
        uint4 packed, uint mediumId, uint iorEncoding) {
    return primeUnpackWavefrontMedium(uint4(packed.xyz, mediumId), iorEncoding);
}
