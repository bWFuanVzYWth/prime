// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/scatter_signal.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";

// Direct publishes sanitized radiance before scatter. A hit changes only these distance lanes;
// never retain unchanged RGB across BSDF sampling or rewrite it on later unrelated vertices.
public void primeStoreScatterHitDistances(uint recordIndex, uint control, float distance,
        bool firstHit, bool guideHit) {
    bool transparent = primeWavefrontTransparentBranch(control);
    bool transmission = primeWavefrontTransmissionBranch(control);
    int2 pixel = int2(primeWavefrontPixel(recordIndex));
    float hitDistance = primeNrdSanitizeHitDistance(distance);
    if (firstHit && transparent) {
        if (transmission) {
            float4 metadata = primeImageLoad(primeWavefrontTransportMetadata, pixel);
            metadata.y = hitDistance;
            primeImageStore(primeWavefrontTransportMetadata, pixel, metadata);
        } else {
            primeImageStore(primeNrdSunPenumbra, pixel, float4(hitDistance));
        }
    }
    if (!guideHit) return;
    bool diffuse = (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    if (!transparent || transmission) {
        if (diffuse) {
            float4 value = primeImageLoad(primeNrdNoisyDiffuse, pixel);
            value.a = hitDistance;
            primeImageStore(primeNrdNoisyDiffuse, pixel, value);
        } else {
            float4 value = primeImageLoad(primeNrdNoisySpecular, pixel);
            value.a = hitDistance;
            primeImageStore(primeNrdNoisySpecular, pixel, value);
        }
    } else if (primeWritesNrdShInputs()) {
        if (diffuse) {
            float4 value = primeImageLoad(primeNrdReflectionNoisyDiffuse, pixel);
            value.a = hitDistance;
            primeImageStore(primeNrdReflectionNoisyDiffuse, pixel, value);
        } else {
            float4 value = primeImageLoad(primeNrdReflectionNoisySpecular, pixel);
            value.a = hitDistance;
            primeImageStore(primeNrdReflectionNoisySpecular, pixel, value);
        }
    }
}
