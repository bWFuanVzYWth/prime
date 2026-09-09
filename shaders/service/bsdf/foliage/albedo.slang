// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/foliage/albedo.slang";

import "service/bsdf/support/sanitization.slang";

import "service/bsdf/contract/albedo.slang";
import "bsdf/compact/foliage/energy.slang";
import "bsdf/compact/foliage/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "service/bsdf/foliage/state.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/math/frame.slang";

public PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactFoliageState state,
        float3 viewDirection,
        uint closureKind) {
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    PrimeCompactFoliageEnergy energy = primeCompactFoliageEnergy(
            primeTransmissionGgxEnergy(), localView, state);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(energy.diffuse);
    result.specular = primeSanitizeDenoiseAlbedo(energy.specular);
    return result;
}
