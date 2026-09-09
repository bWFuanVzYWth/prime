// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/math/microfacet_measure.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public bool primeOpenPbrMicrofacetEffectivelySmooth(PrimeOpenPbrMicrofacet microfacet) {
    return primeOpenPbrReduceMax(microfacet.alpha) < 1.0e-4;
}
