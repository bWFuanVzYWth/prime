// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/model/material.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";

// OpenPBR's default metallic material initializer and outside-medium lookup.
// These are compact boundary utilities, not a retained generic closure graph.

public PrimeOpenPbrMaterial primeCompactMaterialFromMetallic(
        float3 baseColor, float roughness, float metalness, float3 normal) {
    PrimeOpenPbrOnb onb = primeOpenPbrOnbFromNormal(normal);
    PrimeOpenPbrMaterial material;
    material.weight.base = 1.0;
    material.weight.diffuseRoughness = 0.0;
    material.weight.specular = 1.0;
    material.weight.metalness = metalness;
    material.weight.subsurface = 0.0;
    material.weight.transmission = 0.0;
    material.weight.coat = 0.0;
    material.weight.fuzz = 0.0;
    material.weight.thinFilm = 0.0;
    material.weight.diffraction = 0.0;
    material.geometry.thinWalled = 0u;
    material.geometry.thickness = 0.005;
    material.geometry.onb = onb;
    material.specular.color = float3(1.0);
    material.specular.roughness = roughness;
    material.specular.roughnessAnisotropy = 0.0;
    material.specular.ior = 1.5;
    material.emission.luminance = float3(0.0);
    material.base.color = baseColor;
    material.subsurface.color = float3(0.8);
    material.subsurface.radius = float3(0.05, 0.025, 0.0125);
    material.subsurface.scatterAnisotropy = 0.0;
    material.transmission.color = float3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    material.transmission.dispersionAbbeNumber = 20.0;
    material.coat.color = float3(1.0);
    material.coat.roughness = 0.0;
    material.coat.roughnessAnisotropy = 0.0;
    material.coat.ior = 1.6;
    material.coat.darkening = 1.0;
    material.coat.roughening = 1.0;
    material.coat.onb = onb;
    material.fuzz.color = float3(1.0);
    material.fuzz.roughness = 0.5;
    material.thinFilm.thickness = 0.5;
    material.thinFilm.ior = 1.4;
    material.diffraction.color = float3(1.0);
    material.diffraction.thickness = 0.5;
    material.diffraction.invPitch = float2(1.0 / 3.0, 0.0);
    material.diffraction.angle = 0.0;
    material.diffraction.lobeCount = 5u;
    material.diffraction.kind = PRIME_OPENPBR_DIFFRACTION_RECTANGULAR;
    return material;
}

public float primeCompactInverseOutsideIor(PrimeOpenPbrVolume medium) {
    return 1.0 / medium.ior;
}
