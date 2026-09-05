#language slang 2026
module "service/reconstruct/lambert_signal.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";

public void primeLambertWriteSignal(uint2 pixel, PrimeLambertRecord path) {
    bool visible = path.identity.w != 0u;
    float3 radiance = primeNrdSanitizeRadiance(path.radiance.rgb);
    primeImageStore(primeStableRadiance, int2(pixel), float4(visible ? float3(0.0) : radiance, 1.0));
    primeImageStore(primeNrdNoisyDiffuse, int2(pixel), float4(
            visible ? primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX) : float3(0.0),
            visible ? primeNrdSanitizeHitDistance(path.radiance.w) : 0.0));
    if (primeWritesNrdShInputs()) {
        primeImageStore(primeNrdDiffuseDirection, int2(pixel), float4(
                visible ? path.firstDirection.xyz : float3(0.0), 0.0));
    }
}
