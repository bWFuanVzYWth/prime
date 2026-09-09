// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/albedo.slang";

import "service/bsdf/support/sanitization.slang";

import "service/bsdf/contract/albedo.slang";
import "bsdf/compact/dielectric/energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "model/material/defaults.slang";
import "service/bsdf/dielectric/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/math/frame.slang";

public PrimeDenoiseAlbedos primeDenoiseAlbedosFromState(
        PrimeCompactTransmissionState state,
        float3 viewDirection,
        uint closureKind) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeDenoiseAlbedos result;
    result.diffuse = primeSanitizeDenoiseAlbedo(
            primeCompactTransmissionEnergy(
                    primeTransmissionGgxEnergy(),
                    localView,
                    state,
                    PRIME_OPENPBR_FLAG_TRANSMISSION));
    result.specular = primeSanitizeDenoiseAlbedo(
            primeCompactTransmissionEnergy(
                    primeTransmissionGgxEnergy(),
                    localView,
                    state,
                    PRIME_OPENPBR_FLAG_REFLECTION));
    return result;
}

public float3 primeMinecraftPrimaryTransmissionAlbedo(
        PrimeCompactTransmissionState state, uint materialControl) {
    // A rough primary interface has no deterministic transmitted target to supply a material
    // identity. Preserve the medium's one-metre reference color for reconstruction.
    return primeMaterialIsRoughGlass(materialControl)
            ? state.transmissionColor
            : state.transmissionTint;
}
