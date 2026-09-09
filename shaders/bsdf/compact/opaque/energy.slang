// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/energy.slang";

import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/contract/flags.slang";

public PrimeCompactOpaqueEnergy primeCompactOpaqueEnergy(
        float3 wi, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueEnergy result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeCompactConductorEnergy(wi, state, PRIME_OPENPBR_FLAG_ALL);
        return result;
    }
    float3 base = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseEnergy(wi, state, PRIME_OPENPBR_FLAG_DIFFUSE)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceEnergy(state, PRIME_OPENPBR_FLAG_DIFFUSE)
                    : primeCompactLambertEnergy(wi, state, PRIME_OPENPBR_FLAG_DIFFUSE);
    result.diffuse = base * state.layer.transmittanceIn;
    result.specular = primeCompactDielectricEnergy(
            wi, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA);
    return result;
}
