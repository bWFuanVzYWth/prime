// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/pt.slang";

import "bsdf/full/contract/material.slang";
import "bsdf/full/math/frame.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/flags.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/full/math/spectrum.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/opaque/lobes.slang";
import "bsdf/full/opaque/state.slang";

import "bsdf/full/opaque/traits.slang";
import "bsdf/full/opaque/evaluate.slang";
import "bsdf/full/opaque/sample.slang";
import "bsdf/full/dielectric/evaluate.slang";
import "bsdf/full/dielectric/refract.slang";
import "bsdf/full/dielectric/state.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/microfacet_measure.slang";

// The legacy exact OpenPBR supported-domain kernels remain unchanged. PT supplies
// only their source-reachable parameters, after a visibility query; no generic
// material/default graph or closure survives a query.
public PrimeFullCompactOpaqueState
primeFullPtOpaqueState<Energy>(Energy energy, float3 baseColor, float roughness, float interfaceIor,
                               float subsurfaceWeight, bool conductor, float3 f0, float3 f82,
                               float3 wi) where Energy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    PrimeFullCompactOpaqueState state = {};
    state.kind = conductor                 ? PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR
                 : subsurfaceWeight == 1.0 ? PRIME_FULL_COMPACT_OPAQUE_SUBSURFACE
                 : subsurfaceWeight > 0.0  ? PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE
                                           : PRIME_FULL_COMPACT_OPAQUE_DIELECTRIC;
    state.microfacet.alpha = float2(roughness * roughness);
    if (conductor) {
        state.conductorFresnel.f0 = f0;
        state.conductorFresnel.f82 = f82;
        state.conductorFresnel.f90 = float3(1.0);
        state.conductorFresnel.weight = 1.0;
        state.conductorFresnel.energyF0 = clamp(f0, float3(0.0), float3(1.0));
        state.conductorEss = primeFullOpenPbrMicrofacetEffectivelySmooth(state.microfacet)
                                     ? 1.0
                                     : primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                               state.microfacet, wi.z, float3(1.0), float3(1.0))
                                               .x;
        return state;
    }
    state.baseColor = baseColor;
    state.subsurfaceWeight = subsurfaceWeight;
    state.subsurfaceColor = baseColor;
    state.subsurfaceAnisotropy = 0.0;
    state.dielectricFresnel.ior = interfaceIor;
    state.dielectricFresnel.energyIor = primeFullOpenPbrIorAdjustment(interfaceIor, 1.0);
    state.dielectricFresnel.color = float3(1.0);
    state.dielectricMultipleScattering = primeFullOpenPbrMicrofacetDielectricMsCompensation(
            energy, state.microfacet, wi.z, state.dielectricFresnel.energyIor);
    float3 diffuseEnergy =
            state.kind == PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE && subsurfaceWeight < 1.0
                    ? baseColor
                    : float3(0.0);
    float3 subsurfaceEnergy =
            subsurfaceWeight > 0.0
                    ? primeFullCompactSubsurfaceEnergy(state, PRIME_FULL_OPENPBR_FLAG_ALL)
                    : float3(0.0);
    state.diffuseMix =
            primeFullCompactMakeMixState(subsurfaceWeight, diffuseEnergy, subsurfaceEnergy);
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

public PrimeFullCompactTransmissionState
primeFullPtTransmissionState<Energy>(Energy energy, float roughness, float interfaceIor,
                                     bool thinWalled, float3 thinExtinction, float3 wi) where Energy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    PrimeFullCompactTransmissionState state = {};
    state.geometryThinWalled = thinWalled ? 1u : 0u;
    state.entering = wi.z >= 0.0 ? 1u : 0u;
    state.specularMicrofacet.alpha = float2(roughness * roughness);
    state.specularFresnel.ior = interfaceIor;
    state.specularFresnel.energyIor = primeFullOpenPbrIorAdjustment(interfaceIor, 1.0);
    state.specularFresnel.color = float3(1.0);
    state.transmissionTint = float3(1.0);
    state.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    if (thinWalled) {
        state.transmissionVolume.extinction = thinExtinction;
        state.transmissionMicrofacet.alpha =
                clamp(state.specularMicrofacet.alpha *
                              primeFullOpenPbrThinDielectricRoughnessScaler2(interfaceIor),
                      float2(0.0), float2(1.0));
    } else {
        state.transmissionMultipleScattering = primeFullOpenPbrMicrofacetDielectricMsCompensation(
                energy, state.specularMicrofacet, wi.z, interfaceIor);
    }
    return state;
}

// MIS consumes the full closure response and its marginal proposal density,
// retaining the legacy sampler's exact selected direction and random mapping.
public PrimeFullOpenPbrSample primeFullPtOpaqueSample(float3 wi, float3 randomValue,
                                                      PrimeFullCompactOpaqueState state) {
    PrimeFullOpenPbrSample sampled = primeFullCompactOpaqueSample(wi, randomValue, state);
    if (state.kind != PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR && primeFullOpenPbrHasSample(sampled) &&
        !primeFullOpenPbrIsDelta(sampled.throughput.flags)) {
        PrimeFullCompactOpaqueEvaluation evaluated =
                primeFullCompactOpaqueEvaluate(wi, sampled.wo, state);
        sampled.throughput.value = evaluated.throughput.value;
        sampled.pdf = evaluated.pdf;
    }
    return sampled;
}
