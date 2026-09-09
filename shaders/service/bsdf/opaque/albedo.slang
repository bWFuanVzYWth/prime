// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/albedo.slang";

import "service/bsdf/support/sanitization.slang";

import "service/bsdf/contract/albedo.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/opaque/state.slang";
import "service/bsdf/opaque/state.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/math/frame.slang";

public PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactOpaqueState state,
        float3 viewDirection,
        uint closureKind) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeCompactOpaqueEnergy energy = primeCompactOpaqueEnergy(localView, state);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(energy.diffuse);
    result.specular = primeSanitizeDenoiseAlbedo(energy.specular);
    return result;
}
