#language slang 2026
module "state/realtime/guide_normal.slang";

import "prime_realtime_abi.slang";
import "state/realtime/contract.slang";

public void primeStoreWavefrontGuideSurface(
    uint pathIndex, float3 normal, float roughness) {
    int2 pixel = int2(primeWavefrontPixel(pathIndex));
    float4 guide = float4(normal, roughness);
    if (pathIndex >= primeWavefrontPixelCount()) {
        primeImageStore(primeNrdReflectionNormalRoughness, pixel, guide);
    } else {
        primeImageStore(primeNrdNormalRoughness, pixel, guide);
    }
}

public float4 primeLoadWavefrontGuideSurface(uint pathIndex) {
    int2 pixel = int2(primeWavefrontPixel(pathIndex));
    return pathIndex >= primeWavefrontPixelCount()
    ? primeImageLoad(primeNrdReflectionNormalRoughness, pixel)
    : primeImageLoad(primeNrdNormalRoughness, pixel);
}
