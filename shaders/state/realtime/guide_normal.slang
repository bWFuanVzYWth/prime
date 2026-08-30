#language slang 2026
module "state/realtime/guide_normal.slang";

import "state/realtime/contract.slang";

public void primeStoreWavefrontGuideNormal(uint pathIndex, float3 normal) {
    primeWavefrontPaths[pathIndex].psrPacked.xyz = asuint(normal);
}

public float3 primeLoadWavefrontGuideNormal(uint pathIndex) {
    return asfloat(primeWavefrontPaths[pathIndex].psrPacked.xyz);
}
