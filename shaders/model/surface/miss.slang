// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/surface/miss.slang";

import "prime_abi_types.slang";
import "contract/path.slang";
import "contract/light_sample.slang";

public SurfaceInteraction primeMissSurfaceInteraction(uint2 sourcePrimitive, float defaultRoughness) {
    SurfaceInteraction surface;
    surface.position = float3(0.0);
    surface.t = 0.0;
    surface.geometricNormal = float3(0.0, 1.0, 0.0);
    surface.hitKind = PRIME_HIT_NONE;
    surface.baseColor = float3(0.0);
    surface.materialControl = 0u;
    surface.sectionIndex = sourcePrimitive.x;
    surface.emitterIndex = PRIME_NO_LIGHT_INDEX;
    surface.textureLod = 0.0;
    surface.opacity = 0.0;
    surface.shadingNormal = float3(0.0, 1.0, 0.0);
    surface.roughness = defaultRoughness;
    surface.opticalControl = 0u;
    surface.motionZFlags = sourcePrimitive.y;
    surface.mediumId = 0u;
    surface.adjacentMediumId = 0u;
    surface.adjacentBaseColor = float3(0.0);
    surface.adjacentInterfaceControl = 0u;
    return surface;
}
