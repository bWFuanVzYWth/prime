// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/state.slang";

import "service/bsdf/support/medium_conversion.slang";
import "service/bsdf/contract/sample.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/medium_boundary.slang";
import "service/bsdf/support/medium_identity.slang";
import "model/material/transparent.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/volume_transition.slang";
import "service/bsdf/support/boundary_volume.slang";
import "contract/medium.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/math/frame.slang";
import "bsdf/compact/model/material.slang";
import "bsdf/compact/model/volume.slang";

public float primeMinecraftInterfaceIor(uint materialControl, uint opticalControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            0.0, opticalControl, materialControl);
    return primeMaterialIsWater(materialControl)
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);
}

public PrimeCompactTransmissionState primeMinecraftTransmissionStateWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeMedium medium,
        uint mediumId,
        float minimumLinearRoughness) {
    bool thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u;
    float3 closureNormal = thinWalled && dot(outwardNormal, viewDirection) < 0.0
            ? -outwardNormal
            : outwardNormal;
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            closureNormal,
            materialControl,
            roughness,
            opticalControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    // SINGLE solid surfaces encode a vacuum exterior; thin sheets keep the incident medium.
    PrimeMedium outsideMedium = thinWalled ? medium : primeVacuumMedium();
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            primeOpenPbrVolumeFromMedium(outsideMedium),
            rayT);
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        state.transmissionVolume.mediumId = mediumId;
        state.transmissionVolume.iorEncoding = primeMediumIorEncoding(
                materialControl, opticalControl);
    }
    return primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), localView, state);
}

public PrimeCompactTransmissionState primeMinecraftTransmissionState(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeMedium medium,
        uint mediumId) {
    return primeMinecraftTransmissionStateWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            medium,
            mediumId,
            0.0);
}

public bool primeHasAdjacentMedium(uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_INTERFACE_VALID) != 0u;
}

public bool primeUsesAdjacentAirGap(
        bool usesAirGap,
        uint materialControl,
        uint opticalControl,
        uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_MICRO_GAP_ELIGIBLE) != 0u
            && primeAirGapCompatible(
                    usesAirGap,
                    materialControl,
                    opticalControl & PRIME_OPTICAL_FRESNEL_MASK,
                    primeAdjacentMaterialControl(adjacentInterfaceControl),
                    primeAdjacentFresnelCode(adjacentInterfaceControl));
}

public PrimeOpenPbrMaterial primeAirGapMaterial(
        float3 outwardNormal,
        float minimumLinearRoughness) {
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), minimumLinearRoughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = PRIME_THIN_WALLED_THICKNESS_M;
    material.specular.ior = 1.0;
    material.transmission.color = float3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

public PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeMedium medium,
        uint mediumId,
        float minimumLinearRoughness,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        uint adjacentMediumId,
        bool usesAirGap) {
    if (!primeHasAdjacentMedium(adjacentInterfaceControl)) {
        return primeMinecraftTransmissionStateWithMinimumRoughness(
                baseColor,
                opacity,
                outwardNormal,
                materialControl,
                roughness,
                opticalControl,
                viewDirection,
                rayT,
                medium,
                mediumId,
                minimumLinearRoughness);
    }
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            minimumLinearRoughness);
    float3 localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
    PrimeMedium adjacent = primeAdjacentMediumVolume(
            adjacentBaseColor, adjacentInterfaceControl, adjacentMediumId);
    bool airGap = primeUsesAdjacentAirGap(
            usesAirGap,
            materialControl,
            opticalControl,
            adjacentInterfaceControl);
    if (airGap) {
        bool enteringBoundaryMaterial = dot(outwardNormal, viewDirection) >= 0.0;
        float3 closureNormal = enteringBoundaryMaterial
                ? outwardNormal
                : -outwardNormal;
        material = primeAirGapMaterial(
                closureNormal, minimumLinearRoughness);
        localView = primeOpenPbrOnbToLocal(material.geometry.onb, viewDirection);
        PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
                primeTransmissionGgxEnergy(),
                material,
                localView,
                primeOpenPbrVolumeFromMedium(adjacent),
                rayT);
        state.transmissionVolume.mediumId = mediumId;
        state.transmissionVolume.iorEncoding = primeMediumIorEncoding(
                materialControl, opticalControl);
        // Thin-wall shading orients its local frame toward the incident ray. Preserve which
        // physical voxel medium lies across the authored boundary for the later medium transition.
        state.entering = enteringBoundaryMaterial ? 1u : 0u;
        return state;
    }
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            localView,
            primeOpenPbrVolumeFromMedium(adjacent),
            rayT);
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        state.transmissionVolume.mediumId = mediumId;
        state.transmissionVolume.iorEncoding = primeMediumIorEncoding(
                materialControl, opticalControl);
    }
    return primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), localView, state);
}

public PrimeCompactTransmissionState primeMinecraftBoundaryTransmissionState(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float rayT,
        PrimeMedium medium,
        uint mediumId,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        uint adjacentMediumId,
        bool usesAirGap) {
    return primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            medium,
            mediumId,
            0.0,
            adjacentBaseColor,
            adjacentInterfaceControl,
            adjacentMediumId,
            usesAirGap);
}

public PrimeTransmissiveBsdfSample primeApplyAirGapMediumTransition(
        PrimeTransmissiveBsdfSample sampled,
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        uint opticalControl,
        uint mediumId,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        uint adjacentMediumId,
        bool usesAirGap) {
    if ((sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u
            && primeUsesAdjacentAirGap(usesAirGap, materialControl,
                    opticalControl, adjacentInterfaceControl)) {
        sampled.medium = state.entering != 0u
                ? primeBoundarySurfaceVolume(baseColor, opacity,
                        materialControl, opticalControl, mediumId)
                : primeAdjacentMediumVolume(adjacentBaseColor,
                        adjacentInterfaceControl, adjacentMediumId);
    }
    return sampled;
}
