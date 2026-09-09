// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/lambert/path.slang";

import "contract/lambert.slang";

public PrimeLambertRecord primeLambertCameraPath(float3 origin, float3 direction) {
    PrimeLambertRecord path = {};
    path.origin = float4(origin, 0.0);
    path.direction = float4(direction, 0.0);
    path.throughput = float4(1.0, 1.0, 1.0, 0.0);
    path.identity.xy = uint2(0xffffffffu);
    // Untraced paths have no distance sample, including zero-albedo/early-terminated paths.
    path.radiance.w = 0.0;
    return path;
}

public void primeLambertRecordHitDistance(inout float firstDistance, uint completedScatters, float distance) {
    if (completedScatters == 1u) firstDistance = distance >= 0.0 ? distance : 65504.0;
}
