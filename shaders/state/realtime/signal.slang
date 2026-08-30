#language slang 2026
module "state/realtime/signal.slang";

import "prime_realtime_abi.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "contract/realtime/ordinary.slang";
public void primeStoreWavefrontIntermediate(
        uint2 pixel,
        PrimeOrdinarySample result) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(result.guides.specularHitDistance)));
    primeImageStore(
            primeStableRadiance,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.stable),
                    0.0));
    primeImageStore(
            primeNrdSunLighting,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.unshadowedSun),
                    primeNrdSanitizeUnit(result.radiance.sunVisibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(result.guides.sunPenumbra)));
    primeImageStore(
            primeNrdPrimaryPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.guides.primaryDistance));
    primeImageStore(
            primeNrdMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryLinearRoughness));
    primeImageStore(
            primeNrdSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
    primeImageStore(
            primeNrdViewZ,
            coordinate,
            float4(0.0));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdDiffuseDirection,
                coordinate,
                float4(result.guides.diffuseDirection, 0.0));
        primeImageStore(
                primeNrdSpecularDirection,
                coordinate,
                float4(result.guides.specularDirection, 0.0));
    }
}
