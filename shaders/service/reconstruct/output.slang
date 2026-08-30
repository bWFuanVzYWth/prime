#language slang 2026
module "service/reconstruct/output.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/reconstruct/auto_exposure.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "contract/path.slang";

import "contract/realtime/resolved.slang";
import "service/reconstruct/resolved.slang";

// The realtime output adapter encodes reconstruction signals from one resolved model sample and
// never feeds output data back into transport.

public float3 primeNrdEffectiveDirection(
        float3 totalRadiance,
        float3 primaryAreaRadiance,
        float3 primaryAreaDirection,
        float3 continuationDirection,
        float3 albedo) {
    float3 direct = min(primaryAreaRadiance, totalRadiance);
    float3 continuation = max(totalRadiance - direct, float3(0.0));
    float directY = primeNrdY(primeNrdDemodulate(direct, albedo));
    float continuationY = primeNrdY(primeNrdDemodulate(continuation, albedo));
    float totalY = directY + continuationY;
    float3 moment = primaryAreaDirection * directY
            + continuationDirection * continuationY;
    return primeNrdIsFinite(moment) && primeNrdIsFinite(totalY) && totalY > 0.0
            ? clamp(moment / totalY, float3(-1.0), float3(1.0))
            : float3(0.0);
}

public void primeWriteRealtimeOutput(
        uint2 pixel,
        float2 cameraSample,
        PrimeResolvedSample sampleResult,
        float4 reflectionPreviousVirtualPosition) {
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasTransmissionGuide = sampleResult.transparentPrimary
            && sampleResult.transmissionGuides.primaryHitKind == PRIME_HIT_SURFACE;
    bool usesTransmissionAnchor = hasTransmissionGuide
            && primeNrdIsFinite(sampleResult.transmissionAnchorDistance)
            && sampleResult.transmissionAnchorDistance > 0.0;
    // RR cannot represent both the interface and the refracted scene as primary geometry. Keep
    // the guide topology fixed: a transmitted solid-angle surface owns the primary guide
    // regardless of its current Fresnel energy.
    bool rrUsesTransmissionGuide = !nrdShInputs && usesTransmissionAnchor;
    bool rrUsesReflectionGuide = !nrdShInputs
            && primeRrUsesReflectionPrimaryGuide(
                    sampleResult, reflectionPreviousVirtualPosition.w);
    bool rrUsesVirtualPrimaryGuide =
            rrUsesTransmissionGuide || rrUsesReflectionGuide;
    PrimeDenoiserGuides nrdGuides = primeSelectReconstructionPrimaryGuides(
            sampleResult,
            nrdShInputs,
            rrUsesTransmissionGuide,
            rrUsesReflectionGuide);
    if (sampleResult.transparentReflectionOnly && !rrUsesReflectionGuide) {
        nrdGuides.primaryAlbedo = float3(0.0);
        if (nrdShInputs) {
            nrdGuides.primarySpecularAlbedo = float3(0.0);
        }
    }
    float3 noisyDiffuse = sampleResult.radiance.diffuse;
    float3 noisySpecular = sampleResult.radiance.specular;
    if (!nrdShInputs && sampleResult.transparentPrimary) {
        if (rrUsesReflectionGuide) {
            // Pure TIR through a finite PSR target is a direct view of virtual geometry. Preserve
            // the target closure's signal split so RR receives the same guide topology as a
            // directly visible surface.
            noisyDiffuse = sampleResult.reflectionDiffuseRadiance;
            noisySpecular = sampleResult.reflectionSpecularRadiance;
        } else {
            noisyDiffuse += noisySpecular;
            noisySpecular = sampleResult.reflectionDiffuseRadiance
                    + sampleResult.reflectionSpecularRadiance;
        }
    }
    bool hasPrimarySurface = nrdGuides.primaryHitKind == PRIME_HIT_SURFACE;
    float primaryDistance = primeNrdSanitizePrimaryDistance(
            usesTransmissionAnchor
                    ? sampleResult.transmissionAnchorDistance
                    : nrdGuides.primaryDistance,
            hasPrimarySurface);
    bool hasVisibleSurface = sampleResult.guides.primaryHitKind == PRIME_HIT_SURFACE;
    float visibleDistance = primeNrdSanitizePrimaryDistance(
            sampleResult.guides.primaryDistance, hasVisibleSurface);
    uint primaryMaterialFlags = primeNrdPrimaryMaterialFlags(
            nrdGuides.primaryMaterialFlags,
            sampleResult.guides.primaryMaterialFlags,
            nrdShInputs && sampleResult.transparentPrimary);
    float3 primaryPosition = primeNrdIsFinite(nrdGuides.primaryPosition)
            ? nrdGuides.primaryPosition
            : float3(0.0);
    bool primaryHasMotion = nrdGuides.primaryHasMotion
            && primeNrdIsFinite(nrdGuides.primaryPreviousPosition);
    float3 primaryPreviousPosition = primaryHasMotion
            ? nrdGuides.primaryPreviousPosition
            : primaryPosition;
    if (usesTransmissionAnchor) {
        // A refracted hit does not project to this pixel under the camera's straight projection.
        // Put its plane intersection on the visible primary ray so a static camera has zero
        // motion while depth remains anchored to the target surface.
        float3 fallbackDirection = primeNrdSafeNormalize(
                nrdGuides.primaryPosition, float3(0.0, 0.0, 1.0));
        float3 primaryDirection = primeNrdSafeNormalize(
                sampleResult.guides.primaryPosition, fallbackDirection);
        primaryPosition = primaryDirection * primaryDistance;
    }
    float specularHitDistance = !nrdShInputs
            && sampleResult.transparentPrimary
            && !rrUsesReflectionGuide
            ? sampleResult.guides.specularHitDistance
            : nrdGuides.specularHitDistance;
    float rawSignalScale = primeNrdClampRadiancePair(
            noisyDiffuse, noisySpecular, PRIME_NRD_FP16_MAX);
    // These images are a private raygen -> denoiser-preparation scratch contract. The existing
    // ray-tracing-to-compute barrier publishes them before nrd_motion converts them in place.
    primeImageStore(
            primeNrdNoisyDiffuse,
            int2(pixel),
            float4(
                    primeNrdSanitizeRadiance(noisyDiffuse),
                    primeNrdSanitizeHitDistance(nrdGuides.diffuseHitDistance)));
    primeImageStore(
            primeNrdNoisySpecular,
            int2(pixel),
            float4(
                    primeNrdSanitizeRadiance(noisySpecular),
                    primeNrdSanitizeHitDistance(specularHitDistance)));
    primeImageStore(
            primeNrdPrimaryPosition,
            int2(pixel),
            float4(
                    primaryDistance < 0.0
                            ? float3(0.0)
                            : (!nrdShInputs && primaryHasMotion
                                    ? primaryPreviousPosition
                                    : primaryPosition),
                    nrdShInputs
                            ? asfloat(packHalf2x16(cameraSample))
                            : (primaryHasMotion
                                    ? -primaryDistance - 1.0
                                    : primaryDistance)));
    if (nrdShInputs) {
        float3 primaryAreaDiffuse = nrdGuides.primaryAreaDiffuse * rawSignalScale;
        float3 primaryAreaSpecular = nrdGuides.primaryAreaSpecular * rawSignalScale;
        primeImageStore(
                primeNrdDiffuseDirection,
                int2(pixel),
                float4(primeNrdEffectiveDirection(
                        noisyDiffuse,
                        primaryAreaDiffuse,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.diffuseDirection,
                        nrdGuides.primaryAlbedo), 0.0));
        primeImageStore(
                primeNrdSpecularDirection,
                int2(pixel),
                float4(primeNrdEffectiveDirection(
                        noisySpecular,
                        primaryAreaSpecular,
                        nrdGuides.primaryAreaDirection,
                        nrdGuides.specularDirection,
                        nrdGuides.primarySpecularAlbedo), 0.0));
    }
    primeImageStore(
            primeNrdMaterial,
            int2(pixel),
            float4(
                    primeNrdSanitizeAlbedo(nrdGuides.primaryAlbedo),
                    rrUsesVirtualPrimaryGuide ? visibleDistance : primaryDistance));
    primeImageStore(
            primeNrdNormalRoughness,
            int2(pixel),
            primeNrdDirectNormalRoughness(
                    nrdGuides.primaryNormal,
                    nrdGuides.primaryLinearRoughness));
    primeImageStore(
            primeNrdMaterialClass,
            int2(pixel),
            float4(primeNrdMaterialId(primaryMaterialFlags)));
    primeImageStore(
            primeNrdSpecularMaterial,
            int2(pixel),
            float4(
                    primeNrdSanitizeAlbedo(nrdGuides.primarySpecularAlbedo),
                    nrdShInputs
                            ? float(nrdGuides.primaryMaterialFlags)
                            : (rrUsesReflectionGuide
                                    ? nrdGuides.primaryLinearRoughness
                                    : sampleResult.guides.primaryLinearRoughness)));
    if (nrdShInputs) {
        bool visibleHasMotion = sampleResult.guides.primaryHasMotion
                && hasVisibleSurface
                && primeNrdIsFinite(
                        sampleResult.guides.primaryPreviousPosition);
        primeImageStore(
                primeNrdDisplayPosition,
                int2(pixel),
                float4(
                        hasVisibleSurface
                                ? (visibleHasMotion
                                        ? sampleResult.guides.primaryPreviousPosition
                                        : sampleResult.guides.primaryPosition)
                                : float3(0.0),
                        asfloat(hasVisibleSurface
                                ? sampleResult.guides.primaryMaterialFlags
                                        | (visibleHasMotion
                                                ? PRIME_DISPLAY_MOTION_FLAG
                                                : 0u)
                                : 0u)));

        PrimeDenoiserGuides reflectionGuides = sampleResult.reflectionGuides;
        bool hasReflectionGuide = sampleResult.transparentPrimary
                && reflectionGuides.primaryHitKind == PRIME_HIT_SURFACE;
        if (hasReflectionGuide) {
            float reflectionDistance = primeNrdSanitizePrimaryDistance(
                    reflectionGuides.primaryDistance, true);
            float3 reflectionDiffuse = sampleResult.reflectionDiffuseRadiance;
            float3 reflectionSpecular = sampleResult.reflectionSpecularRadiance;
            float reflectionScale = primeNrdClampRadiancePair(
                    reflectionDiffuse, reflectionSpecular, PRIME_NRD_FP16_MAX);
            primeImageStore(
                    primeNrdReflectionNoisyDiffuse,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeRadiance(reflectionDiffuse),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.diffuseHitDistance)));
            primeImageStore(
                    primeNrdReflectionNoisySpecular,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeRadiance(reflectionSpecular),
                            primeNrdSanitizeHitDistance(
                                    reflectionGuides.specularHitDistance)));
            primeImageStore(
                    primeNrdReflectionPosition,
                    int2(pixel),
                    float4(
                            reflectionGuides.primaryHasMotion
                                    && primeNrdIsFinite(
                                            reflectionGuides.primaryPreviousPosition)
                            ? reflectionGuides.primaryPreviousPosition
                            : reflectionGuides.primaryPosition,
                            sampleResult.reflectionDirectionalGuide ? 1.0 : 0.0));
            primeImageStore(
                    primeNrdReflectionDiffuseDirection,
                    int2(pixel),
                    float4(primeNrdEffectiveDirection(
                            reflectionDiffuse,
                            reflectionGuides.primaryAreaDiffuse * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.diffuseDirection,
                            reflectionGuides.primaryAlbedo), 0.0));
            primeImageStore(
                    primeNrdReflectionSpecularDirection,
                    int2(pixel),
                    float4(primeNrdEffectiveDirection(
                            reflectionSpecular,
                            reflectionGuides.primaryAreaSpecular * reflectionScale,
                            reflectionGuides.primaryAreaDirection,
                            reflectionGuides.specularDirection,
                            reflectionGuides.primarySpecularAlbedo), 0.0));
            primeImageStore(
                    primeNrdReflectionMaterial,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeAlbedo(reflectionGuides.primaryAlbedo),
                            reflectionDistance));
            primeImageStore(
                    primeNrdReflectionNormalRoughness,
                    int2(pixel),
                    primeNrdDirectNormalRoughness(
                            reflectionGuides.primaryNormal,
                            reflectionGuides.primaryLinearRoughness));
            primeImageStore(
                    primeNrdReflectionSpecularMaterial,
                    int2(pixel),
                    float4(
                            primeNrdSanitizeAlbedo(
                                    reflectionGuides.primarySpecularAlbedo),
                            float(reflectionGuides.primaryMaterialFlags)));
        } else {
            // nrd_motion checks this marker before touching any other reflection input.
            primeImageStore(
                    primeNrdReflectionMaterial,
                    int2(pixel),
                    float4(1.0, 1.0, 1.0, -1.0));
        }
    } else if (primeTransparentGuideMode()
            == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR) {
        primeImageStore(
                primeNrdReflectionPosition,
                int2(pixel),
                rrUsesReflectionGuide
                        ? float4(0.0, 0.0, 0.0, -1.0)
                        : reflectionPreviousVirtualPosition);
    }
    primeImageStore(
            primeNrdSunLighting,
            int2(pixel),
            float4(
                    primeNrdClampRadiance(
                            sampleResult.radiance.unshadowedSun, PRIME_NRD_FP16_MAX),
                    primeNrdSanitizeUnit(sampleResult.radiance.sunVisibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            int2(pixel),
            float4(primeNrdSanitizeHitDistance(sampleResult.guides.sunPenumbra)));
    // Visible sky/emission is deterministic current-frame energy. A second temporal history here
    // would retain stale silhouettes underneath the reconstruction pass.
    primeImageStore(
            primeStableRadiance,
            int2(pixel),
            float4(primeNrdSanitizeRadiance(sampleResult.radiance.stable), 1.0));
}

public void primeWriteRealtimeOutput(
        uint2 pixel,
        float2 cameraSample,
        PrimeResolvedSample sampleResult) {
    primeWriteRealtimeOutput(
pixel,
            cameraSample,
            sampleResult,
            float4(0.0, 0.0, 0.0, -1.0));
}

