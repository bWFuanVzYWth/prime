// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/traits.slang";

import "service/bsdf/contract/sample.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "model/material/translation.slang";
import "service/bsdf/support/microfacet.slang";

public PrimeClosureTraits primeOpaqueClosureTraits(
        bool conductor, bool discreteSpecular) {
    PrimeClosureTraits result;
    result.measureMask = !conductor || !discreteSpecular
            ? PRIME_MEASURE_SOLID_ANGLE
            : 0u;
    if (discreteSpecular) {
        result.measureMask |= PRIME_MEASURE_DISCRETE;
    }
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | (!conductor ? PRIME_BSDF_EVENT_DIFFUSE : 0u)
            | (discreteSpecular
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}

public PrimeClosureTraits primeOpaqueClosureTraits(PrimeCompactOpaqueState state) {
    bool conductor = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR;
    bool discreteSpecular = primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet);
    return primeOpaqueClosureTraits(conductor, discreteSpecular);
}

public PrimeClosureTraits primeOpaqueClosureTraits(
        float roughness, uint opticalControl, uint materialControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    return primeOpaqueClosureTraits(
            primeOpticalMaterialIsMetal(translated),
            primeOpenPbrMicrofacetEffectivelySmooth(
                    primeClosureMicrofacet(translated.perceptualRoughness)));
}
