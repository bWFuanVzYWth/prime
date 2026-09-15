// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/volume_transition.slang";

import "service/bsdf/support/medium_conversion.slang";
import "bsdf/compact/contract/material.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/transparent.slang";
import "service/bsdf/support/medium_identity.slang";
import "contract/medium.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";
import "bsdf/compact/model/volume.slang";

public PrimeOpenPbrMaterial primeMinecraftTransmissionMaterialWithMinimumRoughness(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float minimumLinearRoughness) {
    bool water = primeMaterialIsWater(materialControl);
    bool thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u;
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    // Alpha classifies the interface; it never scales roughness. The material composer already
    // resolves authored roughness or the configured default.
    float interfaceRoughness = max(
            primeMaterialEffectiveLinearRoughness(
                    materialControl, translated.perceptualRoughness),
            minimumLinearRoughness);
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), interfaceRoughness, 0.0, outwardNormal);
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? PRIME_THIN_WALLED_THICKNESS_M : 1.0;
    // Water's measured boundary is authoritative. Other transmissive models accept only the
    // translator's clamped dielectric F0; custom and predefined-metal encodings fall back to 4%.
    material.specular.ior = water
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);

    if (water) {
        // RoboCute's transmission ABI stores transmittance at a reference depth and recovers
        // extinction as -log(T) / depth. Supplying the measured one-metre transmittance therefore
        // reconstructs PRIME_PURE_WATER_ABSORPTION_M_INV without changing its volume-medium code.
        material.transmission.color = exp(-PRIME_PURE_WATER_ABSORPTION_M_INV);
    } else if (primeMaterialIsColorlessGlass(materialControl)) {
        material.transmission.color = float3(1.0);
    } else {
        material.transmission.color = primeGlassVolumeTransmittance(
                baseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY);
    }
    // Solid transparent materials describe one-metre Beer-Lambert transmittance and enter the
    // path medium. True zero-volume geometry retains RoboCute's explicit thin-wall tint.
    material.transmission.depth = thinWalled ? 0.0 : 1.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

public PrimeOpenPbrMaterial primeMinecraftTransmissionMaterial(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl) {
    return primeMinecraftTransmissionMaterialWithMinimumRoughness(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            0.0);
}

public PrimeMedium primeCameraWaterMedium() {
    // The camera starts without an intersected surface, but pure-water absorption is independent
    // of the discarded biome surface tint, so a neutral placeholder reconstructs the same medium.
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            float3(1.0),
            1.0,
            float3(0.0, 1.0, 0.0),
            PRIME_MATERIAL_FAMILY_DIELECTRIC | PRIME_MATERIAL_MEDIUM_WATER,
            0.0,
            0u);
    PrimeMedium volume = primeMediumFromOpenPbrVolume(
            primeOpenPbrVolumeFromTransmission(material.transmission));
    volume.ior = material.specular.ior;
    volume.mediumId = PRIME_MEDIUM_ID_WATER;
    volume.iorEncoding = PRIME_MEDIUM_IOR_WATER;
    return volume;
}
