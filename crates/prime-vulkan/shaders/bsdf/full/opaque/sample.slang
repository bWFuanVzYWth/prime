// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/sample.slang";

import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";

public PrimeFullOpenPbrSample primeFullCompactOpaqueSample(float3 wi, float3 randomValue,
                                                           PrimeFullCompactOpaqueState state) {
    return state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                   ? primeFullCompactConductorSample(wi, randomValue, state,
                                                     PRIME_FULL_OPENPBR_FLAG_ALL)
                   : primeFullCompactOpaqueLayerSample(wi, randomValue, state, false);
}

public PrimeFullOpenPbrSample
primeFullCompactOpaqueSampleWithEonDiffuse(float3 wi, float3 randomValue,
                                           PrimeFullCompactOpaqueState state) {
    return state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                   ? primeFullCompactConductorSample(wi, randomValue, state,
                                                     PRIME_FULL_OPENPBR_FLAG_ALL)
                   : primeFullCompactOpaqueLayerSample(wi, randomValue, state, true);
}
