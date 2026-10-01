// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/state.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/model/volume.slang";

public PrimeFullCompactTransmissionState
primeFullCompactTransmissionClosureState(PrimeFullCompactTransmissionState state) {
    if (state.geometryThinWalled == 0u) {
        state.entering = 1u;
    }
    return state;
}

public bool primeFullCompactTransmissionSupports(PrimeFullOpenPbrMaterial material) {
    return material.weight.base == 1.0 && material.weight.diffuseRoughness == 0.0 &&
           material.weight.specular == 1.0 && material.weight.metalness == 0.0 &&
           material.weight.subsurface == 0.0 && material.weight.transmission == 1.0 &&
           material.weight.coat == 0.0 && material.weight.fuzz == 0.0 &&
           material.weight.thinFilm == 0.0 && material.weight.diffraction == 0.0 &&
           material.transmission.dispersionScale == 0.0;
}

// energyCosine is abs(wi.z) for the material frame, or wi.z for the physical interface.
// Selecting it here avoids constructing and then replacing the exiting-interface LUT result.
public PrimeFullCompactTransmissionState
primeFullCompactTransmissionStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy, PrimeFullOpenPbrMaterial material, float3 wi,
        PrimeFullOpenPbrVolume outsideMedium, float rayT,
        float energyCosine) where TransmissionGgxEnergy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    PrimeFullCompactTransmissionState state;
    state.onb = material.geometry.onb;
    state.geometryThinWalled = material.geometry.thinWalled;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.rayT = rayT;
    state.originalIor = material.specular.ior;
    state.transmissionColor = material.transmission.color;
    state.specularMicrofacet.alpha = primeFullOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);
    state.specularFresnel.ior = material.specular.ior * (1.0 / outsideMedium.ior);
    state.specularFresnel.energyIor =
            primeFullOpenPbrIorAdjustment(state.specularFresnel.ior, material.weight.specular);
    state.specularFresnel.color = material.specular.color;
    state.transmissionTint =
            material.transmission.depth == 0.0 ? material.transmission.color : float3(1.0);
    state.transmissionVolume = primeFullOpenPbrVolumeFromTransmission(material.transmission);
    state.transmissionVolume.ior = material.specular.ior;
    if (state.geometryThinWalled == 0u && state.entering == 0u) {
        state.transmissionVolume = outsideMedium;
    }
    state.transmissionMicrofacet.alpha = float2(0.0);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    if (state.geometryThinWalled != 0u) {
        state.transmissionMicrofacet.alpha = clamp(
                state.specularMicrofacet.alpha *
                        primeFullOpenPbrThinDielectricRoughnessScaler2(state.specularFresnel.ior),
                float2(0.0), float2(1.0));
    } else {
        state.transmissionMultipleScattering = primeFullOpenPbrMicrofacetDielectricMsCompensation(
                transmissionGgxEnergy, state.specularMicrofacet, energyCosine,
                state.specularFresnel.ior);
    }
    return state;
}

public PrimeFullCompactTransmissionState
primeFullCompactTransmissionStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy, PrimeFullOpenPbrMaterial material, float3 wi,
        PrimeFullOpenPbrVolume outsideMedium, float rayT) where TransmissionGgxEnergy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    return primeFullCompactTransmissionStateInit(transmissionGgxEnergy, material, wi, outsideMedium,
                                                 rayT, abs(wi.z));
}

public PrimeFullCompactTransmissionState
primeFullCompactTransmissionInterfaceState<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy, float3 wi,
        PrimeFullCompactTransmissionState state) where TransmissionGgxEnergy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        return state;
    }
    state.transmissionMultipleScattering = primeFullOpenPbrMicrofacetDielectricMsCompensation(
            transmissionGgxEnergy, state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    return state;
}
