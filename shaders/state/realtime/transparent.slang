#language slang 2026
module "state/realtime/transparent.slang";

import "math/direction.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/guide_normal.slang";
import "state/realtime/signal.slang";
import "contract/path.slang";
import "model/realtime/guide.slang";
import "contract/realtime/transparent.slang";

public void primeStoreRrReflectionGuide(
        uint2 pixel,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    float3 previousPosition = result.guides.primaryHasMotion
            && primeNrdIsFinite(result.guides.primaryPreviousPosition)
            ? result.guides.primaryPreviousPosition
            : result.guides.primaryPosition;
    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(previousPosition, 0.0));
    if (!primaryReflectionOnly) {
        return;
    }
    // RR allocates only an independent reflection-position image. Under pure TIR the
    // transmission identity does not exist, so the reflection guide may safely own the otherwise
    // idle primary material, normal and current-position images.
    primeImageStore(
            primeNrdPrimaryPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.guides.primaryDistance));
    primeImageStore(
            primeNrdMaterial,
            coordinate,
            float4(
                    result.guides.primaryAlbedo,
                    result.guides.primaryLinearRoughness));
    primeImageStore(
            primeNrdSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
}

public void primeStoreTransparentBranchIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        bool hasGuide,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        primeImageStore(
                primeNrdNoisySpecular,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        primeImageStore(
                primeNrdPrimaryPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.guides.primaryDistance));
        primeImageStore(
                primeNrdMaterial,
                coordinate,
                float4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(result.guides.primarySpecularAlbedo, 0.0));
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
        primeImageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                float4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
        return;
    }

    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
    if (!primeWritesNrdShInputs()) {
        float4 visibleScratch = primeImageLoad(primeStableRadiance, coordinate);
        primeImageStore(
                primeStableRadiance,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(
                                result.diffuseRadiance
                                        + result.specularRadiance),
                        visibleScratch.a));
        if (hasGuide) {
            primeStoreRrReflectionGuide(
                    pixel, primaryReflectionOnly, result);
        } else {
            primeImageStore(
                    primeNrdReflectionPosition,
                    coordinate,
                    float4(0.0, 0.0, 0.0, -1.0));
        }
        return;
    }
    primeImageStore(
            primeNrdReflectionNoisyDiffuse,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.diffuseRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.diffuseHitDistance)));
    primeImageStore(
            primeNrdReflectionNoisySpecular,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(result.specularRadiance),
                    primeNrdSanitizeHitDistance(
                            result.guides.specularHitDistance)));
    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.firstHitDistance));
    primeImageStore(
            primeNrdReflectionMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    primeImageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
    if (primeWritesNrdShInputs()) {
        primeImageStore(
                primeNrdReflectionDiffuseDirection,
                coordinate,
                float4(result.guides.diffuseDirection, 0.0));
        primeImageStore(
                primeNrdReflectionSpecularDirection,
                coordinate,
                float4(result.guides.specularDirection, 0.0));
    }
}

// Active transparent branches keep their immutable guide images resident. Per-vertex transport
// reads only radiance, hit metadata and the two fallback directions required until PSR resolves.
public PrimeTransparentBranchResult primeLoadTransparentBranchActiveIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        uint control) {
    int2 coordinate = int2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : float4(primeImageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : float4(0.0);

    PrimeTransparentBranchResult result;
    result.diffuseRadiance = diffuse.rgb;
    result.specularRadiance = specular.rgb;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.primaryMaterialFlags =
            (control >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    if (!hasGuide && nrdShInputs) {
        if (transmissionBranch) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
        } else {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionSpecularDirection,
                            coordinate).xyz);
        }
    }
    if (transmissionBranch) {
        float4 metadata = primeImageLoad(primeWavefrontTransportMetadata, coordinate);
        result.anchorDistance = metadata.x;
        result.firstHitDistance = metadata.y;
    } else {
        result.anchorDistance = -1.0;
        result.firstHitDistance =
                primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    }
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

public void primeInvalidatePendingTransparentGuide(
        uint recordIndex,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    // Invalidating queued PSR only discards its anchor and continuation state. The resident
    // visible-interface guide remains the exact fallback for chain-limit and overflow exits.
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmission,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
}

public void primeStoreTransparentBranchGuide(
        uint2 pixel,
        bool transmissionBranch,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdPrimaryPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.guides.primaryDistance));
        primeImageStore(
                primeNrdMaterial,
                coordinate,
                float4(
                        result.guides.primaryAlbedo,
                        result.guides.primaryLinearRoughness));
        primeImageStore(
                primeNrdSpecularMaterial,
                coordinate,
                float4(result.guides.primarySpecularAlbedo, 0.0));
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
        return;
    }
    if (!primeWritesNrdShInputs()) {
        primeStoreRrReflectionGuide(
                pixel, primaryReflectionOnly, result);
        return;
    }

    primeImageStore(
            primeNrdReflectionPosition,
            coordinate,
            float4(result.guides.primaryPosition, result.firstHitDistance));
    primeImageStore(
            primeNrdReflectionMaterial,
            coordinate,
            float4(result.guides.primaryAlbedo, result.guides.primaryDistance));
    primeImageStore(
            primeNrdReflectionSpecularMaterial,
            coordinate,
            float4(result.guides.primarySpecularAlbedo, 0.0));
    primeImageStore(
            primeNrdReflectionDiffuseDirection,
            coordinate,
            float4(result.guides.diffuseDirection, 0.0));
    primeImageStore(
            primeNrdReflectionSpecularDirection,
            coordinate,
            float4(result.guides.specularDirection, 0.0));
}

public void primeStoreTransparentBranchActiveIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        bool storeGuide,
        bool storeDirectionalPosition,
        bool primaryReflectionOnly,
        PrimeTransparentBranchResult result) {
    int2 coordinate = int2(pixel);
    if (transmissionBranch) {
        primeImageStore(
                primeNrdNoisyDiffuse,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.diffuseRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.diffuseHitDistance)));
        primeImageStore(
                primeNrdNoisySpecular,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(result.specularRadiance),
                        primeNrdSanitizeHitDistance(
                                result.guides.specularHitDistance)));
        primeImageStore(
                primeWavefrontTransportMetadata,
                coordinate,
                float4(
                        result.anchorDistance,
                        result.firstHitDistance,
                        0.0,
                        0.0));
    } else {
        primeImageStore(
                primeNrdSunPenumbra,
                coordinate,
                float4(primeNrdSanitizeHitDistance(result.firstHitDistance)));
        if (primeWritesNrdShInputs()) {
            primeImageStore(
                    primeNrdReflectionNoisyDiffuse,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(result.diffuseRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.diffuseHitDistance)));
            primeImageStore(
                    primeNrdReflectionNoisySpecular,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(result.specularRadiance),
                            primeNrdSanitizeHitDistance(
                                    result.guides.specularHitDistance)));
        } else {
            float4 visibleScratch = primeImageLoad(primeStableRadiance, coordinate);
            primeImageStore(
                    primeStableRadiance,
                    coordinate,
                    float4(
                            primeNrdSanitizeRadiance(
                                    result.diffuseRadiance
                                            + result.specularRadiance),
                            visibleScratch.a));
        }
    }

    if (storeGuide) {
        primeStoreTransparentBranchGuide(
                pixel, transmissionBranch, primaryReflectionOnly, result);
    } else if (storeDirectionalPosition
            && !transmissionBranch) {
        primeImageStore(
                primeNrdReflectionPosition,
                coordinate,
                float4(result.guides.primaryPosition, result.firstHitDistance));
    }
}

public PrimeTransparentBranchResult primeLoadTransparentBranchIntermediate(
        uint2 pixel,
        bool transmissionBranch,
        uint control) {
    int2 coordinate = int2(pixel);
    bool nrdShInputs = primeWritesNrdShInputs();
    bool rrReflectionPrimaryGuide = !nrdShInputs
            && !transmissionBranch
            && (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                    : float4(primeImageLoad(primeStableRadiance, coordinate).rgb, 0.0);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : nrdShInputs
                    ? primeImageLoad(primeNrdReflectionNoisySpecular, coordinate)
                    : float4(0.0);
    float4 position = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdPrimaryPosition, coordinate)
                    : primeImageLoad(primeNrdReflectionPosition, coordinate);
    float4 material = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdMaterial, coordinate)
                    : nrdShInputs
                            ? primeImageLoad(primeNrdReflectionMaterial, coordinate)
                            : float4(0.0, 0.0, 0.0, -1.0);
    float4 specularMaterial = transmissionBranch
            || rrReflectionPrimaryGuide
                    ? primeImageLoad(primeNrdSpecularMaterial, coordinate)
                    : nrdShInputs
                            ? primeImageLoad(primeNrdReflectionSpecularMaterial, coordinate)
                            : float4(0.0);
    float4 branchMetadata = transmissionBranch
            ? primeImageLoad(primeWavefrontTransportMetadata, coordinate)
            : position;
    PrimeTransparentBranchResult result;
    result.diffuseRadiance = diffuse.rgb;
    result.specularRadiance = specular.rgb;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.specularHitDistance = specular.a;
    result.guides.primaryPosition = position.xyz;
    result.guides.primaryDistance =
            transmissionBranch || rrReflectionPrimaryGuide
                    ? position.w
                    : material.w;
    result.guides.primaryHitKind = result.guides.primaryDistance >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryMaterialFlags =
            (control >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    if (transmissionBranch || rrReflectionPrimaryGuide) {
        result.guides.primaryLinearRoughness = material.w;
        if (transmissionBranch && primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
        }
    } else if (nrdShInputs) {
        if (primeWritesNrdShInputs()) {
            result.guides.diffuseDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionDiffuseDirection,
                            coordinate).xyz);
            result.guides.specularDirection = primeRestoreFp16Direction(
                    primeImageLoad(
                            primeNrdReflectionSpecularDirection,
                            coordinate).xyz);
        }
    }
    uint branch = transmissionBranch ? 0u : 1u;
    uint guidePathIndex = primeWavefrontIndex(pixel, branch);
    result.guides.primaryNormal =
            primeLoadWavefrontGuideNormal(guidePathIndex);
    result.guides.primaryLinearRoughness =
            primeLoadWavefrontGuideRoughness(guidePathIndex);
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    result.anchorDistance = transmissionBranch ? branchMetadata.x : -1.0;
    result.firstHitDistance = transmissionBranch
            ? branchMetadata.y
            : primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    result.directionalGuide = primeWavefrontDirectionalGuide(control);
    return result;
}

public void primeFinalizeTransparentBranchGuide(
        uint control,
        inout PrimeTransparentBranchResult result) {
    bool hasGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    if (!primeWavefrontGuideEnabled(control) || hasGuide) {
        return;
    }
    float3 branchDirection = primeWavefrontTransmissionBranch(control)
            ? result.guides.diffuseDirection
            : result.guides.specularDirection;
    result.guides.primaryAreaDiffuse = float3(0.0);
    result.guides.primaryAreaSpecular = float3(0.0);
    result.guides.primaryAreaDirection = float3(0.0);
    if (primeWavefrontTransmissionBranch(control)) {
        result.guides.diffuseDirection = branchDirection;
        result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
    } else {
        result.guides.specularDirection = branchDirection;
        result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
    }
}
