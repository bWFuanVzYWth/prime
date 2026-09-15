// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/sample.slang";

import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";

public PrimeOpenPbrSample primeCompactOpaqueSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state) {
    return state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorSample(
                    wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerSample(
                    wi, randomValue, state, false);
}

public PrimeOpenPbrSample primeCompactOpaqueSampleWithEonDiffuse(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state) {
    return state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorSample(
                    wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerSample(
                    wi, randomValue, state, true);
}
