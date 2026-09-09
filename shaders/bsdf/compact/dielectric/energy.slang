// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/energy.slang";

import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";

public float3 primeCompactTransmissionEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactTransmissionState state,
        uint samplingFlags)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    if (!primeOpenPbrMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeOpenPbrIsSpecular(samplingFlags)) { return float3(0.0); }
    } else if (!primeOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    float helperR = state.geometryThinWalled != 0u
            ? primeOpenPbrMicrofacetDielectricMsCompensation(
                    transmissionGgxEnergy,
                    state.specularMicrofacet,
                    abs(wi.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    float reflected = primeOpenPbrIsReflective(samplingFlags) ? helperR : 0.0;
    float transmitted = primeOpenPbrIsTransmissive(samplingFlags) ? 1.0 - helperR : 0.0;
    return reflected * state.specularFresnel.color
            + transmitted * state.transmissionTint;
}
