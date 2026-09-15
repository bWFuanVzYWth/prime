// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/discrete.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/opaque/state.slang";

public struct PrimeCompactOpaqueDiscreteState {
    public PrimeOpenPbrOnb onb;
    public PrimeOpenPbrMicrofacet microfacet;
    public PrimeOpenPbrConductorFresnel conductorFresnel;
};

public PrimeCompactOpaqueDiscreteState primeCompactOpaqueDiscreteStateInit(
        PrimeOpenPbrMaterial material) {
    PrimeCompactOpaqueDiscreteState state;
    state.onb = material.geometry.onb;
    state.microfacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);
    state.conductorFresnel.f0 = material.base.color * material.weight.base;
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 = clamp(
            material.base.color * material.weight.base * material.weight.specular,
            float3(0.0),
            float3(1.0));
    return state;
}

public PrimeCompactOpaqueDiscreteState primeCompactOpaqueDiscreteStateFromFull(
        PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueDiscreteState result;
    result.onb = state.onb;
    result.microfacet = state.microfacet;
    result.conductorFresnel = state.conductorFresnel;
    return result;
}

public PrimeOpenPbrSample primeCompactConductorDiscreteSample(
        float3 wi,
        PrimeCompactOpaqueDiscreteState state,
        uint samplingFlags) {
    PrimeOpenPbrSample result = primeOpenPbrZeroSample();
    if (wi.z <= 0.0
            || (samplingFlags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) == 0u) {
        return result;
    }
    result.wo = reflect(-wi, float3(0.0, 0.0, 1.0));
    result.throughput.value = primeOpenPbrConductorFresnelUnpolarized(
            state.conductorFresnel, float3(0.0), 0u, wi.z);
    result.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
    result.pdf = 1.0;
    return result;
}

public float3 primeCompactConductorDiscreteEnergy(
        float3 wi,
        PrimeCompactOpaqueDiscreteState state) {
    if (wi.z < 0.0) { return float3(0.0); }
    return primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            state.microfacet,
            wi.z,
            state.conductorFresnel.energyF0,
            clamp(float3(state.conductorFresnel.weight), float3(0.0), float3(1.0)));
}
