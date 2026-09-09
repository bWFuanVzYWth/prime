// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dispatch/traits.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "model/material/defaults.slang";
import "service/bsdf/opaque/traits.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/foliage/traits.slang";
import "transport/surface/access.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeClosureTraits primeSurfaceClosureTraits(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack,
        bool usesAirGap) {
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        return primeMinecraftTransmissionClosureTraits(
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialControl,
                surface.roughness,
                surface.opticalControl,
                viewDirection,
                volumeStack,
                surface.adjacentInterfaceControl,
                usesAirGap);
    }
    if (primeMaterialIsFoliage(surface.materialControl)) {
        return primeFoliageClosureTraits(
                surface.roughness,
                surface.opticalControl,
                surface.materialControl);
    }
    return primeOpaqueClosureTraits(
            surface.roughness,
            surface.opticalControl,
            surface.materialControl);
}
