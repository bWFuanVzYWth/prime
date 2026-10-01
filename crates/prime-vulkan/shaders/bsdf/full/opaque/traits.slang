// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/traits.slang";

import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/opaque/state.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";

public bool primeFullCompactOpaqueSupports(PrimeFullOpenPbrMaterial material) {
    return material.weight.base == 1.0 && material.weight.specular == 1.0 &&
           (material.weight.metalness == 0.0 || material.weight.metalness == 1.0) &&
           material.weight.transmission == 0.0 && material.weight.coat == 0.0 &&
           material.weight.fuzz == 0.0 && material.weight.thinFilm == 0.0 &&
           material.weight.diffraction == 0.0 && material.weight.diffuseRoughness == 0.0 &&
           material.weight.subsurface >= 0.0 && material.weight.subsurface <= 1.0 &&
           (material.weight.subsurface == 0.0 ||
            (material.weight.metalness == 0.0 && material.geometry.thinWalled != 0u));
}

public PrimeFullCompactOpaqueState primeFullCompactOpaqueStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy, PrimeFullOpenPbrMaterial material, float3 wi,
        float inverseOutsideIor) where TransmissionGgxEnergy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    PrimeFullCompactOpaqueState state;
    state.onb = material.geometry.onb;
    state.kind = material.weight.metalness == 1.0    ? PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                 : material.weight.subsurface == 1.0 ? PRIME_FULL_COMPACT_OPAQUE_SUBSURFACE
                 : material.weight.subsurface > 0.0  ? PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE
                                                     : PRIME_FULL_COMPACT_OPAQUE_DIELECTRIC;
    state.baseColor = material.base.color;
    state.subsurfaceWeight = material.weight.subsurface;
    state.subsurfaceColor = material.subsurface.color;
    state.subsurfaceAnisotropy = material.subsurface.scatterAnisotropy;
    state.microfacet.alpha = primeFullOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);

    float interfaceIor = material.specular.ior * inverseOutsideIor;
    state.dielectricFresnel.ior = interfaceIor;
    state.dielectricFresnel.energyIor =
            primeFullOpenPbrIorAdjustment(interfaceIor, material.weight.specular);
    state.dielectricFresnel.color = material.specular.color;
    state.dielectricMultipleScattering = primeFullOpenPbrMicrofacetDielectricMsCompensation(
            transmissionGgxEnergy, state.microfacet, wi.z, state.dielectricFresnel.energyIor);

    state.conductorFresnel.f0 = material.base.color * material.weight.base;
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 =
            clamp(material.base.color * material.weight.base * material.weight.specular,
                  float3(0.0), float3(1.0));
    state.conductorEss = 1.0;
    if (state.kind == PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR &&
        !primeFullOpenPbrMicrofacetEffectivelySmooth(state.microfacet)) {
        state.conductorEss = primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                     state.microfacet, wi.z, float3(1.0), float3(1.0))
                                     .x;
    }

    float3 diffuseEnergy =
            state.kind == PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE && state.subsurfaceWeight < 1.0
                    ? state.baseColor
                    : float3(0.0);
    float3 subsurfaceEnergy =
            state.kind != PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR && state.subsurfaceWeight > 0.0
                    ? primeFullCompactSubsurfaceEnergy(state, PRIME_FULL_OPENPBR_FLAG_ALL)
                    : float3(0.0);
    state.diffuseMix =
            primeFullCompactMakeMixState(state.subsurfaceWeight, diffuseEnergy, subsurfaceEnergy);
    float3 baseEnergy =
            state.kind == PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE
                    ? primeFullCompactMixedDiffuseEnergy(state, PRIME_FULL_OPENPBR_FLAG_ALL)
            : state.kind == PRIME_FULL_COMPACT_OPAQUE_SUBSURFACE
                    ? subsurfaceEnergy
                    : primeFullCompactLambertEnergy(wi, state, PRIME_FULL_OPENPBR_FLAG_ALL);
    state.layer = primeFullCompactMakeLayerState(
            baseEnergy, float3(1.0 - state.dielectricMultipleScattering.z),
            primeFullCompactDielectricEnergy(wi, state, PRIME_FULL_OPENPBR_FLAG_ALL));
    return state;
}
