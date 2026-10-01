// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/discrete.slang";

import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/opaque/state.slang";

public struct PrimeFullCompactOpaqueDiscreteState {
    public PrimeFullOpenPbrOnb onb;
    public PrimeFullOpenPbrMicrofacet microfacet;
    public PrimeFullOpenPbrConductorFresnel conductorFresnel;
};

public PrimeFullCompactOpaqueDiscreteState
primeFullCompactOpaqueDiscreteStateInit(PrimeFullOpenPbrMaterial material) {
    PrimeFullCompactOpaqueDiscreteState state;
    state.onb = material.geometry.onb;
    state.microfacet.alpha = primeFullOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);
    state.conductorFresnel.f0 = material.base.color * material.weight.base;
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 =
            clamp(material.base.color * material.weight.base * material.weight.specular,
                  float3(0.0), float3(1.0));
    return state;
}

public PrimeFullCompactOpaqueDiscreteState
primeFullCompactOpaqueDiscreteStateFromFull(PrimeFullCompactOpaqueState state) {
    PrimeFullCompactOpaqueDiscreteState result;
    result.onb = state.onb;
    result.microfacet = state.microfacet;
    result.conductorFresnel = state.conductorFresnel;
    return result;
}

public PrimeFullOpenPbrSample
primeFullCompactConductorDiscreteSample(float3 wi, PrimeFullCompactOpaqueDiscreteState state,
                                        uint samplingFlags) {
    PrimeFullOpenPbrSample result = primeFullOpenPbrZeroSample();
    if (wi.z <= 0.0 || (samplingFlags & PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION) == 0u) {
        return result;
    }
    result.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
    result.throughput.value =
            primeFullOpenPbrConductorFresnelUnpolarized(state.conductorFresnel, wi.z);
    result.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
    result.pdf = 1.0;
    return result;
}

public float3 primeFullCompactConductorDiscreteEnergy(float3 wi,
                                                      PrimeFullCompactOpaqueDiscreteState state) {
    if (wi.z < 0.0) {
        return float3(0.0);
    }
    return primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
            state.microfacet, wi.z, state.conductorFresnel.energyF0,
            clamp(float3(state.conductorFresnel.weight), float3(0.0), float3(1.0)));
}
