// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/energy.slang";

import "bsdf/compact/dielectric/energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";

public PrimeCompactFoliageEnergy primeCompactFoliageEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactFoliageState state)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    PrimeCompactOpaqueEnergy base = primeCompactOpaqueEnergy(wi, state.base);
    PrimeCompactFoliageEnergy result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.diffuse = base.diffuse;
        result.specular = base.specular;
        return result;
    }
    result.diffuse = base.diffuse * (1.0 - state.transmissionWeight);
    result.specular = base.specular * (1.0 - state.transmissionWeight)
            + primeCompactTransmissionEnergy(
                    transmissionGgxEnergy,
                    wi,
                    primeCompactFoliageTransmissionState(state),
                    PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA)
                    * state.transmissionWeight;
    return result;
}
