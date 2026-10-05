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

// Physical endpoint identity, independent of relative IOR. A submerged sheet's glass
// can have lower IOR than water; selecting the larger endpoint loses its extinction.
public float4 primeThinInsideMedium(bool physicalFront, float4 incident, float4 transmitted) {
    return physicalFront ? transmitted : incident;
}
public float4 primeThinOutsideMedium(bool physicalFront, float4 incident, float4 transmitted) {
    return physicalFront ? incident : transmitted;
}
