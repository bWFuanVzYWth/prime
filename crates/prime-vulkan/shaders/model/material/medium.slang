// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/medium.slang";

import "model/material/fresnel_code.slang";

// Producer proof: only glass with a varying authored G field carries this identity bit.
// The reserved water identity and constant homogeneous media never carry it.
public static const uint PRIME_PBR_DYNAMIC_MEDIUM_IOR = 0x80000000u;

public float primePbrFresnelCodeIor(uint canonicalCode) {
    float root = sqrt(primeDecodeDielectricF0(canonicalCode));
    return (1.0 + root) / (1.0 - root);
}
