// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/foliage/traits.slang";

import "service/bsdf/contract/sample.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "model/material/translation.slang";
import "service/bsdf/support/microfacet.slang";

public PrimeClosureTraits primeFoliageClosureTraits(
        float roughness, uint opticalControl, uint materialControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, materialControl);
    bool discreteSpecular = primeOpenPbrMicrofacetEffectivelySmooth(
            primeClosureMicrofacet(translated.perceptualRoughness));
    PrimeClosureTraits result;
    result.measureMask = PRIME_MEASURE_SOLID_ANGLE
            | (discreteSpecular ? PRIME_MEASURE_DISCRETE : 0u);
    result.eventMask = PRIME_BSDF_EVENT_REFLECTION
            | PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DIFFUSE
            | (discreteSpecular
                    ? PRIME_BSDF_EVENT_DISCRETE
                    : PRIME_BSDF_EVENT_GLOSSY);
    return result;
}
