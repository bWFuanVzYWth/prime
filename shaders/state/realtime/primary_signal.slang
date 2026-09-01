#language slang 2026
module "state/realtime/primary_signal.slang";

import "prime_realtime_abi.slang";
import "service/reconstruct/nrd/common.slang";
import "service/reconstruct/ordinary.slang";
import "state/realtime/contract.slang";
import "contract/realtime/ordinary.slang";

// The primary guide is still the canonical empty guide at this boundary. Only visible direct
// radiance, sun data and area samples may have changed since camera trace.
public PrimeOrdinarySample primeLoadWavefrontPrimaryIntermediate(uint2 pixel) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    float4 sun = primeImageLoad(primeNrdSunLighting, coordinate);
    PrimeOrdinarySample result = primeEmptyOrdinarySample();
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.radiance.unshadowedSun = sun.rgb;
    result.radiance.sunVisibility = sun.a;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.sunPenumbra = primeImageLoad(
            primeNrdSunPenumbra, coordinate).r;
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    return result;
}
