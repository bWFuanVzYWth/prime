// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/shadow_interaction.slang";

import glsl;
import "prime_shadow_interaction_abi.slang";

// Persist f32: the compiler can fold the old canonical half round-trip. Packed f16 storage
// would force rounding that the actual per-hit program does not necessarily perform.
public uint4 primeEncodeShadowInteraction(float3 extinction, bool stained) {
    return uint4(asuint(extinction), stained ? PRIME_SHADOW_INTERACTION_STAINED_FLAG : 0u);
}

public float3 primeShadowInteractionExtinction(uint4 record) {
    return asfloat(record.xyz);
}

public bool primeShadowInteractionIsStained(uint4 record) {
    return (record.w & PRIME_SHADOW_INTERACTION_STAINED_FLAG) != 0u;
}
