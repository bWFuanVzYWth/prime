// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/nrd/direction.slang";

import "service/reconstruct/nrd/common.slang";

public float3 primeNrdEffectiveDirection(
        float3 totalRadiance,
        float3 primaryAreaRadiance,
        float3 primaryAreaDirection,
        float3 continuationDirection,
        float3 albedo) {
    float3 direct = min(primaryAreaRadiance, totalRadiance);
    float3 continuation = max(totalRadiance - direct, float3(0.0));
    float directY = primeNrdY(primeNrdDemodulate(direct, albedo));
    float continuationY = primeNrdY(primeNrdDemodulate(continuation, albedo));
    float totalY = directY + continuationY;
    float3 moment = primaryAreaDirection * directY
            + continuationDirection * continuationY;
    return primeNrdIsFinite(moment) && primeNrdIsFinite(totalY) && totalY > 0.0
            ? clamp(moment / totalY, float3(-1.0), float3(1.0))
            : float3(0.0);
}
