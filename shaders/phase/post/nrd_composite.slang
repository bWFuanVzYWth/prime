#language slang 2026
module "phase/post/nrd_composite.slang";

import "service/image/access.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/epipolar.slang";
import "service/reconstruct/display.slang";
import "service/reconstruct/fsr_input.slang";
import "service/reconstruct/nrd/common.slang";
import "model/material/defaults.slang";
import "service/reconstruct/nrd/sh.slang";

// FSR consumes scene-referred linear Rec.2020 HDR. The display transform belongs strictly
// after upscaling; applying it here would make temporal interpolation operate on non-linear,
// display-referred values and violate both the integrator and FSR signal contracts.
[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> primeCompositeOutput;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeDenoisedDiffuse;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeDenoisedSpecular;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeCompositeMaterial;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeCompositeSpecularMaterial;
[[vk::binding(5, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeStableAccumulation;
[[vk::binding(6, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> primeCompositeAerialRadiance;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> primeCompositeAerialTransmittance;
[[vk::binding(8, 0)]] [[vk::image_format("r8")]] writeonly RWTexture2D<float4> primeFsrReactiveMask;
[[vk::binding(9, 0)]] [[vk::image_format("r8")]] writeonly RWTexture2D<float4> primeFsrTransparencyCompositionMask;
[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeUnshadowedSunLighting;
[[vk::binding(11, 0)]] [[vk::image_format("r16f")]] readonly RWTexture2D<float4> primeSigmaSunShadow;
[[vk::binding(12, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeDenoisedDiffuseSh1;
[[vk::binding(13, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeDenoisedSpecularSh1;
[[vk::binding(14, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeCompositeNormalRoughness;
[[vk::binding(15, 0)]] [[vk::image_format("r32f")]] readonly RWTexture2D<float4> primeCompositeViewZ;
[[vk::binding(16, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeCompositePrimaryPosition;
[[vk::binding(17, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeNoisySpecular;
[[vk::binding(18, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionDenoisedDiffuse;
[[vk::binding(19, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionDenoisedSpecular;
[[vk::binding(20, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionCompositeMaterial;
[[vk::binding(21, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionCompositeSpecularMaterial;
[[vk::binding(22, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionDenoisedDiffuseSh1;
[[vk::binding(23, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeReflectionDenoisedSpecularSh1;
[[vk::binding(24, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeReflectionCompositeNormalRoughness;
[[vk::binding(25, 0)]] [[vk::image_format("r32f")]] readonly RWTexture2D<float4> primeReflectionCompositeViewZ;
[[vk::binding(26, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeReflectionCompositePosition;
[[vk::binding(27, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeDisplayPosition;

struct PrimeCompositePushConstants {
    uint2 outputExtent;
    float sunRadianceMultiplier;
    float2 currentJitterPixels;
    float2 epipoleNdc;
};
[[vk::push_constant]] ConstantBuffer<PrimeCompositePushConstants> primeCompositePush;

// The viewport corner angles are identical for every lane in this 8x8 workgroup.
groupshared AtmEpipolarRange primeCompositeEpipolarRange;

float3 primeCompositeSurfaceSignal(
        float3 diffuseAlbedo,
        float3 specularAlbedo,
        float3 diffuseSignal,
        float3 specularSignal,
        float3 sunSignal) {
    float3 diffuse = primeNrdSanitizeRadiance(diffuseSignal) * diffuseAlbedo;
    float3 specular = primeNrdSanitizeRadiance(specularSignal) * specularAlbedo;
    primeNrdClampRadianceTriple(
            diffuse, specular, sunSignal, PRIME_NRD_FP16_MAX);
    return diffuse + specular + sunSignal;
}

float3 primeCompositeApplyAerial(
        uint2 pixel, float primaryDistance, float3 radiance) {
    if (!primeNrdIsFinite(primaryDistance) || primaryDistance < 0.0) {
        return radiance;
    }
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    int3 dimensions = primeReadonlyImageSize(primeCompositeAerialRadiance);
    float firstSliceDepth = 0.5 / float(max(dimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    float2 screenUv = (float2(pixel) + float2(0.5)
            + primeCompositePush.currentJitterPixels)
            / float2(primeCompositePush.outputExtent);
    float3 uvw = float3(screenUv, normalizedDepth);
    float3 inscatter = max(
            atmSampleEpipolarWithRange(
                    primeCompositeAerialRadiance,
                    screenUv,
                    normalizedDepth,
                    primeCompositePush.epipoleNdc,
                    primeCompositeEpipolarRange).rgb,
            float3(0.0));
    float3 transmittance = clamp(
            atmSample3D(primeCompositeAerialTransmittance, uvw).rgb,
            float3(0.0),
            float3(1.0));
    if (any(isnan(inscatter)) || any(isinf(inscatter))) {
        inscatter = float3(0.0);
    }
    if (any(isnan(transmittance)) || any(isinf(transmittance))) {
        transmittance = float3(1.0);
    }
    return radiance * lerp(float3(1.0), transmittance, nearWeight)
            + inscatter * nearWeight * primeCompositePush.sunRadianceMultiplier;
}

int2 primeCompositeNeighbor(int2 pixel, int2 offset) {
    return clamp(
            pixel + offset,
            int2(0),
            int2(primeCompositePush.outputExtent) - int2(1));
}

float4 primeCompositeLoadNormalRoughness(
        int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(
                    primeReflectionCompositeNormalRoughness,
                    pixel)
            : primeReadonlyImageLoad(primeCompositeNormalRoughness, pixel);
}

float4 primeCompositeLoadDenoisedSh0(
        int2 pixel, bool reflection, bool specular) {
    if (reflection) {
        return specular
                ? primeReadonlyImageLoad(primeReflectionDenoisedSpecular, pixel)
                : primeReadonlyImageLoad(primeReflectionDenoisedDiffuse, pixel);
    }
    return specular
            ? primeReadonlyImageLoad(primeDenoisedSpecular, pixel)
            : primeReadonlyImageLoad(primeDenoisedDiffuse, pixel);
}

float3 primeCompositeLoadDenoisedSh1(
        int2 pixel, bool reflection, bool specular) {
    if (reflection) {
        return specular
                ? primeReadonlyImageLoad(primeReflectionDenoisedSpecularSh1, pixel).xyz
                : primeReadonlyImageLoad(primeReflectionDenoisedDiffuseSh1, pixel).xyz;
    }
    return specular
            ? primeReadonlyImageLoad(primeDenoisedSpecularSh1, pixel).xyz
            : primeReadonlyImageLoad(primeDenoisedDiffuseSh1, pixel).xyz;
}

float4 primeCompositeLoadMaterial(int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(primeReflectionCompositeMaterial, pixel)
            : primeReadonlyImageLoad(primeCompositeMaterial, pixel);
}

float3 primeCompositeLoadSpecularAlbedo(int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(primeReflectionCompositeSpecularMaterial, pixel).rgb
            : primeReadonlyImageLoad(primeCompositeSpecularMaterial, pixel).rgb;
}

float3 primeCompositeLoadPosition(int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(primeReflectionCompositePosition, pixel).xyz
            : primeReadonlyImageLoad(primeCompositePrimaryPosition, pixel).xyz;
}

float primeCompositeLoadViewZ(int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(primeReflectionCompositeViewZ, pixel).x
            : primeReadonlyImageLoad(primeCompositeViewZ, pixel).x;
}

float3 primeCompositeSunSignal(int2 pixel) {
    float4 unshadowed = primeReadonlyImageLoad(primeUnshadowedSunLighting, pixel);
    // The filtered shadow is stored as sqrt(visibility).
    float encodedShadow = primeReadonlyImageLoad(primeSigmaSunShadow, pixel).x;
    float visibility = isnan(encodedShadow) || isinf(encodedShadow)
            ? primeNrdSanitizeUnit(unshadowed.a, 0.0)
            : clamp(encodedShadow * encodedShadow, 0.0, 1.0);
    return unshadowed.rgb * visibility;
}

struct PrimeCompositeResolveGuide {
    bool valid;
    float3 diffuseAlbedo;
    float3 specularAlbedo;
    float3 sun;
    float3 normal;
    float roughness;
    float3 view;
    float viewZ;
    float4 neighborViewZ;
    float3 eastNormal;
    float3 westNormal;
    float3 northNormal;
    float3 southNormal;
};

PrimeCompositeResolveGuide primeCompositeLoadResolveGuide(
        int2 pixel,
        bool reflection,
        float4 material,
        bool includeSun) {
    // Resolve and official ReJitter use one current-frame guide and its four-neighbor geometry.
    PrimeCompositeResolveGuide guide;
    guide.valid = primeNrdIsFinite(material.a)
            && material.a >= 0.0;
    if (!guide.valid) {
        return guide;
    }

    guide.diffuseAlbedo = primeNrdSanitizeAlbedo(material.rgb);
    float4 normalRoughness =
            primeCompositeLoadNormalRoughness(
                    pixel, reflection);
    primeNrdReadNormalRoughness(
            normalRoughness, guide.normal, guide.roughness);
    guide.view = primeNrdSafeNormalize(
            -primeCompositeLoadPosition(pixel, reflection),
            guide.normal);
    int2 east = primeCompositeNeighbor(
            pixel, int2(1, 0));
    int2 west = primeCompositeNeighbor(
            pixel, int2(-1, 0));
    int2 north = primeCompositeNeighbor(
            pixel, int2(0, 1));
    int2 south = primeCompositeNeighbor(
            pixel, int2(0, -1));
    guide.viewZ = primeCompositeLoadViewZ(
            pixel, reflection);
    guide.neighborViewZ = float4(
            primeCompositeLoadViewZ(east, reflection),
            primeCompositeLoadViewZ(west, reflection),
            primeCompositeLoadViewZ(north, reflection),
            primeCompositeLoadViewZ(south, reflection));
    float neighborRoughness;
    float4 neighborNormalRoughness =
            primeCompositeLoadNormalRoughness(
                    east, reflection);
    primeNrdReadNormalRoughness(
            neighborNormalRoughness,
            guide.eastNormal,
            neighborRoughness);
    neighborNormalRoughness =
            primeCompositeLoadNormalRoughness(
                    west, reflection);
    primeNrdReadNormalRoughness(
            neighborNormalRoughness,
            guide.westNormal,
            neighborRoughness);
    neighborNormalRoughness =
            primeCompositeLoadNormalRoughness(
                    north, reflection);
    primeNrdReadNormalRoughness(
            neighborNormalRoughness,
            guide.northNormal,
            neighborRoughness);
    neighborNormalRoughness =
            primeCompositeLoadNormalRoughness(
                    south, reflection);
    primeNrdReadNormalRoughness(
            neighborNormalRoughness,
            guide.southNormal,
            neighborRoughness);
    guide.specularAlbedo = primeNrdSanitizeAlbedo(
            primeCompositeLoadSpecularAlbedo(
                    pixel, reflection));
    guide.sun = includeSun
            ? primeCompositeSunSignal(pixel)
            : float3(0.0);
    return guide;
}

PrimeNrdResolvedSg primeCompositeResolveSh(
        int2 signalPixel,
        bool reflection,
        PrimeCompositeResolveGuide guide) {
    PrimeNrdSg diffuseSg = primeNrdUnpackSg(
            primeCompositeLoadDenoisedSh0(
                    signalPixel, reflection, false),
            primeCompositeLoadDenoisedSh1(
                    signalPixel, reflection, false));
    PrimeNrdSg specularSg = primeNrdUnpackSg(
            primeCompositeLoadDenoisedSh0(
                    signalPixel, reflection, true),
            primeCompositeLoadDenoisedSh1(
                    signalPixel, reflection, true));
    return primeNrdResolveFilteredSg(
            diffuseSg,
            specularSg,
            guide.view,
            guide.roughness,
            guide.viewZ,
            guide.neighborViewZ,
            guide.normal,
            guide.eastNormal,
            guide.westNormal,
            guide.northNormal,
            guide.southNormal);
}

float3 primeCompositeResolveBranchValue(
        int2 signalPixel,
        bool reflection,
        PrimeCompositeResolveGuide guide,
        bool remodulatedSpecular) {
    if (!guide.valid) {
        return float3(0.0);
    }

    PrimeNrdResolvedSg resolved = primeCompositeResolveSh(
            signalPixel, reflection, guide);
    if (remodulatedSpecular) {
        return primeNrdClampRadiance(
                primeNrdSanitizeRadiance(resolved.specular)
                        * guide.specularAlbedo,
                PRIME_NRD_FP16_MAX);
    }
    return primeCompositeSurfaceSignal(
            guide.diffuseAlbedo,
            guide.specularAlbedo,
            resolved.diffuse,
            resolved.specular,
            guide.sun);
}

float3 primeCompositeRadiance(
        uint2 pixel,
        float4 material,
        float3 displayPosition,
        uint visibleMaterialFlags) {
    if (!primeNrdIsFinite(material.a) || material.a < 0.0) {
        // Raygen's sky contract also guarantees a zero display position and no reflection guide,
        // so the old path resolved to this same stable signal without aerial sampling.
        return primeNrdClampRadiance(
                primeReadonlyImageLoad(primeStableAccumulation, int2(pixel)).rgb,
                PRIME_NRD_FP16_MAX);
    }
    bool transparent =
            primeMaterialIsTransmissive(visibleMaterialFlags);
    PrimeCompositeResolveGuide primaryGuide =
            primeCompositeLoadResolveGuide(
                    int2(pixel),
                    false,
                    material,
                    !transparent);
    // Official SG ReJitter restores the current sampling phase multiplicatively at this pixel.
    // Spatially translating the already re-jittered SH applies camera jitter a second time.
    float3 radiance = primeCompositeResolveBranchValue(
            int2(pixel),
            false,
            primaryGuide,
            false);
    if (transparent) {
        float4 reflectionMaterial =
                primeCompositeLoadMaterial(
                        int2(pixel), true);
        PrimeCompositeResolveGuide reflectionGuide =
                primeCompositeLoadResolveGuide(
                        int2(pixel),
                        true,
                        reflectionMaterial,
                        false);
        radiance += primeCompositeResolveBranchValue(
                int2(pixel),
                true,
                reflectionGuide,
                false);
    }
    float3 stable = primeNrdClampRadiance(
            primeReadonlyImageLoad(primeStableAccumulation, int2(pixel)).rgb,
            PRIME_NRD_FP16_MAX);
    radiance += stable;
    float displayDistance = all(displayPosition == float3(0.0))
            ? -1.0
            : length(displayPosition);
    radiance = primeCompositeApplyAerial(pixel, displayDistance, radiance);
    return primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX);
}

float3 primeCompositeSh0Color(float4 sh0) {
    return primeNrdSgExtractColor(primeNrdUnpackSg(sh0, float3(0.0)));
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    if (groupIndex == 0u) {
        primeCompositeEpipolarRange = atmEpipolarVisibleRange(
                primeCompositePush.epipoleNdc);
    }
    GroupMemoryBarrierWithGroupSync();
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeCompositePush.outputExtent)) {
        return;
    }
    float4 displayPosition = primeReadonlyImageLoad(primeDisplayPosition, int2(pixel));
    uint visibleMaterialFlags = asuint(displayPosition.w);
    PrimeFsrMasks fsrMasks = primeFsrMasks(visibleMaterialFlags);
    primeWriteImageStore(
            primeFsrReactiveMask,
            int2(pixel),
            float4(primeFsrSanitizeMask(fsrMasks.reactive)));
    primeWriteImageStore(
            primeFsrTransparencyCompositionMask,
            int2(pixel),
            float4(primeFsrSanitizeMask(
                    fsrMasks.transparencyAndComposition)));
    float4 material = primeReadonlyImageLoad(primeCompositeMaterial, int2(pixel));
    float3 radiance = primeCompositeRadiance(
            pixel,
            material,
            displayPosition.xyz,
            visibleMaterialFlags);
    primeWriteImageStore(
            primeCompositeOutput,
            int2(pixel),
            float4(radiance, 1.0));
}

