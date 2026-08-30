#language slang 2026
module "transport/direct/selection.slang";

import "prime_abi_types.slang";
import "model/material/defaults.slang";
import "transport/surface/access.slang";

public float3 primeAreaLightReceiverNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    if (primeMaterialIsTransmissive(surface.materialControl)
            || primeMaterialIsFoliage(surface.materialControl)) {
        return float3(0.0);
    }
    return primeSurfaceShadingNormal(surface, viewDirection);
}
