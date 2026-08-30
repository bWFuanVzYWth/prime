#language slang 2026
module "state/realtime/guide_normal.slang";

import "state/realtime/contract.slang";

public void primeStoreWavefrontGuideSurface(
        uint pathIndex, float3 normal, float roughness) {
    primeWavefrontPaths[pathIndex].guideNormalRoughness =
            float4(normal, roughness);
}

public float3 primeLoadWavefrontGuideNormal(uint pathIndex) {
    return primeWavefrontPaths[pathIndex].guideNormalRoughness.xyz;
}

public float primeLoadWavefrontGuideRoughness(uint pathIndex) {
    return primeWavefrontPaths[pathIndex].guideNormalRoughness.w;
}
