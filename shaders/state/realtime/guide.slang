#language slang 2026
module "state/realtime/guide.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/camera/projection.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "model/material/defaults.slang";
import "state/realtime/contract.slang";
import "state/realtime/signal.slang";
import "contract/path.slang";

import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "service/reconstruct/ordinary.slang";

public void primeStoreWavefrontVisibleScratch(
        uint2 pixel, PrimeDenoiserGuides visibleGuides) {
    int2 coordinate = int2(pixel);
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 4u)] =
            asuint(visibleGuides.primaryNormal.x);
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 5u)] =
            asuint(visibleGuides.primaryNormal.y);
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 6u)] =
            asuint(visibleGuides.primaryNormal.z);
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 7u)] =
            asuint(visibleGuides.primaryLinearRoughness);
    primeImageStore(
            primeStableRadiance,
            coordinate,
            float4(0.0, 0.0, 0.0, visibleGuides.primaryDistance));
    primeImageStore(primeNrdSunPenumbra, coordinate, float4(0.0));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdDisplayPosition,
                coordinate,
                float4(
                        visibleGuides.primaryPosition,
                        asfloat(visibleGuides.primaryMaterialFlags)));
        primeImageStore(primeNrdSunLighting, coordinate, float4(0.0));
    } else {
        // A transparent primary has no SIGMA sun lane. Before resolve this otherwise-unused image
        // preserves the visible interface's specular albedo for RR/no-post output.
        primeImageStore(
                primeNrdSunLighting,
                coordinate,
                float4(
                        visibleGuides.primarySpecularAlbedo,
                        visibleGuides.primaryLinearRoughness));
    }
}

public PrimeDenoiserGuides primeLoadWavefrontVisibleGuides(
        uint2 pixel,
        PrimeTransparentBranchResult transmission,
        PrimeTransparentBranchResult reflection) {
    int2 coordinate = int2(pixel);
    PrimeDenoiserGuides guides = transmission.guides;
    guides.primaryDistance = primeImageLoad(primeStableRadiance, coordinate).a;
    guides.primaryHitKind = guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    float4 visibleNormalRoughness = asfloat(uint4(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 4u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 5u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 6u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 7u)]));
    guides.primaryNormal = visibleNormalRoughness.xyz;
    guides.primaryLinearRoughness = visibleNormalRoughness.w;
    if (primeWritesNrdShInputs()) {
        float4 displayPosition =
                primeImageLoad(primeNrdDisplayPosition, coordinate);
        guides.primaryPosition = displayPosition.xyz;
        guides.primaryMaterialFlags = asuint(displayPosition.w);
    } else {
        guides.primaryPosition = primeCameraRayDirection(
                pixel, primeRealtimeCameraSample(pixel)) * guides.primaryDistance;
        float4 visibleMaterial = primeImageLoad(primeNrdSunLighting, coordinate);
        guides.primarySpecularAlbedo = visibleMaterial.rgb;
        guides.primaryLinearRoughness = visibleMaterial.a;
    }
    guides.diffuseDirection = transmission.guides.diffuseDirection;
    guides.specularDirection = reflection.guides.specularDirection;
    primeLoadWavefrontArea(
            pixel,
            guides.primaryAreaDiffuse,
            guides.primaryAreaSpecular,
            guides.primaryAreaDirection);
    return guides;
}

// A queued ordinary continuation already owns a primary guide. Only radiance and the two
// first-bounce hit distances evolve on every vertex. The pre-guide discrete chain additionally needs
// its albedo products, but no other immutable guide field participates in transport.
public PrimeOrdinarySample primeLoadWavefrontActiveIntermediate(
        uint2 pixel,
        uint control) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    PrimeOrdinarySample result = primeEmptyOrdinarySample();
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    if ((control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u) {
        result.guides.primaryAlbedo =
                primeImageLoad(primeNrdMaterial, coordinate).rgb;
        result.guides.primarySpecularAlbedo =
                primeImageLoad(primeNrdSpecularMaterial, coordinate).rgb;
    }
    return result;
}

public void primeStoreWavefrontActiveIntermediate(
        uint2 pixel,
        bool guideWasPending,
        PrimeOrdinarySample result) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.diffuse),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.radiance.specular),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    if (guideWasPending) {
        float4 encoded = primeImageLoad(primeNrdSpecularMaterial, coordinate);
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(
                        result.guides.primarySpecularAlbedo,
                        encoded.a));
    }
}
