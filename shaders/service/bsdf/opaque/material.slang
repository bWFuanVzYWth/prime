// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/material.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";
import "model/material/defaults.slang";
import "model/material/translation.slang";
import "prime_abi_push.slang";
import "prime_abi_types.slang";

public PrimeOpenPbrMaterial primeOpaqueMaterialWithMinimumRoughness(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float minimumLinearRoughness) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, flags);
    bool metal = primeOpticalMaterialIsMetal(translated);
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            baseColor,
            max(translated.perceptualRoughness, minimumLinearRoughness),
            metal ? 1.0 : 0.0,
            normal);
    if (metal) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(
                baseColor, translated.fresnelCode);
        material.base.color = fresnel.f0;
        material.specular.color = fresnel.f82Tint;
    } else {
        material.specular.ior = primeOpenPbrF0ToIor(translated.dielectricF0);
        if (primeMaterialFamily(flags) == PRIME_MATERIAL_FAMILY_OPAQUE
                && translated.subsurfaceWeight == 0.0
                && (primePush.path.x & PRIME_PATH_BASE_COLOR_COMPENSATION_MASK) != 0u) {
            // Artistic translation in linear Rec.2020, independent of other channels and F0.
            // Fixed positive exponent preserves black/white and componentwise ordering.
            material.base.color = pow(baseColor, float3(1.04));
        }
        if (translated.thinWalled != 0u) {
            material.weight.subsurface = translated.subsurfaceWeight;
            material.subsurface.color = baseColor;
            material.geometry.thinWalled = 1u;
            material.geometry.thickness = 0.0625;
        }
    }
    return material;
}

public PrimeOpenPbrMaterial primeOpaqueMaterial(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags) {
    return primeOpaqueMaterialWithMinimumRoughness(
            baseColor, normal, roughness, opticalControl, flags, 0.0);
}
