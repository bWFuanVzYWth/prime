// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/classify.slang";

import "contract/light_receiver.slang";
import "service/bsdf/contract/sample.slang";
import "model/material/defaults.slang";
import "service/bsdf/opaque/traits.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/foliage/traits.slang";

public PrimeClosureTraits primeClassifySurface(PrimeSurfaceClassification surface, float mediumIor, bool usesAirGap) {
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        return primeMinecraftTransmissionClosureTraits(surface.materialControl, surface.roughness,
                surface.opticalControl, mediumIor, surface.adjacentInterfaceControl, usesAirGap);
    }
    if (primeMaterialIsFoliage(surface.materialControl)) {
        return primeFoliageClosureTraits(surface.roughness, surface.opticalControl, surface.materialControl);
    }
    return primeOpaqueClosureTraits(surface.roughness, surface.opticalControl, surface.materialControl);
}
