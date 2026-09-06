#language slang 2026
module "service/reconstruct/lambert_signal.slang";
import "state/lambert.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "state/lambert_interface.slang";

public void primeLambertWriteSignal(uint2 pixel, PrimeLambertRecord path) {
    bool visible = (path.identity.w & PRIME_LAMBERT_VISIBLE) != 0u;
    bool reflection = (path.identity.w & PRIME_LAMBERT_REFLECTION) != 0u;
    bool sh = primeWritesNrdShInputs();
    uint index = pixel.y * primePush.outputExtent.x + pixel.x;
    float3 radiance = primeNrdSanitizeRadiance(path.radiance.rgb);
    if (!visible) primeImageStore(primeStableRadiance, int2(pixel), float4(radiance, 1.0));
    primeImageStore(primeNrdNoisyDiffuse, int2(pixel), float4(
            visible && !reflection ? primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX) : float3(0.0),
            visible && !reflection ? primeNrdSanitizeHitDistance(path.radiance.w) : 0.0));
    if (reflection) {
        if (sh) primeImageStore(primeNrdReflectionNoisyDiffuse, int2(pixel), float4(
                primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX), primeNrdSanitizeHitDistance(path.radiance.w)));
        else {
            float distance = primeImageLoad(primeNrdNoisySpecular, int2(pixel)).w;
            primeImageStore(primeNrdNoisySpecular, int2(pixel), float4(
                    primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX), distance));
        }
    }
    if (sh && visible) {
        float3 albedo = reflection ? primeImageLoad(primeNrdReflectionMaterial, int2(pixel)).rgb
                : primeImageLoad(primeNrdMaterial, int2(pixel)).rgb;
        float3 indirect = max(radiance - primeLambertInterfaceRow(index, 1u).rgb, float3(0.0));
        float weight = primeNrdY(primeNrdDemodulate(indirect, albedo));
        float4 direct = primeLambertInterfaceRow(index, 2u);
        float total = direct.w + weight;
        float3 direction = total > 0.0 ? (direct.xyz + weight * path.firstDirection.xyz) / total : float3(0.0);
        direction = primeNrdIsFinite(direction) ? clamp(direction, float3(-1.0), float3(1.0)) : float3(0.0);
        if (reflection) primeImageStore(primeNrdReflectionDiffuseDirection, int2(pixel), float4(direction, 0.0));
        else primeImageStore(primeNrdDiffuseDirection, int2(pixel), float4(direction, 0.0));
    }
}
