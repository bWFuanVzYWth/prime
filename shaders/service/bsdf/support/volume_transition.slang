#language slang 2026
module "service/bsdf/support/volume_transition.slang";

import "bsdf/compact/contract/material.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/transparent.slang";
import "service/bsdf/support/medium_identity.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";
import "bsdf/compact/model/volume.slang";

public PrimeOpenPbrVolumeStack primeEmptyVolumeStack() {
    PrimeOpenPbrVolumeStack result;
    result.values[0].extinction = float3(0.0);
    result.values[0].albedo = float3(0.0);
    result.values[0].anisotropy = 0.0;
    result.values[0].ior = 1.0;
    result.values[0].mediumId = PRIME_MEDIUM_ID_VACUUM;
    result.values[0].iorEncoding = 0u;
    result.values[1] = result.values[0];
    result.count = 0u;
    return result;
}

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
    // Alpha classifies the interface; it never scales roughness. Rough texels use authored
    // Authored roughness or the configured fallback is already resolved by the material composer.
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
        // reconstructs PRIME_PURE_WATER_ABSORPTION_M_INV without changing its volume-stack code.
        material.transmission.color = exp(-PRIME_PURE_WATER_ABSORPTION_M_INV);
    } else if (primeMaterialIsColorlessGlass(materialControl)) {
        material.transmission.color = float3(1.0);
    } else {
        material.transmission.color = primeGlassVolumeTransmittance(
                baseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY);
    }
    // Solid transparent materials describe one-metre Beer-Lambert transmittance and enter the
    // path medium stack. True zero-volume geometry retains RoboCute's explicit thin-wall tint.
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

public PrimeOpenPbrVolumeStack primeCameraWaterVolumeStack() {
    PrimeOpenPbrVolumeStack result = primeEmptyVolumeStack();
    // The camera starts without an intersected surface, but pure-water absorption is independent
    // of the discarded biome surface tint, so a neutral placeholder reconstructs the same medium.
    PrimeOpenPbrMaterial material = primeMinecraftTransmissionMaterial(
            float3(1.0),
            1.0,
            float3(0.0, 1.0, 0.0),
            PRIME_MATERIAL_FAMILY_DIELECTRIC | PRIME_MATERIAL_MEDIUM_WATER,
            0.0,
            0u);
    PrimeOpenPbrVolume volume = primeOpenPbrVolumeFromTransmission(material.transmission);
    volume.ior = material.specular.ior;
    volume.mediumId = PRIME_MEDIUM_ID_WATER;
    volume.iorEncoding = PRIME_MEDIUM_IOR_WATER;
    primeOpenPbrStackPush(result, volume);
    return result;
}
