// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/boundary_volume.slang";

import "service/bsdf/support/medium_conversion.slang";
import "service/bsdf/support/volume_transition.slang";
import "model/material/medium_boundary.slang";
import "service/bsdf/support/medium_identity.slang";
import "bsdf/compact/contract/material.slang";
import "contract/medium.slang";
import "bsdf/compact/model/volume.slang";

public PrimeMedium primeAdjacentMediumVolume(
        float3 baseColor,
        uint adjacentInterfaceControl,
        uint mediumId) {
    if (mediumId == PRIME_MEDIUM_ID_VACUUM) return primeVacuumMedium();
    uint materialControl = primeAdjacentMaterialControl(adjacentInterfaceControl);
    uint opticalControl = primeAdjacentFresnelCode(adjacentInterfaceControl);
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            baseColor,
            1.0,
            float3(0.0, 1.0, 0.0),
            materialControl,
            0.0,
            opticalControl);
    PrimeMedium volume = primeMediumFromOpenPbrVolume(
            primeOpenPbrVolumeFromTransmission(material.transmission));
    volume.ior = material.specular.ior;
    volume.mediumId = mediumId;
    volume.iorEncoding = primeMediumIorEncoding(materialControl, opticalControl);
    return volume;
}

public PrimeMedium primeBoundarySurfaceVolume(
        float3 baseColor,
        float opacity,
        uint materialControl,
        uint opticalControl,
        uint mediumId) {
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            baseColor,
            opacity,
            float3(0.0, 1.0, 0.0),
            materialControl,
            0.0,
            opticalControl);
    PrimeMedium volume = primeMediumFromOpenPbrVolume(
            primeOpenPbrVolumeFromTransmission(material.transmission));
    volume.ior = material.specular.ior;
    volume.mediumId = mediumId;
    volume.iorEncoding = primeMediumIorEncoding(materialControl, opticalControl);
    return volume;
}
