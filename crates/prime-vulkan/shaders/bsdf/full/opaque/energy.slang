// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/energy.slang";

import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/contract/flags.slang";

public PrimeFullCompactOpaqueEnergy
primeFullCompactOpaqueEnergy(float3 wi, PrimeFullCompactOpaqueState state) {
    PrimeFullCompactOpaqueEnergy result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeFullCompactConductorEnergy(wi, state, PRIME_FULL_OPENPBR_FLAG_ALL);
        return result;
    }
    float3 base =
            state.kind == PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE
                    ? primeFullCompactMixedDiffuseEnergy(state, PRIME_FULL_OPENPBR_FLAG_DIFFUSE)
            : state.kind == PRIME_FULL_COMPACT_OPAQUE_SUBSURFACE
                    ? primeFullCompactSubsurfaceEnergy(state, PRIME_FULL_OPENPBR_FLAG_DIFFUSE)
                    : primeFullCompactLambertEnergy(wi, state, PRIME_FULL_OPENPBR_FLAG_DIFFUSE);
    result.diffuse = base * state.layer.transmittanceIn;
    result.specular = primeFullCompactDielectricEnergy(
            wi, state, PRIME_FULL_OPENPBR_FLAG_SPECULAR | PRIME_FULL_OPENPBR_FLAG_DELTA);
    return result;
}
