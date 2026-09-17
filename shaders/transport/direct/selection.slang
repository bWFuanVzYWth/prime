// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/direct/selection.slang";

import "prime_abi_types.slang";
import "model/material/defaults.slang";
import "contract/light_receiver.slang";
import "math/surface_normal.slang";

public float3 primeAreaLightReceiverNormal(
        uint materialControl, float3 shadingNormal, float3 geometricNormal, float3 viewDirection) {
    if (primeMaterialIsTransmissive(materialControl)
            || primeMaterialIsFoliage(materialControl)) {
        return float3(0.0);
    }
    return primeIncidentShadingNormal(shadingNormal, geometricNormal, viewDirection);
}

public float3 primeAreaLightReceiverNormal(SurfaceInteraction surface, float3 viewDirection) {
    return primeAreaLightReceiverNormal(surface.materialControl, surface.shadingNormal, surface.geometricNormal, viewDirection);
}

public float3 primeAreaLightReceiverNormal(PrimeLightReceiver receiver, float3 viewDirection) {
    return primeAreaLightReceiverNormal(receiver.classification.materialControl, receiver.shadingNormal, receiver.geometricNormal, viewDirection);
}
