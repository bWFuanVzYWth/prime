// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/traits.slang";

import "prime_abi_types.slang";
import "contract/light_receiver.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/classify.slang";

public PrimeClosureTraits primeSurfaceClosureTraits(
        SurfaceInteraction surface, float mediumIor, bool usesAirGap) {
    PrimeSurfaceClassification classification;
    classification.materialControl = surface.materialControl;
    classification.roughness = surface.roughness;
    classification.opticalControl = surface.opticalControl;
    classification.adjacentInterfaceControl = surface.adjacentInterfaceControl;
    return primeClassifySurface(classification, mediumIor, usesAirGap);
}
