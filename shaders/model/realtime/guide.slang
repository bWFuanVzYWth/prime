// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/realtime/guide.slang";

import "contract/path.slang";
import "contract/realtime/guide.slang";
import "model/material/defaults.slang";

public PrimeDenoiserGuides primeEmptyDenoiserGuides() {
    PrimeDenoiserGuides guides;
    guides.primaryDistance = -1.0;
    guides.specularHitDistance = 0.0;
    guides.diffuseHitDistance = 0.0;
    guides.sunPenumbra = 0.0;
    guides.primaryAlbedo = float3(0.0);
    guides.primaryHitKind = PRIME_HIT_NONE;
    guides.primaryNormal = float3(0.0, 1.0, 0.0);
    guides.primaryMaterialFlags = 0u;
    guides.primarySpecularAlbedo = float3(0.0);
    guides.primaryLinearRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    guides.primaryPosition = float3(0.0);
    guides.primaryPreviousPosition = float3(0.0);
    guides.primaryHasMotion = false;
    guides.diffuseDirection = float3(0.0);
    guides.specularDirection = float3(0.0);
    guides.primaryAreaDiffuse = float3(0.0);
    guides.primaryAreaSpecular = float3(0.0);
    guides.primaryAreaDirection = float3(0.0);
    return guides;
}
