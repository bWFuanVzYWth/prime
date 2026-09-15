// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/state.slang";

import "bsdf/compact/dielectric/energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";

public bool primeCompactFoliageSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0
            && material.weight.specular == 1.0
            && (material.weight.metalness == 0.0 || material.weight.metalness == 1.0)
            && material.weight.subsurface >= 0.0
            && material.weight.subsurface <= 1.0
            && (material.weight.subsurface == 0.0 || material.weight.metalness == 0.0)
            && material.weight.transmission >= 0.0
            && material.weight.transmission <= 1.0
            && material.weight.coat == 0.0
            && material.weight.fuzz == 0.0
            && material.weight.thinFilm == 0.0
            && material.weight.diffraction == 0.0
            && material.weight.diffuseRoughness == 0.0
            && material.geometry.thinWalled != 0u
            && material.transmission.depth == 0.0
            && material.transmission.dispersionScale == 0.0;
}

public PrimeCompactFoliageState primeCompactFoliageStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeOpenPbrMaterial material,
        float3 wi,
        PrimeOpenPbrVolume outsideMedium)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    PrimeCompactFoliageState state;
    float inverseOutsideIor = 1.0 / outsideMedium.ior;
    state.base = primeCompactOpaqueStateInit(
            transmissionGgxEnergy, material, wi, inverseOutsideIor);
    state.transmissionWeight = material.weight.transmission;
    PrimeCompactTransmissionState transmission = primeCompactTransmissionStateInit(
            transmissionGgxEnergy, material, wi, outsideMedium, 0.0);
    state.transmissionMicrofacet = transmission.transmissionMicrofacet;
    state.transmissionTint = transmission.transmissionTint;

    PrimeCompactOpaqueEnergy baseEnergy = primeCompactOpaqueEnergy(wi, state.base);
    float3 glossyDiffuseEnergy = baseEnergy.specular + baseEnergy.diffuse;
    float3 transmissionEnergy = primeCompactTransmissionEnergy(
            transmissionGgxEnergy, wi, transmission, PRIME_OPENPBR_FLAG_ALL);
    state.dielectricMix = primeCompactFoliageMakeMixState(
            state.transmissionWeight,
            state.transmissionWeight < 1.0 ? glossyDiffuseEnergy : float3(0.0),
            state.transmissionWeight > 0.0 ? transmissionEnergy : float3(0.0));
    return state;
}
