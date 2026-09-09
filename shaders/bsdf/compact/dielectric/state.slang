// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/state.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/volume.slang";

public PrimeCompactTransmissionState primeCompactTransmissionClosureState(
        PrimeCompactTransmissionState state) {
    if (state.geometryThinWalled == 0u) {
        state.entering = 1u;
    }
    return state;
}

public bool primeCompactTransmissionSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0
            && material.weight.diffuseRoughness == 0.0
            && material.weight.specular == 1.0
            && material.weight.metalness == 0.0
            && material.weight.subsurface == 0.0
            && material.weight.transmission == 1.0
            && material.weight.coat == 0.0
            && material.weight.fuzz == 0.0
            && material.weight.thinFilm == 0.0
            && material.weight.diffraction == 0.0
            && material.transmission.dispersionScale == 0.0;
}

public PrimeCompactTransmissionState primeCompactTransmissionStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeOpenPbrMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    PrimeCompactTransmissionState state;
    state.onb = material.geometry.onb;
    state.geometryThinWalled = material.geometry.thinWalled;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.rayT = rayT;
    state.originalIor = material.specular.ior;
    state.transmissionColor = material.transmission.color;
    state.specularMicrofacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness,
            material.specular.roughnessAnisotropy);
    state.specularFresnel.ior = material.specular.ior * inverseOutsideIor;
    state.specularFresnel.energyIor = primeOpenPbrIorAdjustment(
            state.specularFresnel.ior, material.weight.specular);
    state.specularFresnel.color = material.specular.color;
    state.transmissionTint = material.transmission.depth == 0.0
            ? material.transmission.color : float3(1.0);
    state.transmissionVolume = primeOpenPbrVolumeFromTransmission(material.transmission);
    state.transmissionMicrofacet.alpha = float2(0.0);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha
                * primeOpenPbrThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                float2(0.0),
                float2(1.0));
    } else {
        float3 materialWi = primeCompactTransmissionMaterialFrame(wi, state);
        state.transmissionMultipleScattering = primeOpenPbrMicrofacetDielectricMsCompensation(
                transmissionGgxEnergy,
                state.specularMicrofacet,
                materialWi.z,
                state.specularFresnel.ior);
    }
    return state;
}

public PrimeCompactTransmissionState primeCompactTransmissionInterfaceState<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactTransmissionState state)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        return state;
    }
    state.transmissionMultipleScattering = primeOpenPbrMicrofacetDielectricMsCompensation(
            transmissionGgxEnergy,
            state.specularMicrofacet,
            wi.z,
            state.specularFresnel.ior);
    return state;
}
