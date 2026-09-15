// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/foliage/state.slang";

import "service/bsdf/support/medium_conversion.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/foliage/state.slang";
import "bsdf/compact/foliage/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "service/bsdf/opaque/material.slang";
import "service/bsdf/support/volume_transition.slang";
import "contract/medium.slang";
import "bsdf/compact/math/frame.slang";
import "bsdf/compact/model/material.slang";

static const float PRIME_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

public PrimeOpenPbrMaterial primeMinecraftFoliageMaterialWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeOpaqueMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            materialControl,
            minimumLinearRoughness);
    material.weight.transmission = PRIME_FOLIAGE_TRANSMISSION_WEIGHT;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = 0.0625;
    material.specular.ior = 1.45;
    material.transmission.color = clamp(baseColor, float3(0.02), float3(1.0));
    material.transmission.depth = 0.0;
    return material;
}

public PrimeOpenPbrMaterial primeMinecraftFoliageMaterial(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint materialControl) {
    return primeMinecraftFoliageMaterialWithMinimumRoughness(
            baseColor,
            normal,
            roughness,
            opticalControl,
            materialControl,
            0.0);
}

public PrimeCompactFoliageState primeMinecraftFoliageStateWithMinimumRoughness(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        PrimeMedium medium,
        float minimumLinearRoughness) {
    float3 closureNormal = dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeOpenPbrMaterial material = primeMinecraftFoliageMaterialWithMinimumRoughness(
            baseColor,
            closureNormal,
            roughness,
            opticalControl,
            materialControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    return primeCompactFoliageStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            primeOpenPbrVolumeFromMedium(medium));
}

public PrimeCompactFoliageState primeMinecraftFoliageState(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        PrimeMedium medium) {
    return primeMinecraftFoliageStateWithMinimumRoughness(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            medium,
            0.0);
}
