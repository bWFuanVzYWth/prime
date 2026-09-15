// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/detached_guide_medium.slang";

import "contract/medium.slang";
import "service/bsdf/support/medium_identity.slang";

// Guides carry one exact u16 identity and its exact 9-bit IOR source, without radiance state.
public uint primePackDetachedGuideMedium(PrimeMedium medium) {
    return medium.mediumId | (medium.iorEncoding << 16u);
}

public PrimeMedium primeUnpackDetachedGuideMedium(uint packed) {
    PrimeMedium medium = primeVacuumMedium();
    medium.mediumId = packed & 0xffffu;
    medium.iorEncoding = (packed >> 16u) & 0x1ffu;
    medium.ior = medium.mediumId == 0u ? 1.0 : primeDecodeMediumIor(medium.iorEncoding);
    return medium;
}
