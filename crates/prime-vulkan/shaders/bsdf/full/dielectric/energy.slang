// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/energy.slang";

import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";

public float3 primeFullCompactTransmissionEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy, float3 wi,
        PrimeFullCompactTransmissionState state, uint samplingFlags) where TransmissionGgxEnergy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    if (!primeFullOpenPbrMicrofacetEffectivelySmooth(state.specularMicrofacet) &&
        state.specularFresnel.ior != 1.0) {
        if (!primeFullOpenPbrIsSpecular(samplingFlags)) {
            return float3(0.0);
        }
    } else if (!primeFullOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    float helperR = state.geometryThinWalled != 0u
                            ? primeFullOpenPbrMicrofacetDielectricMsCompensation(
                                      transmissionGgxEnergy, state.specularMicrofacet, abs(wi.z),
                                      state.specularFresnel.ior)
                                      .z
                            : state.transmissionMultipleScattering.z;
    float reflected = primeFullOpenPbrIsReflective(samplingFlags) ? helperR : 0.0;
    float transmitted = primeFullOpenPbrIsTransmissive(samplingFlags) ? 1.0 - helperR : 0.0;
    return reflected * state.specularFresnel.color + transmitted * state.transmissionTint;
}
