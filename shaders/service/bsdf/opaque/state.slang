// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/state.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/state.slang";
import "service/bsdf/opaque/material.slang";
import "service/bsdf/support/transmission_energy.slang";
import "contract/medium.slang";
import "bsdf/compact/math/frame.slang";

public PrimeCompactOpaqueState primeOpaqueStateWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        PrimeMedium medium,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeOpaqueMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = 1.0 / medium.ior;
    // Material translation restricts opaque production inputs to the exact OpenPBR
    // dielectric/conductor/subsurface submanifold accepted by the compact module.
    return primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            inverseOutsideIor);
}

public PrimeCompactOpaqueState primeOpaqueState(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        PrimeMedium medium) {
    return primeOpaqueStateWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            medium,
            0.0);
}
