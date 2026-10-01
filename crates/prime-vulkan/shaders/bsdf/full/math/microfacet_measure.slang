// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/math/microfacet_measure.slang";

import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";

public bool primeFullOpenPbrMicrofacetEffectivelySmooth(PrimeFullOpenPbrMicrofacet microfacet) {
    return primeFullOpenPbrReduceMax(microfacet.alpha) < 1.0e-4;
}
