// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/state.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/state.slang";
import "service/bsdf/opaque/material.slang";
import "service/bsdf/support/transmission_energy.slang";
import "model/material/defaults.slang";
import "service/bsdf/support/volume_transition.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/math/frame.slang";
import "bsdf/compact/model/material.slang";

public PrimeOpenPbrMaterial primeMinecraftTransmissionMaterial(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl) {
    return primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            packUnorm4x8(float4(0.5, 0.5, 1.0, 1.0)),
            packUnorm4x8(float4(0.0, 4.0 / 255.0, 0.0, 1.0)));
}

public PrimeCompactOpaqueState primeOpaqueStateWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeOpaqueMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    float inverseOutsideIor = primeCompactInverseOutsideIor(localView.z, volumeStack);
    // Material translation restricts opaque production inputs to the exact OpenPBR
    // dielectric/conductor/subsurface submanifold accepted by the compact module.
    return primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            inverseOutsideIor,
            rayT);
}

public PrimeCompactOpaqueState primeOpaqueState(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeOpaqueStateWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            volumeStack,
            0.0);
}
