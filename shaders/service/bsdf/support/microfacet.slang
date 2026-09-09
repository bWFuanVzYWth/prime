// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/microfacet.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";


public PrimeOpenPbrMicrofacet primeClosureMicrofacet(float perceptualRoughness) {
    PrimeOpenPbrMicrofacet result;
    result.alpha = primeOpenPbrSpecularNdfRoughnesses(
            perceptualRoughness, 0.0);
    return result;
}
