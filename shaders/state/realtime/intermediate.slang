// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/intermediate.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "contract/path.slang";
import "state/realtime/area.slang";
import "contract/realtime/ordinary.slang";
import "model/realtime/guide.slang";
import "math/direction.slang";
import "policy/runtime/config.slang";

public PrimeOrdinarySample primeLoadWavefrontIntermediate(
        uint2 pixel,
        uint pathIndex,
        uint denoiserControl) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    float4 stable = primeImageLoad(primeStableRadiance, coordinate);
    float4 sun = primeImageLoad(primeNrdSunLighting, coordinate);
    float4 position = primeImageLoad(primeNrdPrimaryPosition, coordinate);
    float4 material = primeImageLoad(primeNrdMaterial, coordinate);
    float4 specularMaterial = primeImageLoad(primeNrdSpecularMaterial, coordinate);
    PrimeOrdinarySample result;
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.radiance.stable = stable.rgb;
    result.radiance.unshadowedSun = sun.rgb;
    result.radiance.sunVisibility = sun.a;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.primaryDistance = position.w;
    result.guides.specularHitDistance = specular.a;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.sunPenumbra = primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryHitKind = position.w >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    float4 guideNormalRoughness = pathIndex >= primeWavefrontPixelCount()
            ? primeImageLoad(primeNrdReflectionNormalRoughness, coordinate)
            : primeImageLoad(primeNrdNormalRoughness, coordinate);
    result.guides.primaryNormal = guideNormalRoughness.xyz;
    result.guides.primaryMaterialFlags =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = guideNormalRoughness.w;
    result.guides.primaryPosition = position.xyz;
    uint4 previous = primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].psrPacked;
    result.guides.primaryHasMotion =
            (previous.w & PRIME_WAVEFRONT_PRIMARY_MOTION) != 0u;
    result.guides.primaryPreviousPosition = result.guides.primaryHasMotion
            ? asfloat(previous.xyz)
            : position.xyz;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    return result;
}
